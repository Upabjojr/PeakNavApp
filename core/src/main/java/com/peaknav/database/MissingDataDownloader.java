package com.peaknav.database;


import static com.peaknav.utils.PeakNavUtils.getC;

import com.peaknav.network.PeakNavHttpCompressDownloader;
import com.peaknav.network.PeakNavDownloadManager;
import com.peaknav.pbf.PbfLayer;

import com.peaknav.geo.Tile;

import java.util.List;

public class MissingDataDownloader {

    public PeakNavDownloadManager getPeakNavDownloadManager() {
        return peakNavDownloadManager;
    }

    public final PeakNavDownloadManager peakNavDownloadManager;
    private volatile double lat;
    private volatile double lon;

    public MissingDataDownloader(PeakNavHttpCompressDownloader eleDown, MapSqlite mapSqlite) {
        this.peakNavDownloadManager = new PeakNavDownloadManager(
                mapSqlite, eleDown,
                PbfLayer.ZOOM_LEVEL_POI, 3,
                PbfLayer.ZOOM_LEVEL_HIGHWAYS, 3);
    }

    public void setCoords(double lat, double lon) {
        this.lat = lat;
        this.lon = lon;
    }

    public static com.peaknav.geo.BoundingBox getBoundingBoxOfTargetTiles(List<Tile> targetTiles) {
        com.peaknav.geo.BoundingBox bb = targetTiles.get(0).getBoundingBox();
        for (Tile targetTile : targetTiles) {
            com.peaknav.geo.BoundingBox bb1 = targetTile.getBoundingBox();
            bb = bb.extendBoundingBox(bb1);
        }
        return bb;
    }

    public PeakNavDownloadManager.Outcome doDownload() {
        return doDownload(false);
    }

    /** The area around the coordinates last set with {@link #setCoords}; see {@link #download(double, double, boolean)}. */
    public PeakNavDownloadManager.Outcome doDownload(boolean goToLocation) {
        return download(lat, lon, goToLocation);
    }

    /**
     * Queues the area around a place, fetches the queue, and optionally goes there.
     *
     * <p>The place is this call's own, not fields set beforehand, and one download runs at a
     * time: the place screen's, the banner's and the one taken up at start could overlap, and
     * with shared coordinates one queued or went to the other's area; on iOS their database
     * transactions nested, and the exception that threw from the inner one's end aborted both.
     */
    public PeakNavDownloadManager.Outcome download(double lat, double lon, boolean goToLocation) {
        // Read before waiting for its turn: a stop meanwhile is meant for this one too.
        int generation = peakNavDownloadManager.cancelGeneration();
        synchronized (this) {
            if (peakNavDownloadManager.cancelGeneration() != generation) {
                return PeakNavDownloadManager.Outcome.cancelledBeforeStart();
            }
            this.lat = lat;
            this.lon = lon;
            return download(true, goToLocation, lat, lon, generation);
        }
    }

    /** The user stopped the download: the one running, and the ones waiting behind it. */
    public void cancel() {
        peakNavDownloadManager.cancel();
    }

    /**
     * Takes up a download the app was closed during: the tiles still queued from it, and no new
     * area. They are fetched and the map refreshed as any download does.
     */
    public PeakNavDownloadManager.Outcome resumeQueued() {
        int generation = peakNavDownloadManager.cancelGeneration();
        synchronized (this) {
            if (peakNavDownloadManager.cancelGeneration() != generation) {
                return PeakNavDownloadManager.Outcome.cancelledBeforeStart();
            }
            return download(false, false, lat, lon, generation);
        }
    }

    private PeakNavDownloadManager.Outcome download(boolean queueArea, boolean goToLocation,
                                                    double lat, double lon, int generation) {

        // TODO: add checks to avoid re-downloading the same file multiple times:

        // The shared figure too, not only the bar: the welcome screen reads that one, and it
        // still held the last download's end.
        com.peaknav.compatibility.PeakNavAppState.getAppState().setMapDataDownloadProgressRatio(0f);

        if (queueArea) {
            peakNavDownloadManager.addDataToQueue(lat, lon);
        }

        PeakNavDownloadManager.Outcome outcome = peakNavDownloadManager.processQueue(generation);

        // This should be able to redraw the missing tiles after downloading
        // more data from the internet:
        getC().elevationImageProviderManager.clearProviders();
        // Area labels are cached by tile, including the "there is nothing here" answer, so the
        // tiles that just arrived would stay invisible until a restart without this.
        getC().areaRegistry.invalidateCache();

        // Not after a stop: the place was to be gone to once its data was here.
        if (goToLocation && !outcome.cancelled) {
            getC().L.setCurrentTargetCoordsAfterTileUpdates(lat, lon);
        }

        getC().tileManager.updateMapTiles(true);

        return outcome;
    }

    public double getLongitude() {
        return lon;
    }

    public double getLatitude() {
        return lat;
    }
}
