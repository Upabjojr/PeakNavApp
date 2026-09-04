package com.peaknav.skyline;

import java.util.Arrays;

/**
 * Traces the skyline - the boundary between sky and ground - across a photograph, one row
 * per column. Classical image processing plus, when the build ships one, a small learned
 * pixel classifier ({@link SkyClassifier}):
 *
 * <ol>
 * <li>a gradient magnitude image (Sobel on a blurred luminance and on a blue-yellow chroma,
 *     the latter because a sunlit snow ridge against a blue sky has almost no luminance
 *     edge);</li>
 * <li>a first guess at the border by threshold search, after Shen &amp; Wang: for each
 *     gradient threshold the border is the first strong edge down every column, and the
 *     threshold kept is the one whose sky and ground halves are most distinct in colour
 *     relative to how varied each is;</li>
 * <li>a sky probability for every pixel - from {@link SkyClassifier}'s trees over
 *     {@link SkyFeatures} (colour, texture, edge softness, contrast with the sky above),
 *     which is what separates snow from cloud and a hazy ridge from the sky; or, in a
 *     build without the model, from a Gaussian colour model of each half of that guess;</li>
 * <li>with the classifier, a second forest ({@link BoundaryFeatures}) scoring every
 *     position as "the skyline passes here" from what lies just above and below it,
 *     blended into the edge term;</li>
 * <li>a minimum-cost path through the columns (Viterbi) that likes strong edges, dislikes
 *     non-sky pixels above it and sky pixels just below it, and pays for vertical jumps -
 *     an L1 penalty, which the two-pass min-convolution applies in linear time.</li>
 * </ol>
 *
 * Input images should be small - {@link #DEFAULT_WIDTH} pixels wide - both for speed and
 * because fine detail (trees, antennas) only distracts from the ridge line.
 */
public final class SkylineExtractor {

    /** Width photos are reduced to before extraction. */
    public static final int DEFAULT_WIDTH = 480;

    // Path cost weights. Edge strengths are normalised to [0, 1]; region terms count
    // offending pixels (non-sky above the path, sky just below it), so that
    // REGION_PIXELS of them cost as much as a maximal edge gains - a per-pixel penalty,
    // not a fraction: averaged over the height it was too weak to stop the path from
    // sliding down a mountain onto the stronger snow-line or forest edge below.
    private static final float EDGE_WEIGHT = 1.0f;
    private static final float REGION_WEIGHT = 1.0f;
    private static final float REGION_PIXELS = 0.05f;   // fraction of the image height
    private static final float JUMP_PENALTY = 0.04f;
    /**
     * Depth of the band below a candidate row checked for sky pixels, as a fraction of
     * height, without the boundary model: the whole picture, which with the pixel
     * classifier's probabilities is what keeps the path off a cloud (there is sky under a
     * cloud, never under a ridge).
     */
    private static final float BELOW_BAND = 1.0f;
    // With the boundary model the path cost is the one tuned on hand-traced skylines
    // (study/ALGORITHM.md section 4): the edge is 0.7 boundary log-probability + 0.3
    // gradient with weight 2, the region terms saturate at 0.5 so a blob of misread sky
    // above the ridge cannot outweigh the ridge's edge, the band below is 0.15 H again,
    // and the jump penalty is 0.3 per pixel.
    private static final float BOUNDARY_MIX = 0.7f;
    private static final float BOUNDARY_EDGE_WEIGHT = 2.0f;
    private static final float BOUNDARY_REGION_CAP = 0.5f;
    private static final float BOUNDARY_BELOW_BAND = 0.15f;
    private static final float BOUNDARY_JUMP_PENALTY = 0.3f;
    private static final double BOUNDARY_FLOOR = 0.02;
    /** Discourages the trivial "everything is ground" path unless the image supports it. */
    private static final float TOP_ROW_PENALTY = 0.5f;
    private static final int THRESHOLD_STEPS = 24;
    private static final double BLUR_SIGMA = 1.2;

    /** The result: a row per column and a confidence per column. */
    public static final class Skyline {
        public final float[] rows;
        public final float[] confidence;
        public final int width;
        public final int height;
        /** Per-pixel probability of sky the path was traced through, for inspection; may be null. */
        public final float[] skyProbability;

        Skyline(float[] rows, float[] confidence, int width, int height, float[] skyProbability) {
            this.rows = rows;
            this.confidence = confidence;
            this.width = width;
            this.height = height;
            this.skyProbability = skyProbability;
        }
    }

    /**
     * Whether to use the shipped {@link SkyClassifier} for the per-pixel sky probability;
     * off, the colour-model fallback runs. {@code -Dpeaknav.skyline.classifier=false} turns
     * it off for benchmark comparisons.
     */
    static volatile boolean useClassifier = !"false".equals(System.getProperty("peaknav.skyline.classifier"));

    private SkylineExtractor() {
    }

    public static void setUseClassifier(boolean on) {
        useClassifier = on;
    }

    /**
     * Extracts the skyline from an image given as packed RGB(A) pixels, one {@code int} per
     * pixel with red in bits 16-23, green in 8-15 and blue in 0-7 (the top byte is ignored),
     * row-major from the top-left corner.
     */
    public static Skyline extract(int[] rgb, int width, int height) {
        final int n = width * height;
        float[] r = new float[n], g = new float[n], b = new float[n];
        for (int i = 0; i < n; i++) {
            int p = rgb[i];
            r[i] = ((p >> 16) & 0xFF) / 255f;
            g[i] = ((p >> 8) & 0xFF) / 255f;
            b[i] = (p & 0xFF) / 255f;
        }
        return extract(r, g, b, width, height);
    }

    static Skyline extract(float[] r, float[] g, float[] b, int width, int height) {
        final int n = width * height;

        // 1. gradient magnitude, the larger of luminance and chroma edges
        float[] lum = new float[n], chroma = new float[n];
        for (int i = 0; i < n; i++) {
            lum[i] = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i];
            chroma[i] = b[i] - 0.5f * (r[i] + g[i]);
        }
        float[] grad = gradientMagnitude(gaussianBlur(lum, width, height, BLUR_SIGMA), width, height);
        float[] gradC = gradientMagnitude(gaussianBlur(chroma, width, height, BLUR_SIGMA), width, height);
        float gmax = 0;
        for (int i = 0; i < n; i++) {
            grad[i] = Math.max(grad[i], gradC[i]);
            gmax = Math.max(gmax, grad[i]);
        }
        float[] normalised = new float[n];
        // Saturate at the 90th percentile: a ridge against the sky only has to be a clear
        // edge, not the strongest one in the picture.
        float p90 = percentile(grad, 0.90f);
        for (int i = 0; i < n; i++) {
            normalised[i] = Math.min(1f, grad[i] / (p90 + 1e-6f));
        }

        // 2. threshold search for a first border
        int[] border = new int[width];
        int[] candidate = new int[width];
        double bestJ = Double.NEGATIVE_INFINITY;
        double[] bestStats = null;
        for (int k = 0; k < THRESHOLD_STEPS; k++) {
            double t = gmax * 0.03 * Math.pow(0.6 / 0.03, k / (double) (THRESHOLD_STEPS - 1));
            for (int x = 0; x < width; x++) {
                int y = 0;
                while (y < height && grad[y * width + x] <= t) {
                    y++;
                }
                candidate[x] = y;
            }
            double[] stats = regionStats(r, g, b, width, height, candidate);
            if (stats == null) {
                continue;
            }
            // squared distance between the two mean colours over the sum of the variances
            double separation = 0, spread = 1e-6;
            for (int c = 0; c < 3; c++) {
                double d = stats[c] - stats[12 + c];
                separation += d * d;
                spread += stats[3 + c * 4] + stats[15 + c * 4];
            }
            double J = separation / spread;
            if (J > bestJ) {
                bestJ = J;
                bestStats = stats;
                System.arraycopy(candidate, 0, border, 0, width);
            }
        }
        if (bestStats == null) {
            // degenerate image: no usable split; report a flat, unconfident skyline
            float[] rows = new float[width];
            Arrays.fill(rows, height / 2f);
            return new Skyline(rows, new float[width], width, height, null);
        }

        // 3. sky probability per pixel: the learned classifier when the build ships one,
        //    else the Gaussian colour models of the two halves
        float[] pSky;
        float[][] planes = null;
        SkyClassifier classifier = useClassifier ? SkyClassifier.shipped() : null;
        if (classifier != null) {
            planes = SkyFeatures.compute(r, g, b, width, height);
            pSky = classifier.probabilities(planes, n);
        } else {
            double[] invSky = invert3(bestStats, 3);
            double[] invGround = invert3(bestStats, 15);
            pSky = new float[n];
            for (int i = 0; i < n; i++) {
                double ms = mahalanobis(r[i], g[i], b[i], bestStats, 0, invSky);
                double mg = mahalanobis(r[i], g[i], b[i], bestStats, 12, invGround);
                double es = Math.exp(-0.5 * ms), eg = Math.exp(-0.5 * mg);
                pSky[i] = (float) (es / (es + eg + 1e-9));
            }
        }

        // 4. the edge term: the gradient alone, or blended with the boundary model's
        //    log-probability that the skyline passes through each pixel
        float[] edge = normalised;
        float[] boundaryProbability = null;
        float edgeWeight = EDGE_WEIGHT, jump = JUMP_PENALTY, cap = 0, bandFraction = BELOW_BAND;
        SkyClassifier boundary = planes != null ? SkyClassifier.shippedBoundary() : null;
        if (boundary != null) {
            boundaryProbability = boundary.probabilities(BoundaryFeatures.compute(planes, pSky, width, height), n);
            edge = new float[n];
            double scale = -Math.log(BOUNDARY_FLOOR);
            for (int i = 0; i < n; i++) {
                double eq = 1 + Math.log(boundaryProbability[i] + BOUNDARY_FLOOR) / scale;   // in (0, 1]
                edge[i] = (float) (BOUNDARY_MIX * eq + (1 - BOUNDARY_MIX) * normalised[i]);
            }
            edgeWeight = BOUNDARY_EDGE_WEIGHT;
            jump = BOUNDARY_JUMP_PENALTY;
            cap = BOUNDARY_REGION_CAP;
            bandFraction = BOUNDARY_BELOW_BAND;
        }

        // 5. path cost: -edge + region terms, then Viterbi with an L1 jump penalty
        float[] cost = new float[n];
        int band = Math.max(2, (int) (bandFraction * height));
        float perPixel = 1f / (REGION_PIXELS * height);
        float[] cumNonSky = new float[height + 1];
        float[] cumSky = new float[height + 1];
        for (int x = 0; x < width; x++) {
            cumNonSky[0] = 0;
            cumSky[0] = 0;
            for (int y = 0; y < height; y++) {
                float p = pSky[y * width + x];
                cumNonSky[y + 1] = cumNonSky[y] + (1 - p);
                cumSky[y + 1] = cumSky[y] + p;
            }
            for (int y = 0; y < height; y++) {
                float above = cumNonSky[y] * perPixel;                       // non-sky in rows [0, y)
                int end = Math.min(height - 1, y + band);
                float below = (cumSky[end] - cumSky[y]) * perPixel;          // sky in rows [y, end)
                float region = REGION_WEIGHT * (above + below);
                if (cap > 0) {
                    region = Math.min(region, cap);
                }
                float c = -edgeWeight * edge[y * width + x] + region;
                if (y == 0) {
                    c += TOP_ROW_PENALTY;
                }
                cost[y * width + x] = c;
            }
        }
        int[] path = viterbi(cost, width, height, jump);
        float[] rows = new float[width];
        float[] confidence = new float[width];
        for (int x = 0; x < width; x++) {
            rows[x] = path[x];
            confidence[x] = boundaryProbability != null
                    ? boundaryProbability[path[x] * width + x] : normalised[path[x] * width + x];
        }
        return new Skyline(rows, confidence, width, height, pSky);
    }

    /**
     * Mean and covariance of the pixels above (sky) and below (ground) a border, packed as
     * {meanS(3), covS(9), meanG(3), covG(9)}; null when either half is (nearly) empty.
     */
    private static double[] regionStats(float[] r, float[] g, float[] b, int width, int height, int[] border) {
        double[] sum = new double[6];
        double[] sq = new double[18];
        long ns = 0, ng = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                int o = y < border[x] ? 0 : 3;
                if (o == 0) ns++; else ng++;
                double[] v = {r[i], g[i], b[i]};
                for (int c = 0; c < 3; c++) {
                    sum[o + c] += v[c];
                    for (int d = 0; d < 3; d++) {
                        sq[o * 3 + c * 3 + d] += v[c] * v[d];
                    }
                }
            }
        }
        if (ns < 50 || ng < 50) {
            return null;
        }
        double[] out = new double[24];
        for (int half = 0; half < 2; half++) {
            long cnt = half == 0 ? ns : ng;
            int o = half * 3, base = half * 12;
            for (int c = 0; c < 3; c++) {
                out[base + c] = sum[o + c] / cnt;
            }
            for (int c = 0; c < 3; c++) {
                for (int d = 0; d < 3; d++) {
                    out[base + 3 + c * 3 + d] = sq[o * 3 + c * 3 + d] / cnt - out[base + c] * out[base + d];
                }
            }
        }
        return out;
    }

    /** Inverse of the 3x3 covariance at {@code off}, regularised for flat colours. */
    private static double[] invert3(double[] s, int off) {
        double a = s[off] + 1e-3, b = s[off + 1], c = s[off + 2];
        double d = s[off + 3], e = s[off + 4] + 1e-3, f = s[off + 5];
        double g = s[off + 6], h = s[off + 7], i = s[off + 8] + 1e-3;
        double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        double det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-18) {
            det = 1e-18;
        }
        return new double[]{
                A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
                B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
                C / det, -(a * h - b * g) / det, (a * e - b * d) / det};
    }

    private static double mahalanobis(float r, float g, float b, double[] s, int meanOff, double[] inv) {
        double dr = r - s[meanOff], dg = g - s[meanOff + 1], db = b - s[meanOff + 2];
        double tr = inv[0] * dr + inv[1] * dg + inv[2] * db;
        double tg = inv[3] * dr + inv[4] * dg + inv[5] * db;
        double tb = inv[6] * dr + inv[7] * dg + inv[8] * db;
        return dr * tr + dg * tg + db * tb;
    }

    /**
     * Minimum-cost left-to-right path, one row per column, paying {@code jump} per pixel of
     * vertical movement between neighbouring columns.
     */
    private static int[] viterbi(float[] cost, int width, int height, float jump) {
        float[] acc = new float[height];
        float[] best = new float[height];
        int[] arg = new int[height];
        int[] back = new int[width * height];
        for (int y = 0; y < height; y++) {
            acc[y] = cost[y * width];
        }
        for (int x = 1; x < width; x++) {
            for (int y = 0; y < height; y++) {
                best[y] = acc[y];
                arg[y] = y;
            }
            for (int y = 1; y < height; y++) {
                float c = best[y - 1] + jump;
                if (c < best[y]) {
                    best[y] = c;
                    arg[y] = arg[y - 1];
                }
            }
            for (int y = height - 2; y >= 0; y--) {
                float c = best[y + 1] + jump;
                if (c < best[y]) {
                    best[y] = c;
                    arg[y] = arg[y + 1];
                }
            }
            for (int y = 0; y < height; y++) {
                back[y * width + x] = arg[y];
                acc[y] = best[y] + cost[y * width + x];
            }
        }
        int[] path = new int[width];
        int yBest = 0;
        for (int y = 1; y < height; y++) {
            if (acc[y] < acc[yBest]) {
                yBest = y;
            }
        }
        path[width - 1] = yBest;
        for (int x = width - 1; x > 0; x--) {
            path[x - 1] = back[path[x] * width + x];
        }
        return path;
    }

    static float[] gaussianBlur(float[] src, int width, int height, double sigma) {
        return gaussianBlur(src, width, height, sigma, sigma);
    }

    /** Separable Gaussian blur with clamped borders and its own sigma per axis. */
    static float[] gaussianBlur(float[] src, int width, int height, double sigmaX, double sigmaY) {
        float[] kx = kernel(sigmaX), ky = kernel(sigmaY);
        int rx = kx.length / 2, ry = ky.length / 2;
        float[] tmp = new float[src.length];
        float[] out = new float[src.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float v = 0;
                for (int k = -rx; k <= rx; k++) {
                    int xx = Math.min(width - 1, Math.max(0, x + k));
                    v += kx[k + rx] * src[y * width + xx];
                }
                tmp[y * width + x] = v;
            }
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float v = 0;
                for (int k = -ry; k <= ry; k++) {
                    int yy = Math.min(height - 1, Math.max(0, y + k));
                    v += ky[k + ry] * tmp[yy * width + x];
                }
                out[y * width + x] = v;
            }
        }
        return out;
    }

    private static float[] kernel(double sigma) {
        int radius = (int) Math.ceil(3 * sigma);
        float[] kernel = new float[2 * radius + 1];
        float sum = 0;
        for (int i = -radius; i <= radius; i++) {
            kernel[i + radius] = (float) Math.exp(-0.5 * i * i / (sigma * sigma));
            sum += kernel[i + radius];
        }
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] /= sum;
        }
        return kernel;
    }

    /** Sobel gradient magnitude with clamped borders. */
    static float[] gradientMagnitude(float[] src, int width, int height) {
        float[] out = new float[src.length];
        for (int y = 0; y < height; y++) {
            int ym = Math.max(0, y - 1), yp = Math.min(height - 1, y + 1);
            for (int x = 0; x < width; x++) {
                int xm = Math.max(0, x - 1), xp = Math.min(width - 1, x + 1);
                float gx = (src[ym * width + xp] + 2 * src[y * width + xp] + src[yp * width + xp])
                        - (src[ym * width + xm] + 2 * src[y * width + xm] + src[yp * width + xm]);
                float gy = (src[yp * width + xm] + 2 * src[yp * width + x] + src[yp * width + xp])
                        - (src[ym * width + xm] + 2 * src[ym * width + x] + src[ym * width + xp]);
                out[y * width + x] = (float) Math.sqrt(gx * gx + gy * gy);
            }
        }
        return out;
    }

    private static float percentile(float[] values, float fraction) {
        float[] copy = values.clone();
        Arrays.sort(copy);
        int i = Math.min(copy.length - 1, Math.max(0, Math.round(fraction * (copy.length - 1))));
        return copy[i];
    }
}
