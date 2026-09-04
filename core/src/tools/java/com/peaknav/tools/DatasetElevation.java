package com.peaknav.tools;

import com.peaknav.skyline.ElevationSampler;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An {@link ElevationSampler} over the elevation dataset's zoom-8 tiles on disk - the same
 * JPEG+PNG pairs the app downloads (see the README's datasets section and
 * {@code peaknav.terrain} in the Python package for the encoding). Looks in the app's own
 * cache ({@code ~/.peaknav}) and the Python package's ({@code ~/.cache/peaknav/elev_tiles.v2}),
 * or in the directory named by {@code PEAKNAV_ELEV_CACHE}.
 *
 * <p>Off the render thread and outside the app, so it decodes with ImageIO rather than
 * libGDX and keeps whole tiles as {@code short[]} - 32 MB each at full detail; a handful
 * stay cached, enough for one horizon (a 120 km radius touches at most nine).
 */
public final class DatasetElevation implements ElevationSampler {

    private static final int ZOOM = 8;
    private static final int N = 1 << ZOOM;
    private static final int CACHE_TILES = 10;

    private final File[] roots;

    private final Map<Long, short[]> cache = new LinkedHashMap<Long, short[]>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, short[]> eldest) {
            return size() > CACHE_TILES;
        }
    };
    /** Tiles known to be absent, so a coastal block is not re-probed on disk per sample. */
    private final Map<Long, Boolean> missing = new LinkedHashMap<>();

    public DatasetElevation() {
        String home = System.getProperty("user.home");
        String env = System.getenv("PEAKNAV_ELEV_CACHE");
        roots = new File[]{
                env != null ? new File(env) : new File(home, ".peaknav"),
                new File(home, ".peaknav"),
                new File(home, ".cache/peaknav/elev_tiles.v2"),
        };
    }

    /** Whether any tile is available at all - lets callers skip when no data is installed. */
    public boolean hasTileFor(double latitude, double longitude) {
        return tile(tileX(longitude), tileY(latitude)) != null;
    }

    @Override
    public float elevationMeters(double latitude, double longitude) {
        double fx = (longitude + 180.0) / 360.0 * N;
        double fy = (1.0 - mercatorY(latitude) / Math.PI) / 2.0 * N;
        int tx = clamp((int) Math.floor(fx), 0, N - 1);
        int ty = clamp((int) Math.floor(fy), 0, N - 1);
        short[] t = tile(tx, ty);
        if (t == null) {
            return Float.NaN;
        }
        int size = (int) Math.sqrt(t.length);
        int px = clamp((int) ((fx - tx) * size), 0, size - 1);
        int py = clamp((int) ((fy - ty) * size), 0, size - 1);
        return t[py * size + px];
    }

    private static double mercatorY(double latDeg) {
        double lat = Math.toRadians(latDeg);
        return Math.log(Math.tan(lat) + 1.0 / Math.cos(lat));
    }

    private static int tileX(double lon) {
        return clamp((int) Math.floor((lon + 180.0) / 360.0 * N), 0, N - 1);
    }

    private static int tileY(double lat) {
        return clamp((int) Math.floor((1.0 - mercatorY(lat) / Math.PI) / 2.0 * N), 0, N - 1);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private synchronized short[] tile(int x, int y) {
        long key = ((long) x << 20) | y;
        short[] t = cache.get(key);
        if (t != null || missing.containsKey(key)) {
            return t;
        }
        t = load(x, y);
        if (t == null) {
            missing.put(key, Boolean.TRUE);
        } else {
            cache.put(key, t);
        }
        return t;
    }

    private short[] load(int x, int y) {
        String name = String.format(java.util.Locale.ENGLISH, "elev.z08.x%05d.y%05d.f000", x, y);
        for (File root : roots) {
            File[] candidates = {
                    new File(root, String.format(java.util.Locale.ENGLISH,
                            "elev_tiles/zoom_08/x_%05d/y_%05d/%s", x, y, name)),
                    new File(root, String.format(java.util.Locale.ENGLISH, "x%05d/%s", x, name)),
            };
            for (File base : candidates) {
                File jpg = new File(base.getPath() + ".jpg");
                File png = new File(base.getPath() + ".png");
                if (jpg.exists() && png.exists()) {
                    try {
                        return decode(jpg, png);
                    } catch (IOException e) {
                        System.err.println("cannot read " + jpg + ": " + e);
                    }
                }
            }
        }
        return null;
    }

    /**
     * The dataset's encoding: the PNG names a 1024 m band, the JPEG the position inside it
     * in 4 m steps, flipped in odd bands - {@code PeakNavUtils.convertImageBytesToElevationMeters}
     * in the app.
     */
    private static short[] decode(File jpgFile, File pngFile) throws IOException {
        BufferedImage jpg = ImageIO.read(jpgFile);
        BufferedImage png = ImageIO.read(pngFile);
        if (jpg == null || png == null) {
            throw new IOException("undecodable tile");
        }
        int size = jpg.getWidth();
        Raster rj = jpg.getRaster();
        Raster rp = png.getRaster();
        short[] out = new short[size * size];
        int[] rowJ = new int[size];
        int[] rowP = new int[size];
        for (int yy = 0; yy < size; yy++) {
            rj.getSamples(0, yy, size, 1, 0, rowJ);
            rp.getSamples(0, yy, size, 1, 0, rowP);
            for (int xx = 0; xx < size; xx++) {
                int j = rowJ[xx] & 0xFF;
                int band = (rowP[xx] & 0xFF) - 128;
                if ((band & 1) != 0) {
                    j = 255 - j;
                }
                out[yy * size + xx] = (short) ((j + band * 256) * 4);
            }
        }
        return out;
    }
}
