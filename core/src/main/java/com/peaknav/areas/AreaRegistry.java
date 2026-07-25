package com.peaknav.areas;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads the labelled-area database ({@code areas.json}) once, lazily — the file is read through
 * {@link Gdx#files}, which is only ready after libGDX has started, so it must not be read in the
 * constructor.
 */
public class AreaRegistry {

    private volatile List<MapArea> areas = null;

    public List<MapArea> getAreas() {
        List<MapArea> cached = areas;
        if (cached != null) {
            return cached;
        }
        return load();
    }

    private synchronized List<MapArea> load() {
        if (areas != null) {
            return areas;
        }
        List<MapArea> list = new ArrayList<>();
        try {
            FileHandle file = Gdx.files.internal("areas.json");
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
                                jo.getFloat("peakMeters", 0f)));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Areas] failed to load areas.json: " + e.getMessage());
        }
        areas = Collections.unmodifiableList(list);
        return areas;
    }
}
