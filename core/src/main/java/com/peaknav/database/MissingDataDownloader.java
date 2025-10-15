package com.peaknav.database;


import static com.peaknav.utils.PeakNavUtils.getC;

import com.badlogic.gdx.Gdx;
import com.peaknav.network.PeakNavHttpCompressDownloader;
import com.peaknav.network.PeakNavDownloadManager;
import com.peaknav.pbf.PbfLayer;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.widgets.WidgetGetter;

import org.mapsforge.core.model.Tile;

import java.util.List;

public class MissingDataDownloader {

    public PeakNavDownloadManager getPeakNavDownloadManager() {
        return peakNavDownloadManager;
    }

    public final PeakNavDownloadManager peakNavDownloadManager;
    private double lat;
    private double lon;

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

    public static org.mapsforge.core.model.BoundingBox getBoundingBoxOfTargetTiles(List<Tile> targetTiles) {
        org.mapsforge.core.model.BoundingBox bb = targetTiles.get(0).getBoundingBox();
        for (Tile targetTile : targetTiles) {
            org.mapsforge.core.model.BoundingBox bb1 = targetTile.getBoundingBox();
            bb = bb.extendBoundingBox(bb1);
        }
        return bb;
    }

    public void doDownload() {
        doDownload(false);
    }

    public void doDownload(boolean goToLocation) {

        // TODO: add checks to avoid re-downloading the same file multiple times:

        Gdx.app.postRunnable(() -> {
            WidgetGetter.TableLocation tableLocation = MapViewerSingleton.getViewerInstance().tableLocation;
            tableLocation.progressBar.setValue(0.f);
            tableLocation.progressBarTable.setVisible(true);
        });

        peakNavDownloadManager.addDataToQueue(lat, lon);

        peakNavDownloadManager.processQueue();

        // This should be able to redraw the missing tiles after downloading
        // more data from the internet:
        getC().elevationImageProviderManager.clearProviders();

        if (goToLocation) {
            getC().L.setCurrentTargetCoordsAfterTileUpdates(lat, lon);
        }

        getC().tileManager.updateMapTiles(true);

    }

    public double getLongitude() {
        return lon;
    }

    public double getLatitude() {
        return lat;
    }
}
