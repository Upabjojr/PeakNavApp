package com.peaknav.viewer.render_tiles;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.utils.PeakNavUtils.getC;

import com.badlogic.gdx.graphics.Pixmap;
import com.peaknav.roads.RoadClassifier;
import com.peaknav.roads.RoadFeature;
import com.peaknav.roads.RoadLabelPlanner;
import com.peaknav.roads.RoadTextures;
import com.peaknav.roads.RoadTileRasterizer;
import com.peaknav.viewer.tiles.MapTile;

import com.peaknav.geo.BoundingBox;
import com.peaknav.pbf.MapReadResult;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Draws one tile's roads, tracks, trails and pistes: reads its ways from the downloaded map data,
 * sorts them into classes, and rasterizes them into the two distance textures the terrain shader
 * styles (see {@link RoadTileRasterizer}). It also plans where their names may go, for the
 * road-name labels.
 *
 * <p>Nothing here touches a platform API - no canvas, no graphics factory - which is what gives
 * iOS its roads and trails: the mapsforge renderer this replaces needed a canvas per platform,
 * and iOS never had one.
 */
public class TileRendererRunnerRoads extends TileRendererRunner {

    /**
     * Ways are read from a box this much wider than the tile, as a fraction of its size: a road
     * running just outside the edge still reaches into the tile by its width, and without it the
     * line would be clipped at the tile boundary.
     */
    private static final double READ_PAD = 0.03;

    public TileRendererRunnerRoads(TileRenderer tileRenderer, MapTile mapTile) {
        super(tileRenderer, mapTile, PixmapLayerName.BASE_ROADS);
    }

    @Override
    protected boolean checkLayerDrawn() {
        return mapTile.isLayerDrawn(PixmapLayerName.BASE_ROADS);
    }

    @Override
    protected void renderAndDraw(PixmapLayerName ignored) {
        if (!roadsExpectedFor(tile)) {
            return;
        }
        if (!getC().dataRetrieveThreadManager.isLabelUpdatesHeld()) {
            // Interactive pacing: reading a tile's map data is the expensive part, and it should
            // not compete with a camera still streaming terrain in. Scripted rendering drains the
            // queue flat out instead, so that its "quiet" wait can actually become quiet.
            getAppState().waitForLastAnyMapTileUpdateTime(250);
        }
        if (mapTile.isDisposed()) {
            return;
        }
        BoundingBox bb = tile.getBoundingBox();
        MapReadResult data = tileRenderer.pbfMapDataStore.readMapDataPadded(tile, READ_PAD);
        List<RoadFeature> features = RoadClassifier.classifyAll(data.ways);
        mapTile.roadLabels = RoadLabelPlanner.plan(features,
                bb.maxLatitude, bb.minLatitude, bb.maxLongitude, bb.minLongitude);

        int res = ROAD_TILE_SUPERSAMPLE * 256;
        RoadTextures textures = RoadTileRasterizer.rasterize(features,
                bb.maxLatitude, bb.minLatitude, bb.maxLongitude, bb.minLongitude, res);
        mapTile.setRoadMetersPerTexel(textures.metersPerTexel);
        if (textures.empty) {
            // Nothing to draw here: no texture to hold in memory, and no work left outstanding.
            mapTile.setLayerDrawnEmpty(PixmapLayerName.ROADS_AUX);
            mapTile.setLayerDrawnEmpty(PixmapLayerName.BASE_ROADS);
            return;
        }
        // The aux texture first: BASE_ROADS is what "the roads are drawn" is keyed on
        // (TileRenderer.pendingRoadWork), so it has to be the last to arrive.
        mapTile.setTexturePixmap(PixmapLayerName.ROADS_AUX, toPixmap(textures.aux, textures.auxRes));
        mapTile.setTexturePixmap(PixmapLayerName.BASE_ROADS, toPixmap(textures.distance, textures.res));
    }

    /** Wraps raw RGBA8888 bytes, row 0 at the top, in a pixmap for the texture upload. */
    static Pixmap toPixmap(byte[] rgba, int res) {
        Pixmap pixmap = new Pixmap(res, res, Pixmap.Format.RGBA8888);
        pixmap.setBlending(Pixmap.Blending.None);
        ByteBuffer buf = pixmap.getPixels();
        buf.position(0);
        buf.put(rgba);
        buf.position(0);
        return pixmap;
    }
}
