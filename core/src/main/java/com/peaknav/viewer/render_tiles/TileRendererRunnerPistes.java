package com.peaknav.viewer.render_tiles;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.utils.PeakNavUtils.getC;

import com.peaknav.geo.BoundingBox;
import com.peaknav.pbf.MapReadResult;
import com.peaknav.pbf.PbfLayer;
import com.peaknav.pistes.LiftRasterizer;
import com.peaknav.pistes.PisteRasterizer;
import com.peaknav.roads.RoadLabelPlanner;
import com.peaknav.viewer.PhotoSkylineAligner;
import com.peaknav.viewer.tiles.MapTile;

/**
 * Draws one tile's ski runs and lifts for the ski slopes viewer: reads the downhill pistes and the
 * lifts ({@link LiftRasterizer}, into {@link PixmapLayerName#SKI_LIFTS}) from the
 * {@link PbfLayer#PBF_PISTES} data and rasterizes them (see {@link PisteRasterizer}) into the
 * {@link PixmapLayerName#SKI_SLOPES} texture the terrain shader paints and animates, and plans
 * where the runs' names may be written (see RoadNameRenderer).
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
        // Where the runs' names may go, whether or not they are shown: the switch acts at once.
        mapTile.pisteLabels = RoadLabelPlanner.planPistes(PisteRasterizer.labelFeatures(data.ways),
                bb.maxLatitude, bb.minLatitude, bb.maxLongitude, bb.minLongitude);
        mapTile.liftLabels = RoadLabelPlanner.planLifts(LiftRasterizer.labelFeatures(data.ways),
                bb.maxLatitude, bb.minLatitude, bb.maxLongitude, bb.minLongitude);
        // Downhill and uphill from the loaded terrain; NaN where it is not loaded, and then the way's direction.
        PisteRasterizer.Elevation terrain = TileRendererRunnerPistes::endHeight;
        PisteRasterizer.Result lifts = LiftRasterizer.rasterize(data.ways,
                bb.maxLatitude, bb.minLatitude, bb.maxLongitude, bb.minLongitude, RES, terrain);
        PisteRasterizer.Result result = PisteRasterizer.rasterize(data.ways,
                bb.maxLatitude, bb.minLatitude, bb.maxLongitude, bb.minLongitude, RES, terrain);
        // The lifts first: SKI_SLOPES is what "the ski layer is drawn" is keyed on, so it comes last.
        if (lifts.empty) {
            mapTile.setLayerDrawnEmpty(PixmapLayerName.SKI_LIFTS);
        } else {
            mapTile.setTexturePixmap(PixmapLayerName.SKI_LIFTS, TileRendererRunnerRoads.toPixmap(lifts.rgba, lifts.res));
        }
        if (result.empty) {
            mapTile.setLayerDrawnEmpty(PixmapLayerName.SKI_SLOPES);
            return;
        }
        mapTile.setTexturePixmap(PixmapLayerName.SKI_SLOPES, TileRendererRunnerRoads.toPixmap(result.rgba, result.res));
    }

    /**
     * The heights of the runs' and lifts' ends, as first read: a run crossing two tiles took its
     * direction from the terrain loaded when each was drawn, and a way whose ends were read
     * differently - a finer tile loaded between the two, or one end not loaded yet - flowed
     * opposite ways across the seam. Once read, an end keeps its height, so every tile decides
     * alike. Not while it is unknown: a tile drawn before then keeps the way's own direction.
     */
    private static final java.util.Map<Long, Float> END_HEIGHTS =
            new java.util.LinkedHashMap<Long, Float>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Long, Float> eldest) {
                    return size() > 20_000;
                }
            };

    private static float endHeight(double lat, double lon) {
        // Ways meet in the same points: a millionth of a degree tells them apart, some 10 cm.
        long key = Math.round((lat + 90) * 1e6) * 400_000_000L + Math.round((lon + 180) * 1e6);
        synchronized (END_HEIGHTS) {
            Float known = END_HEIGHTS.get(key);
            if (known != null) {
                return known;
            }
        }
        float height = PhotoSkylineAligner.loadedTerrain().elevationMeters(lat, lon);
        if (!Float.isNaN(height)) {
            synchronized (END_HEIGHTS) {
                END_HEIGHTS.put(key, height);
            }
        }
        return height;
    }
}
