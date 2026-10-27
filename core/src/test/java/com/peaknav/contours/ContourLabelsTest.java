package com.peaknav.contours;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ContourLabelsTest {

    private static final double M_PER_DEG = 111320.0;

    /** A grid over a 2 km square at 46° N whose ground rises eastward by {@code slope}. */
    private static float[] plane(int size, double widthMeters, float base, float slope) {
        float[] e = new float[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                e[y * size + x] = base + slope * (float) (x * widthMeters / (size - 1));
            }
        }
        return e;
    }

    @Test
    void spotsLieOnTheirLevelAndKnowTheSlope() {
        int size = 65;
        double width = 2000;
        double south = 46.0, north = south + width / M_PER_DEG;
        double cos = Math.cos(Math.toRadians(0.5 * (south + north)));
        double west = 7.0, east = west + width / (M_PER_DEG * cos);
        float slope = 0.25f; // 500 m of rise across: levels 1100 to 1500
        List<ContourLabelCandidate> spots = ContourLabelPlanner.plan(
                plane(size, width, 1010f, slope), size, south, north, west, east);
        assertFalse(spots.isEmpty());
        for (ContourLabelCandidate c : spots) {
            assertEquals(0, c.level % ContourLabelPlanner.LEVEL_STEP_METERS);
            assertTrue(c.level >= 1100 && c.level <= 1500, "level " + c.level);
            double eastMeters = (c.lon - west) * M_PER_DEG * cos;
            double ground = 1010 + slope * eastMeters;
            assertEquals(c.level, ground, 1.5, "a spot sits on its own line");
            assertEquals(slope, c.gradientEast, 0.01f);
            assertEquals(0f, c.gradientNorth, 0.01f);
        }
    }

    @Test
    void oneSpotPerLevelAndBlock() {
        int size = 65;
        double width = 2000;
        double south = 46.0, north = south + width / M_PER_DEG;
        double cos = Math.cos(Math.toRadians(0.5 * (south + north)));
        double west = 7.0, east = west + width / (M_PER_DEG * cos);
        List<ContourLabelCandidate> spots = ContourLabelPlanner.plan(
                plane(size, width, 1010f, 0.25f), size, south, north, west, east);
        Map<String, Integer> perBlock = new HashMap<>();
        Map<Integer, Integer> perLevel = new HashMap<>();
        for (ContourLabelCandidate c : spots) {
            String key = c.level + "/" + c.blockX + "/" + c.blockY;
            assertEquals(null, perBlock.put(key, 1), "two spots for " + key);
            perLevel.merge(c.level, 1, Integer::sum);
        }
        // A north-south line 2 km long crosses about eight blocks of 250 m.
        for (int n : perLevel.values()) {
            assertTrue(n >= 7 && n <= 10, "spots on one line: " + n);
        }
    }

    @Test
    void flatGroundHasNoLabels() {
        int size = 33;
        float[] e = new float[size * size];
        java.util.Arrays.fill(e, 1200f);
        assertTrue(ContourLabelPlanner.plan(e, size, 46.0, 46.01, 7.0, 7.01).isEmpty());
    }

    @Test
    void labelsOnlyTheLinesTheShaderDrawsBold() {
        // A pixel spanning 100/7 m of height: the finest interval is 100 m, just reached, so the
        // lines every 400 m are bold but not faded in yet, and those every 800 m fully bold.
        float de = 100f / 7f;
        assertTrue(ContourLabels.labelled(1600, de));
        assertFalse(ContourLabels.labelled(1200, de));
        assertFalse(ContourLabels.labelled(1300, de));
        // A little finer: the 400 m lines have faded in.
        float finer = de * 0.6f;
        assertTrue(ContourLabels.labelled(1200, finer));
        assertFalse(ContourLabels.labelled(1300, finer));
        // Close up the interval stops at 25 m, and every 100 m line is bold.
        assertTrue(ContourLabels.labelled(1300, 0.01f));
        assertTrue(ContourLabels.labelled(2100, 0.01f));
    }

    @Test
    void fewerAmountsKeepFewerBlocks() {
        ContourLabelCandidate c = new ContourLabelCandidate(1000, 46, 7, 0.2f, 0f, -6, 8);
        assertTrue(c.kept(ContourLabels.stride(ContourLabels.AMOUNT_MANY)));
        assertTrue(c.kept(ContourLabels.stride(ContourLabels.AMOUNT_SOME)));
        assertFalse(c.kept(ContourLabels.stride(ContourLabels.AMOUNT_FEW)));
        assertTrue(new ContourLabelCandidate(1000, 46, 7, 0.2f, 0f, -8, 4)
                .kept(ContourLabels.stride(ContourLabels.AMOUNT_FEW)));
    }
}
