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

    // Device tier, decided once from the app's max heap. ~384 MB comfortably covers desktop and
    // modern phones; older low-RAM phones fall below it and keep the original, cheapest render path.
    private static final long MAX_HEAP_MB = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
    private static final boolean DEVICE_HAS_HEADROOM = MAX_HEAP_MB >= 384;

    /**
     * The road/ski-piste tile is rasterised at {@code SUPERSAMPLE * 256}px — this <em>is</em> the
     * Mapsforge "number of rendering pixels": {@code DatabaseRenderer.executeJob} builds the bitmap
     * with {@code createTileBitmap(rendererJob.tile.tileSize, …)} and projects way geometry through
     * {@code getMapSize(zoom, tileSize)}, so a larger factor means both a bigger bitmap <em>and</em>
     * road shapes drawn at proportionally higher pixel density (sharper single pixels).
     *
     * <p>Cost grows with the <em>square</em> of the factor as an RGBA texture (plus ~33% for the
     * mip-map chain): 512px (2) ≈ 1.4 MB, 1024px (4) ≈ 5.6 MB, 2048px (8) ≈ 22 MB; several tiles are
     * live within the ~33 km road cutoff, so this is the main lever on map memory.
     *
     * <p><b>Must be a power of two.</b> {@code factor * 256} has to be power-of-two so that
     * {@link com.peaknav.viewer.tiles.MapTile} can generate mip-maps for it on GL ES 2 — mip-maps +
     * anisotropic filtering are what actually stop the roads aliasing at the grazing angles the
     * terrain is viewed at (raw resolution alone does not). So use 2, 4, or 8, not 3/5/6. Devices
     * with headroom render at 4 (1024px); constrained phones use 2 (512px, and still get the
     * mip-map/anisotropic smoothing, which matters more than the raw pixel count). Push the headroom
     * value to 8 for a desktop-class machine. (This governs road <em>shapes</em>; stroke width is
     * fixed in px by the theme, so very thin roads still look thin — thicken them in base_roads.xml.)
     */
    static final int ROAD_TILE_SUPERSAMPLE = DEVICE_HAS_HEADROOM ? 4 : 2;

    /**
     * On-tile size of map labels (road / place names) relative to a standard 256px map tile.
     *
     * <p>Mapsforge rasterises these labels into the tile bitmap and scales their font by the
     * {@link RendererJob} {@code textScale} <em>only</em> — the per-tile {@code userScaleFactor} does
     * not affect text (see mapsforge {@code RenderContext#createWayRenderInfo}). Because we render
     * the tile {@link #ROAD_TILE_SUPERSAMPLE}× larger than a 256px tile, a fixed textScale makes a
     * label occupy {@code 1/SUPERSAMPLE} of the on-screen tile that the same label would on a normal
     * map — i.e. it shrinks as we raise the supersample. So {@code textScale} must scale <em>with</em>
     * the supersample factor. {@code textScale = MAP_LABEL_SIZE * SUPERSAMPLE}: a value of 1.0 matches
     * a standard map, and we use a little more since the panorama is viewed foreshortened toward the
     * horizon. (The old hard-coded {@code 2.0f} was ~0.5–0.67× a standard map — the unreadable case.)
     */
    private static final float MAP_LABEL_SIZE = 1.6f;
    private static final float MAP_LABEL_TEXT_SCALE = MAP_LABEL_SIZE * ROAD_TILE_SUPERSAMPLE;

    static {
        System.out.println("[TileRenderer] road tile supersample=" + ROAD_TILE_SUPERSAMPLE
                + " -> " + (ROAD_TILE_SUPERSAMPLE * 256) + "px tiles, labelTextScale="
                + MAP_LABEL_TEXT_SCALE + " (maxHeap=" + MAX_HEAP_MB + " MB)");
    }

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
        Tile largeTile = new Tile(tile.tileX, tile.tileY, tile.zoomLevel,
                ROAD_TILE_SUPERSAMPLE * tile.tileSize);
        displayModel.setFixedTileSize(largeTile.tileSize);
        RendererJob rendererJob = new RendererJob(largeTile, tileRenderer.pbfMapDataStore, renderThemeFuture,
                displayModel, MAP_LABEL_TEXT_SCALE, true, false);
        getAppState().waitForLastAnyMapTileUpdateTime(500);
        return tileRenderer.getDatabaseRenderer().executeJob(rendererJob);
    }

    float getInverseScaleFactor(byte zoomLevel) {
        // 15 is the ceiling of the tile zoom range; guard against div-by-zero for zoom >= 15.
        int denom = Math.max(1, 15 - zoomLevel);
        return 1.f / denom;
    }

}
