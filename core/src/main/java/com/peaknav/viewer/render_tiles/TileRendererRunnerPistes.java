package com.peaknav.viewer.render_tiles;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.utils.PeakNavUtils.getC;

import com.peaknav.geo.BoundingBox;
import com.peaknav.pbf.MapReadResult;
import com.peaknav.pbf.PbfLayer;
import com.peaknav.pistes.PisteRasterizer;
import com.peaknav.viewer.PhotoSkylineAligner;
import com.peaknav.viewer.tiles.MapTile;

/**
 * Draws one tile's ski runs for the ski slopes viewer: reads the downhill pistes from the
 * {@link PbfLayer#PBF_PISTES} data and rasterizes them (see {@link PisteRasterizer}) into the
 * {@link PixmapLayerName#SKI_SLOPES} texture the terrain shader paints and animates.
 */
public class TileRendererRunnerPistes extends TileRendererRunner {

    /** Texels a side: enough for a fat run on the nearest tiles, and one texture per tile with pistes. */
    static final int RES = 512;
    /** As for the roads: a run just outside the tile still reaches into it by its width. */
    private static final double READ_PAD = 0.03;

    public TileRendererRunnerPistes(TileRenderer tileRenderer, MapTile mapTile) {
        super(tileRenderer, mapTile, PixmapLayerName.SKI_SLOPES);
    }

    @Override
    protected boolean checkLayerDrawn() {
        return mapTile.isLayerDrawn(PixmapLayerName.SKI_SLOPES);
    }

    @Override
    protected void renderAndDraw(PixmapLayerName ignored) {
        if (!roadsExpectedFor(tile)) {
            return;
        }
        if (!getC().dataRetrieveThreadManager.isLabelUpdatesHeld()) {
            getAppState().waitForLastAnyMapTileUpdateTime(250); // as for the roads
        }
        if (mapTile.isDisposed()) {
            return;
        }
        BoundingBox bb = tile.getBoundingBox();
        MapReadResult data = tileRenderer.pbfMapDataStore.readMapDataPadded(tile, READ_PAD, PbfLayer.PBF_PISTES);
        PisteRasterizer.Result result = PisteRasterizer.rasterize(data.ways,
                bb.maxLatitude, bb.minLatitude, bb.maxLongitude, bb.minLongitude, RES,
                // Downhill from the loaded terrain; NaN where it is not loaded, and then the way's direction.
                (lat, lon) -> PhotoSkylineAligner.loadedTerrain().elevationMeters(lat, lon));
        if (result.empty) {
            mapTile.setLayerDrawnEmpty(PixmapLayerName.SKI_SLOPES);
            return;
        }
        mapTile.setTexturePixmap(PixmapLayerName.SKI_SLOPES, TileRendererRunnerRoads.toPixmap(result.rgba, result.res));
    }
}
