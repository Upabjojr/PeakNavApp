package com.peaknav.viewer.tiles;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.database.CheckMissingData.getMinZoomTile;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.viewer.screens.LabelLoading.State.LOADING;
import static com.peaknav.viewer.screens.LabelLoading.State.LOADING_UPDATING;

import com.badlogic.gdx.Gdx;
import com.peaknav.elevation.ElevationImageAbstract;
import com.peaknav.elevation.ElevationImageStorage;
import com.peaknav.utils.StoppableRunnable;
import com.peaknav.viewer.MapViewerSingleton;

import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.LatLongUtils;
import org.mapsforge.core.util.MercatorProjection;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;

public class UpdateMapTilesRunnable extends StoppableRunnable {
    private static LatLong lastUpdatedPos = null;

    private final boolean forceReload;
    private final float MAX_DISTANCE_METERS = 500000;  // 500 km (reasonable from 25 km elev)
    private LatLong targetLatLong;

    /** One subdivision turns a tile into four: three tiles more than before. */
    private static final int SUBDIVISION_COST = 3;

    /**
     * Out to this distance — in min-zoom tile widths, see {@link #getDistance} — the detail the
     * ramp asks for is always provided, whatever is left of the tile allowance. It reaches to the
     * end of the ramp, so the allowance can only ever trim the flat backdrop beyond it, never the
     * ground the user is looking at.
     */
    private static final double GUARANTEED_DETAIL_TILES = 1.42;
    /** Tiles that may be added on top of the coarse baseline; set from the device's memory. */
    private int extraTileBudget = 0;
    private int extraTilesSpent = 0;

    /**
     * Detail bought by making a tile's mesh denser instead of splitting it into four.
     *
     * <p>Both routes give the same ground spacing, but subdividing multiplies everything the tile
     * owns — mesh, layer textures, provider bookkeeping — by four, while lowering the elevation
     * factor by one only quadruples the vertex array and leaves the textures alone. Since the
     * textures dominate, refining in place costs roughly an order of magnitude less for the same
     * relief, which is what lets distant terrain keep its shape on a device that cannot afford
     * more tiles.
     *
     * <p>How far it can go is limited by {@link ElevationImageAbstract#MAX_MESH_VERTICES}: element
     * indices are 16-bit, so a mesh edge of 129 is the most a tile can carry, i.e. one step of
     * refinement (halving the spacing). Asking for more overflows the index cast and tears holes
     * through the terrain, so the request is clamped here and again in MapTile.
     *
     * <p>The factor also names a file on disk: the elevation crop for a min-zoom tile exists at
     * one resolution per factor ({@code 4096 / 2^f} px, see ElevationImageStorage), so a finer
     * mesh is only possible where that file was downloaded. A tile must never end up with a denser
     * mesh and no elevation data, so availability is checked before a level is refined.
     */
    private long vertexBudget = 0;
    private long verticesSpent = 0;

    /** ElevationImageStorage asserts the factor never goes below this. */
    private static final int MIN_ELEV_FACTOR = 2;
    /** Zoom levels are small; this bounds the per-zoom arrays. */
    private static final int MAX_ZOOM_INDEX = 20;

    /** Vertices in a tile's mesh at the given zoom level and elevation factor. */
    private static long vertexCount(int zoomLevel, int elevFactor) {
        long edge = 1 + 4096L / (1L << (zoomLevel - MapTile.ZOOM_LEVEL_MIN)) / (1L << elevFactor);
        return edge * edge;
    }

    private static Tile minZoomAncestor(Tile tile) {
        Tile t = tile;
        while (t.zoomLevel > MapTile.ZOOM_LEVEL_MIN) {
            t = t.getParent();
        }
        return t;
    }

    /** Whether the elevation crop for this min-zoom tile exists on disk at that factor. */
    private static boolean elevationCropExists(Tile minZoomTile, int elevFactor) {
        return Gdx.files.external(
                    ElevationImageStorage.getElevationCropsPathJpg(minZoomTile, elevFactor)).exists()
                && Gdx.files.external(
                    ElevationImageStorage.getElevationCropsPathPng(minZoomTile, elevFactor)).exists();
    }

    /**
     * Refinement is decided per ZOOM LEVEL, never per tile.
     *
     * <p>Mesh resolution used to be a pure function of the zoom level, so two neighbours at the
     * same zoom always had identical meshes and their weld was a straight one-to-one average — no
     * interpolation, nothing to go wrong. Deciding the factor per tile would scatter stitched
     * boundaries all over the map instead of leaving them at the few zoom transitions. Refining a
     * whole level at a time keeps same-zoom neighbours identical, and refined levels are taken as
     * a run from the coarsest so adjacent levels are only ever equal or two-to-one.
     *
     * @return refinement (0 or 1) indexed by zoom level
     */
    private int[] decideRefinementPerZoom(List<Tile> accepted) {
        int[] refine = new int[MAX_ZOOM_INDEX];
        int[] count = new int[MAX_ZOOM_INDEX];
        java.util.List<java.util.List<Tile>> byZoom = new java.util.ArrayList<>(MAX_ZOOM_INDEX);
        for (int i = 0; i < MAX_ZOOM_INDEX; i++) {
            byZoom.add(new java.util.ArrayList<>());
        }
        for (Tile t : accepted) {
            if (t.zoomLevel >= 0 && t.zoomLevel < MAX_ZOOM_INDEX) {
                count[t.zoomLevel]++;
                byZoom.get(t.zoomLevel).add(t);
            }
        }
        // Coarsest first: those tiles carry the least relief today, so refining them buys the most.
        for (int zoom = MapTile.ZOOM_LEVEL_MIN; zoom < MAX_ZOOM_INDEX; zoom++) {
            if (count[zoom] == 0) {
                continue;
            }
            int base = MapTile.computeZoomElevFactor(zoom);
            int finer = base - 1;
            // Stop at the first level that cannot be refined rather than skipping it, so the
            // refined levels stay a run from the coarsest and no level is ever denser than a
            // coarser one.
            if (finer < MIN_ELEV_FACTOR
                    || MapTile.clampElevFactorToIndexLimit(zoom, finer) != finer) {
                break;
            }
            long cost = count[zoom] * (vertexCount(zoom, finer) - vertexCount(zoom, base));
            if (verticesSpent + cost > vertexBudget) {
                break;
            }
            if (!allCropsExist(byZoom.get(zoom), finer)) {
                break; // all or nothing: one tile falling back would break the uniformity
            }
            verticesSpent += cost;
            refine[zoom] = 1;
        }
        return refine;
    }

    /** True only if every one of these tiles can be read at that elevation factor. */
    private boolean allCropsExist(List<Tile> tiles, int elevFactor) {
        java.util.Set<Tile> checked = new java.util.HashSet<>();
        for (Tile t : tiles) {
            Tile ancestor = minZoomAncestor(t);
            if (!checked.add(ancestor)) {
                continue; // one file serves every tile under the same min-zoom block
            }
            if (!elevationCropExists(ancestor, elevFactor)) {
                return false;
            }
        }
        return true;
    }

    public UpdateMapTilesRunnable(boolean forceReload) {
        this.forceReload = forceReload;
    }

    @Override
    public void run() {
        getAppState().setLastAnyMapTileUpdateTimeToNow();
        updateMapTilesWorker(forceReload);
        getC().dataRetrieveThreadManager.triggerReadData();
        getC().tileManager.startAerialAndDataRenderExecutors();  // here
        getC().L.checkTargetCoordsAfterTileUpdates();
    }

    /**
     * Nearest tile first, measured the same way the detail decision measures it: to the tile's
     * nearest edge, not its centre. A big tile right next to the viewer has a distant centre, so
     * ordering by centre put it late in the queue — after the detail allowance had been spent on
     * tiles that were further away, leaving a coarse tile in the foreground.
     */
    private final PriorityQueue<Tile> tileIndices = new PriorityQueue<>(64, (t1, t2) ->
            Double.compare(getDistance(t1.getBoundingBox()), getDistance(t2.getBoundingBox())));

    public void interruptDrawingThread() {
        getC().dataRetrieveThreadManager.stopRunnableUpdateVisibility();
        PixmapLayers.stopLayerDrawPixmapExecutor();
        getC().tileManager.tileRenderer.execDraw.stopLoop();
    }

    private void updateMapTilesWorker(boolean forceReload) {
        targetLatLong = getC().L.getTargetLatLong();

        if (getC().mapTileStorage.getNumberOfMapTiles() == 0) {
            forceReload = true;
        }

        if (!forceReload && lastUpdatedPos != null && LatLongUtils.sphericalDistance(lastUpdatedPos, targetLatLong) < 200) {
            return;
        }

        MapViewerSingleton.getViewerInstance().labelLoading.setState(LOADING_UPDATING);

        ElevationImageStorage eis = new ElevationImageStorage(
                getMinZoomTile(targetLatLong.getLatitude(), targetLatLong.getLongitude()),
                MapTile.computeZoomElevFactor(MapTile.ZOOM_LEVEL_MIN)
        );
        if (!eis.checkImageExistence()) {
            return;
        }

        // TODO: if there are no downloaded tiles for the current coordinates,
        // there should be some prompts to ask the users if they want to download the missing tiles.

        interruptDrawingThread();

        // EXPERIMENT: was mapTilesForDisposal.clear(), which dropped tiles the render thread
        // had not disposed yet - freeing the Java object but never the GPU texture or mesh.
        getC().mapTileStorage.readyToDispose = true;

        long totalMemory = getNativeScreenCaller().getTotalMemory();
        double totalMemoryGB = totalMemory / 1024.0 / 1024.0 / 1024.0;

        // step: how far the coarse z8 baseline extends. maxTiles: how many tiles may exist in
        // total, which is what actually bounds memory — every tile costs the same mesh (65x65
        // vertices, ~185 KB) plus its layer textures, so the tile count is the memory.
        // step: how far the coarse z8 baseline extends. maxTiles: how many tiles may exist in
        // total, which is what bounds memory — every tile costs the same mesh (65x65 vertices,
        // ~185 KB) plus its layer textures, so the tile count is the memory.
        int step;
        int maxTiles;
        long extraVertices;
        if (totalMemoryGB > 4.4) {
            step = 4;
            maxTiles = 300;
            extraVertices = 1_200_000L;
        } else if (totalMemoryGB > 4.0) {
            step = 3;
            maxTiles = 260;
            extraVertices = 700_000L;
        } else if (totalMemoryGB > 3.0) {
            step = 2;
            maxTiles = 230;
            extraVertices = 450_000L;
        } else {
            // Genuinely small devices: near enough the tile count the old thresholds produced
            // (~106 here) to be safe, while the gentler ramp spends it better.
            step = 2;
            maxTiles = 210;
            extraVertices = 300_000L;
        }
        vertexBudget = extraVertices;
        verticesSpent = 0;
        byte zl = MapTile.ZOOM_LEVEL_MIN;
        int tileX = MercatorProjection.longitudeToTileX(targetLatLong.getLongitude(), zl);
        int tileY = MercatorProjection.latitudeToTileY(targetLatLong.getLatitude(), zl);
        int maxTileVal = 1 << zl;
        for (int i = tileX - step; i <= tileX + step; i++) {
            for (int j = tileY - step; j <= tileY + step; j++) {
                if (i < 0 || j < 0 || i >= maxTileVal || j >= maxTileVal) {
                    continue;
                }
                tileIndices.add(new Tile(i, j, zl, MapTile.MF_ZOOM));
            }
        }

        // Subdividing one tile replaces it with four, so it costs three extra tiles. Everything
        // above the coarse baseline is drawn from this allowance, and because the queue hands out
        // tiles nearest-first the allowance is spent on the closest terrain and runs out on the
        // farthest — detail where it covers the most screen, coarse where it does not.
        extraTileBudget = Math.max(0, maxTiles - tileIndices.size());
        extraTilesSpent = 0;

        // TODO: if "forceReload" is true, all previous threads
        // loading the tiles should be interrupted and restarted:

        // Settle which tiles exist and at which zoom, then pick the mesh resolution for whole zoom
        // levels at once, then build them: the factor has to be the same for every tile of a level,
        // and that is not known until the last one is in.
        List<Tile> acceptedIndices = new java.util.ArrayList<>();
        while (!tileIndices.isEmpty()) {
            checkStopThrow();
            processAddMapTiles(acceptedIndices);
        }
        int[] refineByZoom = decideRefinementPerZoom(acceptedIndices);

        List<MapTile> mapTilesNew = new LinkedList<>();
        for (Tile tileIndex : acceptedIndices) {
            checkStopThrow();
            mapTilesNew.add(obtainMapTile(tileIndex, refineByZoom, forceReload));
        }

        Collections.sort(mapTilesNew, (mapTile1, mapTile2) -> {
            LatLong o1 = mapTile1.getImpWhiteTileIndex();
            LatLong o2 = mapTile2.getImpWhiteTileIndex();

            double d1 = LatLongUtils.distance(o1, targetLatLong);
            double d2 = LatLongUtils.distance(o2, targetLatLong);

            return Double.compare(d1, d2);
        });

        checkStopThrow();

        getC().mapTileStorage.setMapTileList(mapTilesNew);
        getC().mapTileStorage.queueWeldersForAlreadyDrawnTiles();

        // this has to be at the end, because the thread may be interrupted:
        if (getC().mapTileStorage.getNumberOfMapTiles() > 0) {
            lastUpdatedPos = targetLatLong;
        }

        getC().mapTileStorage.readyToDispose = true;

        MapViewerSingleton.getViewerInstance().labelLoading.setState(LOADING);
    }

    /** Web-mercator Y of a latitude, 0 at the north edge of the world and 1 at the south. */
    private static double mercatorY(double latitude) {
        double clamped = Math.max(-85.05112878, Math.min(85.05112878, latitude));
        double s = Math.sin(Math.toRadians(clamped));
        return 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
    }

    /**
     * Distance from the target to this tile — to its nearest edge, zero when the target is inside
     * it — measured in min-zoom tile widths.
     *
     * <p>Deliberately measured in projected units rather than degrees. Mercator tiles cover less
     * and less ground the further north they are, so a radius expressed in degrees of latitude
     * spans a handful of tiles at the equator and a great many near the pole: the same rule that
     * loads a couple of hundred tiles over the Alps asked for the better part of a thousand over
     * Svalbard. In projected units a given distance is always the same number of tiles, so the
     * detail rules below cost the same wherever the user is.
     */
    private double getDistance(BoundingBox bb) {
        final double units = 1 << MapTile.ZOOM_LEVEL_MIN;
        double tx = (targetLatLong.getLongitude() + 180.0) / 360.0 * units;
        double ty = mercatorY(targetLatLong.getLatitude()) * units;

        double x0 = (bb.minLongitude + 180.0) / 360.0 * units;
        double x1 = (bb.maxLongitude + 180.0) / 360.0 * units;
        double y0 = mercatorY(bb.maxLatitude) * units; // north edge is the smaller Y
        double y1 = mercatorY(bb.minLatitude) * units;

        double dx = Math.max(Math.max(x0 - tx, 0.0), tx - x1);
        double dy = Math.max(Math.max(y0 - ty, 0.0), ty - y1);
        return Math.hypot(dx, dy);
    }

    private void addSubTiles(Tile tileIndex) {
        byte zoomLevelP1 = (byte) (tileIndex.zoomLevel + 1);
        int tileX0 = 2*tileIndex.tileX;
        int tileY0 = 2*tileIndex.tileY;
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                tileIndices.add(new Tile(tileX0 + x, tileY0 + y, zoomLevelP1, MapTile.MF_ZOOM));
            }
        }
    }

    /**
     * Detail wanted at a given distance from the target, in min-zoom tile widths (see
     * {@link #getDistance}). One unit is a zoom-8 tile: about 156 km at the equator.
     *
     * <p>Every MapTile carries the same 65x65 vertex grid whatever its zoom level — the elevation
     * factor in {@link MapTile#computeZoomElevFactor} is chosen to keep it constant — so the zoom
     * level alone sets the ground spacing between vertices, and each level costs four times as many
     * tiles as the one below. At this latitude that spacing is roughly:
     * z12 107 m, z11 213 m, z10 426 m, z9 852 m, z8 1705 m.
     *
     * <p>The old thresholds fell from z12 to z8 between 0.2 and 0.45 degrees — the full sixteenfold
     * loss of detail packed into 22..50 km, which is well inside what you can actually see from a
     * summit, so mid-distance ranges turned blocky. The bands below roughly double with each level
     * dropped, which is the natural progression for terrain LOD: detail then falls away gradually
     * instead of falling off a cliff, and it keeps useful relief out to a couple of hundred km.
     *
     * <p>Being generous here is safe because the total is capped separately: see
     * {@link #extraTileBudget}. This function says what would look best, the budget decides how
     * much of it the device can afford.
     */
    private int getDesiredZoomLevel(double distance) {
        if (distance < 0.16) {
            return 12;
        } else if (distance < 0.36) {
            return 11;
        } else if (distance < 0.71) {
            return 10;
        } else if (distance < GUARANTEED_DETAIL_TILES) {
            return 9;
        }
        return 8;
    }

    private void processAddMapTiles(List<Tile> acceptedIndices) {

        Tile tileIndex = tileIndices.remove();

        BoundingBox bb = tileIndex.getBoundingBox();
        double distance = getDistance(bb);
        int zoomLevelDesired = getDesiredZoomLevel(distance);

        if (zoomLevelDesired > tileIndex.zoomLevel
                && (distance <= GUARANTEED_DETAIL_TILES
                    || extraTilesSpent + SUBDIVISION_COST <= extraTileBudget)) {
            // Close terrain is refined whatever the allowance says. It fills most of the screen
            // and carries the satellite imagery — a tile is textured as a whole, so an oversized
            // tile nearby is not just coarse ground, it is a blurry photograph over it. The
            // allowance exists to stop the far field growing without bound, and running out
            // there costs far less than running out here.
            if (distance > GUARANTEED_DETAIL_TILES) {
                extraTilesSpent += SUBDIVISION_COST;
            }
            addSubTiles(tileIndex);
            return;
        }

        acceptedIndices.add(tileIndex);
    }

    /** Reuses the existing tile when it already has the right mesh resolution, else rebuilds it. */
    private MapTile obtainMapTile(Tile tileIndex, int[] refineByZoom, boolean forceReload) {
        int zoom = tileIndex.zoomLevel;
        int refine = (zoom >= 0 && zoom < MAX_ZOOM_INDEX) ? refineByZoom[zoom] : 0;
        int elevFactor = MapTile.clampElevFactorToIndexLimit(
                zoom, MapTile.computeZoomElevFactor(zoom) - refine);

        MapTile newMapTile = getC().mapTileStorage.getFromTileIndexExact(tileIndex);
        if (newMapTile != null && !forceReload && newMapTile.zoomElevFactor != elevFactor) {
            newMapTile = null; // mesh resolution for this level changed; rebuild rather than reuse
        }
        if (newMapTile == null || forceReload) {
            newMapTile = addNewMapTile(tileIndex, elevFactor);
        } else {
            getC().mapTileStorage.mapTilesForDisposal.remove(newMapTile);
            newMapTile.setMapTileState(MapTile.MapTileState.ELEVATION_DATA_NOT_LOADED);
        }
        return newMapTile;
    }

    private synchronized MapTile addNewMapTile(Tile tileIndex, int elevFactor) {
        checkStopThrow();

        getAppState().setLastAnyMapTileUpdateTimeToNow();
        return new MapTile(tileIndex, elevFactor);

        /*

        double distance = Math.sqrt(Math.pow(startLatdex - latdex, 2) + Math.pow(startLondex - londex, 2));
        float distanceMeters = Units.convertLatitsToMeters((float)distance/SUB);
        if (distanceMeters > MAX_DISTANCE_METERS)
            return null;

        if (distance > 25)
            zoomFactor = Math.max(24, zoomFactor);
        else if (distance > 10)
            zoomFactor = Math.max(16, zoomFactor);
        else if (distance > 8)
            zoomFactor = Math.max(8, zoomFactor);
        else if (distance > 4)
            zoomFactor = Math.max(4, zoomFactor);

        // Make sure the tile width is always 2k+1 where k is integer
        while (1800 % (cropFactor * zoomFactor) != 0)
            zoomFactor++;

        return new MapTile(index, cropFactor, zoomFactor);

         */
    }

}
