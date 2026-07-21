import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Preferences;
import com.peaknav.viewer.imgmapprovider.SatelliteImageProvider;
import com.peaknav.viewer.imgmapprovider.SatelliteProviderRegistry;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Covers the custom satellite sources the user can add from the options menu, including that they
 * survive a restart (a fresh registry reading back the same preference store).
 *
 * <p>The built-in providers are deliberately not exercised here: the enum initialiser resolves a
 * translated name, which needs the app's i18n bundle to be loaded.
 */
public class TestSatelliteProviderRegistry {

    /** Minimal in-memory stand-in for the libGDX preference store. */
    private static class FakePreferences implements Preferences {
        final Map<String, Object> values = new HashMap<>();

        public Preferences putBoolean(String key, boolean val) { values.put(key, val); return this; }
        public Preferences putInteger(String key, int val) { values.put(key, val); return this; }
        public Preferences putLong(String key, long val) { values.put(key, val); return this; }
        public Preferences putFloat(String key, float val) { values.put(key, val); return this; }
        public Preferences putString(String key, String val) { values.put(key, val); return this; }
        public Preferences put(Map<String, ?> vals) { values.putAll(vals); return this; }

        public boolean getBoolean(String key) { return getBoolean(key, false); }
        public int getInteger(String key) { return getInteger(key, 0); }
        public long getLong(String key) { return getLong(key, 0); }
        public float getFloat(String key) { return getFloat(key, 0); }
        public String getString(String key) { return getString(key, ""); }

        public boolean getBoolean(String key, boolean defValue) {
            return values.containsKey(key) ? (Boolean) values.get(key) : defValue;
        }
        public int getInteger(String key, int defValue) {
            return values.containsKey(key) ? (Integer) values.get(key) : defValue;
        }
        public long getLong(String key, long defValue) {
            return values.containsKey(key) ? (Long) values.get(key) : defValue;
        }
        public float getFloat(String key, float defValue) {
            return values.containsKey(key) ? (Float) values.get(key) : defValue;
        }
        public String getString(String key, String defValue) {
            return values.containsKey(key) ? (String) values.get(key) : defValue;
        }

        public Map<String, ?> get() { return values; }
        public boolean contains(String key) { return values.containsKey(key); }
        public void clear() { values.clear(); }
        public void remove(String key) { values.remove(key); }
        public void flush() { }
    }

    private static final String URL_A = "https://a.example.com/{z}/{x}/{y}.png";
    private static final String URL_B = "https://b.example.com/{zoom}/{x}/{-y}.jpg";

    @Test
    public void addsACustomProvider() {
        FakePreferences prefs = new FakePreferences();
        SatelliteProviderRegistry registry = new SatelliteProviderRegistry(prefs);

        assertNull(registry.addCustomProvider(URL_A, "My tiles", "Me"));

        List<SatelliteImageProvider> custom = registry.getCustomProviders();
        assertEquals(1, custom.size());
        assertEquals("My tiles", custom.get(0).getProviderName());
        assertEquals(URL_A, custom.get(0).getUrlTemplate());
        assertEquals("Me", custom.get(0).getRawCopyrightNotice());
        assertTrue(custom.get(0).isCustom());
    }

    @Test
    public void customProvidersSurviveARestart() {
        FakePreferences prefs = new FakePreferences();
        SatelliteProviderRegistry registry = new SatelliteProviderRegistry(prefs);
        assertNull(registry.addCustomProvider(URL_A, "First", "Credit A"));
        assertNull(registry.addCustomProvider(URL_B, "Second", "Credit B"));

        // A fresh registry over the same store stands in for restarting the app.
        SatelliteProviderRegistry reloaded = new SatelliteProviderRegistry(prefs);
        List<SatelliteImageProvider> custom = reloaded.getCustomProviders();
        assertEquals(2, custom.size());
        assertEquals("First", custom.get(0).getProviderName());
        assertEquals(URL_B, custom.get(1).getUrlTemplate());
        assertEquals("Credit B", custom.get(1).getRawCopyrightNotice());
    }

    @Test
    public void removingLeavesTheRemainingOnesIntact() {
        FakePreferences prefs = new FakePreferences();
        SatelliteProviderRegistry registry = new SatelliteProviderRegistry(prefs);
        registry.addCustomProvider(URL_A, "First", "Credit A");
        registry.addCustomProvider(URL_B, "Second", "Credit B");

        registry.removeCustomProvider(registry.getCustomProviders().get(0));
        assertEquals(1, registry.getCustomProviders().size());
        assertEquals("Second", registry.getCustomProviders().get(0).getProviderName());

        // and the removal must not resurrect the deleted entry after a reload
        SatelliteProviderRegistry reloaded = new SatelliteProviderRegistry(prefs);
        assertEquals(1, reloaded.getCustomProviders().size());
        assertEquals(URL_B, reloaded.getCustomProviders().get(0).getUrlTemplate());
    }

    @Test
    public void rejectsInvalidTemplateWithAReason() {
        SatelliteProviderRegistry registry = new SatelliteProviderRegistry(new FakePreferences());
        assertNotNull(registry.addCustomProvider("not a url", "x", ""));
        assertNotNull(registry.addCustomProvider("https://e.com/no-placeholders.png", "x", ""));
        assertNotNull(registry.addCustomProvider("https://e.com/{z}/{x}/{y}/{apikey}.png", "x", ""));
        assertEquals(0, registry.getCustomProviders().size());
    }

    @Test
    public void rejectsDuplicateUrl() {
        SatelliteProviderRegistry registry = new SatelliteProviderRegistry(new FakePreferences());
        assertNull(registry.addCustomProvider(URL_A, "First", ""));
        assertNotNull(registry.addCustomProvider(URL_A, "Again", ""));
        assertEquals(1, registry.getCustomProviders().size());
    }

    @Test
    public void fallsBackToHostWhenNoNameGiven() {
        SatelliteProviderRegistry registry = new SatelliteProviderRegistry(new FakePreferences());
        assertNull(registry.addCustomProvider(URL_A, "  ", ""));
        assertEquals("a.example.com", registry.getCustomProviders().get(0).getProviderName());
    }

    @Test
    public void skipsStoredEntriesThatNoLongerParse() {
        FakePreferences prefs = new FakePreferences();
        prefs.putInteger("satellite_custom_count", 2);
        prefs.putString("satellite_custom_url_0", "https://broken.example.com/no-placeholders");
        prefs.putString("satellite_custom_name_0", "Broken");
        prefs.putString("satellite_custom_attribution_0", "");
        prefs.putString("satellite_custom_url_1", URL_A);
        prefs.putString("satellite_custom_name_1", "Good");
        prefs.putString("satellite_custom_attribution_1", "");

        SatelliteProviderRegistry registry = new SatelliteProviderRegistry(prefs);
        assertEquals(1, registry.getCustomProviders().size(), "the broken entry should be skipped");
        assertEquals("Good", registry.getCustomProviders().get(0).getProviderName());
    }
}
