package com.peaknav.viewer.render_tiles;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PreferencesManager.P;
import static com.peaknav.viewer.controller.MapController.getNumOfCpuCores;

import com.peaknav.geo.LatLong;
import com.peaknav.geo.Tile;
import com.peaknav.geo.LatLongUtils;

import java.util.Collections;
import java.util.List;

import com.peaknav.pbf.PbfMapDataStore;
import com.peaknav.utils.PeakNavThreadExecutor;
import com.peaknav.viewer.controller.MapController;
import com.peaknav.viewer.imgmapprovider.SatelliteImageProvider;
import com.peaknav.viewer.tiles.MapTile;

/**
 * Schedules the per-tile map layers: the satellite imagery, fetched and drawn by
 * {@link TileRendererRunnerSatellite}, and the roads, tracks, trails and pistes, rasterized into
 * distance textures by {@link TileRendererRunnerRoads} and styled by the terrain shader.
 *
 * <p>The roads used to be drawn by mapsforge onto a canvas each platform had to supply, which is
 * why iOS - with no such canvas - had none. They are now arithmetic into byte arrays, the same on
 * every platform, and their colours and dashes live in the shader.
 */
public class TileRenderer {

    private SatelliteImageProvider lastSatelliteImageProvider = null;

    final PbfMapDataStore pbfMapDataStore;
    public final PeakNavThreadExecutor tileRendererExecutor = new PeakNavThreadExecutor(1, "tileRendererExecutor1");
    public final PeakNavThreadExecutor tileRendererExecutorSat = new PeakNavThreadExecutor(2, "tileRendererExecutor2");
    public final PeakNavThreadExecutor execDraw;

    public TileRenderer(MapController C) {
        execDraw = new PeakNavThreadExecutor(
                Math.max(getNumOfCpuCores() / 2, 1),
                "execDraw");
        pbfMapDataStore = C.mapDataManager.getMultiMapDataStore();
    }

    /** Part of the start-up sequence; the road renderer has nothing left to set up. */
    public void initialize() {
    }

    public List<Tile> getTileZoomScaledPositions(LatLong center, double maxDistance, byte zoomLevel,
                                                 int tileSize) {

        TileAlgorithmScaledRanges algo = new TileAlgorithmScaledRanges(
                (float)center.getLatitude(), (float)center.getLongitude(), zoomLevel, tileSize,
                maxDistance
        );
        List<Tile> tiles = algo.getTiles();

        final LatLong current = getC().L.getTargetLatLong();

        Collections.sort(tiles, (tile1, tile2) -> {
            LatLong center1 = tile1.getBoundingBox().getCenterPoint();
            LatLong center2 = tile2.getBoundingBox().getCenterPoint();
            double d1 = LatLongUtils.distance(center1, current);
            double d2 = LatLongUtils.distance(center2, current);
            return Double.compare(d1, d2);
        });

        return tiles;
    }

    public void drawExecutorStop() {
        tileRendererExecutor.stopLoop();
    }

    public void drawSatelliteLayer() {
        SatelliteImageProvider satelliteImageProvider = P.getUnderlayImageProviderObject();
        tileRendererExecutorSat.stopLoop();
        for (MapTile mapTile : getC().mapTileStorage.getMapTiles()) {
            TileRendererRunnerSatellite renderer = new TileRendererRunnerSatellite(
                    this,
                    mapTile,
                    PixmapLayerName.UNDERLAY_LAYER,
                    satelliteImageProvider);
            tileRendererExecutorSat.executeStoppableRunnable(renderer);
        }
        lastSatelliteImageProvider = satelliteImageProvider;
        tileRendererExecutorSat.execute(() -> getC().cacheDirManager.removeOldCacheFiles());
    }

    /**
     * How much road/path drawing is still outstanding: tiles near enough to be given a road layer
     * that have not been rasterised yet, plus whatever the renderer thread still holds.
     *
     * <p>A tile reaches {@code IS_DRAWN} as soon as its elevation mesh is ready — the roads are
     * rasterised afterwards, on a separate low-priority executor, and only then handed over as a
     * pixmap. So "every tile is drawn" is NOT "the map is finished", and anything that captures an
     * image on that signal alone gets terrain with no paths on it. The headless renderer's wait
     * asks this as well; see {@code PeakNavRenderer.awaitTilesLoaded}.
     *
     * <p>Counts only what is actually expected: nothing when the roads layer is switched off, and
     * nothing for tiles past {@link TileRendererRunner#ROAD_CUTOFF_DEGREES}, which never get one.
     */
    public int pendingRoadWork() {
        if (!P.isPixmapLayerNameVisible(PixmapLayerName.BASE_ROADS))
            return 0;
        int pending = 0;
        for (MapTile mapTile : getC().mapTileStorage.getMapTiles()) {
            if (mapTile.isDisposed())
                continue;
            if (!TileRendererRunner.roadsExpectedFor(mapTile.tile))
                continue;
            if (!mapTile.isLayerDrawn(PixmapLayerName.BASE_ROADS))
                pending++;
        }
        return pending;
    }

    /** True while the road renderer has nothing queued and nothing in hand. */
    public boolean isRoadRendererIdle() {
        return tileRendererExecutor.getQueue().isEmpty()
                && tileRendererExecutor.getActiveCount() == 0;
    }

    public void drawArea(PixmapLayerName pixmapLayerName) {
        if (pixmapLayerName != PixmapLayerName.BASE_ROADS)
            return;

        // Nearest tile first. These are rasterised one at a time, and a full neighbourhood takes
        // longer than anyone waits for a frame - so the ORDER decides what a picture taken
        // meanwhile contains. Storage order scattered the work all round the compass, leaving the
        // foreground bare while tiles behind the camera were drawn; nearest-first fills the view
        // from the ground up, which is also the order the frame needs them in.
        java.util.List<MapTile> waiting = new java.util.ArrayList<>();
        for (MapTile mapTile : getC().mapTileStorage.getMapTiles()) {
            if (!mapTile.isLayerDrawn(pixmapLayerName))
                waiting.add(mapTile);
        }
        final LatLong target = getC().L.getTargetLatLong();
        Collections.sort(waiting, (a, b) -> Double.compare(
                LatLongUtils.distance(a.getImpWhiteTileIndex(), target),
                LatLongUtils.distance(b.getImpWhiteTileIndex(), target)));

        for (MapTile mapTile : waiting) {
            TileRendererRunner renderer = new TileRendererRunnerRoads(this, mapTile);
            renderer.setPriority(Thread.MIN_PRIORITY);
            tileRendererExecutor.executeStoppableRunnable(renderer);
        }
    }

}
