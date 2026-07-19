import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Polygon;
import com.peaknav.viewer.labels.LabelOverlapIndex;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * LabelOverlapIndex replaces an all-pairs scan, so the thing that actually matters is that it
 * keeps exactly the same labels the brute-force version kept. These tests compare the two
 * label-for-label on randomised layouts that mimic the on-screen label rectangles.
 */
public class TestLabelOverlapIndex {

    private static final int SCREEN_W = 1920;
    private static final int SCREEN_H = 1080;

    /** The original algorithm: compare each candidate against every polygon accepted so far. */
    private static boolean[] bruteForce(List<Polygon> candidates) {
        boolean[] placed = new boolean[candidates.size()];
        List<Polygon> accepted = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Polygon candidate = candidates.get(i);
            boolean overlaps = false;
            for (Polygon previous : accepted) {
                if (Intersector.overlapConvexPolygons(candidate, previous)) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                accepted.add(candidate);
            }
            placed[i] = !overlaps;
        }
        return placed;
    }

    private static boolean[] indexed(List<Polygon> candidates) {
        LabelOverlapIndex index = new LabelOverlapIndex();
        index.reset(SCREEN_W, SCREEN_H);
        boolean[] placed = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            placed[i] = index.tryPlace(candidates.get(i));
        }
        return placed;
    }

    /** Builds a label-like rotated rectangle. Rotations mirror DrawLabelCategory (0/30/45 deg). */
    private static Polygon label(float x, float y, float w, float h, float rotation) {
        Polygon polygon = new Polygon(new float[]{0, 0, w, 0, w, h, 0, h});
        polygon.setPosition(0, 0);
        polygon.setRotation(rotation);
        polygon.translate(x, y);
        return polygon;
    }

    private static List<Polygon> randomLabels(Random random, int count, float spreadX, float spreadY) {
        float[] rotations = {0f, 30f, 45f};
        List<Polygon> labels = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            float w = 60f + random.nextFloat() * 220f;   // label text widths
            float h = 18f + random.nextFloat() * 22f;    // label text heights
            float x = random.nextFloat() * spreadX;
            float y = random.nextFloat() * spreadY;
            labels.add(label(x, y, w, h, rotations[random.nextInt(rotations.length)]));
        }
        return labels;
    }

    private static void assertSameDecisions(List<Polygon> labels) {
        boolean[] expected = bruteForce(labels);
        boolean[] actual = indexed(labels);
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i],
                    "label " + i + " placement differs from the brute-force result");
        }
    }

    @Test
    public void matchesBruteForceOnRandomLayouts() {
        for (int seed = 0; seed < 40; seed++) {
            Random random = new Random(seed);
            assertSameDecisions(randomLabels(random, 300, SCREEN_W, SCREEN_H));
        }
    }

    @Test
    public void matchesBruteForceWhenLabelsAreDenselyPacked() {
        // Many labels crammed into a small area: lots of genuine overlaps.
        for (int seed = 0; seed < 20; seed++) {
            Random random = new Random(1000 + seed);
            assertSameDecisions(randomLabels(random, 400, 500, 300));
        }
    }

    @Test
    public void matchesBruteForceForLabelsProjectedOutsideTheScreen() {
        // POIs outside the frustum project to coordinates far off screen, including negatives.
        // Those still take part in the overlap pass, and grid cells are clamped, so verify the
        // clamping stays conservative rather than dropping comparisons.
        for (int seed = 0; seed < 20; seed++) {
            Random random = new Random(2000 + seed);
            List<Polygon> labels = new ArrayList<>();
            for (int i = 0; i < 250; i++) {
                float x = -6000f + random.nextFloat() * 14000f;
                float y = -4000f + random.nextFloat() * 9000f;
                labels.add(label(x, y, 80f + random.nextFloat() * 200f, 20f,
                        new float[]{0f, 30f, 45f}[random.nextInt(3)]));
            }
            assertSameDecisions(labels);
        }
    }

    @Test
    public void identicalLabelsOnlyKeepTheFirst() {
        List<Polygon> labels = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            labels.add(label(400, 400, 150, 25, 0));
        }
        boolean[] placed = indexed(labels);
        assertTrue(placed[0], "the first label should always be placed");
        for (int i = 1; i < placed.length; i++) {
            assertTrue(!placed[i], "duplicate label " + i + " should be hidden");
        }
    }

    @Test
    public void farApartLabelsAreAllPlaced() {
        List<Polygon> labels = new ArrayList<>();
        for (int row = 0; row < 8; row++) {
            for (int cCol = 0; cCol < 6; cCol++) {
                labels.add(label(cCol * 300f, row * 120f, 100f, 25f, 0f));
            }
        }
        for (boolean placed : indexed(labels)) {
            assertTrue(placed, "well separated labels should never be hidden");
        }
    }

    @Test
    public void resetClearsPreviouslyPlacedLabels() {
        LabelOverlapIndex index = new LabelOverlapIndex();
        index.reset(SCREEN_W, SCREEN_H);
        assertTrue(index.tryPlace(label(400, 400, 150, 25, 0)));
        assertTrue(!index.tryPlace(label(400, 400, 150, 25, 0)));

        index.reset(SCREEN_W, SCREEN_H);
        assertTrue(index.tryPlace(label(400, 400, 150, 25, 0)),
                "after reset the same label must fit again");
    }
}
