package com.peaknav.viewer.render_tiles;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.viewer.render_tiles.PixmapLayerName.UNDERLAY_LAYER;

import com.peaknav.viewer.imgmapprovider.SatelliteImageProvider;
import com.peaknav.viewer.tiles.MapTile;

import java.io.File;

public class TileRendererRunnerSatellite extends TileRendererRunner {
    private final SatelliteImageProvider tileProvider;

    public TileRendererRunnerSatellite(
                TileRenderer tileRenderer,
                TileRenderer.RenderThemes renderThemes,
                MapTile mapTile,
                PixmapLayerName layer,
                SatelliteImageProvider tileProvider
            ) {
        super(tileRenderer, renderThemes, mapTile, layer);
        this.tileProvider = tileProvider;
    }

    @Override
    protected void renderAndDraw(PixmapLayerName pixmapLayerName) {
        assert pixmapLayerName == UNDERLAY_LAYER;

        // Bracketed so PeakNavAppState.getPendingSatelliteWork() reports the truth:
        // 0 there means every satellite tile requested so far is fetched AND drawn,
        // which is what "the imagery has finished loading" actually means. The
        // finally guarantees a failed download cannot leave the counter pinned.
        getAppState().satelliteWorkStarted();
        try {
            tileProvider.downloadTileImageIfNotExists(tile);
            File imgFile = tileProvider.getImageFileHandle(tile.zoomLevel, tile.tileX, tile.tileY);
            if (imgFile.exists()) {
                drawTileOnMap(imgFile, UNDERLAY_LAYER);
            }
        } finally {
            getAppState().satelliteWorkFinished();
        }
    }

    @Override
    protected boolean checkLayerDrawn() {
        return false;
    }
}
