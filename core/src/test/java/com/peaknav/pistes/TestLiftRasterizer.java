package com.peaknav.pistes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.geo.LatLong;
import com.peaknav.pbf.Tag;
import com.peaknav.pbf.Way;
import com.peaknav.roads.RoadClass;
import com.peaknav.roads.RoadFeature;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TestLiftRasterizer {

    private static final double N = 46.01, S = 46.00, E = 11.01, W = 11.00;
    private static final int RES = 256;

    private static Way way(String[] tags, double... latLon) {
        List<Tag> list = new ArrayList<>();
        for (int i = 0; i + 1 < tags.length; i += 2) {
            list.add(new Tag(tags[i], tags[i + 1]));
        }
        LatLong[] points = new LatLong[latLon.length / 2];
        for (int i = 0; i < points.length; i++) {
            points[i] = new LatLong(latLon[2 * i], latLon[2 * i + 1]);
        }
        return new Way((byte) 0, list, new LatLong[][]{points});
    }

    private static int texel(double lat, double lon) {
        int x = (int) ((lon - W) / (E - W) * RES), y = (int) ((N - lat) / (N - S) * RES);
        return (y * RES + x) * 4;
    }

    private static double phase(PisteRasterizer.Result r, double lat, double lon) {
        int o = texel(lat, lon);
        return Math.atan2((r.rgba[o] & 0xFF) / 255.0 * 2 - 1, (r.rgba[o + 1] & 0xFF) / 255.0 * 2 - 1) / (2 * Math.PI);
    }

    @Test
    void kindsOfLift() {
        assertEquals(LiftRasterizer.Kind.CABLE_CAR, LiftRasterizer.kindOf(Collections.singletonList(new Tag("aerialway", "cable_car"))));
        assertEquals(LiftRasterizer.Kind.GONDOLA, LiftRasterizer.kindOf(Collections.singletonList(new Tag("aerialway", "gondola"))));
        assertEquals(LiftRasterizer.Kind.GONDOLA, LiftRasterizer.kindOf(Collections.singletonList(new Tag("aerialway", "mixed_lift"))));
        assertEquals(LiftRasterizer.Kind.CHAIR_LIFT, LiftRasterizer.kindOf(Collections.singletonList(new Tag("aerialway", "chair_lift"))));
        for (String drag : new String[]{"drag_lift", "t-bar", "j-bar", "platter", "rope_tow"}) {
            assertEquals(LiftRasterizer.Kind.DRAG_LIFT, LiftRasterizer.kindOf(Collections.singletonList(new Tag("aerialway", drag))), drag);
        }
        assertEquals(LiftRasterizer.Kind.MAGIC_CARPET, LiftRasterizer.kindOf(Collections.singletonList(new Tag("aerialway", "magic_carpet"))));
        for (String not : new String[]{"goods", "zip_line", "station", "pylon", "abandoned", "proposed"}) {
            assertNull(LiftRasterizer.kindOf(Collections.singletonList(new Tag("aerialway", not))), not);
        }
        assertNull(LiftRasterizer.kindOf(Arrays.asList(new Tag("type", "route"), new Tag("aerialway", "chair_lift"))),
                "only a way's own tag makes it a lift");
        // Each kind's code sits in the middle of its own fifth.
        assertEquals(0.1f, LiftRasterizer.code(LiftRasterizer.Kind.CABLE_CAR), 1e-6);
        assertEquals(0.9f, LiftRasterizer.code(LiftRasterizer.Kind.MAGIC_CARPET), 1e-6);
    }

    @Test
    void aLiftIsADistanceFieldTravellingUphill() {
        double lat = 46.005;
        // Drawn west to east, from the top station down: the terrain rises to the west.
        Way lift = way(new String[]{"aerialway", "chair_lift", "name", "Sunnegga"}, lat, 11.001, lat, 11.009);
        PisteRasterizer.Elevation risesWest = (la, lo) -> (float) (2500 - (lo - W) * 50_000);
        PisteRasterizer.Result r = LiftRasterizer.rasterize(Collections.singletonList(lift), N, S, E, W, RES, risesWest);
        assertTrue(!r.empty);
        int centre = texel(lat, 11.005);
        // A texel centre lies up to half a texel off the line: the field there is nearly, not quite, 1.
        double metresPerTexel = (E - W) * 111320.0 * Math.cos(Math.toRadians(lat)) / RES;
        float reach = LiftRasterizer.reachTexels(metresPerTexel);
        assertTrue((r.rgba[centre + 3] & 0xFF) >= Math.round(255 * (1 - 0.71f / reach)),
                "about 1 on the line: " + (r.rgba[centre + 3] & 0xFF));
        assertEquals(Math.round(LiftRasterizer.code(LiftRasterizer.Kind.CHAIR_LIFT) * 255), r.rgba[centre + 2] & 0xFF);
        double fiveMetres = 5 / 111320.0;
        int off = r.rgba[texel(lat + fiveMetres, 11.005) + 3] & 0xFF;
        assertTrue(off > 0 && off < 255, "falling off away from it: " + off);
        assertEquals(0, r.rgba[texel(lat + 40 / 111320.0, 11.005) + 3] & 0xFF, "nothing well away from it");

        double forty = 40 / (111320.0 * Math.cos(Math.toRadians(lat)));
        double step = phase(r, lat, 11.005 - forty) - phase(r, lat, 11.005);
        step -= Math.floor(step + 0.5);
        assertEquals(40 / LiftRasterizer.PATTERN_METRES, step, 0.03, "the phase grows westwards, uphill");
    }

    @Test
    void liftsAreLabelledByNameAndNumber() {
        Way lift = way(new String[]{"aerialway", "gondola", "name", "Tulot - Malga Cioca", "ref", "60"},
                46.001, 11.001, 46.009, 11.009);
        Way goods = way(new String[]{"aerialway", "goods", "name", "Materiale"}, 46.002, 11.002, 46.008, 11.002);
        List<RoadFeature> features = LiftRasterizer.labelFeatures(Arrays.asList(lift, goods));
        assertEquals(1, features.size(), "a goods lift is not labelled");
        assertEquals(RoadClass.LIFT, features.get(0).roadClass);
        assertEquals("Tulot - Malga Cioca", features.get(0).name);
        assertEquals("60", features.get(0).number);
        assertTrue(!com.peaknav.roads.RoadLabelPlanner.planLifts(features, N, S, E, W).isEmpty());
        assertTrue(com.peaknav.roads.RoadLabelPlanner.planPistes(features, N, S, E, W).isEmpty(), "lifts are not pistes");
    }
}
