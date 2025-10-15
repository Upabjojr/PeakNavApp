package com.peaknav.elevation;

import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.Tile;

public class ElevationImageFlat extends ElevationImageAbstract {

    public ElevationImageFlat(Tile tile, BoundingBox boundingBox) {
        this(64, tile, boundingBox);
    }

    private ElevationImageFlat(int edgeLength, Tile tile, BoundingBox boundingBox) {
        super(edgeLength, tile, boundingBox);
    }

    @Override
    protected short[] computeElevationsFromImages() {
        short[] elevationsShort = new short[edgeLength*edgeLength];

        for (int y = 0; y < elevationsShort.length; y++) {;
            elevationsShort[y] = 0;
        }
        return elevationsShort;
    }

    @Override
    public ElevationImageFlat rescale(int newScale) {
        return new ElevationImageFlat(
                edgeLength/newScale,
                tile,
                boundingBox);
    }

    @Override
    public ElevationImageFlat crop(Tile newTile) {
        if (tile == newTile)
            return this;
        return new ElevationImageFlat(newTile, newTile.getBoundingBox());
    }

    @Override
    public void dispose() {
    }
}
