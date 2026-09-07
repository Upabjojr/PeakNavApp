import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.stars.StarExtractor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

/**
 * The star extractor on an invented night sky: Gaussian stars of known sub-pixel centres
 * on a background with a light-pollution gradient and noise, a Moon-sized disc and a
 * bright trail thrown in to be rejected.
 */
class TestStarExtractor {

    private static final int W = 480, H = 360;

    /** The stars planted: {x, y, peak brightness}. */
    private static final float[][] STARS = {
            {100.3f, 50.7f, 200}, {300.6f, 80.2f, 150}, {200.1f, 200.9f, 90}, {420.4f, 300.5f, 60},
            {50.8f, 250.3f, 40}, {380.2f, 150.6f, 30}, {150.5f, 320.1f, 25}, {250.9f, 120.4f, 18},
            {330.3f, 250.7f, 14}, {70.6f, 130.2f, 12},
    };

    static int[] paint(float[][] stars, boolean withMoon, boolean withTrail, long seed) {
        Random rng = new Random(seed);
        float[] lum = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                lum[y * W + x] = 15 + 25f * x / W + 10f * y / H + (float) rng.nextGaussian() * 2.5f;
            }
        }
        double sigma = 1.3;
        for (float[] s : stars) {
            for (int dy = -5; dy <= 5; dy++) {
                for (int dx = -5; dx <= 5; dx++) {
                    int x = Math.round(s[0]) + dx, y = Math.round(s[1]) + dy;
                    if (x < 0 || y < 0 || x >= W || y >= H) continue;
                    double d2 = (x - s[0]) * (x - s[0]) + (y - s[1]) * (y - s[1]);
                    lum[y * W + x] += s[2] * Math.exp(-d2 / (2 * sigma * sigma));
                }
            }
        }
        if (withMoon) {
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    if (Math.hypot(x - 400, y - 60) < 22) {
                        lum[y * W + x] = 250;
                    }
                }
            }
        }
        if (withTrail) {
            for (int x = 20; x < 460; x++) {
                int y = 280 + (x - 20) / 30;
                lum[y * W + x] += 120;
            }
        }
        int[] rgb = new int[W * H];
        for (int i = 0; i < rgb.length; i++) {
            int v = Math.max(0, Math.min(255, Math.round(lum[i])));
            rgb[i] = (v << 16) | (v << 8) | v;
        }
        return rgb;
    }

    @Test
    @DisplayName("Every planted star is found at its centre, brightest first")
    void findsStars() {
        StarExtractor.Field field = StarExtractor.extract(paint(STARS, false, false, 1), W, H);
        assertTrue(field.looksLikeNightSky(), "a dark picture with point sources");
        assertTrue(field.sources.length >= STARS.length, "found " + field.sources.length);
        for (float[] s : STARS) {
            double best = Double.MAX_VALUE;
            for (StarExtractor.Source src : field.sources) {
                best = Math.min(best, Math.hypot(src.x - s[0], src.y - s[1]));
            }
            assertTrue(best < 0.4, "star at " + s[0] + "," + s[1] + " off by " + best);
        }
        for (int i = 1; i < field.sources.length; i++) {
            assertTrue(field.sources[i - 1].flux >= field.sources[i].flux, "sorted by flux");
        }
        // The brightest planted star is the brightest source found.
        assertEquals(STARS[0][0], field.sources[0].x, 0.4);
        assertEquals(STARS[0][1], field.sources[0].y, 0.4);
        assertTrue(field.medianLuminance < 45, "median " + field.medianLuminance);
    }

    @Test
    @DisplayName("A Moon-sized disc and a trail are not stars")
    void rejectsBlobs() {
        StarExtractor.Field field = StarExtractor.extract(paint(STARS, true, true, 2), W, H);
        for (StarExtractor.Source src : field.sources) {
            assertTrue(Math.hypot(src.x - 400, src.y - 60) > 25, "the disc came out as a source: " + src);
            assertTrue(src.area <= 300 && src.peak < 240, "an oversized source: " + src);
            // nothing on the trail's line either
            int trailY = 280 + (Math.round(src.x) - 20) / 30;
            assertTrue(Math.abs(src.y - trailY) > 2 || src.x < 20 || src.x > 460, "on the trail: " + src);
        }
        // The stars away from the disc and the trail are still there.
        int found = 0;
        for (float[] s : STARS) {
            for (StarExtractor.Source src : field.sources) {
                if (Math.hypot(src.x - s[0], src.y - s[1]) < 0.5) {
                    found++;
                    break;
                }
            }
        }
        assertTrue(found >= STARS.length - 1, "found " + found + " of " + STARS.length);
    }

    @Test
    @DisplayName("A bright picture is not a night sky")
    void daylightIsNotNight() {
        Random rng = new Random(3);
        int[] rgb = new int[W * H];
        for (int i = 0; i < rgb.length; i++) {
            int v = 150 + rng.nextInt(60);
            rgb[i] = (v << 16) | (v << 8) | v;
        }
        StarExtractor.Field field = StarExtractor.extract(rgb, W, H);
        assertFalse(field.looksLikeNightSky());
    }

    @Test
    @DisplayName("Reduction averages blocks and keeps a star's light")
    void reduces() {
        int[] rgb = paint(STARS, false, false, 4);
        int[] size = new int[2];
        int[] small = StarExtractor.reduce(rgb, W, H, 240, size);
        assertEquals(240, size[0]);
        assertEquals(180, size[1]);
        // The brightest star, at (100.3, 50.7), lands near (50, 25) in the reduced picture.
        StarExtractor.Field field = StarExtractor.extract(small, size[0], size[1]);
        assertTrue(field.sources.length >= 6, "found " + field.sources.length);
        assertEquals(STARS[0][0] / 2, field.sources[0].x, 0.8);
        assertEquals(STARS[0][1] / 2, field.sources[0].y, 0.8);
    }
}
