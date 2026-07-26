package com.peaknav.areas;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the labelled-area database in slippy-map tiles, mirroring how the POI/highway PBF tiles are
 * stored: instead of one file per region, areas live in
 * {@code areas/AREAS/<zoom>/<tileX/100>/<tileX%100>/<tileY/100>/<tileY%100>.json}, i.e. the standard
 * web-mercator tile whose X and Y numbers are split at the hundreds into directory levels. Only the
 * tiles around the current location are ever loaded, and each tile is parsed at most once (cached,
 * including "no such tile" as an empty list). Files are read through {@link Gdx#files}, which is only
 * ready after libGDX starts, so nothing is read until {@link #getAreasNear} is first called.
 */
public class AreaRegistry {

    /** Zoom level the area tiles are generated at (see the pipeline). */
    private static final int ZOOM = 9;

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

    private List<MapArea> loadTile(int tileX, int tileY) {
        long key = tileKey(tileX, tileY);
        List<MapArea> cached = tileCache.get(key);
        if (cached != null) {
            return cached;
        }
        List<MapArea> list = new ArrayList<>();
        try {
            String path = "areas/AREAS/" + ZOOM + "/" + (tileX / 100) + "/" + (tileX % 100)
                    + "/" + (tileY / 100) + "/" + (tileY % 100) + ".json";
            FileHandle file = Gdx.files.internal(path);
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
