package com.peaknav.utils;

import org.mapsforge.core.model.Tile;

public class TileAndZoomElevFactor {
    public final Tile tile;
    public final int zoomElevFactor;

    public TileAndZoomElevFactor(Tile tile, int zoomElevFactor) {
        this.tile = tile;
        this.zoomElevFactor = zoomElevFactor;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof TileAndZoomElevFactor) {
            TileAndZoomElevFactor ob = (TileAndZoomElevFactor) other;
            return tile.equals(ob.tile) && zoomElevFactor == ob.zoomElevFactor;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return tile.hashCode() + 100000* zoomElevFactor;
    }

    /*
    public CoordIntBlock getWithoutRescale() {
        return new CoordIntBlock(latitude, longitude);
    }
     */
}
