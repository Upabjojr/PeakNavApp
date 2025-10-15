package com.peaknav.utils;

import org.mapsforge.core.model.BoundingBox;

public class TileBoundingBox {
    public float north;
    public float south;
    public float east;
    public float west;

    public TileBoundingBox() {
    }

    public TileBoundingBox(BoundingBox boundingBox) {
        north = (float) boundingBox.maxLatitude;
        south = (float) boundingBox.minLatitude;
        east = (float) boundingBox.maxLongitude;
        west = (float) boundingBox.minLongitude;
    }

    public BoundingBox toMapsforgeBoundingBox() {
        BoundingBox boundingBox = new BoundingBox(
                south, west, north, east
        );
        return boundingBox;
    }
}
