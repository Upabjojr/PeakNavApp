import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.viewer.labels.PoiObject;

import org.junit.jupiter.api.Test;

/**
 * Which of two summits claims a contested label spot.
 *
 * <p>Written from the real data in the app's own POI extract for Fuji. Both the mountain and
 * the point on its crater rim carry {@code isolation_parent=1000000000} - a sentinel meaning
 * "nothing higher near", not a distance - so the old score, {@code max(elevation,
 * isolation_parent/10)}, was a hundred million for both and every such pair tied. Order then
 * fell to however the list was built, which is why Fuji appeared only sometimes.
 */
public class TestPeakPriority {

    private static final int NOTHING_HIGHER_NEAR = 1_000_000_000;

    private static final int NOTHING_HIGHER_NEAR_2 = NOTHING_HIGHER_NEAR;

    /** Does the first of the two claim the spot? Larger rank wins. */
    private static boolean firstWins(float prom1, float ele1, int iso1,
                                     float prom2, float ele2, int iso2) {
        return PoiObject.peakRank(prom1, ele1, iso1) > PoiObject.peakRank(prom2, ele2, iso2);
    }

    @Test
    public void theMountainOutranksItsOwnCraterRim() {
        // The exact figures from ~/.peaknav/map_folder: Kengamine is 20 cm HIGHER and carries
        // no prominence; Fuji carries prominence=3776. Both share the isolation sentinel, so
        // every other term ties and the rim used to win on height.
        assertTrue(firstWins(3776f, 3776.2f, NOTHING_HIGHER_NEAR,
                        -1f, 3776.2f, NOTHING_HIGHER_NEAR),
                "Fuji must outrank the point on its own crater rim");
    }

    @Test
    public void betweenTwoDocumentedMountainsTheMoreProminentWins() {
        assertTrue(firstWins(3776f, 3776f, NOTHING_HIGHER_NEAR, 300f, 2693f, 2620));
    }

    @Test
    public void withoutProminenceTheOldOrderingStillApplies() {
        // The crater's minor summits carry no prominence and sane isolation values; among
        // them height still decides, as it always did.
        assertTrue(firstWins(-1f, 3749f, 220, -1f, 3733f, 220));
    }

    @Test
    public void theIsolationSentinelNoLongerManufacturesTies() {
        // Two untagged peaks both carrying the sentinel: the old score made them equal at a
        // hundred million each. Height must decide instead.
        assertTrue(firstWins(-1f, 4000f, NOTHING_HIGHER_NEAR, -1f, 3000f, NOTHING_HIGHER_NEAR));
    }
}
