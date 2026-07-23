import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.config.ProviderStore;
import com.peaknav.viewer.imgmapprovider.SatelliteImageProvider;
import com.peaknav.viewer.imgmapprovider.SatelliteProviderRegistry;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Covers the custom satellite sources the user can add from the options menu, including that they
 * survive a restart (a fresh registry reading back the same JSON store) and the one-time import of
 * sources an older build kept in preferences.
 *
 * <p>The built-in providers are deliberately not exercised here: the enum initialiser resolves a
 * translated name, which needs the app's i18n bundle to be loaded.
 */
public class TestSatelliteProviderRegistry {

    /** Minimal in-memory stand-in for the JSON file the registry persists to. */
    private static class InMemoryStore implements ProviderStore {
        private String json;

        @Override
        public String read() {
            return json;
        }

        @Override
        public void write(String json) {
            this.json = json;
        }
    }

    private static final String URL_A = "https://a.example.com/{z}/{x}/{y}.png";
    private static final String URL_B = "https://b.example.com/{zoom}/{x}/{-y}.jpg";

    @Test
    public void addsACustomProvider() {
        SatelliteProviderRegistry registry = new SatelliteProviderRegistry(new InMemoryStore());

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
        InMemoryStore store = new InMemoryStore();
        SatelliteProviderRegistry registry = new SatelliteProviderRegistry(store);
        assertNull(registry.addCustomProvider(URL_A, "First", "Credit A"));
        assertNull(registry.addCustomProvider(URL_B, "Second", "Credit B"));

        // A fresh registry over the same store stands in for restarting the app.
        SatelliteProviderRegistry reloaded = new SatelliteProviderRegistry(store);
        List<SatelliteImageProvider> custom = reloaded.getCustomProviders();
        assertEquals(2, custom.size());
        assertEquals("First", custom.get(0).getProviderName());
        assertEquals(URL_B, custom.get(1).getUrlTemplate());
        assertEquals("Credit B", custom.get(1).getRawCopyrightNotice());
    }

    @Test
    public void removingLeavesTheRemainingOnesIntact() {
        InMemoryStore store = new InMemoryStore();
        SatelliteProviderRegistry registry = new SatelliteProviderRegistry(store);
        registry.addCustomProvider(URL_A, "First", "Credit A");
        registry.addCustomProvider(URL_B, "Second", "Credit B");

        registry.removeCustomProvider(registry.getCustomProviders().get(0));
        assertEquals(1, registry.getCustomProviders().size());
        assertEquals("Second", registry.getCustomProviders().get(0).getProviderName());

        // and the removal must not resurrect the deleted entry after a reload
        SatelliteProviderRegistry reloaded = new SatelliteProviderRegistry(store);
        assertEquals(1, reloaded.getCustomProviders().size());
        assertEquals(URL_B, reloaded.getCustomProviders().get(0).getUrlTemplate());
    }

    @Test
    public void rejectsInvalidTemplateWithAReason() {
        SatelliteProviderRegistry registry = new SatelliteProviderRegistry(new InMemoryStore());
        assertNotNull(registry.addCustomProvider("not a url", "x", ""));
        assertNotNull(registry.addCustomProvider("https://e.com/no-placeholders.png", "x", ""));
        assertNotNull(registry.addCustomProvider("https://e.com/{z}/{x}/{y}/{apikey}.png", "x", ""));
        assertEquals(0, registry.getCustomProviders().size());
    }

    @Test
    public void rejectsDuplicateUrl() {
        SatelliteProviderRegistry registry = new SatelliteProviderRegistry(new InMemoryStore());
        assertNull(registry.addCustomProvider(URL_A, "First", ""));
        assertNotNull(registry.addCustomProvider(URL_A, "Again", ""));
        assertEquals(1, registry.getCustomProviders().size());
    }

    @Test
    public void fallsBackToHostWhenNoNameGiven() {
        SatelliteProviderRegistry registry = new SatelliteProviderRegistry(new InMemoryStore());
        assertNull(registry.addCustomProvider(URL_A, "  ", ""));
        assertEquals("a.example.com", registry.getCustomProviders().get(0).getProviderName());
    }

    @Test
    public void skipsStoredEntriesThatNoLongerParse() {
        InMemoryStore store = new InMemoryStore();
        store.write("[{\"url\":\"https://broken.example.com/no-placeholders\",\"name\":\"Broken\"},"
                + "{\"url\":\"" + URL_A + "\",\"name\":\"Good\"}]");

        SatelliteProviderRegistry registry = new SatelliteProviderRegistry(store);
        assertEquals(1, registry.getCustomProviders().size(), "the broken entry should be skipped");
        assertEquals("Good", registry.getCustomProviders().get(0).getProviderName());
    }

    @Test
    public void importsLegacyPreferenceEntriesOnlyWhenEmpty() {
        InMemoryStore store = new InMemoryStore();
        SatelliteProviderRegistry registry = new SatelliteProviderRegistry(store);

        boolean imported = registry.importIfEmpty(Arrays.asList(
                new String[]{URL_A, "Legacy A", "Credit A"},
                new String[]{"https://broken/no-placeholders", "Broken", ""}));
        assertTrue(imported);
        assertEquals(1, registry.getCustomProviders().size());
        assertEquals("Legacy A", registry.getCustomProviders().get(0).getProviderName());

        // A second import must be a no-op now that the registry is non-empty.
        boolean importedAgain = registry.importIfEmpty(
                java.util.Collections.singletonList(new String[]{URL_B, "Legacy B", ""}));
        assertEquals(false, importedAgain);
        assertEquals(1, registry.getCustomProviders().size());
    }
}
