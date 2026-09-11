package com.peaknav.viewer.render_tiles;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getCacheDir;
import static com.peaknav.utils.PeakNavUtils.readImage;

import com.badlogic.gdx.graphics.Pixmap;
import com.peaknav.utils.StoppableRunnable;
import com.peaknav.viewer.tiles.MapTile;

import org.mapsforge.core.model.Tile;

import java.io.File;
import java.util.Locale;

public abstract class TileRendererRunner extends StoppableRunnable {

    // Device tier, decided once from the app's max heap. ~384 MB comfortably covers desktop and
    // modern phones; older low-RAM phones fall below it and keep the original, cheapest render path.
    private static final long MAX_HEAP_MB = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
    private static final boolean DEVICE_HAS_HEADROOM = MAX_HEAP_MB >= 384;

    /**
     * The road distance texture is {@code SUPERSAMPLE * 256} texels a side (and its aux texture
     * half that). A distance field keeps its edges sharp far below the resolution a coloured
     * mask would need - the shader draws the line, the texture only says where it is - so the
     * extra texels buy thin trails close to the camera rather than smoothness.
     *
     * <p>Cost grows with the <em>square</em> of the factor as an RGBA texture (plus ~33% for the
     * mip-map chain, and a quarter again for the aux texture): 512px (2) ≈ 1.7 MB, 1024px (4) ≈
     * 7 MB; several tiles are live within the ~33 km road cutoff, so this is the main lever on map
     * memory.
     *
     * <p><b>Must be a power of two.</b> {@code factor * 256} has to be power-of-two so that
     * {@link com.peaknav.viewer.tiles.MapTile} can generate mip-maps for it on GL ES 2 - mip-maps +
     * anisotropic filtering are what stop the roads shimmering at the grazing angles the terrain
     * is viewed at. So use 2, 4, or 8, not 3/5/6.
     */
    static final int ROAD_TILE_SUPERSAMPLE = DEVICE_HAS_HEADROOM ? 4 : 2;

    static {
        System.out.println("[TileRenderer] road tile supersample=" + ROAD_TILE_SUPERSAMPLE
                + " -> " + (ROAD_TILE_SUPERSAMPLE * 256) + "px distance textures"
                + " (maxHeap=" + MAX_HEAP_MB + " MB)");
    }

    protected final TileRenderer tileRenderer;
    protected final Tile tile;
    protected final MapTile mapTile;
    protected final PixmapLayerName layer;

    public TileRendererRunner(TileRenderer tileRenderer, MapTile mapTile, PixmapLayerName layer) {
        this.tileRenderer = tileRenderer;
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

    /**
     * How far from the viewer roads and pistes are still rasterised, in degrees (~33 km).
     * Past it a tile deliberately gets no road layer at all, which is why anything waiting for
     * "the paths are drawn" has to ask {@link #roadsExpectedFor} rather than expect every tile to
     * end up with one - it would wait for a layer that is never coming.
     */
    public static final double ROAD_CUTOFF_DEGREES = 0.3;

    /** Will this tile ever be given a road/piste layer, at the current target? */
    public static boolean roadsExpectedFor(Tile tile) {
        return tile.getBoundingBox().getCenterPoint()
                .distance(getC().L.getTargetLatLong()) <= ROAD_CUTOFF_DEGREES;
    }

}
