package com.peaknav.elevation;

import static com.peaknav.utils.PeakNavUtils.getC;

import com.peaknav.utils.TileAndZoomElevFactor;
import com.peaknav.utils.PeakNavThreadExecutor;
import com.peaknav.viewer.tiles.MapTile;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.MercatorProjection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
                // Cleared only once a provider actually supplied the elevation, so a block whose
                // files were still missing leaves the request standing for the reload that follows
                // a download.
                if (setCurrentPositionFromProvider(
                        getC().L.getTargetLatitude(), getC().L.getTargetLongitude(), provider)) {
                    targetQueueBlock = null;
                }
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

    /**
     * Reports the ground elevation under the target, which is what puts the camera on the terrain.
     *
     * @return false when this provider has no elevation to give, so the caller can leave the
     *         request pending for one that has. A provider whose files were missing carries a null
     *         image, and the assert that used to stand for this check is compiled out of release
     *         builds — so on a first run, before any data is downloaded, this threw NullPointer
     *         inside the loading thread instead of simply waiting for the data.
     */
    private boolean setCurrentPositionFromProvider(float targetLat, float targetLon, ElevationImageProvider provider) {
        if (provider == null || provider.getElevationImage() == null) {
            return false;
        }
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
        return true;
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

    // Elevation providers used to accumulate for the whole session: one per (min-zoom tile,
    // detail) block ever visited, each holding a storage ElevationImage (two off-heap pixmaps
    // plus short[]/float[] arrays), and nothing removed them except a post-download clear. As
    // the user panned around the world this grew without bound. This caps the cache: providers
    // that no live map tile needs, and that have no crop in flight (referenceCount 0), are the
    // most distant coarse blocks — evicting them frees that memory, and they reload cheaply
    // from local disk if revisited.
    //
    // Safe to run only from the elevation-retrieval thread (the sole caller of
    // submitToExecutor, which is what raises a provider's reference count): a provider seen at
    // referenceCount 0 there has no crop in flight and none can start mid-sweep.
    private static final int MAX_PROVIDERS = 48;

    public void evictUnneededProviders(Set<TileAndZoomElevFactor> stillNeeded,
                                       int targetTileX, int targetTileY) {
        synchronized (providers) {
            int over = providers.size() - MAX_PROVIDERS;
            if (over <= 0)
                return;

            List<TileAndZoomElevFactor> candidates = new ArrayList<>();
            for (Map.Entry<TileAndZoomElevFactor, ElevationImageProvider> entry : providers.entrySet()) {
                if (stillNeeded.contains(entry.getKey()))
                    continue;
                if (entry.getValue().getReferenceCount() != 0)
                    continue;
                candidates.add(entry.getKey());
            }

            // Farthest tiles first: least likely to be looked at again soon.
            candidates.sort((a, b) -> Long.compare(
                    tileDistanceSq(b.tile, targetTileX, targetTileY),
                    tileDistanceSq(a.tile, targetTileX, targetTileY)));

            for (int i = 0; i < candidates.size() && over > 0; i++) {
                TileAndZoomElevFactor key = candidates.get(i);
                ElevationImageProvider provider = providers.remove(key);
                if (provider == null)
                    continue;
                // mapToRescale maps a tile to its best-detail provider key; only drop it if it
                // still points at the provider we are evicting.
                if (key.equals(mapToRescale.get(key.tile))) {
                    mapToRescale.remove(key.tile);
                }
                provider.dispose();
                over--;
            }
        }
    }

    private static long tileDistanceSq(Tile tile, int targetTileX, int targetTileY) {
        long dx = (long) tile.tileX - targetTileX;
        long dy = (long) tile.tileY - targetTileY;
        return dx * dx + dy * dy;
    }
}
