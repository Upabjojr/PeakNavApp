package com.peaknav.elevation;

import com.peaknav.utils.TileAndZoomElevFactor;
import com.peaknav.viewer.tiles.MapTile;

import org.mapsforge.core.model.Tile;

import java.util.concurrent.atomic.AtomicInteger;

public class ElevationImageProvider {

    public final Tile tile;
    private final ElevationImageStorage elevationImageStorage;
    private final AtomicInteger referenceCounter = new AtomicInteger(0);
    private boolean loaded;
    private ElevationImageAbstract elevationImage = null;
    private boolean missingElevationFiles = false;

    public ElevationImageProvider(Tile tile, int zoomElevLevel) {
        this.tile = tile;

        elevationImageStorage = new ElevationImageStorage(tile, zoomElevLevel);
    }

    public ElevationImageProvider(TileAndZoomElevFactor cb) {
        this(cb.tile, cb.zoomElevFactor);
    }

    public boolean isMissingElevationFiles() {
        return missingElevationFiles;
    }

    public void loadElevationData() {

        elevationImage = elevationImageStorage.getElevationImage();
        if (elevationImage == null) {
            missingElevationFiles = true;
        } else {
            loaded = true;
        }

    }

    public ElevationImageAbstract provideForMapTile(MapTile mapTile) {
        if (!isLoaded())
            throw new RuntimeException("not loaded");

        return elevationImage.crop(mapTile.tile);
    }

    public boolean isLoaded() {
        return loaded;
    }

    // TODO: this is never disposed of, should be handled somehow:
    public void dispose() {
        if (referenceCounter.get() > 0) {
            throw new RuntimeException("referenceCounter value is greater than zero."
                    + " There are still MapTile objects referencing to it");
        }
        // TODO: add disposal code
        elevationImage.dispose();
    }

    public ElevationImageAbstract getElevationImage() {
        return elevationImage;
    }

    public void incrementReferenceCounter() {
        referenceCounter.incrementAndGet();
    }

    public void decrementReferenceCounter() {
        referenceCounter.decrementAndGet();
    }

    /*
    public void disposalSchedule() {
        scheduledForDisposal = true;
    }
     */

}
