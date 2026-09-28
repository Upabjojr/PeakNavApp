package com.peaknav.pbf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.peaknav.geo.Tile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tiles around the one a place is in, where the world's edge is near: the antimeridian,
 * which the columns go round, and the first and last rows, which they do not.
 */
class TestPoiNeighbours {

    private static final byte ZOOM = 9;
    private static final int LAST = (1 << ZOOM) - 1;

    @Test
    @DisplayName("west of the first column is the last one, and east of the last the first")
    void roundTheAntimeridian() {
        Tile west = new Tile(0, 200, ZOOM, 256);      // 180 W: the Aleutians
        assertEquals(LAST, PbfMapDataStore.neighbour(west, -1, 0).tileX);
        assertEquals(LAST - 1, PbfMapDataStore.neighbour(west, -2, 0).tileX);
        assertEquals(1, PbfMapDataStore.neighbour(west, 1, 0).tileX);

        Tile east = new Tile(LAST, 260, ZOOM, 256);   // 180 E: Fiji
        assertEquals(0, PbfMapDataStore.neighbour(east, 1, 0).tileX);
        assertEquals(1, PbfMapDataStore.neighbour(east, 2, 0).tileX);
        assertEquals(260, PbfMapDataStore.neighbour(east, 2, 0).tileY);
    }

    @Test
    @DisplayName("there is nothing above the first row or below the last")
    void noRowsBeyondThePoles() {
        assertNull(PbfMapDataStore.neighbour(new Tile(10, 0, ZOOM, 256), 0, -1));
        assertNull(PbfMapDataStore.neighbour(new Tile(10, LAST, ZOOM, 256), 0, 1));
        assertNotNull(PbfMapDataStore.neighbour(new Tile(10, LAST, ZOOM, 256), 0, 0));
    }

    @Test
    @DisplayName("away from the edges a neighbour is the tile beside it")
    void inTheMiddle() {
        Tile centre = new Tile(268, 181, ZOOM, 256);   // the Alps
        Tile beside = PbfMapDataStore.neighbour(centre, -2, 1);
        assertEquals(266, beside.tileX);
        assertEquals(182, beside.tileY);
        assertEquals(ZOOM, beside.zoomLevel);
    }
}
