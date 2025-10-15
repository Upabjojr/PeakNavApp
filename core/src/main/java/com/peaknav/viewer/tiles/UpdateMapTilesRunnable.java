package com.peaknav.viewer.tiles;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.database.CheckMissingData.getMinZoomTile;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.viewer.screens.LabelLoading.State.LOADING;
import static com.peaknav.viewer.screens.LabelLoading.State.LOADING_UPDATING;

import com.peaknav.elevation.ElevationImageStorage;
import com.peaknav.utils.StoppableRunnable;
import com.peaknav.viewer.MapViewerSingleton;

import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.LatLongUtils;
import org.mapsforge.core.util.MercatorProjection;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;

public class UpdateMapTilesRunnable extends StoppableRunnable {
    private static LatLong lastUpdatedPos = null;

    private final boolean forceReload;
    private final float MAX_DISTANCE_METERS = 500000;  // 500 km (reasonable from 25 km elev)
    private LatLong targetLatLong;

    public UpdateMapTilesRunnable(boolean forceReload) {
        this.forceReload = forceReload;
    }

    @Override
    public void run() {
        getAppState().setLastAnyMapTileUpdateTimeToNow();
        updateMapTilesWorker(forceReload);
        getC().dataRetrieveThreadManager.triggerReadData();
        getC().tileManager.startAerialAndDataRenderExecutors();  // here
        getC().L.checkTargetCoordsAfterTileUpdates();
    }

    private final PriorityQueue<Tile> tileIndices = new PriorityQueue<>(64, (t1, t2) -> {
        double d1 = LatLongUtils.distance(
                t1.getBoundingBox().getCenterPoint(), targetLatLong);
        double d2 = LatLongUtils.distance(
                t2.getBoundingBox().getCenterPoint(), targetLatLong);
        return Double.compare(d1, d2);
    });

    public void interruptDrawingThread() {
        getC().dataRetrieveThreadManager.stopRunnableUpdateVisibility();
        PixmapLayers.stopLayerDrawPixmapExecutor();
        getC().tileManager.tileRenderer.execDraw.stopLoop();
    }

    private void updateMapTilesWorker(boolean forceReload) {
        targetLatLong = getC().L.getTargetLatLong();

        if (getC().mapTileStorage.getNumberOfMapTiles() == 0) {
            forceReload = true;
        }

        if (!forceReload && lastUpdatedPos != null && LatLongUtils.sphericalDistance(lastUpdatedPos, targetLatLong) < 200) {
            return;
        }

        MapViewerSingleton.getViewerInstance().labelLoading.setState(LOADING_UPDATING);

        ElevationImageStorage eis = new ElevationImageStorage(
                getMinZoomTile(targetLatLong.getLatitude(), targetLatLong.getLongitude()),
                MapTile.computeZoomElevFactor(MapTile.ZOOM_LEVEL_MIN)
        );
        if (!eis.checkImageExistence()) {
            return;
        }

        // TODO: if there are no downloaded tiles for the current coordinates,
        // there should be some prompts to ask the users if they want to download the missing tiles.

        interruptDrawingThread();

        getC().mapTileStorage.mapTilesForDisposal.clear();

        long totalMemory = getNativeScreenCaller().getTotalMemory();
        double totalMemoryGB = totalMemory / 1024.0 / 1024.0 / 1024.0;

        int step;
        if (totalMemoryGB > 4.4) {
            step = 4;
        } else if (totalMemoryGB > 4.0) {
            step = 3;
        } else {
            step = 2;
        }
        byte zl = MapTile.ZOOM_LEVEL_MIN;
        int tileX = MercatorProjection.longitudeToTileX(targetLatLong.getLongitude(), zl);
        int tileY = MercatorProjection.latitudeToTileY(targetLatLong.getLatitude(), zl);
        int maxTileVal = 1 << zl;
        for (int i = tileX - step; i <= tileX + step; i++) {
            for (int j = tileY - step; j <= tileY + step; j++) {
                if (i < 0 || j < 0 || i >= maxTileVal || j >= maxTileVal) {
                    continue;
                }
                tileIndices.add(new Tile(i, j, zl, MapTile.MF_ZOOM));
            }
        }

        List<MapTile> mapTilesNew = new LinkedList<>();

        // TODO: if "forceReload" is true, all previous threads
        // loading the tiles should be interrupted and restarted:

        while (!tileIndices.isEmpty()) {
            checkStopThrow();
            processAddMapTiles(mapTilesNew, forceReload);
        }

        Collections.sort(mapTilesNew, (mapTile1, mapTile2) -> {
            LatLong o1 = mapTile1.getImpWhiteTileIndex();
            LatLong o2 = mapTile2.getImpWhiteTileIndex();

            double d1 = LatLongUtils.distance(o1, targetLatLong);
            double d2 = LatLongUtils.distance(o2, targetLatLong);

            return Double.compare(d1, d2);
        });

        checkStopThrow();

        getC().mapTileStorage.setMapTileList(mapTilesNew);
        getC().mapTileStorage.queueWeldersForAlreadyDrawnTiles();

        // this has to be at the end, because the thread may be interrupted:
        if (getC().mapTileStorage.getNumberOfMapTiles() > 0) {
            lastUpdatedPos = targetLatLong;
        }

        getC().mapTileStorage.readyToDispose = true;

        MapViewerSingleton.getViewerInstance().labelLoading.setState(LOADING);
    }

    private double getDistance(BoundingBox bb) {
        double distance;
        double lat = targetLatLong.getLatitude();
        double lon = targetLatLong.getLongitude();
        double latDiff = Double.min(Math.abs(bb.minLatitude - lat), Math.abs(bb.maxLatitude - lat));
        double lonDiff = Double.min(Math.abs(bb.minLongitude - lon), Math.abs(bb.maxLongitude - lon));
        if (bb.contains(this.targetLatLong)) {
            distance = 0;
        } else if (bb.minLatitude < lat && lat < bb.maxLatitude) {
            distance = lonDiff;
        } else if (bb.minLongitude < lon && lon < bb.maxLongitude) {
            distance = latDiff;
        } else {
            distance = Math.hypot(latDiff, lonDiff);
        }
        return distance;
    }

    private void addSubTiles(Tile tileIndex) {
        byte zoomLevelP1 = (byte) (tileIndex.zoomLevel + 1);
        int tileX0 = 2*tileIndex.tileX;
        int tileY0 = 2*tileIndex.tileY;
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                tileIndices.add(new Tile(tileX0 + x, tileY0 + y, zoomLevelP1, MapTile.MF_ZOOM));
            }
        }
    }

    private int getDesiredZoomLevel(double distance) {
        int zoomLevelDesired;

        if (distance < 0.1) {
            zoomLevelDesired = 12;
        } else if (distance < 0.2) {
            zoomLevelDesired = 12;
        } else if (distance < 0.25) {
            zoomLevelDesired = 11;
        } else if (distance < 0.35) {
            zoomLevelDesired = 10;
        } else if (distance < 0.45) {
            zoomLevelDesired = 9;
        } else {
            zoomLevelDesired = 8;
        }
        return zoomLevelDesired;
    }

    private void processAddMapTiles(List<MapTile> mapTilesNew, boolean forceReload) {

        Tile tileIndex = tileIndices.remove();

        BoundingBox bb = tileIndex.getBoundingBox();
        double distance = getDistance(bb);
        int zoomLevelDesired = getDesiredZoomLevel(distance);

        if (zoomLevelDesired > tileIndex.zoomLevel) {
            addSubTiles(tileIndex);
            return;
        }

        MapTile newMapTile = getC().mapTileStorage.getFromTileIndexExact(tileIndex);
        if (newMapTile == null || forceReload) {
            newMapTile = addNewMapTile(tileIndex);
        } else {
            getC().mapTileStorage.mapTilesForDisposal.remove(newMapTile);
            newMapTile.setMapTileState(MapTile.MapTileState.ELEVATION_DATA_NOT_LOADED);
        }
        mapTilesNew.add(newMapTile);
    }

    private synchronized MapTile addNewMapTile(Tile tileIndex) {
        checkStopThrow();

        getAppState().setLastAnyMapTileUpdateTimeToNow();
        return new MapTile(tileIndex);

        /*

        double distance = Math.sqrt(Math.pow(startLatdex - latdex, 2) + Math.pow(startLondex - londex, 2));
        float distanceMeters = Units.convertLatitsToMeters((float)distance/SUB);
        if (distanceMeters > MAX_DISTANCE_METERS)
            return null;

        if (distance > 25)
            zoomFactor = Math.max(24, zoomFactor);
        else if (distance > 10)
            zoomFactor = Math.max(16, zoomFactor);
        else if (distance > 8)
            zoomFactor = Math.max(8, zoomFactor);
        else if (distance > 4)
            zoomFactor = Math.max(4, zoomFactor);

        // Make sure the tile width is always 2k+1 where k is integer
        while (1800 % (cropFactor * zoomFactor) != 0)
            zoomFactor++;

        return new MapTile(index, cropFactor, zoomFactor);

         */
    }

}
