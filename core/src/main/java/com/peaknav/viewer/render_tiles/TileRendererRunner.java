package com.peaknav.viewer.render_tiles;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getCacheDir;
import static com.peaknav.utils.PeakNavUtils.readImage;

import com.badlogic.gdx.graphics.Pixmap;
import com.peaknav.utils.StoppableRunnable;
import com.peaknav.viewer.tiles.MapTile;

import org.mapsforge.core.graphics.TileBitmap;
import org.mapsforge.core.model.Tile;
import org.mapsforge.map.layer.renderer.RendererJob;
import org.mapsforge.map.model.DisplayModel;
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

public abstract class TileRendererRunner extends StoppableRunnable {
    protected final TileRenderer tileRenderer;
    protected final Tile tile;
    protected final MapTile mapTile;
    protected final PixmapLayerName layer;
    private final TileRenderer.RenderThemes renderThemes;

    public TileRendererRunner(TileRenderer tileRenderer, TileRenderer.RenderThemes renderThemes, MapTile mapTile, PixmapLayerName layer) {
        this.tileRenderer = tileRenderer;
        this.renderThemes = renderThemes;
        this.mapTile = mapTile;
        this.tile = mapTile.tile;
        this.layer = layer;
    }

    protected abstract void renderAndDraw(PixmapLayerName pixmapLayerName);

    @Override
    public void run() {
        if (checkLayerDrawn())
            return;

        renderAndDraw(layer);
    }

    protected abstract boolean checkLayerDrawn();

    void drawTileOnMap(TileBitmap tileBitmap, PixmapLayerName pixmapLayerName) {
        ByteArrayOutputStream ostream = new ByteArrayOutputStream();
        try {
            tileBitmap.compress(ostream);
            byte[] bytes = ostream.toByteArray();
            Pixmap pixmap = new Pixmap(bytes, 0, bytes.length);
            mapTile.setTexturePixmap(pixmapLayerName, pixmap);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    void drawTileOnMap(File tileBitmapCacheFile, PixmapLayerName pixmapLayerName) {
        Pixmap pixmap = readImage(tileBitmapCacheFile);
        mapTile.setTexturePixmap(pixmapLayerName, pixmap);
    }

    public static File getTileBitmapCacheFile(Tile tile, PixmapLayerName pixmapLayerName) {
        String filename = String.format(
                Locale.ENGLISH,
                "%s_%02d_%05d_%05d.png",
                pixmapLayerName.name(), tile.zoomLevel, tile.tileX, tile.tileY);
        File file = new File(getCacheDir(), "tile_bitmaps");
        if (!file.exists())
            file.mkdir();
        return new File(file, filename);
    }

    TileBitmap renderTile(Tile tile, PixmapLayerName pixmapLayerName) {
        RenderThemeFuture renderThemeFuture;
        double distance = tile.getBoundingBox().getCenterPoint().distance(getC().L.getTargetLatLong());
        switch (pixmapLayerName) {
            case BASE_ROADS:
                if (distance > 0.3)
                    return null;
                renderThemeFuture = renderThemes.renderThemeFutureBaseRoads;
                break;
            case SKI_SLOPES:
                if (distance > 0.3)
                    return null;
                renderThemeFuture = renderThemes.renderThemeFutureSkiSlopes;
                break;
            default:
                return null;
        }
        DisplayModel displayModel = new DisplayModel();
        displayModel.setUserScaleFactor(getInverseScaleFactor(tile.zoomLevel));
        Tile largeTile = new Tile(tile.tileX, tile.tileY, tile.zoomLevel, 3*tile.tileSize);
        displayModel.setFixedTileSize(largeTile.tileSize);
        RendererJob rendererJob = new RendererJob(largeTile, tileRenderer.pbfMapDataStore, renderThemeFuture,
                displayModel, 2.0f, true, false);
        getAppState().waitForLastAnyMapTileUpdateTime(500);
        return tileRenderer.getDatabaseRenderer().executeJob(rendererJob);
    }

    float getInverseScaleFactor(byte zoomLevel) {
        return 1.f/(15 - zoomLevel);
    }

}
