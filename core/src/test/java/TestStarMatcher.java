import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.peaknav.sky.StarCatalog;
import com.peaknav.sky.StarPositions;
import com.peaknav.stars.StarExtractor;
import com.peaknav.stars.StarMatcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The star matcher on a photograph painted from the app's own catalogue: the real sky over
 * the Alps one September night, a camera pose chosen in advance, every star bright enough
 * drawn where that camera would see it (with noise, a light-pollution gradient and a Moon
 * to ignore), and the pose recovered from the picture alone - with and without knowing
 * the field of view, from a prior some way off, and refused for a picture of nothing.
 *
 * <p>The thresholds in {@link StarMatcher} were set with these cases and the extractor's
 * numbers in mind; a synthetic sky is kinder than a phone's (no lens distortion, no
 * clouds), so the tolerances here are tight and the real-world margin is in the
 * confidence rules, not the accuracy.
 */
class TestStarMatcher {

    private static final double LAT = 46.0, LON = 8.0;
    /** 2026-09-07 22:00 UTC, a September evening: the Summer Triangle high in the south-west. */
    private static final long TIME = 1788818400000L;
    private static final int W = 1200, H = 900;

    /** The catalogue as shipped, read straight from the assets folder. */
    private static StarCatalog catalog() throws IOException {
        File f = new File("../assets/sky/stars.dat");
        if (!f.exists()) {
            f = new File("assets/sky/stars.dat");
        }
        assumeTrue(f.exists(), "no star catalogue at " + f.getAbsolutePath());
        StarCatalog c = new StarCatalog();
        List<float[]> rows = new ArrayList<float[]>();
        BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] p = line.trim().split("\\s+");
                if (p.length < 3) continue;
                rows.add(new float[]{Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2])});
            }
        } finally {
            in.close();
        }
        c.raDeg = new float[rows.size()];
        c.decDeg = new float[rows.size()];
        c.mag = new float[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            c.raDeg[i] = rows.get(i)[0];
            c.decDeg[i] = rows.get(i)[1];
            c.mag[i] = rows.get(i)[2];
        }
        c.count = rows.size();
        return c;
    }

    // ---- an independent pinhole camera, to paint the truth with ----

    private static double[] forward(double bearingDeg, double pitchDeg) {
        double b = Math.toRadians(bearingDeg), p = Math.toRadians(pitchDeg);
        return new double[]{Math.sin(b) * Math.cos(p), Math.cos(b) * Math.cos(p), Math.sin(p)};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] unit(double[] v) {
        double l = Math.sqrt(dot(v, v));
        return new double[]{v[0] / l, v[1] / l, v[2] / l};
    }

    /**
     * Where a direction lands in a picture taken at a pose, or null when out of the frame.
     * Right = forward x up, level up = right x forward, and a positive roll turns the up
     * vector towards the right - the horizon then tilts clockwise, as in the app.
     */
    static double[] project(double[] enu, double bearing, double pitch, double roll, double vfov) {
        double[] fwd = forward(bearing, pitch);
        double[] right0 = unit(cross(fwd, new double[]{0, 0, 1}));
        double[] level = cross(right0, fwd);
        double r = Math.toRadians(roll);
        double[] up = unit(new double[]{
                level[0] * Math.cos(r) - right0[0] * Math.sin(r),
                level[1] * Math.cos(r) - right0[1] * Math.sin(r),
                level[2] * Math.cos(r) - right0[2] * Math.sin(r)});
        double[] right = cross(fwd, up);
        double z = dot(enu, fwd);
        if (z < 0.05) {
            return null;
        }
        double f = (H / 2.0) / Math.tan(Math.toRadians(vfov) / 2);
        double x = W / 2.0 + dot(enu, right) / z * f;
        double y = H / 2.0 - dot(enu, up) / z * f;
        if (x < 0 || y < 0 || x >= W || y >= H) {
            return null;
        }
        return new double[]{x, y};
    }

    /** The night sky as a camera at the pose would photograph it. */
    static int[] paint(StarPositions sky, double bearing, double pitch, double roll, double vfov, long seed) {
        Random rng = new Random(seed);
        float[] lum = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                lum[y * W + x] = 12 + 20f * y / H + 8f * x / W + (float) rng.nextGaussian() * 2.5f;
            }
        }
        double sigma = 1.4;
        int drawn = 0;
        for (int s = 0; s < sky.count; s++) {
            double[] p = project(new double[]{sky.enu[s * 3], sky.enu[s * 3 + 1], sky.enu[s * 3 + 2]},
                    bearing, pitch, roll, vfov);
            if (p == null) {
                continue;
            }
            // A magnitude-0 star peaks at 600 above the sky (clipped at 255, like a phone's
            // sensor); each magnitude is a factor 2.5 fainter, so mag 5 is 6 levels - barely there.
            double peak = 600 * Math.pow(10, -0.4 * sky.mag[s]);
            if (peak < 4) {
                continue;
            }
            drawn++;
            for (int dy = -6; dy <= 6; dy++) {
                for (int dx = -6; dx <= 6; dx++) {
                    int x = (int) Math.round(p[0]) + dx, y = (int) Math.round(p[1]) + dy;
                    if (x < 0 || y < 0 || x >= W || y >= H) continue;
                    double d2 = (x - p[0]) * (x - p[0]) + (y - p[1]) * (y - p[1]);
                    lum[y * W + x] += peak * Math.exp(-d2 / (2 * sigma * sigma));
                }
            }
        }
        assertTrue(drawn >= 15, "only " + drawn + " stars in the frame; pick another pose");
        // The Moon: a saturated disc.
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (Math.hypot(x - 1000, y - 150) < 30) {
                    lum[y * W + x] = 255;
                }
            }
        }
        int[] rgb = new int[W * H];
        for (int i = 0; i < rgb.length; i++) {
            int v = Math.max(0, Math.min(255, Math.round(lum[i])));
            rgb[i] = (v << 16) | (v << 8) | v;
        }
        return rgb;
    }

    private static double angleDiff(double a, double b) {
        double d = (a - b) % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return Math.abs(d);
    }

    private static StarPositions sky() throws IOException {
        return StarPositions.compute(catalog(), LAT, LON, TIME, 6.0f);
    }

    @Test
    @DisplayName("With the field of view known, the pose comes back to a tenth of a degree")
    void knownFieldOfView() throws IOException {
        StarPositions sky = sky();
        double bearing = 215, pitch = 38, roll = 14, vfov = 58;
        StarExtractor.Field field = StarExtractor.extract(paint(sky, bearing, pitch, roll, vfov, 1), W, H);
        assertTrue(field.looksLikeNightSky());
        StarMatcher.Match m = StarMatcher.of(field, sky.enu, sky.mag, sky.count)
                .match(bearing + 20, pitch - 10, StarMatcher.DEFAULT_PRIOR_RADIUS_DEG, (float) vfov);
        assertNotNull(m);
        assertTrue(m.isConfident(), "not confident: " + m);
        assertTrue(angleDiff(m.bearingDeg, bearing) < 0.15, "bearing: " + m);
        assertEquals(pitch, m.pitchDeg, 0.15, "pitch: " + m);
        assertEquals(roll, m.rollDeg, 0.2, "roll: " + m);
        assertEquals(vfov, m.verticalFovDeg, 0.5, "vfov: " + m);
        assertTrue(m.matched >= 12, "matched: " + m);
    }

    @Test
    @DisplayName("Without the field of view, and from a prior well off, it is still found")
    void unknownFieldOfView() throws IOException {
        StarPositions sky = sky();
        double bearing = 215, pitch = 38, roll = -9, vfov = 47;
        StarExtractor.Field field = StarExtractor.extract(paint(sky, bearing, pitch, roll, vfov, 2), W, H);
        StarMatcher.Match m = StarMatcher.of(field, sky.enu, sky.mag, sky.count)
                .match(bearing - 25, pitch + 12, StarMatcher.DEFAULT_PRIOR_RADIUS_DEG, Float.NaN);
        assertNotNull(m);
        assertTrue(m.isConfident(), "not confident: " + m);
        assertTrue(angleDiff(m.bearingDeg, bearing) < 0.3, "bearing: " + m);
        assertEquals(pitch, m.pitchDeg, 0.3, "pitch: " + m);
        assertEquals(roll, m.rollDeg, 0.4, "roll: " + m);
        assertEquals(vfov, m.verticalFovDeg, 1.0, "vfov: " + m);
    }

    @Test
    @DisplayName("A prior pointing the wrong way falls back to the whole sky")
    void wrongPrior() throws IOException {
        StarPositions sky = sky();
        double bearing = 40, pitch = 55, roll = 3, vfov = 62;
        StarExtractor.Field field = StarExtractor.extract(paint(sky, bearing, pitch, roll, vfov, 3), W, H);
        StarMatcher.Match m = StarMatcher.of(field, sky.enu, sky.mag, sky.count)
                .match(bearing + 150, 20, StarMatcher.DEFAULT_PRIOR_RADIUS_DEG, (float) vfov);
        assertNotNull(m);
        assertTrue(m.isConfident(), "not confident: " + m);
        assertTrue(angleDiff(m.bearingDeg, bearing) < 0.2, "bearing: " + m);
        assertEquals(pitch, m.pitchDeg, 0.2, "pitch: " + m);
    }

    @Test
    @DisplayName("Random dots are not a match")
    void noise() throws IOException {
        StarPositions sky = sky();
        Random rng = new Random(5);
        float[][] dots = new float[30][];
        for (int i = 0; i < dots.length; i++) {
            dots[i] = new float[]{20 + rng.nextFloat() * (W - 40), 20 + rng.nextFloat() * (H - 40), 20 + rng.nextInt(200)};
        }
        float[] x = new float[dots.length], y = new float[dots.length];
        for (int i = 0; i < dots.length; i++) {
            x[i] = dots[i][0];
            y[i] = dots[i][1];
        }
        StarMatcher.Match m = new StarMatcher(x, y, W, H, sky.enu, sky.mag, sky.count).match();
        assertTrue(m == null || !m.isConfident(), "random dots matched: " + m);
    }

    @Test
    @DisplayName("Too few sources give no answer at all")
    void tooFew() throws IOException {
        StarPositions sky = sky();
        StarMatcher.Match m = new StarMatcher(new float[]{10, 20}, new float[]{10, 20}, W, H,
                sky.enu, sky.mag, sky.count).match();
        assertFalse(m != null, "two dots are not a picture of the sky");
    }
}
