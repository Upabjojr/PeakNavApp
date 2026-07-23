import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peaknav.config.ProviderStore;
import com.peaknav.network.DownloadProvider;
import com.peaknav.network.DownloadProviderRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Covers the map-data (download) provider list: it seeds the HuggingFace default on first run,
 * persists edits as JSON, and survives a restart. Uses an in-memory store so no file system is
 * needed.
 */
public class TestDownloadProviderRegistry {

    private static class InMemoryStore implements ProviderStore {
        String json;

        @Override
        public String read() {
            return json;
        }

        @Override
        public void write(String json) {
            this.json = json;
        }
    }

    private static final String ELEV = "https://mirror.example.com/elev/";
    private static final String MAP = "https://mirror.example.com/map/";

    @Test
    public void seedsHuggingFaceDefaultOnFirstRun() {
        InMemoryStore store = new InMemoryStore();
        DownloadProviderRegistry registry = new DownloadProviderRegistry(store);

        List<DownloadProvider> providers = registry.getProviders();
        assertEquals(1, providers.size());
        assertTrue(providers.get(0).elevationBaseUrl.contains("huggingface.co"));
        assertTrue(providers.get(0).mapDataBaseUrl.contains("huggingface.co"));
        // The default was written back so the file exists and is editable.
        assertNotNull(store.read());
    }

    @Test
    public void addsAndPersistsAcrossRestart() {
        InMemoryStore store = new InMemoryStore();
        DownloadProviderRegistry registry = new DownloadProviderRegistry(store);
        assertNull(registry.addProvider("My mirror", ELEV, MAP));
        assertEquals(2, registry.getProviders().size());

        DownloadProviderRegistry reloaded = new DownloadProviderRegistry(store);
        List<DownloadProvider> providers = reloaded.getProviders();
        assertEquals(2, providers.size());
        assertEquals("My mirror", providers.get(1).name);
        assertEquals(ELEV, providers.get(1).elevationBaseUrl);
    }

    @Test
    public void appendsTrailingSlashAndDefaultsNameToHost() {
        DownloadProviderRegistry registry = new DownloadProviderRegistry(new InMemoryStore());
        assertNull(registry.addProvider("  ", "https://tiles.example.org/elev",
                "https://tiles.example.org/map"));
        DownloadProvider added = registry.getProviders().get(1);
        assertEquals("https://tiles.example.org/elev/", added.elevationBaseUrl);
        assertEquals("tiles.example.org", added.name);
    }

    @Test
    public void rejectsNonHttpAndEmptyUrls() {
        DownloadProviderRegistry registry = new DownloadProviderRegistry(new InMemoryStore());
        assertNotNull(registry.addProvider("bad", "ftp://x/", "https://y/"));
        assertNotNull(registry.addProvider("empty", "", "https://y/"));
        assertEquals(1, registry.getProviders().size(), "only the seeded default remains");
    }

    @Test
    public void editsInPlaceKeepingOrder() {
        DownloadProviderRegistry registry = new DownloadProviderRegistry(new InMemoryStore());
        registry.addProvider("Second", ELEV, MAP);
        assertNull(registry.updateProvider(0, "Renamed default",
                "https://new.example.com/e/", "https://new.example.com/m/"));

        List<DownloadProvider> providers = registry.getProviders();
        assertEquals("Renamed default", providers.get(0).name);
        assertEquals("https://new.example.com/e/", providers.get(0).elevationBaseUrl);
        assertEquals("Second", providers.get(1).name);
    }

    @Test
    public void removingTheLastOneReseedsTheDefault() {
        DownloadProviderRegistry registry = new DownloadProviderRegistry(new InMemoryStore());
        registry.removeProvider(0);
        assertEquals(1, registry.getProviders().size());
        assertTrue(registry.getProviders().get(0).elevationBaseUrl.contains("huggingface.co"));
    }
}
