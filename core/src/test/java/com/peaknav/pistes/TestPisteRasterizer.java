package com.peaknav.pistes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.geo.LatLong;
import com.peaknav.pbf.Tag;
import com.peaknav.pbf.Way;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TestPisteRasterizer {

    // A box of about 780 m by 1110 m, drawn at 256 texels: about 3 m a texel east-west.
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

    private static int texel(PisteRasterizer.Result r, double lat, double lon) {
        int x = (int) ((lon - W) / (E - W) * RES), y = (int) ((N - lat) / (N - S) * RES);
        return (y * RES + x) * 4;
    }

    private static int u(byte b) {
        return b & 0xFF;
    }

    /** The stored flow phase at a texel, in turns. */
    private static double phase(PisteRasterizer.Result r, double lat, double lon) {
        int o = texel(r, lat, lon);
        double sin = u(r.rgba[o]) / 255.0 * 2 - 1, cos = u(r.rgba[o + 1]) / 255.0 * 2 - 1;
        return Math.atan2(sin, cos) / (2 * Math.PI);
    }

    @Test
    void difficultiesAreBlueRedOrBlack() {
        assertEquals(PisteRasterizer.BLUE, PisteRasterizer.difficultyOf(Arrays.asList(new Tag("piste:type", "downhill"), new Tag("piste:difficulty", "novice"))));
        assertEquals(PisteRasterizer.BLUE, PisteRasterizer.difficultyOf(Arrays.asList(new Tag("piste:type", "downhill"), new Tag("piste:difficulty", "easy"))));
        assertEquals(PisteRasterizer.RED, PisteRasterizer.difficultyOf(Arrays.asList(new Tag("piste:type", "downhill"), new Tag("piste:difficulty", "intermediate"))));
        assertEquals(PisteRasterizer.RED, PisteRasterizer.difficultyOf(Collections.singletonList(new Tag("piste:type", "downhill"))), "no grade: red");
        assertEquals(PisteRasterizer.BLACK, PisteRasterizer.difficultyOf(Arrays.asList(new Tag("piste:type", "downhill"), new Tag("piste:difficulty", "advanced"))));
        assertEquals(PisteRasterizer.BLACK, PisteRasterizer.difficultyOf(Arrays.asList(new Tag("piste:type", "downhill"), new Tag("piste:difficulty", "freeride"))));
        assertNull(PisteRasterizer.difficultyOf(Arrays.asList(new Tag("piste:type", "nordic"), new Tag("piste:difficulty", "easy"))), "cross-country is not a slope");
        assertNull(PisteRasterizer.difficultyOf(Collections.singletonList(new Tag("aerialway", "chair_lift"))), "nor is a lift");
    }

    @Test
    void aRunIsFatColouredAndFlowsDownhill() {
        double lat = 46.005;
        // Drawn west to east; the terrain falls to the east.
        Way run = way(new String[]{"piste:type", "downhill", "piste:difficulty", "advanced"}, lat, 11.001, lat, 11.009);
        PisteRasterizer.Elevation fallsEast = (la, lo) -> (float) (2000 - (lo - W) * 50_000);
        PisteRasterizer.Result r = PisteRasterizer.rasterize(Collections.singletonList(run), N, S, E, W, RES, fallsEast);
        assertTrue(!r.empty);
        int centre = texel(r, lat, 11.005);
        assertEquals(255, u(r.rgba[centre + 3]), "covered along its core");
        assertEquals(255, u(r.rgba[centre + 2]), "black");
        // 14 m either side: covered 10 m off the centre line, clear 30 m off.
        double tenMetres = 10 / 111320.0, thirtyMetres = 30 / 111320.0;
        assertEquals(255, u(r.rgba[texel(r, lat + tenMetres, 11.005) + 3]), "fat");
        assertEquals(0, u(r.rgba[texel(r, lat + thirtyMetres, 11.005) + 3]));

        // Along the run the phase grows downhill, eastwards: 20 m further on, about a fifth of a band.
        double twentyMetres = 20 / (111320.0 * Math.cos(Math.toRadians(lat)));
        double step = phase(r, lat, 11.005 + twentyMetres) - phase(r, lat, 11.005);
        step -= Math.floor(step + 0.5);
        assertEquals(20 / PisteRasterizer.PATTERN_METRES, step, 0.05, "grows eastwards, downhill");

        // The same run with the terrain rising to the east flows back west.
        PisteRasterizer.Elevation risesEast = (la, lo) -> (float) (1000 + (lo - W) * 50_000);
        PisteRasterizer.Result up = PisteRasterizer.rasterize(Collections.singletonList(run), N, S, E, W, RES, risesEast);
        double back = phase(up, lat, 11.005 + twentyMetres) - phase(up, lat, 11.005);
        back -= Math.floor(back + 0.5);
        assertEquals(-20 / PisteRasterizer.PATTERN_METRES, back, 0.05, "reversed: downhill is westwards");

        // Without heights, the way's own direction.
        PisteRasterizer.Result unknown = PisteRasterizer.rasterize(Collections.singletonList(run), N, S, E, W, RES, null);
        double own = phase(unknown, lat, 11.005 + twentyMetres) - phase(unknown, lat, 11.005);
        own -= Math.floor(own + 0.5);
        assertTrue(own > 0, "as drawn");
    }

    @Test
    void anAreaIsATranslucentFillWithoutDirection() {
        Way area = way(new String[]{"piste:type", "downhill", "piste:difficulty", "easy", "area", "yes"},
                46.002, 11.002, 46.002, 11.006, 46.006, 11.006, 46.006, 11.002, 46.002, 11.002);
        PisteRasterizer.Result r = PisteRasterizer.rasterize(Collections.singletonList(area), N, S, E, W, RES, null);
        int inside = texel(r, 46.004, 11.004);
        assertEquals(Math.round(PisteRasterizer.AREA_COVERAGE * 255), u(r.rgba[inside + 3]));
        assertEquals(128, u(r.rgba[inside]), "no flow in an area");
        assertEquals(128, u(r.rgba[inside + 1]));
        assertEquals(0, u(r.rgba[inside + 2]), "blue");
        assertEquals(0, u(r.rgba[texel(r, 46.008, 11.004) + 3]), "nothing outside it");
    }

    @Test
    void liftsAndCrossCountryDrawNothing() {
        List<Way> ways = Arrays.asList(
                way(new String[]{"aerialway", "chair_lift"}, 46.002, 11.002, 46.008, 11.008),
                way(new String[]{"piste:type", "nordic", "piste:difficulty", "easy"}, 46.003, 11.001, 46.003, 11.009));
        assertTrue(PisteRasterizer.rasterize(ways, N, S, E, W, RES, null).empty);
        assertTrue(PisteRasterizer.rasterize(new ArrayList<Way>(), N, S, E, W, RES, null).empty);
    }
}
