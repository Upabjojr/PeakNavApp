package com.peaknav.viewer.render_tiles;

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

        tileProvider.downloadTileImageIfNotExists(tile);
        File imgFile = tileProvider.getImageFileHandle(tile.zoomLevel, tile.tileX, tile.tileY);
        if (imgFile.exists()) {
            drawTileOnMap(imgFile, UNDERLAY_LAYER);
        }
    }

    @Override
    protected boolean checkLayerDrawn() {
        return false;
    }
}
