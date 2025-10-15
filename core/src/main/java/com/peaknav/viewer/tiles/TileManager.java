
package com.peaknav.viewer.tiles;

import static com.peaknav.utils.PreferencesManager.P;

import com.peaknav.utils.PeakNavThreadExecutor;
import com.peaknav.utils.PeakNavUtils;
import com.peaknav.viewer.controller.MapController;
import com.peaknav.viewer.render_tiles.PixmapLayerName;
import com.peaknav.viewer.render_tiles.TileRenderer;

public class TileManager {

    public final TileRenderer tileRenderer;
    private final PeakNavThreadExecutor executorUpdateMapTiles = new PeakNavThreadExecutor(1, "executorUpdateMapTiles");

    public TileManager(MapController mapController) {
        tileRenderer = new TileRenderer(mapController);
    }

    public void updateMapTiles() {
        updateMapTiles(false);
    }

    public synchronized void updateMapTiles(boolean forceReload) {
        executorUpdateMapTiles.stopLoop();
        executorUpdateMapTiles.executeStoppableRunnable(new UpdateMapTilesRunnable(forceReload));
        PeakNavUtils.freePixmapCache();
    }

    public void startAerialAndDataRenderExecutors() {
        // TODO:
        //this should run after updateMapTiles... it should update the bounding boxes
        // and stop all previous executors...
        // getC().L.computeMapHighwaysDataBoundingBox();

        tileRenderer.drawExecutorStop();

        if (P.isPixmapLayerNameVisible(PixmapLayerName.UNDERLAY_LAYER))
            tileRenderer.drawSatelliteLayer();
        if (P.isPixmapLayerNameVisible(PixmapLayerName.BASE_ROADS))
            tileRenderer.drawArea(PixmapLayerName.BASE_ROADS);
    }

}
