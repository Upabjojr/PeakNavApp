import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.skyline.ElevationSampler;
import com.peaknav.skyline.SkylineExtractor;
import com.peaknav.skyline.SkylineMatcher;
import com.peaknav.skyline.TerrainHorizon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

/**
 * The photo-to-terrain matcher on an invented landscape: a handful of Gaussian peaks on a
 * plain, a camera pose chosen in advance, a "photograph" painted from the terrain's own
 * horizon at that pose (blue sky above the ridge line, textured ground below), and the
 * pose recovered from the picture alone. No dataset, no files - the real-photo numbers
 * live in {@code TestSkylineDataset} and {@code SkylineBenchmark}.
 */
class TestSkylineMatcher {

    /** Observer position; the terrain is defined around it. */
    private static final double LAT = 46.0, LON = 8.0;

    /**
     * Peaks as {bearing deg, distance km, height m, width km}, on a 1000 m plain: six
     * big ones placed by hand, plus a scatter of smaller ones from a fixed seed so the
     * horizon is jagged and every direction has its own signature - smooth bumps alone
     * all look alike through a narrow lens, and the matcher rightly finds them ambiguous.
     */
    private static final double[][] PEAKS = peaks();

    private static double[][] peaks() {
        double[][] big = {
                {30, 12, 2600, 3.0}, {75, 25, 3800, 5.0}, {120, 8, 1900, 2.0},
                {200, 15, 3100, 4.0}, {260, 30, 4200, 6.0}, {330, 10, 2200, 2.5},
        };
        Random rng = new Random(42);
        double[][] all = new double[big.length + 24][];
        System.arraycopy(big, 0, all, 0, big.length);
        for (int i = big.length; i < all.length; i++) {
            all[i] = new double[]{rng.nextDouble() * 360, 4 + rng.nextDouble() * 30,
                    1300 + rng.nextDouble() * 2200, 0.6 + rng.nextDouble() * 1.8};
        }
        return all;
    }

    /** An analytic elevation model: Gaussian peaks on a plain, no data beyond 150 km. */
    private static final ElevationSampler TERRAIN = new ElevationSampler() {
        @Override
        public float elevationMeters(double latitude, double longitude) {
            double north = (latitude - LAT) * 111195.0;
            double east = (longitude - LON) * 111195.0 * Math.cos(Math.toRadians(LAT));
            if (Math.hypot(north, east) > 150000) {
                return Float.NaN;
            }
            double h = 1000;
            for (double[] p : PEAKS) {
                double b = Math.toRadians(p[0]);
                double px = Math.sin(b) * p[1] * 1000, py = Math.cos(b) * p[1] * 1000;
                double d2 = (east - px) * (east - px) + (north - py) * (north - py);
                double w = p[3] * 1000;
                h += (p[2] - 1000) * Math.exp(-d2 / (2 * w * w));
            }
            return (float) h;
        }
    };

    private static float[] paint(TerrainHorizon horizon, float bearing, float pitch, float vfov,
                                 int w, int h, long seed) {
        return paint(horizon, bearing, pitch, vfov, 0f, w, h, seed);
    }

    private static float[] paint(TerrainHorizon horizon, float bearing, float pitch, float vfov,
                                 float roll, int w, int h, long seed) {
        float[] ridge = projected(horizon, bearing, pitch, vfov, roll, w, h);
        Random rng = new Random(seed);
        float[] r = new float[w * h], g = new float[w * h], b = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                boolean sky = y < ridge[x];
                float noise = (float) (rng.nextGaussian() * 0.03);
                if (sky) {
                    r[i] = 0.55f + 0.2f * y / h + noise;
                    g[i] = 0.70f + 0.15f * y / h + noise;
                    b[i] = 0.95f + noise;
                } else {
                    float texture = rng.nextFloat() * 0.25f;
                    r[i] = 0.35f + texture + noise;
                    g[i] = 0.33f + texture + noise;
                    b[i] = 0.30f + texture + noise;
                }
            }
        }
        float[] rgb = new float[3 * w * h];
        System.arraycopy(r, 0, rgb, 0, w * h);
        System.arraycopy(g, 0, rgb, w * h, w * h);
        System.arraycopy(b, 0, rgb, 2 * w * h, w * h);
        return rgb;
    }

    /** The matcher's own projection, reached through a throwaway instance. */
    private static float[] projected(TerrainHorizon horizon, float bearing, float pitch, float vfov,
                                     float roll, int w, int h) {
        float[] zeros = new float[w];
        float[] ones = new float[w];
        java.util.Arrays.fill(ones, 1f);
        return new SkylineMatcher(horizon, zeros, ones, w, h).projectHorizon(bearing, pitch, vfov, roll);
    }

    private static int[] pack(float[] rgb, int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int r = clamp(rgb[i]), g = clamp(rgb[n + i]), b = clamp(rgb[2 * n + i]);
            out[i] = (r << 16) | (g << 8) | b;
        }
        return out;
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255)));
    }

    private static double bearingError(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    @Test
    @DisplayName("the horizon sees the peaks where they were put")
    void horizonShowsThePeaks() {
        TerrainHorizon h = TerrainHorizon.compute(TERRAIN, LAT, LON, 20, 720);
        assertEquals(1.0, h.coverage, 1e-9, "the analytic terrain covers the whole march");
        // Peak at 120 deg: 900 m up over 8 km -> about 6.4 degrees, minus curvature.
        assertTrue(h.angleAt(120) >= Math.toDegrees(Math.atan2(900 - 20, 8000)) - 0.5,
                "peak at 120 deg reads " + h.angleAt(120));
        // Peak at 260 deg: 3200 m up over 30 km -> about 6 degrees (61 m of curvature).
        assertTrue(h.angleAt(260) >= Math.toDegrees(Math.atan2(3200 - 20 - 61, 30000)) - 0.5,
                "peak at 260 deg reads " + h.angleAt(260));
        // The big peaks dominate where they stand; the scatter of small ones never reaches
        // their elevation angles, so the two checks above hold with the scatter present.
        assertTrue(h.reliefDeg(0, 360) > 1.5, "the landscape has relief");
    }

    @Test
    @DisplayName("recovers bearing, pitch and field of view from a painted photograph")
    void recoversThePose() {
        TerrainHorizon horizon = TerrainHorizon.compute(TERRAIN, LAT, LON, 20, 720);
        int w = 480, h = 320;
        float[][] poses = {{235f, 4f, 40f}, {60f, -3f, 55f}, {335f, 8f, 28f}};
        for (float[] pose : poses) {
            int[] rgb = pack(paint(horizon, pose[0], pose[1], pose[2], w, h, 7), w * h);
            SkylineExtractor.Skyline sky = SkylineExtractor.extract(rgb, w, h);
            long t0 = System.nanoTime();
            SkylineMatcher.Match m = new SkylineMatcher(horizon, sky.rows, sky.confidence, w, h).match();
            System.out.println(String.format(java.util.Locale.ENGLISH, "level %s -> %s in %.2fs",
                    java.util.Arrays.toString(pose), m, (System.nanoTime() - t0) / 1e9));
            assertEquals(0, bearingError(m.bearingDeg, pose[0]), 1.5, "bearing for " + m);
            assertEquals(pose[1], m.pitchDeg, 1.0, "pitch for " + m);
            assertEquals(pose[2], m.verticalFovDeg, pose[2] * 0.1, "field of view for " + m);
            assertTrue(m.isConfident(), "a clean synthetic skyline should be a confident match: " + m);
        }
    }

    @Test
    @DisplayName("recovers the roll of a photograph taken with the camera tilted")
    void recoversTheRoll() {
        TerrainHorizon horizon = TerrainHorizon.compute(TERRAIN, LAT, LON, 20, 720);
        int w = 480, h = 320;
        // {bearing, pitch, vfov, roll}: hand-held tilts either way, the last one past the
        // coarse search's outermost roll so the refinement has to walk the rest.
        float[][] poses = {{235f, 4f, 40f, -7f}, {60f, -3f, 55f, 5f}, {335f, 8f, 28f, 3.5f}, {150f, 2f, 45f, 11f}};
        for (float[] pose : poses) {
            int[] rgb = pack(paint(horizon, pose[0], pose[1], pose[2], pose[3], w, h, 5), w * h);
            SkylineExtractor.Skyline sky = SkylineExtractor.extract(rgb, w, h);
            long t0 = System.nanoTime();
            SkylineMatcher.Match m = new SkylineMatcher(horizon, sky.rows, sky.confidence, w, h).match();
            System.out.println(String.format(java.util.Locale.ENGLISH, "tilted %s -> %s in %.2fs",
                    java.util.Arrays.toString(pose), m, (System.nanoTime() - t0) / 1e9));
            assertEquals(0, bearingError(m.bearingDeg, pose[0]), 1.5, "bearing for " + m);
            assertEquals(pose[1], m.pitchDeg, 1.0, "pitch for " + m);
            assertEquals(pose[2], m.verticalFovDeg, pose[2] * 0.1, "field of view for " + m);
            assertEquals(pose[3], m.rollDeg, 1.0, "roll for " + m);
            assertTrue(m.isConfident(), "a clean tilted skyline should still be a confident match: " + m);
        }
    }

    @Test
    @DisplayName("a known field of view narrows the search and still lands on the pose")
    void knownFieldOfView() {
        TerrainHorizon horizon = TerrainHorizon.compute(TERRAIN, LAT, LON, 20, 720);
        int w = 480, h = 360;
        int[] rgb = pack(paint(horizon, 118f, 2f, 33f, w, h, 3), w * h);
        SkylineExtractor.Skyline sky = SkylineExtractor.extract(rgb, w, h);
        SkylineMatcher.Match m = new SkylineMatcher(horizon, sky.rows, sky.confidence, w, h).match(33f);
        assertEquals(0, bearingError(m.bearingDeg, 118), 1.0, m.toString());
        assertEquals(33f, m.verticalFovDeg, 2f, m.toString());
    }

    @Test
    @DisplayName("a flat horizon is never a confident match")
    void flatHorizonIsRefused() {
        ElevationSampler plain = new ElevationSampler() {
            @Override
            public float elevationMeters(double latitude, double longitude) {
                return 500f;
            }
        };
        TerrainHorizon horizon = TerrainHorizon.compute(plain, LAT, LON, 20, 720);
        int w = 480, h = 320;
        int[] rgb = pack(paint(horizon, 90f, 0f, 45f, w, h, 11), w * h);
        SkylineExtractor.Skyline sky = SkylineExtractor.extract(rgb, w, h);
        SkylineMatcher.Match m = new SkylineMatcher(horizon, sky.rows, sky.confidence, w, h).match();
        assertFalse(m.isConfident(), "a sea horizon carries no bearing: " + m);
    }
}
