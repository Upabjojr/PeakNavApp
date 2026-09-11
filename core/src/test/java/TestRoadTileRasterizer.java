import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.roads.RoadClass;
import com.peaknav.roads.RoadFeature;
import com.peaknav.roads.RoadGeo;
import com.peaknav.roads.RoadTextures;
import com.peaknav.roads.RoadTileRasterizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The distance textures the terrain shader draws roads from: that every class lands in its own
 * channel, that the stored distances are the geometry's, and that the dash phase runs along the
 * way so the dashes continue across tile edges.
 */
class TestRoadTileRasterizer {

    // A 0.01 x 0.01 degree tile at 46 N, 64 texels a side.
    private static final double N = 46.01, S = 46.00, E = 7.01, W = 7.00;
    private static final int RES = 64;
    private static final float TOL = 0.06f; // one step of the 8-bit encoding, and a little

    /** A way along the parallel through the tile's middle: exactly between rows 31 and 32. */
    private static RoadFeature across(RoadClass cls, float attribute, double west, double east) {
        return new RoadFeature(cls, attribute,
                new double[]{46.005, 46.005}, new double[]{west, east}, false, null);
    }

    private static RoadTextures raster(RoadFeature... features) {
        return RoadTileRasterizer.rasterize(Arrays.asList(features), N, S, E, W, RES);
    }

    @Test
    @DisplayName("a texel's value is its distance to the line, in texels, in the line's channel")
    void distancesAreTheGeometry() {
        RoadTextures t = raster(across(RoadClass.TRACK, 0f, W, E));
        assertFalse(t.empty);
        // Texel centres sit at half-integers, the line at row 32.0.
        assertEquals(0.5f, t.distanceAt(10, 31, RoadTileRasterizer.CH_TRACK), TOL);
        assertEquals(0.5f, t.distanceAt(10, 32, RoadTileRasterizer.CH_TRACK), TOL);
        assertEquals(2.5f, t.distanceAt(10, 29, RoadTileRasterizer.CH_TRACK), TOL);
        assertEquals(RoadTileRasterizer.BAND, t.distanceAt(10, 10, RoadTileRasterizer.CH_TRACK), TOL,
                "past the band the distance is clamped");
        for (int ch : new int[]{RoadTileRasterizer.CH_ROAD, RoadTileRasterizer.CH_PATH,
                RoadTileRasterizer.CH_PISTE}) {
            assertEquals(RoadTileRasterizer.BAND, t.distanceAt(10, 31, ch), TOL,
                    "a track leaves the other channels empty");
        }
    }

    @Test
    @DisplayName("a major road is drawn wider by lowering its distances, not with a channel of its own")
    void rankIsBakedIntoTheDistance() {
        float service = raster(across(RoadClass.ROAD, RoadFeature.RANK_SERVICE, W, E))
                .distanceAt(10, 31, RoadTileRasterizer.CH_ROAD);
        float major = raster(across(RoadClass.ROAD, RoadFeature.RANK_MAJOR, W, E))
                .distanceAt(10, 31, RoadTileRasterizer.CH_ROAD);
        double mpt = RoadGeo.metersPerTexel(N, S, E, W, RES);
        assertEquals(3.0 / mpt, service - major, TOL, "three metres more half-width");
    }

    @Test
    @DisplayName("an empty tile says so, and holds nothing anywhere")
    void emptyTile() {
        RoadTextures t = raster();
        assertTrue(t.empty);
        for (int ch = 0; ch < 4; ch++) {
            assertEquals(RoadTileRasterizer.BAND, t.distanceAt(5, 5, ch), TOL);
        }
        assertEquals(128, t.auxAt(3, 3, 0), "a zero phase vector: no trail here");
        assertEquals(128, t.auxAt(3, 3, 1));
        RoadTextures far = raster(across(RoadClass.PATH, 0f, 8.0, 8.1));
        assertTrue(far.empty, "a way nowhere near the tile draws nothing");
    }

    @Test
    @DisplayName("the dash phase is the distance along the way, and survives into the next tile")
    void dashPhaseRunsAlongTheWay() {
        // One trail from the west edge of this tile to the east edge of its neighbour.
        RoadFeature trail = across(RoadClass.PATH, RoadFeature.TRAIL_MOUNTAIN, W, E + 0.01);
        RoadTextures west = raster(trail);
        RoadTextures east = RoadTileRasterizer.rasterize(Collections.singletonList(trail),
                N, S, E + 0.01, E, RES);
        int auxRow = 15; // aux texel centres at 15.5 -> 31 full-res, half a texel off the line
        for (int col : new int[]{0, 7, 20, 31}) {
            assertPhase(west, col, auxRow, W, W);
            assertPhase(east, col, auxRow, E, W);
        }
        assertEquals(128, west.auxAt(20, auxRow, 3), 1, "mountain difficulty, 0.5");
    }

    /** The phase stored at an aux texel equals 2 pi (metres from the way's start) / base. */
    private static void assertPhase(RoadTextures t, int col, int row, double tileWest, double wayWest) {
        double lon = tileWest + (2 * col + 1) / (double) RES * 0.01;
        double meters = RoadGeo.metersBetween(46.005, wayWest, 46.005, lon);
        double expected = 2 * Math.PI * meters / RoadTileRasterizer.DASH_BASE_METERS;
        double s = t.auxAt(col, row, 0) / 255.0 * 2 - 1;
        double c = t.auxAt(col, row, 1) / 255.0 * 2 - 1;
        double got = Math.atan2(s, c);
        double diff = Math.atan2(Math.sin(got - expected), Math.cos(got - expected));
        assertEquals(0, diff, 0.05, "phase at aux column " + col + " of the tile at " + tileWest);
        assertEquals(1.0, Math.hypot(s, c), 0.02, "stored as a unit vector");
    }

    @Test
    @DisplayName("a piste area is inside-negative, with its difficulty across it")
    void pisteArea() {
        RoadFeature area = new RoadFeature(RoadClass.PISTE, RoadFeature.PISTE_ADVANCED,
                new double[]{46.002, 46.002, 46.008, 46.008, 46.002},
                new double[]{7.002, 7.008, 7.008, 7.002, 7.002}, true, null);
        RoadTextures t = raster(area);
        assertEquals(-RoadTileRasterizer.BIAS, t.distanceAt(32, 32, RoadTileRasterizer.CH_PISTE), TOL,
                "deep inside, the distance bottoms out at -BIAS");
        assertEquals(RoadTileRasterizer.BAND, t.distanceAt(2, 2, RoadTileRasterizer.CH_PISTE), TOL);
        assertTrue(t.distanceAt(32, 12, RoadTileRasterizer.CH_PISTE) > 0f, "just outside the top edge");
        assertEquals(153, t.auxAt(16, 16, 2), 1, "advanced, 0.6, over the whole area");
    }

    @Test
    @DisplayName("the texture's byte encoding round-trips distances across the band")
    void encoding() {
        for (float d = -RoadTileRasterizer.BIAS; d <= RoadTileRasterizer.BAND; d += 0.37f) {
            assertEquals(d, RoadTileRasterizer.decodeDistance(RoadTileRasterizer.encodeDistance(d)),
                    0.03f);
        }
        assertEquals(RoadTileRasterizer.BAND,
                RoadTileRasterizer.decodeDistance(RoadTileRasterizer.encodeDistance(99f)), 1e-4f);
        assertEquals(-RoadTileRasterizer.BIAS,
                RoadTileRasterizer.decodeDistance(RoadTileRasterizer.encodeDistance(-99f)), 1e-4f);
    }

    @Test
    @DisplayName("at a crossing each class keeps its own line; rivers are not drawn at all")
    void crossing() {
        List<RoadFeature> fs = new ArrayList<>();
        fs.add(across(RoadClass.ROAD, RoadFeature.RANK_SERVICE, W, E));
        fs.add(new RoadFeature(RoadClass.PATH, 0f, new double[]{S, N},
                new double[]{7.005, 7.005}, false, null));
        fs.add(new RoadFeature(RoadClass.WATER, 0f, new double[]{S, N},
                new double[]{7.002, 7.002}, false, "Vispa"));
        RoadTextures t = RoadTileRasterizer.rasterize(fs, N, S, E, W, RES);
        assertEquals(0.5f, t.distanceAt(31, 31, RoadTileRasterizer.CH_ROAD), TOL);
        assertEquals(0.5f, t.distanceAt(31, 31, RoadTileRasterizer.CH_PATH), TOL);
        assertEquals(0.5f, t.distanceAt(31, 5, RoadTileRasterizer.CH_PATH), TOL);
        assertEquals(RoadTileRasterizer.BAND, t.distanceAt(13, 5, RoadTileRasterizer.CH_PATH), TOL,
                "the river left no trace");
    }

    @Test
    @DisplayName("aux and distance textures are the documented sizes, whatever the resolution")
    void sizes() {
        RoadTextures t = RoadTileRasterizer.rasterize(Collections.<RoadFeature>emptyList(),
                N, S, E, W, 256);
        assertEquals(256, t.res);
        assertEquals(128, t.auxRes);
        assertEquals(256 * 256 * 4, t.distance.length);
        assertEquals(128 * 128 * 4, t.aux.length);
        assertEquals(RoadGeo.metersPerTexel(N, S, E, W, 256), t.metersPerTexel, 1e-3);
    }
}
