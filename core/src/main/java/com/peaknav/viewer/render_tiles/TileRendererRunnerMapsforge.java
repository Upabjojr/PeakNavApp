package com.peaknav.viewer.render_tiles;

import com.peaknav.viewer.tiles.MapTile;

import org.mapsforge.core.graphics.GraphicFactory;
import org.mapsforge.core.graphics.TileBitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class TileRendererRunnerMapsforge extends TileRendererRunner {
    protected final GraphicFactory graphicFactory;

    public TileRendererRunnerMapsforge(TileRenderer tileRenderer, TileRenderer.RenderThemes renderThemes, MapTile mapTile, PixmapLayerName layer) {
        super(tileRenderer, renderThemes, mapTile, layer);
        this.graphicFactory = tileRenderer.graphicFactory;
    }

    @Override
    protected void renderAndDraw(PixmapLayerName pixmapLayerName) {
        assert pixmapLayerName != PixmapLayerName.UNDERLAY_LAYER;

        TileBitmap tileBmp = renderTile(tile, pixmapLayerName);
        if (tileBmp == null)
            return;
        drawTileOnMap(tileBmp, pixmapLayerName);
    }

    @Override
    protected boolean checkLayerDrawn() {
        return mapTile.isLayerDrawn(layer);
    }


    /*protected void renderSavePixmapAndSendToMapTile(File tileBitmapCacheFile, PixmapLayerName pixmapLayerName) {
        TileBitmap tileBitmap = renderTile(tile, pixmapLayerName);
        saveTileBitmapToCacheFile(tileBitmap, tileBitmapCacheFile, pixmapLayerName);
        drawTileOnMap(tileBitmapCacheFile, pixmapLayerName);
    }*/

    private void saveTileBitmapToCacheFile(TileBitmap tileBitmap, File tileBitmapCacheFile, PixmapLayerName pixmapLayerName) {
        FileOutputStream outputStream;
        try {
            outputStream = new FileOutputStream(tileBitmapCacheFile);
            tileBitmap.compress(outputStream);
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
    }

}
