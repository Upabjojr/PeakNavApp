package com.peaknav.utils;

import com.badlogic.gdx.Preferences;

import java.util.Map;

/**
 * Preferences that several threads may write at once, one at a time.
 *
 * <p>The settings are written from the menus' worker, from the thread that saves the camera's
 * orientation while it turns, from the render thread and from the generic executor. libGDX's
 * preferences are not made for that. On Android they keep one editor, created on the first
 * put and dropped by flush: a flush between another thread's two puts threw on that thread,
 * or left its value in an editor already applied, never saved. On the desktop two flushes at
 * once rewrote the same file together, and one shorter than the other left the file
 * unreadable - every setting lost at the next start.
 */
final class SynchronizedPreferences implements Preferences {

    private final Preferences preferences;

    SynchronizedPreferences(Preferences preferences) {
        this.preferences = preferences;
    }

    @Override public synchronized Preferences putBoolean(String key, boolean val) { preferences.putBoolean(key, val); return this; }
    @Override public synchronized Preferences putInteger(String key, int val) { preferences.putInteger(key, val); return this; }
    @Override public synchronized Preferences putLong(String key, long val) { preferences.putLong(key, val); return this; }
    @Override public synchronized Preferences putFloat(String key, float val) { preferences.putFloat(key, val); return this; }
    @Override public synchronized Preferences putString(String key, String val) { preferences.putString(key, val); return this; }
    @Override public synchronized Preferences put(Map<String, ?> vals) { preferences.put(vals); return this; }
    @Override public synchronized boolean getBoolean(String key) { return preferences.getBoolean(key); }
    @Override public synchronized int getInteger(String key) { return preferences.getInteger(key); }
    @Override public synchronized long getLong(String key) { return preferences.getLong(key); }
    @Override public synchronized float getFloat(String key) { return preferences.getFloat(key); }
    @Override public synchronized String getString(String key) { return preferences.getString(key); }
    @Override public synchronized boolean getBoolean(String key, boolean defValue) { return preferences.getBoolean(key, defValue); }
    @Override public synchronized int getInteger(String key, int defValue) { return preferences.getInteger(key, defValue); }
    @Override public synchronized long getLong(String key, long defValue) { return preferences.getLong(key, defValue); }
    @Override public synchronized float getFloat(String key, float defValue) { return preferences.getFloat(key, defValue); }
    @Override public synchronized String getString(String key, String defValue) { return preferences.getString(key, defValue); }
    @Override public synchronized Map<String, ?> get() { return preferences.get(); }
    @Override public synchronized boolean contains(String key) { return preferences.contains(key); }
    @Override public synchronized void clear() { preferences.clear(); }
    @Override public synchronized void remove(String key) { preferences.remove(key); }
    @Override public synchronized void flush() { preferences.flush(); }
}
