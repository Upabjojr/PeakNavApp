package com.peaknav.utils;

import com.badlogic.gdx.Preferences;

import java.util.HashMap;
import java.util.Map;

/**
 * Preferences that read like the stored ones and are never written back.
 *
 * <p>The values are copied from the real store when this is created, so a session sees the same
 * settings the app was left in and the same provider choices; every change after that lives in
 * this map alone, and {@link #flush()} does nothing.
 *
 * <p>This exists for the headless renderer. It shares a data directory with the desktop app -
 * deliberately, so the two need not download the world twice - and that directory holds the
 * preferences file as well as the map data. A scripted render that switches the sky off, turns
 * labels on, picks an imagery provider and moves the viewpoint was writing all of it into the
 * preferences of whoever's machine it ran on, so the next time the app opened, its sky was off
 * and it started over Tenerife. Map data stays shared; settings stop leaking.
 */
public class EphemeralPreferences implements Preferences {

    private final Map<String, Object> values = new HashMap<>();

    public EphemeralPreferences(Preferences stored) {
        if (stored != null) {
            values.putAll(stored.get());
        }
    }

    @Override
    public Preferences putBoolean(String key, boolean val) {
        values.put(key, val);
        return this;
    }

    @Override
    public Preferences putInteger(String key, int val) {
        values.put(key, val);
        return this;
    }

    @Override
    public Preferences putLong(String key, long val) {
        values.put(key, val);
        return this;
    }

    @Override
    public Preferences putFloat(String key, float val) {
        values.put(key, val);
        return this;
    }

    @Override
    public Preferences putString(String key, String val) {
        values.put(key, val);
        return this;
    }

    @Override
    public Preferences put(Map<String, ?> vals) {
        values.putAll(vals);
        return this;
    }

    @Override
    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    @Override
    public int getInteger(String key) {
        return getInteger(key, 0);
    }

    @Override
    public long getLong(String key) {
        return getLong(key, 0L);
    }

    @Override
    public float getFloat(String key) {
        return getFloat(key, 0f);
    }

    @Override
    public String getString(String key) {
        return getString(key, "");
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object v = values.get(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        // A store that was read back from a file may hand every value over as a string.
        return v == null ? defValue : Boolean.parseBoolean(v.toString());
    }

    @Override
    public int getInteger(String key, int defValue) {
        Object v = values.get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return v == null ? defValue : Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    @Override
    public long getLong(String key, long defValue) {
        Object v = values.get(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return v == null ? defValue : Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    @Override
    public float getFloat(String key, float defValue) {
        Object v = values.get(key);
        if (v instanceof Number) {
            return ((Number) v).floatValue();
        }
        try {
            return v == null ? defValue : Float.parseFloat(v.toString());
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    @Override
    public String getString(String key, String defValue) {
        Object v = values.get(key);
        return v == null ? defValue : v.toString();
    }

    @Override
    public Map<String, ?> get() {
        return new HashMap<String, Object>(values);
    }

    @Override
    public boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override
    public void clear() {
        values.clear();
    }

    @Override
    public void remove(String key) {
        values.remove(key);
    }

    /** Deliberately nothing: this is the whole point of the class. */
    @Override
    public void flush() {
    }
}
