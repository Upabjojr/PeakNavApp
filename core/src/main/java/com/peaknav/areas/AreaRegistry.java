package com.peaknav.areas;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the labelled-area database in geographic tiles, mirroring how POIs are stored: instead of
 * one file with every area on earth, areas live in {@code areas/areas_<latIdx>_<lonIdx>.json} files,
 * where the tile index is {@code floor(deg / TILE_DEG)}. Only the tiles around the current location
 * are ever loaded, and each tile is parsed at most once (cached, including "no such tile" as an
 * empty list). Files are read through {@link Gdx#files}, which is only ready after libGDX starts, so
 * nothing is read until {@link #getAreasNear} is first called.
 */
public class AreaRegistry {

    /** Tile size in degrees. A 3x3 neighbourhood of 2° tiles guarantees ≥2° (~222 km) of coverage
     *  around the viewer in every direction — comfortably beyond the farthest area relevance range. */
    private static final double TILE_DEG = 2.0;

    private final Map<Long, List<MapArea>> tileCache = new HashMap<>();

    // Cache the last neighbourhood so a per-frame call is a no-op until the viewer crosses a tile.
    private long lastCentreKey = Long.MIN_VALUE;
    private List<MapArea> lastResult = Collections.emptyList();

    /** All areas whose tile is in the 3x3 neighbourhood of the tile containing (lat, lon). */
    public synchronized List<MapArea> getAreasNear(double lat, double lon) {
        int latTile = (int) Math.floor(lat / TILE_DEG);
        int lonTile = (int) Math.floor(lon / TILE_DEG);
        long centreKey = tileKey(latTile, lonTile);
        if (centreKey == lastCentreKey) {
            return lastResult;
        }
        List<MapArea> result = new ArrayList<>();
        for (int dLat = -1; dLat <= 1; dLat++) {
            for (int dLon = -1; dLon <= 1; dLon++) {
                result.addAll(loadTile(latTile + dLat, lonTile + dLon));
            }
        }
        lastCentreKey = centreKey;
        lastResult = result;
        return result;
    }

    private List<MapArea> loadTile(int latTile, int lonTile) {
        long key = tileKey(latTile, lonTile);
        List<MapArea> cached = tileCache.get(key);
        if (cached != null) {
            return cached;
        }
        List<MapArea> list = new ArrayList<>();
        try {
            FileHandle file = Gdx.files.internal("areas/areas_" + latTile + "_" + lonTile + ".json");
            if (file.exists()) {
                JsonValue root = new JsonReader().parse(file);
                JsonValue array = (root != null) ? root.get("areas") : null;
                if (array != null) {
                    for (JsonValue jo = array.child; jo != null; jo = jo.next) {
                        list.add(new MapArea(
                                jo.getString("name", ""),
                                jo.getString("type", "island"),
                                jo.getFloat("lat"),
                                jo.getFloat("lon"),
                                jo.getFloat("semiMajorKm"),
                                jo.getFloat("semiMinorKm"),
                                jo.getFloat("rotationDeg", 0f),
                                jo.getFloat("peakMeters", 0f),
                                jo.getFloat("visibleRangeKm", 0f)));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Areas] failed to load tile " + latTile + "," + lonTile + ": " + e.getMessage());
        }
        List<MapArea> immutable = Collections.unmodifiableList(list);
        tileCache.put(key, immutable);
        return immutable;
    }

    private static long tileKey(int latTile, int lonTile) {
        return (((long) latTile) << 32) | (lonTile & 0xFFFFFFFFL);
    }
}
