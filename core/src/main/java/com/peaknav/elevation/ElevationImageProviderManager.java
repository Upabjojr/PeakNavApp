package com.peaknav.elevation;

import static com.peaknav.utils.PeakNavUtils.getC;

import com.peaknav.utils.TileAndZoomElevFactor;
import com.peaknav.utils.PeakNavThreadExecutor;
import com.peaknav.viewer.tiles.MapTile;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.MercatorProjection;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

public class ElevationImageProviderManager {
    public static final ConcurrentHashMap<TileAndZoomElevFactor, ElevationImageProvider> providers = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<Tile, TileAndZoomElevFactor> mapToRescale = new ConcurrentHashMap<>();
    private final Set<TileAndZoomElevFactor> loadInProgress = Collections.synchronizedSet(new HashSet<>());

    private final PeakNavThreadExecutor executorLoadElevationData = new PeakNavThreadExecutor(1, "load-elev-data");
    private volatile Tile targetQueueBlock = null;

    public ElevationImageProviderManager() {}

    private void checkProviderQueue(TileAndZoomElevFactor cbr, ElevationImageProvider provider) {
        Tile cb = cbr.tile;
        synchronized (providers) {
            if (targetQueueBlock != null && targetQueueBlock.equals(cb)) {
                setCurrentPositionFromProvider(
                        getC().L.getTargetLatitude(), getC().L.getTargetLongitude(), provider);
                targetQueueBlock = null;
            }
        }
    }

    public ElevationImageProvider getProvider(TileAndZoomElevFactor tileZoomElev) {
        if (providers.containsKey(tileZoomElev)) {
            ElevationImageProvider provider = providers.get(tileZoomElev);
            checkProviderQueue(tileZoomElev, provider);
            return provider;
        }
        return null;
    }

    public Future<?> queueForLoadingProvider(TileAndZoomElevFactor tileZoomElev) {
        return executorLoadElevationData.submit(() -> {
            if (!addToLoadInProgress(tileZoomElev))
                return;
            ElevationImageProvider provider = new ElevationImageProvider(tileZoomElev);
            provider.loadElevationData();

            synchronized (providers) {
                providers.put(tileZoomElev, provider);
                Tile cb = tileZoomElev.tile;
                if (mapToRescale.containsKey(cb)) {
                    TileAndZoomElevFactor otherCb = mapToRescale.get(cb);
                    if (otherCb.zoomElevFactor > tileZoomElev.zoomElevFactor) {
                        mapToRescale.put(cb, tileZoomElev);
                    }
                } else {
                    mapToRescale.put(cb, tileZoomElev);
                }
            }
            removeFromLoadInProgress(tileZoomElev);
            checkProviderQueue(tileZoomElev, provider);
        });
    }

    public ElevationImageProvider getProviderOrQueueForLoading(TileAndZoomElevFactor tileZoomElev) {
        ElevationImageProvider provider = getProvider(tileZoomElev);
        if (provider != null)
            return provider;

        queueForLoadingProvider(tileZoomElev);
        return null;
    }

    private void setCurrentPositionFromProvider(float targetLat, float targetLon, ElevationImageProvider provider) {
        assert provider.getElevationImage() != null;
        List<MapTile> mapTiles  = getC().mapTileStorage.getMapTiles();
        ElevationImageAbstract elevationImage = null;
        if (mapTiles.size() > 0) {
            MapTile mapTile = mapTiles.get(0);
            if (mapTile.tileBoundingBox.toMapsforgeBoundingBox().contains(
                    new LatLong(targetLat, targetLon))) {
                elevationImage = mapTile.elevationImage;
            }
        }
        if (elevationImage == null) {
            elevationImage = provider.getElevationImage();
        }
        float ele = elevationImage.getTileElevationLatitsFromMaxCoords(targetLon, targetLat);
        getC().L.setCurrentFinalCoords(targetLat, targetLon, ele);
    }

    private boolean addToLoadInProgress(TileAndZoomElevFactor cbr) {
        synchronized (loadInProgress) {
            if (loadInProgress.contains(cbr))
                return false;
            if (providers.containsKey(cbr))
                return false;
            loadInProgress.add(cbr);
            return true;
        }
    }

    private void removeFromLoadInProgress(TileAndZoomElevFactor cbr) {
        synchronized (loadInProgress) {
            if (loadInProgress.contains(cbr)) {
                loadInProgress.remove(cbr);
            }
        }
    }

    public void setProviderForTargetCoords(float targetLatitude, float targetLongitude) {
        // TODO: check if there is already some loaded provider to use
        // TODO: if there is one, use it! But remember to load other tiles later
        // TODO: if there is none, just load it!


        int tileX = MercatorProjection.longitudeToTileX(targetLongitude, MapTile.ZOOM_LEVEL_MIN);
        int tileY = MercatorProjection.latitudeToTileY(targetLatitude, MapTile.ZOOM_LEVEL_MIN);
        Tile cb = new Tile(tileX, tileY, MapTile.ZOOM_LEVEL_MIN, MapTile.MF_ZOOM);
        synchronized (providers) {
            boolean flag = true;
            if (mapToRescale.containsKey(cb)) {
                TileAndZoomElevFactor cbr = mapToRescale.get(cb);
                ElevationImageProvider provider = providers.get(cbr);
                if (cbr.zoomElevFactor <= 2) {
                    flag = false;
                }
                setCurrentPositionFromProvider(targetLatitude, targetLongitude, provider);
            }
            if (flag) {
                targetQueueBlock = cb;
            }
        }
    }

    public void clearProviders() {
        mapToRescale.clear();
        providers.clear();
    }
}
