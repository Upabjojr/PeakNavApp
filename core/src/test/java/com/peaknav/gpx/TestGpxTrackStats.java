package com.peaknav.gpx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.utils.PreferencesManager.UnitSystem;

import org.junit.jupiter.api.Test;

public class TestGpxTrackStats {

    private static GpxTrack track(double[][] latLonEle) {
        GpxTrack track = new GpxTrack("Test");
        for (double[] p : latLonEle) {
            boolean hasEle = p.length > 2;
            track.add((float) p[0], (float) p[1], hasEle ? (float) p[2] : 0f, hasEle, 0L, false);
        }
        return track;
    }

    @Test
    void walkingTimeFollowsDin33466() {
        // 12 km on the flat: 3 h.
        assertEquals(180, GpxTrackStats.walkingMinutes(12000, 0, 0), 1e-9);
        // 4 km and 900 m up: 60 min across, 180 min up -> 180 + 30.
        assertEquals(210, GpxTrackStats.walkingMinutes(4000, 900, 0), 1e-9);
        // 6 km, 500 m down: 90 min across, 60 min down -> 90 + 30.
        assertEquals(120, GpxTrackStats.walkingMinutes(6000, 0, 500), 1e-9);
    }

    @Test
    void climbsDropsAndProfileOfARecordedTrack() {
        // Up 600 m with a few metres of GPS jitter on the way, then down 200 m.
        GpxTrack t = track(new double[][]{
                {46.000, 7.700, 1000}, {46.005, 7.700, 1003}, {46.010, 7.700, 1001},
                {46.015, 7.700, 1300}, {46.020, 7.700, 1600}, {46.025, 7.700, 1400}});
        GpxTrackStats stats = GpxTrackStats.of(t, null);
        assertNotNull(stats);
        assertEquals(5 * 556, stats.distanceMetres, 5, "about 556 m per 0.005 degrees of latitude");
        assertEquals(600, stats.ascentMetres, 1e-6, "the jitter of 3 m is not climbing");
        assertEquals(200, stats.descentMetres, 1e-6);
        assertEquals(1600, stats.highestMetres, 1e-6);
        assertEquals(1000, stats.lowestMetres, 1e-6);
        assertEquals(GpxTrackStats.PROFILE_SAMPLES, stats.profileMetres.length);
        assertEquals(1000, stats.profileMetres[0], 1e-3);
        assertEquals(1400, stats.profileMetres[stats.profileMetres.length - 1], 1e-3);
        // GpxTrack keeps coordinates as floats: about a metre of precision.
        assertEquals(46.0, stats.startLat, 1e-5);
        assertEquals(46.025, stats.endLat, 1e-5);
    }

    @Test
    void heightsComeFromTheTerrainWhenTheGpxHasNone() {
        GpxTrack t = track(new double[][]{{46.000, 7.700}, {46.010, 7.700}, {46.020, 7.700}});
        GpxTrackStats withoutTerrain = GpxTrackStats.of(t, null);
        assertTrue(Double.isNaN(withoutTerrain.highestMetres));
        assertNull(withoutTerrain.profileMetres, "no heights, no profile");
        assertEquals(0, withoutTerrain.ascentMetres, 1e-9);

        GpxTrackStats withTerrain = GpxTrackStats.of(t, (lat, lon) -> (float) (1000 + (lat - 46.0) * 50_000));
        assertEquals(1000, withTerrain.ascentMetres, 0.1);
        assertEquals(2000, withTerrain.highestMetres, 0.1);
        assertNull(GpxTrackStats.of(track(new double[][]{{46, 7}}), null), "a single point is no track");
    }

    @Test
    void numbersAreWrittenInTheChosenUnits() {
        assertEquals("12.4 km", GpxTrackStats.formatDistance(12400, UnitSystem.METRIC));
        assertEquals("850 m", GpxTrackStats.formatDistance(850, UnitSystem.METRIC));
        assertEquals("7.7 mi", GpxTrackStats.formatDistance(12400, UnitSystem.IMPERIAL));
        assertEquals("2789 ft", GpxTrackStats.formatDistance(850, UnitSystem.IMPERIAL));
        assertEquals("1234 m", GpxTrackStats.formatHeight(1234.4, UnitSystem.METRIC));
        assertEquals("4049 ft", GpxTrackStats.formatHeight(1234.2, UnitSystem.IMPERIAL));
        assertEquals("-", GpxTrackStats.formatHeight(Double.NaN, UnitSystem.METRIC));
        assertEquals("3 h 05 min", GpxTrackStats.formatDuration(184.6));
        assertEquals("40 min", GpxTrackStats.formatDuration(40.2));
        assertEquals("46.02070 N, 7.74910 E", GpxTrackStats.formatPosition(46.0207, 7.7491));
        assertEquals("33.44890 S, 70.66930 W", GpxTrackStats.formatPosition(-33.4489, -70.6693));
    }
}
