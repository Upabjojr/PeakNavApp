package com.peaknav.utils;

import org.mapsforge.core.model.BoundingBox;

public class BoundingBoxUtils {

    public static BoundingBox convertToBoundingBox(TileBoundingBox tileBoundingBox) {
        return new BoundingBox(tileBoundingBox.south,
                tileBoundingBox.west, tileBoundingBox.north, tileBoundingBox.east);
    }

    public static BoundingBox getIntersection(BoundingBox bb, BoundingBox tileBb) {
        double crdX1, crdY1, crdX2, crdY2;

        if (!bb.intersects(tileBb))
            return null;

        crdX1 = Math.max(tileBb.minLongitude, bb.minLongitude);
        crdX2 = Math.min(tileBb.maxLongitude, bb.maxLongitude);
        crdY1 = Math.max(tileBb.minLatitude, bb.minLatitude);
        crdY2 = Math.min(tileBb.maxLatitude, bb.maxLatitude);

        return new BoundingBox(crdY2, crdX1, crdY1, crdX2);
    }

}
