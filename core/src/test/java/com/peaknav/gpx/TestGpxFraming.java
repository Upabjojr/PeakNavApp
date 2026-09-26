package com.peaknav.gpx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Random;

public class TestGpxFraming {

    /** A portrait phone: 30 degree lens, 1080x2340, with margins like the app's controls. */
    private static final GpxFraming.Screen PHONE = new GpxFraming.Screen(30f, 1080f / 2340f,
            0.18f, 0.16f, 0.37f, 0.21f);
    /** One kilometre in latits, near enough (a degree of latitude is about 111 km). */
    private static final float KM = 1f / 111.32f;

    /** A random walk, like a hike: kilometres across, a few hundred metres of climb. */
    private static float[][] walk(long seed, int n, float stepKm) {
        Random random = new Random(seed);
        float[][] p = new float[3][n];
        float heading = random.nextFloat() * 6.28f;
        for (int i = 1; i < n; i++) {
            heading += (random.nextFloat() - 0.5f) * 0.8f;
            p[0][i] = p[0][i - 1] + (float) Math.sin(heading) * stepKm * KM;
            p[1][i] = p[1][i - 1] + (float) Math.cos(heading) * stepKm * KM;
            p[2][i] = p[2][i - 1] + (random.nextFloat() - 0.3f) * 0.02f * KM;
        }
        return p;
    }

    /** Where a point lands on screen, as the camera's normalised device coordinates. */
    private static float[] project(GpxFraming.Result cam, float x, float y, float z) {
        float[] f = {cam.dirX, cam.dirY, cam.dirZ};
        double h = Math.toRadians(cam.headingDegrees);
        float[] r = {(float) Math.cos(h), (float) -Math.sin(h), 0f};
        float[] u = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
        float px = x - cam.x, py = y - cam.y, pz = z - cam.z;
        float depth = f[0] * px + f[1] * py + f[2] * pz;
        float tv = (float) Math.tan(Math.toRadians(15));
        float th = tv * 1080f / 2340f;
        return new float[]{(r[0] * px + r[1] * py + r[2] * pz) / depth / th,
                (u[0] * px + u[1] * py + u[2] * pz) / depth / tv, depth};
    }

    @Test
    void everyPointLandsInTheFreePartOfTheScreen() {
        for (long seed = 1; seed <= 20; seed++) {
            float[][] p = walk(seed, 300, 0.05f);
            GpxFraming.Result cam = GpxFraming.frame(p[0], p[1], p[2], PHONE, Float.NaN, 0f, 0f, null);
            for (int i = 0; i < p[0].length; i++) {
                float[] s = project(cam, p[0][i], p[1][i], p[2][i]);
                assertTrue(s[2] > 0, "in front of the camera");
                assertTrue(s[0] >= -1 + 2 * 0.18f - 1e-3f && s[0] <= 1 - 2 * 0.16f + 1e-3f, "x " + s[0]);
                assertTrue(s[1] >= -1 + 2 * 0.21f - 1e-3f && s[1] <= 1 - 2 * 0.37f + 1e-3f, "y " + s[1]);
            }
        }
    }

    @Test
    void theTrackTouchesTheFreeRectangleSoItIsShownAsLargeAsItFits() {
        float[][] p = walk(7, 300, 0.05f);
        GpxFraming.Result cam = GpxFraming.frame(p[0], p[1], p[2], PHONE, Float.NaN, 0f, 0f, null);
        float minX = 9, maxX = -9, minY = 9, maxY = -9;
        for (int i = 0; i < p[0].length; i++) {
            float[] s = project(cam, p[0][i], p[1][i], p[2][i]);
            minX = Math.min(minX, s[0]);
            maxX = Math.max(maxX, s[0]);
            minY = Math.min(minY, s[1]);
            maxY = Math.max(maxY, s[1]);
        }
        boolean fillsWidth = minX < -1 + 2 * 0.18f + 1e-3f && maxX > 1 - 2 * 0.16f - 1e-3f;
        boolean fillsHeight = minY < -1 + 2 * 0.21f + 1e-3f && maxY > 1 - 2 * 0.37f - 1e-3f;
        assertTrue(fillsWidth || fillsHeight, "touches both sides in one direction at least");
    }

    @Test
    void aShortTrackIsNotViewedFromCloserThanTheMinimum() {
        float[] xs = {0f, 0.01f * KM}, ys = {0f, 0f}, zs = {0f, 0f};
        GpxFraming.Result cam = GpxFraming.frame(xs, ys, zs, PHONE, Float.NaN, 1.5f * KM, 0f, null);
        float d = (float) Math.sqrt(sq(cam.x - 0.005f * KM) + sq(cam.y) + sq(cam.z));
        assertEquals(1.5f * KM, d, 0.01f * KM);
    }

    @Test
    void theCameraStaysAboveTheGroundUnderIt() {
        float[][] p = walk(3, 200, 0.02f);
        final float ground = 3 * KM;    // a high plateau under wherever the camera ends up
        GpxFraming.Result cam = GpxFraming.frame(p[0], p[1], p[2], PHONE, Float.NaN, 0f, 0.4f * KM,
                (x, y) -> ground);
        assertTrue(cam.z >= ground + 0.4f * KM - 1e-6f, "camera at " + cam.z / KM + " km");
    }

    @Test
    void aMountainInTheWayTurnsTheCameraToAnotherSide() {
        // A straight track west to east; a wall south of it higher than any camera would stand.
        float[] xs = new float[50], ys = new float[50], zs = new float[50];
        for (int i = 0; i < 50; i++) {
            xs[i] = i * 0.1f * KM;
        }
        GpxFraming.Terrain wallToTheSouth = (x, y) -> y < -0.3f * KM ? 20 * KM : 0f;
        GpxFraming.Result cam = GpxFraming.frame(xs, ys, zs, PHONE, Float.NaN, 0f, 0f, wallToTheSouth);
        assertTrue(cam.y > -0.3f * KM, "camera north of the wall, at y " + cam.y / KM + " km");
    }

    @Test
    void lookingUpTheTrackWinsWhenTheViewsAreOtherwiseAlike() {
        // A round track: every heading shows it the same size, so the preference decides.
        float[] xs = new float[72], ys = new float[72], zs = new float[72];
        for (int i = 0; i < 72; i++) {
            double a = Math.toRadians(i * 5);
            xs[i] = (float) Math.cos(a) * KM;
            ys[i] = (float) Math.sin(a) * KM;
        }
        GpxFraming.Result cam = GpxFraming.frame(xs, ys, zs, PHONE, 90f, 0f, 0f, null);
        assertEquals(90f, cam.headingDegrees, 1e-3f);
    }

    private static float sq(float v) {
        return v * v;
    }
}
