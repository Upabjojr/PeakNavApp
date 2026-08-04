package com.peaknav.areas;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.peaknav.pbf.PbfLayer;
import com.peaknav.utils.PathUtils;
import com.peaknav.viewer.tiles.MapTile;

import org.mapsforge.core.model.Tile;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the labelled-area database, one file per zoom-9 slippy tile.
 *
 * <p>Area tiles are downloaded map data, stored and addressed exactly like the POI and highway
 * extracts: under {@code map_folder/AREAS/} in the app's external folder, named by
 * {@link PathUtils#getPbfExternalFilePath}. They used to be bundled with the app, which meant every
 * install carried the whole world's areas whether or not the user had that region; now they arrive
 * with the rest of a region's map data.
 *
 * <p>Only the tiles around the current location are ever loaded, and each tile is parsed at most
 * once — cached, including "no such tile" as an empty list, so a region without area data costs one
 * failed lookup rather than one per frame.
 */
public class AreaRegistry {

    /** Zoom level the area tiles are generated at (see the pipeline). */
    private static final int ZOOM = PbfLayer.ZOOM_LEVEL_AREAS;
    /** Top-level folder the archives unpack into, directly under the external storage root. */
    private static final String AREA_FOLDER = PbfLayer.AREAS.name();

    /**
     * How far around the viewer to load tiles, in degrees. Sized to cover
     * {@link MapArea#MAX_RANGE_KM} (250 km ≈ 2.25°) plus a tile's own extent (~0.7°), so an area
     * whose tile is near the edge but whose visible range reaches the viewer is loaded before the
     * relevance cull. (The no-op cache below can erode the margin by up to one more tile while the
     * viewer crosses the centre tile; at that extreme edge a label may appear one rescan late.)
     */
    private static final double COVERAGE_DEG = 3.0;

    /**
     * At most this many tiles are parsed per {@link #getAreasNear} call. The call runs on the
     * render thread, so an unbounded first scan (or a high-latitude one, where mercator rows
     * shrink in degrees and the fixed window spans many more tiles) would hitch a frame badly;
     * instead the neighbourhood fills in over a few frames.
     */
    private static final int TILE_LOADS_PER_CALL = 12;

    /** Mercator rows shrink towards the poles; clamp the scan so it cannot explode there. */
    private static final int MAX_TILE_ROWS = 48;

    /**
     * Loaded tiles are kept in an access-ordered LRU, so memory no longer grows without bound as
     * the viewer travels (each entry is one parsed tile, or an empty list for an ocean tile).
     */
    private static final int MAX_CACHED_TILES = 1024;

    private final Map<Long, List<MapArea>> tileCache =
            new LinkedHashMap<Long, List<MapArea>>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, List<MapArea>> eldest) {
                    return size() > MAX_CACHED_TILES;
                }
            };

    // Cache the last neighbourhood so a per-frame call is a no-op until the viewer crosses a tile.
    private long lastCentreKey = Long.MIN_VALUE;
    private List<MapArea> lastResult = Collections.emptyList();

    /**
     * Forgets everything read so far, so the next look-up goes back to disk.
     *
     * <p>Needed after a download. A tile that was not on disk is remembered as "no areas here",
     * which is what stops a region without area data costing a failed lookup every frame — but it
     * also means a tile that arrives later is never noticed. Together with the neighbourhood
     * cache below, that left freshly downloaded labels invisible until the app was restarted or
     * the viewer moved far enough to cross into tiles that had never been looked up.
     */
    public synchronized void invalidateCache() {
        tileCache.clear();
        lastCentreKey = Long.MIN_VALUE;
        lastResult = Collections.emptyList();
    }

    /** All areas whose tile lies within {@link #COVERAGE_DEG} of (lat, lon). */
    public synchronized List<MapArea> getAreasNear(double lat, double lon) {
        int centreX = lon2tileX(lon);
        int centreY = lat2tileY(lat);
        long centreKey = tileKey(centreX, centreY);
        if (centreKey == lastCentreKey) {
            return lastResult;
        }

        int n = 1 << ZOOM;
        int xMin = lon2tileX(lon - COVERAGE_DEG);
        int xMax = lon2tileX(lon + COVERAGE_DEG);
        int yTop = lat2tileY(lat + COVERAGE_DEG); // higher latitude -> smaller Y
        int yBot = lat2tileY(lat - COVERAGE_DEG);
        if (yBot - yTop > MAX_TILE_ROWS) {
            yTop = Math.max(yTop, centreY - MAX_TILE_ROWS / 2);
            yBot = Math.min(yBot, centreY + MAX_TILE_ROWS / 2);
        }

        List<MapArea> result = new ArrayList<>();
        int loads = 0;
        boolean complete = true;
        for (int x = xMin; x <= xMax; x++) {
            int xx = ((x % n) + n) % n; // wrap around the antimeridian
            for (int y = yTop; y <= yBot; y++) {
                if (y < 0 || y >= n) continue; // no tiles past the poles
                List<MapArea> tile = tileCache.get(tileKey(xx, y));
                if (tile == null) {
                    if (loads >= TILE_LOADS_PER_CALL) {
                        complete = false; // budget spent — pick this tile up next frame
                        continue;
                    }
                    loads++;
                    tile = loadTile(xx, y);
                }
                result.addAll(tile);
            }
        }
        // Only remember the neighbourhood once every tile in it has been loaded; until then the
        // next frame re-enters (all cache hits plus the next budget's worth of loads).
        lastCentreKey = complete ? centreKey : Long.MIN_VALUE;
        lastResult = result;
        return result;
    }

    /**
     * Has every area tile around this point been read?
     *
     * <p>{@link #getAreasNear} reads at most {@link #TILE_LOADS_PER_CALL} tiles per call and is
     * called once per rendered frame, so the neighbourhood arrives over several frames and the
     * first of them see only part of it. That is invisible while someone is looking at a moving
     * map and obvious in a video, whose opening frames were missing area names that appeared a
     * moment later. Anything capturing an image should wait for this - under a timeout, like
     * every other readiness signal.
     *
     * <p>True exactly when the last scan for this centre tile finished: the neighbourhood is only
     * remembered ({@code lastCentreKey}) once complete.
     */
    public synchronized boolean isNeighbourhoodLoaded(double lat, double lon) {
        return tileKey(lon2tileX(lon), lat2tileY(lat)) == lastCentreKey;
    }

    private List<MapArea> loadTile(int tileX, int tileY) {
        long key = tileKey(tileX, tileY);
        List<MapArea> cached = tileCache.get(key);
        if (cached != null) {
            return cached;
        }
        List<MapArea> list = new ArrayList<>();
        try {
            FileHandle file = new FileHandle(areaTileFile(tileX, tileY));
            if (file.exists()) {
                JsonValue root = new JsonReader().parse(file);
                JsonValue array = (root != null) ? root.get("areas") : null;
                if (array != null) {
                    for (JsonValue jo = array.child; jo != null; jo = jo.next) {
                        // Per-entry, so one malformed area (e.g. a missing "lon") skips only
                        // itself — previously it aborted the loop and the truncated list was
                        // cached for the whole session.
                        try {
                            list.add(new MapArea(
                                    jo.getString("name", ""),
                                    jo.getString("type", "island"),
                                    jo.getFloat("lat"),
                                    jo.getFloat("lon"),
                                    jo.getFloat("semiMajorKm"),
                                    jo.getFloat("semiMinorKm"),
                                    jo.getFloat("rotationDeg", 0f),
                                    jo.getFloat("peakMeters", 0f),
                                    jo.getFloat("visibleRangeKm", 0f),
                                    jo.getInt("population", 0)));
                        } catch (RuntimeException e) {
                            System.err.println("[Areas] skipped bad entry in tile "
                                    + tileX + "," + tileY + ": " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Areas] failed to load tile " + tileX + "," + tileY + ": " + e.getMessage());
        }
        List<MapArea> immutable = Collections.unmodifiableList(list);
        tileCache.put(key, immutable);
        return immutable;
    }

    /**
     * File one area tile is unpacked to — located exactly like a PBF extract.
     *
     * <p>Areas are downloaded map data like the rest, so they go through the very same path
     * builder, differing only in the extension the layer carries
     * ({@link PbfLayer#getFileExtension}):
     *
     * <pre>map_folder/AREAS/zoom_09/xa_02/xb_58/ya_01/yb_88_AREAS_z_09_x_0258_y_0188.json</pre>
     *
     * <p>The archives hold that same path from {@code map_folder} down, so they unpack at the
     * external root like every other layer. The pipeline builds the string with
     * {@code common.utils.tile_relpath}; the two have to agree exactly or a downloaded tile is
     * never found.
     */
    static File areaTileFile(int tileX, int tileY) {
        Tile tile = new Tile(tileX, tileY, (byte) ZOOM, MapTile.MF_ZOOM);
        return PathUtils.getPbfExternalFilePath(tile, PbfLayer.AREAS);
    }

    private static int lon2tileX(double lon) {
        int n = 1 << ZOOM;
        return (int) Math.floor((lon + 180.0) / 360.0 * n);
    }

    private static int lat2tileY(double lat) {
        int n = 1 << ZOOM;
        double clamped = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double latRad = Math.toRadians(clamped);
        return (int) Math.floor(
                (1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * n);
    }

    private static long tileKey(int tileX, int tileY) {
        return (((long) tileX) << 32) | (tileY & 0xFFFFFFFFL);
    }
}
