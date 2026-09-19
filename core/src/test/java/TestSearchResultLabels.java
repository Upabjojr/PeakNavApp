import static org.junit.jupiter.api.Assertions.assertEquals;

import com.peaknav.database.LuceneGeonameSearch;
import com.peaknav.utils.PreferencesManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The line each search result shows. The index stores heights in metres; someone who chose miles
 * and feet reads feet on the map's peak labels, and used to read metres in the search list beside
 * them.
 */
class TestSearchResultLabels {

    @Test
    @DisplayName("a peak's height is written in the reader's own units")
    void elevationFollowsTheUnitSystem() {
        assertEquals("4478 m", LuceneGeonameSearch.GeonameResult.formatElevation(
                4478, PreferencesManager.UnitSystem.METRIC));
        assertEquals("14692 ft", LuceneGeonameSearch.GeonameResult.formatElevation(
                4478, PreferencesManager.UnitSystem.IMPERIAL));
        // Denali, and a hill: rounded to the nearest foot, never a fraction of one.
        assertEquals("20308 ft", LuceneGeonameSearch.GeonameResult.formatElevation(
                6190, PreferencesManager.UnitSystem.IMPERIAL));
        assertEquals("328 ft", LuceneGeonameSearch.GeonameResult.formatElevation(
                100, PreferencesManager.UnitSystem.IMPERIAL));
    }

    @Test
    @DisplayName("with no preferences yet, the result still reads in metres")
    void withoutPreferencesTheLabelIsMetric() {
        PreferencesManager kept = PreferencesManager.P;
        PreferencesManager.P = null;        // before the app has built its preferences
        try {
            LuceneGeonameSearch.GeonameResult peak = new LuceneGeonameSearch.GeonameResult(
                    "Matterhorn", "Matterhorn", 45.9763f, 7.6586f, 0, "CH/IT", 4478, true);
            assertEquals("Matterhorn (CH/IT) (4478 m)", peak.getFullName());
        } finally {
            PreferencesManager.P = kept;
        }
    }

    @Test
    @DisplayName("a place that is not a peak carries no height at all")
    void placesKeepTheirPlainName() {
        LuceneGeonameSearch.GeonameResult town = new LuceneGeonameSearch.GeonameResult(
                "Zermatt", "Zermatt", 46.0207f, 7.7491f, 5643, "CH");
        assertEquals("Zermatt (CH)", town.getFullName());
    }
}
