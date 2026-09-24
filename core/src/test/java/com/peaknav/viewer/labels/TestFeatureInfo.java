package com.peaknav.viewer.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.areas.MapArea;
import com.peaknav.utils.PreferencesManager.UnitSystem;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

public class TestFeatureInfo {

    private static FeatureInfo.Row row(FeatureInfo info, String label) {
        for (FeatureInfo.Row row : info.rows) {
            if (row.label.equals(label)) {
                return row;
            }
        }
        return null;
    }

    @Test
    public void aLakeTellsItsSurfaceSizeAndWhereItIs() {
        MapArea lake = new MapArea("Lago di Garda", "lake", 45.65f, 10.68f, 25f, 6f, 0f, 65f, 0f, 0, "Q6414");
        // Standing at Riva del Garda, at its northern end.
        FeatureInfo info = FeatureInfo.of(lake, new FeatureInfo.Viewer(45.886, 10.842, "it", UnitSystem.METRIC));
        assertEquals("Lago di Garda", info.title);
        assertEquals("Feature kind lake", info.kind);
        assertEquals("65 m", row(info, "Feature elevation").value);
        assertEquals("~ 50.0 km × 12.0 km", row(info, "Feature size").value);
        assertEquals("29.1 km", row(info, "Feature distance").value);
        assertTrue(row(info, "Feature direction").value.endsWith(" 206°"), row(info, "Feature direction").value);
        assertEquals("https://www.wikidata.org/wiki/Q6414", row(info, "Feature wikidata").url);
        assertNull(row(info, "Feature population"));
        assertTrue(info.tags.isEmpty());
    }

    @Test
    public void aRangeTellsItsHighestPoint() {
        MapArea range = new MapArea("Dolomiti di Brenta", "mountain_range", 46.17f, 10.88f, 20f, 6f, 30f, 3173f, 0f, 0);
        FeatureInfo info = FeatureInfo.of(range, new FeatureInfo.Viewer(Double.NaN, Double.NaN, "en", UnitSystem.IMPERIAL));
        assertEquals("Feature kind mountain range", info.kind);
        assertEquals("10410 ft", row(info, "Feature highest point").value);
        assertNull(row(info, "Feature distance"), "no distance when where the viewer stands is not known");
        assertNull(row(info, "Feature wikidata"));
    }

    @Test
    public void otherNamesLeaveOutTheShownOneAndRepeats() {
        Map<String, String> tags = new HashMap<>();
        tags.put("name", "Matterhorn");
        tags.put("name:it", "Monte Cervino");
        tags.put("alt_name", "Cervin;Monte Cervino");
        tags.put("name:ja", "マッターホルン");
        assertEquals("Monte Cervino, Cervin", FeatureInfo.otherNames("Matterhorn", tags, "it"));
        assertEquals("Cervin, Monte Cervino", FeatureInfo.otherNames("Matterhorn", tags, "en"));
        assertNull(FeatureInfo.otherNames("Matterhorn", new HashMap<String, String>(), "en"));
    }

    @Test
    public void linksAreMadeFromHowTheyAreTagged() {
        assertEquals("https://de.wikipedia.org/wiki/Z%C3%BCrich_See",
                FeatureInfo.wikipediaLink("de:Zürich See"));
        assertNull(FeatureInfo.wikipediaLink("Matterhorn"));
        assertEquals("https://www.rifugio.it", FeatureInfo.webLink("www.rifugio.it"));
        assertEquals("http://a.ch/x", FeatureInfo.webLink("http://a.ch/x; https://b.ch"));
        assertNull(FeatureInfo.wikidataLink("not an item"));
    }

    @Test
    public void bearingsGoClockwiseFromNorth() {
        assertEquals(0, FeatureInfo.bearingDegrees(46, 8, 46.1, 8), 0.01);
        assertEquals(90, FeatureInfo.bearingDegrees(0, 8, 0, 8.1), 0.01);
        assertEquals(180, FeatureInfo.bearingDegrees(46.1, 8, 46, 8), 0.01);
        assertEquals(270, FeatureInfo.bearingDegrees(0, 8.1, 0, 8), 0.01);
        assertEquals(111195, FeatureInfo.distanceMetres(0, 0, 1, 0), 1);
    }

    @Test
    public void tagsThatAreLinksCanBeOpened() {
        assertEquals("https://example.org/hut", FeatureInfo.tagLink("contact:facebook", "https://example.org/hut"));
        assertEquals("https://www.rifugio.it", FeatureInfo.tagLink("website", "www.rifugio.it"));
        assertEquals("https://www.wikidata.org/wiki/Q1374", FeatureInfo.tagLink("wikidata", "Q1374"));
        assertEquals("https://www.wikidata.org/wiki/Q42", FeatureInfo.tagLink("brand:wikidata", "Q42"));
        assertEquals("https://de.wikipedia.org/wiki/Matterhorn", FeatureInfo.tagLink("wikipedia", "de:Matterhorn"));
        assertEquals("https://commons.wikimedia.org/wiki/Category:Matterhorn",
                FeatureInfo.tagLink("wikimedia_commons", "Category:Matterhorn"));
        assertEquals("mailto:info@rifugio.it", FeatureInfo.tagLink("email", "info@rifugio.it"));
        assertEquals("tel:+390465501200", FeatureInfo.tagLink("phone", "+39 0465 501200"));
        assertNull(FeatureInfo.tagLink("ele", "4478"));
        assertNull(FeatureInfo.tagLink("image", "a picture"));
        assertEquals("https://www.openstreetmap.org/node/281747458", FeatureInfo.osmNodeLink(281747458L));
    }
}
