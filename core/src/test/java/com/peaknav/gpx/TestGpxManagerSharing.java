package com.peaknav.gpx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TestGpxManagerSharing {

    private static final String GPX = "<gpx version=\"1.1\"><trk><name>t</name><trkseg>"
            + "<trkpt lat=\"46.0207\" lon=\"7.7491\"/><trkpt lat=\"46.0080\" lon=\"7.7680\"/>"
            + "</trkseg></trk></gpx>";

    @Test
    void onlyTracksFromNowhereOnTheDeviceAreOffered() {
        GpxManager manager = new GpxManager();
        // Opened from a file: already a file, nothing to offer.
        assertEquals(1, manager.loadFromXml(GPX, false));
        assertFalse(manager.hasShareable());

        // Downloaded or made on the map: offered, with its text and a file name.
        assertEquals(1, manager.loadShareableXml(GPX, "https://example.org/tracks/Monte%20Bondone.gpx?x=1", false));
        assertTrue(manager.hasShareable());
        assertEquals(GPX, manager.getShareableXml());
        assertEquals("Monte_20Bondone.gpx", manager.getShareableName());

        manager.clear();
        assertFalse(manager.hasShareable(), "cleared with the tracks");
        assertNull(manager.getShareableXml());

        // Nothing parsed, nothing offered.
        assertEquals(0, manager.loadShareableXml("<gpx/>", "empty", false));
        assertFalse(manager.hasShareable());
    }

    @Test
    void aRouteMadeOnTheMapIsMarkedAsHavingComputedHeights() {
        GpxManager manager = new GpxManager();
        assertEquals(1, manager.loadFromXml(GPX, false));
        assertFalse(manager.getTracks().get(0).hasComputedHeights(), "a file's heights were recorded");
        assertEquals(1, manager.loadComputedXml(GPX, "PeakNav_route", false));
        assertTrue(manager.getTracks().get(1).hasComputedHeights(), "a route's came from the terrain");
        assertTrue(manager.hasShareable(), "and it can be saved or shared, like a download");
        assertEquals("PeakNav_route.gpx", manager.getShareableName());
    }

    @Test
    void fileNamesAreSafeOnEveryFileSystem() {
        assertEquals("PeakNav_route_46.00800_7.76800.gpx", GpxManager.safeFileName("PeakNav_route_46.00800_7.76800"));
        assertEquals("track.gpx", GpxManager.safeFileName("C:\\Users\\me\\track.GPX"));
        assertEquals("a_b_c.gpx", GpxManager.safeFileName("a b:c"));
        assertEquals("PeakNav_track.gpx", GpxManager.safeFileName("https://example.org/"));
        assertEquals("PeakNav_track.gpx", GpxManager.safeFileName(null));
        assertEquals("hidden.gpx", GpxManager.safeFileName("..hidden"));
    }
}
