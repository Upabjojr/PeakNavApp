package com.peaknav.gpx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * GPX as other programs write it: with a byte-order mark, with namespace prefixes, with
 * heights that are not numbers. Each of these used to end as "no path found", or as an
 * exception while the track's statistics were drawn.
 */
class TestGpxParser {

    private static String gpx(String points) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<gpx version=\"1.1\" creator=\"test\"><trk><name>Test</name><trkseg>"
                + points + "</trkseg></trk></gpx>";
    }

    private static final String TWO_POINTS =
            "<trkpt lat=\"46.0\" lon=\"7.0\"><ele>1000</ele></trkpt>"
            + "<trkpt lat=\"46.01\" lon=\"7.01\"><ele>1100</ele></trkpt>";

    @Test
    @DisplayName("a byte-order mark in front does not hide the track")
    void byteOrderMark() {
        assertEquals(1, GpxParser.parse(gpx(TWO_POINTS)).size());
        assertEquals(1, GpxParser.parse("﻿" + gpx(TWO_POINTS)).size());
        assertEquals(1, GpxParser.parse("﻿\r\n " + gpx(TWO_POINTS)).size());
    }

    @Test
    @DisplayName("elements with a namespace prefix are the same elements")
    void namespacePrefix() {
        String xml = "<gpx:gpx xmlns:gpx=\"http://www.topografix.com/GPX/1/1\"><gpx:trk>"
                + "<gpx:name>Prefixed</gpx:name><gpx:trkseg>"
                + "<gpx:trkpt lat=\"46.0\" lon=\"7.0\"><gpx:ele>1000</gpx:ele></gpx:trkpt>"
                + "<gpx:trkpt lat=\"46.01\" lon=\"7.01\"><gpx:ele>1100</gpx:ele></gpx:trkpt>"
                + "</gpx:trkseg></gpx:trk></gpx:gpx>";
        List<GpxTrack> tracks = GpxParser.parse(xml);
        assertEquals(1, tracks.size());
        assertEquals("Prefixed", tracks.get(0).getName());
        assertEquals(2, tracks.get(0).size());
    }

    @Test
    @DisplayName("decimal character references are read")
    void decimalReferences() {
        String xml = gpx(TWO_POINTS).replace("<name>Test</name>", "<name>Caf&#233; &#x2192; Col</name>");
        List<GpxTrack> tracks = GpxParser.parse(xml);
        assertEquals(1, tracks.size());
        assertEquals("Café → Col", tracks.get(0).getName());
    }

    @Test
    @DisplayName("a height that is not a number is no height; a point that is nowhere is left out")
    void notNumbers() {
        String xml = gpx("<trkpt lat=\"46.0\" lon=\"7.0\"><ele>NaN</ele></trkpt>"
                + "<trkpt lat=\"NaN\" lon=\"7.0\"><ele>1000</ele></trkpt>"
                + "<trkpt lat=\"46.01\" lon=\"Infinity\"><ele>1000</ele></trkpt>"
                + "<trkpt lat=\"46.01\" lon=\"7.01\"><ele>NaN</ele></trkpt>"
                + "<trkpt lat=\"46.02\" lon=\"7.02\"><ele>Infinity</ele></trkpt>");
        List<GpxTrack> tracks = GpxParser.parse(xml);
        assertEquals(1, tracks.size());
        GpxTrack track = tracks.get(0);
        assertEquals(3, track.size());
        // The statistics of such a track are made, not thrown out of.
        GpxTrackStats stats = GpxTrackStats.of(track, null);
        assertNotNull(stats);
        assertFalse(stats.hasTwoProfiles());
    }

    @Test
    @DisplayName("heights that are all missing leave the statistics without a profile, not without a track")
    void noHeightAnywhere() {
        GpxTrack track = new GpxTrack("Test");
        track.add(46.0f, 7.0f, Float.NaN, true, 0L, false);
        track.add(46.01f, 7.01f, Float.NaN, true, 0L, false);
        assertNotNull(GpxTrackStats.of(track, null));
        assertTrue(track.size() == 2);
    }
}
