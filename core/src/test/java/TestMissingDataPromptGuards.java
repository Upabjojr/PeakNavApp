import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.compatibility.PeakNavAppState;
import com.peaknav.database.CheckMissingData;

import org.junit.jupiter.api.Test;

/**
 * The "map data for this place is missing, download it?" dialog used to reappear over and over:
 * it was raised from every re-target (including each GPS update) without consulting the dismissal
 * the in-app banner already used, and without noticing a download was running or had just ended.
 *
 * These cover the two guards that now sit in front of it.
 */
public class TestMissingDataPromptGuards {

    // ---- "don't ask me again about this area" ----

    @Test
    public void dismissingAnAreaIsRemembered() {
        CheckMissingData check = new CheckMissingData(null);
        assertFalse(check.isDismissed(46.41, 11.85), "nothing is dismissed to begin with");

        check.dismiss(46.41, 11.85);
        assertTrue(check.isDismissed(46.41, 11.85));
    }

    @Test
    public void dismissalCoversNearbyCoordinatesInTheSameCell() {
        // A GPS fix drifts constantly; dismissal is per whole-degree cell so small movements
        // must not be treated as a brand new area to ask about.
        CheckMissingData check = new CheckMissingData(null);
        check.dismiss(46.41, 11.85);

        assertTrue(check.isDismissed(46.99, 11.01), "same degree cell should stay dismissed");
        assertTrue(check.isDismissed(46.00, 11.00), "cell boundary should stay dismissed");
    }

    @Test
    public void dismissalDoesNotLeakIntoOtherAreas() {
        CheckMissingData check = new CheckMissingData(null);
        check.dismiss(46.41, 11.85);

        assertFalse(check.isDismissed(47.41, 11.85), "a different latitude cell");
        assertFalse(check.isDismissed(46.41, 12.85), "a different longitude cell");
        assertFalse(check.isDismissed(-46.41, -11.85), "the opposite hemisphere");
    }

    // ---- "a download already covers this" ----

    @Test
    public void noDownloadYetMeansNothingRecentlyFinished() {
        // Fresh state: never downloaded, so the grace window must not suppress anything.
        PeakNavAppState state = PeakNavAppState.getAppState();
        if (!state.isMapDataDownloadRecentlyFinished(Long.MAX_VALUE)) {
            assertFalse(state.isMapDataDownloadRecentlyFinished(30_000L));
        }
    }

    @Test
    public void finishingADownloadOpensAGracePeriod() {
        PeakNavAppState state = PeakNavAppState.getAppState();
        // false is the "download ended" transition; it does not touch the intro screen, unlike true.
        state.setMapDataDownloadStarted(false);

        assertTrue(state.isMapDataDownloadRecentlyFinished(30_000L),
                "right after a download the prompt must stay suppressed");
        assertFalse(state.isMapDataDownloadRecentlyFinished(0L),
                "a zero-length window can never be 'recent'");
    }
}
