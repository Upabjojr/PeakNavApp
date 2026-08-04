import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Preferences;
import com.peaknav.utils.EphemeralPreferences;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * The headless renderer shares a data directory with the desktop app so map data is downloaded
 * once, and that directory holds the preferences file too. These cover the wrapper that keeps a
 * scripted render from writing its sky, label and viewpoint choices into somebody's app.
 */
class TestEphemeralPreferences {

    /** A stand-in for the real store that records whether anything was written to disk. */
    private static final class RecordingPreferences implements Preferences {
        final Map<String, Object> values = new HashMap<>();
        int flushes = 0;

        @Override public Preferences putBoolean(String k, boolean v) { values.put(k, v); return this; }
        @Override public Preferences putInteger(String k, int v) { values.put(k, v); return this; }
        @Override public Preferences putLong(String k, long v) { values.put(k, v); return this; }
        @Override public Preferences putFloat(String k, float v) { values.put(k, v); return this; }
        @Override public Preferences putString(String k, String v) { values.put(k, v); return this; }
        @Override public Preferences put(Map<String, ?> v) { values.putAll(v); return this; }
        @Override public boolean getBoolean(String k) { return getBoolean(k, false); }
        @Override public int getInteger(String k) { return getInteger(k, 0); }
        @Override public long getLong(String k) { return getLong(k, 0L); }
        @Override public float getFloat(String k) { return getFloat(k, 0f); }
        @Override public String getString(String k) { return getString(k, ""); }
        @Override public boolean getBoolean(String k, boolean d) {
            return values.containsKey(k) ? (Boolean) values.get(k) : d;
        }
        @Override public int getInteger(String k, int d) {
            return values.containsKey(k) ? (Integer) values.get(k) : d;
        }
        @Override public long getLong(String k, long d) {
            return values.containsKey(k) ? (Long) values.get(k) : d;
        }
        @Override public float getFloat(String k, float d) {
            return values.containsKey(k) ? (Float) values.get(k) : d;
        }
        @Override public String getString(String k, String d) {
            return values.containsKey(k) ? (String) values.get(k) : d;
        }
        @Override public Map<String, ?> get() { return values; }
        @Override public boolean contains(String k) { return values.containsKey(k); }
        @Override public void clear() { values.clear(); }
        @Override public void remove(String k) { values.remove(k); }
        @Override public void flush() { flushes++; }
    }

    @Test
    @DisplayName("stored settings are visible, so a session starts from the real configuration")
    void readsThrough() {
        RecordingPreferences stored = new RecordingPreferences();
        stored.putBoolean("viewer_sky", true);
        stored.putString("underlay_image_provider", "LANDSAT");
        stored.putFloat("last_latitude", 46.02f);

        Preferences prefs = new EphemeralPreferences(stored);

        assertTrue(prefs.getBoolean("viewer_sky", false), "should read the stored value");
        assertEquals("LANDSAT", prefs.getString("underlay_image_provider", ""));
        assertEquals(46.02f, prefs.getFloat("last_latitude", 0f), 1e-4);
        assertTrue(prefs.contains("viewer_sky"));
        assertEquals("fallback", prefs.getString("never_set", "fallback"));
    }

    @Test
    @DisplayName("nothing a render changes reaches the store, flush included")
    void writesGoNowhere() {
        RecordingPreferences stored = new RecordingPreferences();
        stored.putBoolean("viewer_sky", true);
        stored.putFloat("last_latitude", 46.02f);

        Preferences prefs = new EphemeralPreferences(stored);

        // What a scripted render does: sky off, labels rearranged, viewpoint moved.
        prefs.putBoolean("viewer_sky", false);
        prefs.putBoolean("viewer_show_peaks", false);
        prefs.putFloat("last_latitude", 28.27f);
        prefs.remove("underlay_image_provider");
        prefs.flush();

        assertFalse(prefs.getBoolean("viewer_sky", true), "the session sees its own change");
        assertTrue(stored.getBoolean("viewer_sky", false),
                "the stored setting must be untouched - this is the leak that was happening");
        assertEquals(46.02f, stored.getFloat("last_latitude", 0f), 1e-4,
                "the app's saved position must not move because a render moved");
        assertFalse(stored.contains("viewer_show_peaks"), "new keys must not appear in the store");
        assertEquals(0, stored.flushes, "flush() must never reach the real store");
    }

    @Test
    @DisplayName("clear() empties the session, not the user's settings")
    void clearIsLocal() {
        RecordingPreferences stored = new RecordingPreferences();
        stored.putBoolean("viewer_sky", true);

        Preferences prefs = new EphemeralPreferences(stored);
        prefs.clear();
        prefs.flush();

        assertFalse(prefs.contains("viewer_sky"));
        assertTrue(stored.contains("viewer_sky"), "the store keeps its settings");
        assertEquals(0, stored.flushes);
    }

    @Test
    @DisplayName("values that come back from a file as strings still read as their own type")
    void coercesStringValues() {
        RecordingPreferences stored = new RecordingPreferences();
        stored.putString("viewer_sky", "true");
        stored.putString("viewer_sky_mode", "2");
        stored.putString("last_latitude", "46.02");

        Preferences prefs = new EphemeralPreferences(stored);

        assertTrue(prefs.getBoolean("viewer_sky", false));
        assertEquals(2, prefs.getInteger("viewer_sky_mode", 0));
        assertEquals(46.02f, prefs.getFloat("last_latitude", 0f), 1e-4);
        assertEquals(7, prefs.getInteger("viewer_sky", 7), "unparseable falls back to the default");
    }
}
