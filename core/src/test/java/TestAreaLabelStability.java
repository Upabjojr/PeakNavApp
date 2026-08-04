import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.areas.MapArea;
import com.peaknav.viewer.labels.AreaLabelStability;

import org.junit.jupiter.api.Test;

/**
 * Area labels used to blink while the camera orbited a summit. Two independent causes, both
 * re-evaluated at frame rate: the "hidden by mountains" test, which samples depth pixmaps rendered
 * for an older camera position and so flips verdict on anything grazing a silhouette, and the
 * competition for a contested spot, which two near-tied names traded every frame.
 *
 * <p>These tests drive the smoothing with the patterns that caused the flicker — an alternating
 * terrain verdict, and a label drifting a pixel in and out of a neighbour — and check that the
 * label on screen stays put while a genuine, sustained change still gets through.
 */
public class TestAreaLabelStability {

    private static MapArea area(String name) {
        return new MapArea(name, "mountain_range", 46.0f, 8.0f, 12f, 6f, 0f, 3000f, 80f, 0);
    }

    /** Half-second decisions, as the renderer makes them. */
    private static long at(int decision) {
        return 1_000_000L + decision * 500L;
    }

    @Test
    public void alternatingTerrainVerdictsDoNotMoveTheLabel() {
        AreaLabelStability stability = new AreaLabelStability();
        MapArea range = area("Mischabel");
        assertTrue(stability.record(range, true, at(0)), "first sighting is believed at once");

        // The raw test alternates, as it does for an area grazing a ridge line while the camera
        // circles: every other decision says hidden. The verdict must not follow it.
        for (int decision = 1; decision <= 20; decision++) {
            boolean raw = decision % 2 == 0;
            assertTrue(stability.record(range, raw, at(decision)),
                    "label dropped on decision " + decision + " of an alternating terrain test");
        }
    }

    @Test
    public void sustainedOcclusionStillHidesTheLabel() {
        AreaLabelStability stability = new AreaLabelStability();
        MapArea range = area("Weisshorn");
        stability.record(range, true, at(0));

        assertTrue(stability.record(range, false, at(1)), "one dissent is not enough");
        assertFalse(stability.record(range, false, at(2)), "two running dissents overturn it");
        assertFalse(stability.record(range, false, at(3)), "and it stays hidden");
    }

    @Test
    public void dissentDoesNotAccumulateAcrossAgreement() {
        AreaLabelStability stability = new AreaLabelStability();
        MapArea range = area("Grand Combin");
        stability.record(range, true, at(0));

        // hidden, visible, hidden, visible ... never twice running, so the verdict holds -
        // otherwise stray samples would add up over a long orbit and eventually flip it.
        for (int decision = 1; decision <= 9; decision += 2) {
            assertTrue(stability.record(range, false, at(decision)));
            assertTrue(stability.record(range, true, at(decision + 1)));
        }
        assertTrue(stability.lastVerdict(range));
    }

    @Test
    public void anAreaComingBackIntoViewIsBelievedImmediately() {
        AreaLabelStability stability = new AreaLabelStability();
        MapArea range = area("Monte Rosa");
        assertFalse(stability.lastVerdict(range), "never sampled: nothing on screen to keep");
        assertTrue(stability.record(range, true, at(0)), "appears without waiting for a second vote");
    }

    @Test
    public void framesBetweenDecisionsReuseTheVerdict() {
        AreaLabelStability stability = new AreaLabelStability();
        MapArea range = area("Dent Blanche");
        stability.record(range, true, at(0));
        // ~30 frames pass before the next decision; each reads the same answer, and none of them
        // costs a depth-pixmap sample.
        for (int frame = 0; frame < 30; frame++) {
            assertTrue(stability.lastVerdict(range));
        }
        assertEquals(1, stability.remembered());
    }

    // ------------------------------------------------------------------ de-overlap slack

    @Test
    public void aSittingLabelKeepsItsSpotThroughAGrazingOverlap() {
        // Two names side by side, the second drifting a couple of pixels into the first as the
        // camera turns. Without slack the incumbent is displaced and takes the spot back moments
        // later - the blink. With slack it holds.
        float slack = 6f;
        for (float drift = 0f; drift <= 5f; drift += 1f) {
            assertFalse(AreaLabelStability.namesOverlap(100f, 50f, 80f, 20f, slack,
                            180f - drift, 50f, 80f, 20f),
                    "incumbent displaced by a " + drift + "px graze");
        }
    }

    @Test
    public void aRealOverlapStillDisplacesTheIncumbent() {
        float slack = 6f;
        assertTrue(AreaLabelStability.namesOverlap(100f, 50f, 80f, 20f, slack,
                        140f, 50f, 80f, 20f),
                "half-covered names must not both be drawn");
    }

    @Test
    public void newcomersGetNoSlack() {
        // Same geometry as the grazing case, but the candidate is not the sitting label: touching
        // is enough to keep it out, so nothing is drawn on top of what is already there.
        assertTrue(AreaLabelStability.namesOverlap(100f, 50f, 80f, 20f, 0f,
                179f, 50f, 80f, 20f));
    }

    @Test
    public void slackWiderThanTheLabelDoesNotInvertIt() {
        // A short name with generous slack would shrink to a negative-width rectangle, which
        // overlaps nothing at all - it would be drawn straight over its neighbour.
        assertTrue(AreaLabelStability.namesOverlap(100f, 50f, 10f, 8f, 40f,
                        60f, 40f, 100f, 40f),
                "a label swallowed by its own slack still collides at its centre");
    }

    // ------------------------------------------------------------ lakes and their islands

    private static MapArea lake(String name, float lat, float lon, float majorKm, float minorKm,
                                float rotationDeg) {
        return new MapArea(name, "lake", lat, lon, majorKm, minorKm, rotationDeg, 0f, 60f, 0);
    }

    @Test
    public void anIslandInsideALakeIsRecognisedAsInsideIt() {
        // Isola Bella sits in Lake Maggiore; the lake's name must not lose to the islet's.
        MapArea maggiore = lake("Lago Maggiore", 45.9500f, 8.6000f, 30f, 5f, 20f);
        assertTrue(AreaLabelStability.ellipseContains(maggiore, 45.9500f, 8.6000f),
                "the centre is inside");
        assertFalse(AreaLabelStability.ellipseContains(maggiore, 46.6000f, 8.6000f),
                "a point 70 km north is not");
    }

    @Test
    public void containmentFollowsTheEllipseRotation() {
        // A long thin lake lying east-west: a point 8 km east is in it, the same distance
        // north is not. Rotate the lake by a right angle and the two swap.
        MapArea eastWest = lake("Long", 46.0f, 8.0f, 10f, 2f, 0f);
        float eastKm = 8f / (111.32f * (float) Math.cos(Math.toRadians(46.0)));
        assertTrue(AreaLabelStability.ellipseContains(eastWest, 46.0f, 8.0f + eastKm));
        assertFalse(AreaLabelStability.ellipseContains(eastWest, 46.0f + 8f / 111.32f, 8.0f));

        MapArea northSouth = lake("Long", 46.0f, 8.0f, 10f, 2f, 90f);
        assertFalse(AreaLabelStability.ellipseContains(northSouth, 46.0f, 8.0f + eastKm));
        assertTrue(AreaLabelStability.ellipseContains(northSouth, 46.0f + 8f / 111.32f, 8.0f));
    }

    @Test
    public void nothingIsInsideANullArea() {
        assertFalse(AreaLabelStability.ellipseContains(null, 46f, 8f));
    }
}
