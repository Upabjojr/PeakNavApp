package com.peaknav.stars;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Finds the stars in a photograph of the night sky: the point sources standing out of the
 * sky background, each with its sub-pixel centre and its brightness.
 *
 * <p>Classical source extraction, nothing learned:
 * <ol>
 * <li>a luminance image;</li>
 * <li>the sky background and its noise, estimated per cell of a coarse grid as the median
 *     and the median absolute deviation of the cell (stars cover a tiny fraction of the
 *     pixels, so the median is the sky), then interpolated between cell centres - which
 *     follows the light-pollution gradient and the vignetting a flat threshold would trip
 *     over;</li>
 * <li>pixels a few noise deviations above that background, grouped into connected
 *     blobs;</li>
 * <li>each blob's flux (the light above the background), peak and light-weighted centroid.
 *     Blobs too large to be a star (the Moon, a lamp, a lit cloud edge), too elongated
 *     (a trail, a wire, the horizon glow) or cut by the picture's edge are dropped.</li>
 * </ol>
 *
 * <p>The sources come out brightest first, which is the order the matcher wants them in:
 * the brightest few are the ones certain to be catalogue stars. Photos should be reduced
 * to about {@link #DEFAULT_WIDTH} pixels first ({@link #reduce}); that is plenty for the
 * pose, and it keeps a phone's twelve megapixels from taking seconds here.
 */
public final class StarExtractor {

    /** Width photos are reduced to before extraction. */
    public static final int DEFAULT_WIDTH = 1200;
    /** Detection threshold, in units of the local noise deviation (of the smoothed picture) above the background. */
    private static final float DETECTION_SIGMAS = 4.5f;
    /** ...and never below this many luminance levels (0-255): JPEG blocks are noisy too. */
    private static final float DETECTION_MIN_LEVELS = 4f;
    /** Largest blob still taken for a star, in pixels of the reduced image. */
    private static final int MAX_AREA = 300;
    /** Largest bounding-box side, pixels. */
    private static final int MAX_SIDE = 24;
    /** Longest bounding-box side over the shortest: a trail or a wire, not a star. */
    private static final float MAX_ELONGATION = 2.5f;
    /** At most this many sources are returned. */
    public static final int MAX_SOURCES = 80;
    /** A picture with a median luminance (0-255) above this is not a night sky. */
    public static final float NIGHT_MAX_MEDIAN = 70f;
    /** A night sky has at least this many point sources. */
    public static final int NIGHT_MIN_SOURCES = 6;

    /** A point source in the picture. */
    public static final class Source {
        /** Centre, in pixels of the image given to {@link #extract}; y grows downwards. */
        public final float x, y;
        /** Light above the sky background, summed over the blob (luminance levels). */
        public final float flux;
        /** Brightest pixel above the background. */
        public final float peak;
        /** Pixels in the blob. */
        public final int area;

        Source(float x, float y, float flux, float peak, int area) {
            this.x = x;
            this.y = y;
            this.flux = flux;
            this.peak = peak;
            this.area = area;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ENGLISH, "(%.1f, %.1f) flux %.0f", x, y, flux);
        }
    }

    /** What was found in a picture. */
    public static final class Field {
        /** The sources, brightest (largest flux) first. */
        public final Source[] sources;
        public final int width, height;
        /** Median luminance of the picture, 0-255. */
        public final float medianLuminance;
        /** Typical noise deviation of the sky background, luminance levels. */
        public final float noise;

        Field(Source[] sources, int width, int height, float medianLuminance, float noise) {
            this.sources = sources;
            this.width = width;
            this.height = height;
            this.medianLuminance = medianLuminance;
            this.noise = noise;
        }

        /** Whether the picture looks like a night sky: dark, with point sources in it. */
        public boolean looksLikeNightSky() {
            return medianLuminance <= NIGHT_MAX_MEDIAN && sources.length >= NIGHT_MIN_SOURCES;
        }
    }

    private StarExtractor() {
    }

    /**
     * Extracts the point sources of a picture.
     *
     * @param rgb    the pixels, packed 0xRRGGBB, row-major
     * @param width  the picture's width in pixels
     * @param height its height
     */
    public static Field extract(int[] rgb, int width, int height) {
        int n = width * height;
        float[] lum = new float[n];
        for (int i = 0; i < n; i++) {
            int p = rgb[i];
            lum[i] = 0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF);
        }
        // Detection runs on a 3x3 mean: a star is a blob a few pixels wide, so the mean keeps
        // most of its light while cutting the pixel noise by three - a faint star becomes a
        // clear detection and a lone noisy pixel does not. Positions and fluxes are measured
        // on the raw pixels afterwards.
        float[] smooth = new float[n];
        for (int y = 0; y < height; y++) {
            int y0 = Math.max(0, y - 1), y1 = Math.min(height - 1, y + 1);
            for (int x = 0; x < width; x++) {
                int x0 = Math.max(0, x - 1), x1 = Math.min(width - 1, x + 1);
                float sum = 0;
                for (int yy = y0; yy <= y1; yy++) {
                    for (int xx = x0; xx <= x1; xx++) {
                        sum += lum[yy * width + xx];
                    }
                }
                smooth[y * width + x] = sum / ((y1 - y0 + 1) * (x1 - x0 + 1));
            }
        }

        // Background and noise on a coarse grid, interpolated between the cell centres.
        int cell = Math.max(16, Math.min(width, height) / 20);
        int cols = Math.max(1, (width + cell - 1) / cell);
        int rows = Math.max(1, (height + cell - 1) / cell);
        float[] cellBg = new float[cols * rows];
        float[] cellNoise = new float[cols * rows];
        float[] scratch = new float[cell * cell];
        for (int cy = 0; cy < rows; cy++) {
            for (int cx = 0; cx < cols; cx++) {
                int x0 = cx * cell, y0 = cy * cell;
                int x1 = Math.min(width, x0 + cell), y1 = Math.min(height, y0 + cell);
                int k = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        scratch[k++] = smooth[y * width + x];
                    }
                }
                float median = select(scratch, k, k / 2);
                for (int i = 0; i < k; i++) {
                    scratch[i] = Math.abs(scratch[i] - median);
                }
                // 1.4826 turns a median absolute deviation into a Gaussian sigma; a floor keeps a
                // clipped-black sky (deviation 0) from flagging every pixel of JPEG noise.
                float sigma = Math.max(1.0f, 1.4826f * select(scratch, k, k / 2));
                cellBg[cy * cols + cx] = median;
                cellNoise[cy * cols + cx] = sigma;
            }
        }
        float[] all = lum.clone();
        float medianLum = select(all, n, n / 2);
        float noise = 0;
        for (float s : cellNoise) {
            noise += s;
        }
        noise /= cellNoise.length;

        // Anything large and bright - the Moon, a lamp, a lit cloud - is masked out with a
        // margin before detection. The background grid follows such an object, so its rim
        // would otherwise come through as arcs the size of stars.
        boolean[] excluded = bigObjects(smooth, width, height, medianLum + Math.max(5 * noise, 20));

        // Threshold mask, on the smoothed picture.
        boolean[] above = new boolean[n];
        for (int y = 0; y < height; y++) {
            float gy = Math.max(0, Math.min(rows - 1, (y + 0.5f) / cell - 0.5f));
            int r0 = (int) gy, r1 = Math.min(rows - 1, r0 + 1);
            float fy = gy - r0;
            for (int x = 0; x < width; x++) {
                float gx = Math.max(0, Math.min(cols - 1, (x + 0.5f) / cell - 0.5f));
                int c0 = (int) gx, c1 = Math.min(cols - 1, c0 + 1);
                float fx = gx - c0;
                float bg = lerp2(cellBg, cols, c0, c1, r0, r1, fx, fy);
                float sg = lerp2(cellNoise, cols, c0, c1, r0, r1, fx, fy);
                float thr = bg + Math.max(DETECTION_SIGMAS * sg, DETECTION_MIN_LEVELS);
                int i = y * width + x;
                above[i] = smooth[i] > thr && !excluded[i];
                lum[i] -= bg;   // from here on: light above the background
            }
        }

        // Connected blobs (8-connected), each measured as it is flooded.
        List<Source> found = new ArrayList<Source>();
        int[] stack = new int[Math.max(64, n / 64)];
        for (int start = 0; start < n; start++) {
            if (!above[start]) {
                continue;
            }
            int sp = 0;
            stack[sp++] = start;
            above[start] = false;
            int area = 0;
            double sumW = 0, sumX = 0, sumY = 0;
            float peak = 0;
            int minX = width, maxX = -1, minY = height, maxY = -1;
            while (sp > 0) {
                int i = stack[--sp];
                int x = i % width, y = i / width;
                float w = Math.max(0.01f, lum[i]);
                area++;
                sumW += w;
                sumX += w * x;
                sumY += w * y;
                peak = Math.max(peak, lum[i]);
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= height) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= width) continue;
                        int j = ny * width + nx;
                        if (above[j]) {
                            above[j] = false;
                            if (sp == stack.length) {
                                stack = Arrays.copyOf(stack, stack.length * 2);
                            }
                            stack[sp++] = j;
                        }
                    }
                }
            }
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            if (area > MAX_AREA || bw > MAX_SIDE || bh > MAX_SIDE) {
                continue;   // the Moon, a lamp, a lit cloud
            }
            if (Math.max(bw, bh) > MAX_ELONGATION * Math.min(bw, bh) && Math.max(bw, bh) > 4) {
                continue;   // a trail, a wire, a glow along an edge
            }
            if (minX == 0 || minY == 0 || maxX == width - 1 || maxY == height - 1) {
                continue;   // cut by the edge: its centre is not where it seems
            }
            // The centre again over a window two pixels wider than the blob, on all the
            // light above the background: a faint star has only its brightest few pixels
            // over the threshold, and a centroid of those alone is pulled towards the one
            // the noise happened to lift.
            double wSum = 0, wX = 0, wY = 0;
            for (int y = Math.max(0, minY - 2); y <= Math.min(height - 1, maxY + 2); y++) {
                for (int x = Math.max(0, minX - 2); x <= Math.min(width - 1, maxX + 2); x++) {
                    float w = lum[y * width + x];
                    if (w > 0) {
                        wSum += w;
                        wX += w * x;
                        wY += w * y;
                    }
                }
            }
            float cx = (float) (wSum > 0 ? wX / wSum : sumX / sumW);
            float cy = (float) (wSum > 0 ? wY / wSum : sumY / sumW);
            found.add(new Source(cx, cy, (float) sumW, peak, area));
        }
        Collections.sort(found, new Comparator<Source>() {
            @Override
            public int compare(Source a, Source b) {
                return Float.compare(b.flux, a.flux);
            }
        });
        if (found.size() > MAX_SOURCES) {
            found = new ArrayList<Source>(found.subList(0, MAX_SOURCES));
        }
        return new Field(found.toArray(new Source[found.size()]), width, height, medianLum, noise);
    }

    /**
     * Reduces a picture to at most {@code maxWidth} pixels wide by averaging whole blocks
     * of pixels. Averaging (not sampling) is the point: a star is a few pixels in a large
     * photo and a sparse sample would miss it, while the block mean keeps its light and
     * quietens the noise around it.
     *
     * @param rgb     packed 0xRRGGBB pixels
     * @param width   the picture's width
     * @param height  its height
     * @param sizeOut receives {new width, new height}
     * @return the reduced pixels, packed the same way; the input itself when it is small enough
     */
    public static int[] reduce(int[] rgb, int width, int height, int maxWidth, int[] sizeOut) {
        int block = (width + maxWidth - 1) / maxWidth;
        if (block <= 1) {
            sizeOut[0] = width;
            sizeOut[1] = height;
            return rgb;
        }
        int nw = width / block, nh = height / block;
        int[] out = new int[nw * nh];
        int count = block * block;
        for (int ty = 0; ty < nh; ty++) {
            for (int tx = 0; tx < nw; tx++) {
                int r = 0, g = 0, b = 0;
                for (int dy = 0; dy < block; dy++) {
                    int row = (ty * block + dy) * width + tx * block;
                    for (int dx = 0; dx < block; dx++) {
                        int p = rgb[row + dx];
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                    }
                }
                out[ty * nw + tx] = ((r / count) << 16) | ((g / count) << 8) | (b / count);
            }
        }
        sizeOut[0] = nw;
        sizeOut[1] = nh;
        return out;
    }

    /**
     * Pixels of every connected blob above {@code threshold} that is too big for a star,
     * grown by a three-pixel margin.
     */
    private static boolean[] bigObjects(float[] value, int width, int height, float threshold) {
        int n = width * height;
        boolean[] pending = new boolean[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (value[i] > threshold) {
                pending[i] = true;
                count++;
            }
        }
        boolean[] big = new boolean[n];
        if (count == 0) {
            return big;
        }
        int[] stack = new int[Math.max(64, n / 64)];
        int[] members = new int[Math.max(64, n / 64)];
        for (int start = 0; start < n; start++) {
            if (!pending[start]) {
                continue;
            }
            int sp = 0, m = 0;
            stack[sp++] = start;
            pending[start] = false;
            int minX = width, maxX = -1, minY = height, maxY = -1;
            while (sp > 0) {
                int i = stack[--sp];
                if (m == members.length) {
                    members = Arrays.copyOf(members, members.length * 2);
                }
                members[m++] = i;
                int x = i % width, y = i / width;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= height) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= width) continue;
                        int j = ny * width + nx;
                        if (pending[j]) {
                            pending[j] = false;
                            if (sp == stack.length) {
                                stack = Arrays.copyOf(stack, stack.length * 2);
                            }
                            stack[sp++] = j;
                        }
                    }
                }
            }
            if (m <= MAX_AREA && maxX - minX + 1 <= MAX_SIDE && maxY - minY + 1 <= MAX_SIDE) {
                continue;   // star-sized: left to the detection proper
            }
            for (int k = 0; k < m; k++) {
                int x = members[k] % width, y = members[k] / width;
                for (int dy = -3; dy <= 3; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= height) continue;
                    for (int dx = -3; dx <= 3; dx++) {
                        int nx = x + dx;
                        if (nx >= 0 && nx < width) {
                            big[ny * width + nx] = true;
                        }
                    }
                }
            }
        }
        return big;
    }

    private static float lerp2(float[] grid, int cols, int c0, int c1, int r0, int r1, float fx, float fy) {
        float top = grid[r0 * cols + c0] * (1 - fx) + grid[r0 * cols + c1] * fx;
        float bottom = grid[r1 * cols + c0] * (1 - fx) + grid[r1 * cols + c1] * fx;
        return top * (1 - fy) + bottom * fy;
    }

    /** The k-th smallest of the first {@code n} values (quickselect; reorders the array). */
    static float select(float[] a, int n, int k) {
        if (n <= 0) {
            return 0;
        }
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            float pivot = a[(lo + hi) >>> 1];
            int i = lo, j = hi;
            while (i <= j) {
                while (a[i] < pivot) i++;
                while (a[j] > pivot) j--;
                if (i <= j) {
                    float t = a[i];
                    a[i] = a[j];
                    a[j] = t;
                    i++;
                    j--;
                }
            }
            if (k <= j) {
                hi = j;
            } else if (k >= i) {
                lo = i;
            } else {
                break;
            }
        }
        return a[k];
    }
}
