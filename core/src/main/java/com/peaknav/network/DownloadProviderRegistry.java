package com.peaknav.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.peaknav.config.ProviderStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The ordered list of {@link DownloadProvider}s the app fetches map data from, persisted
 * as JSON (see {@link com.peaknav.config.JsonConfigStore}). Providers are tried in order
 * per tile until one succeeds, so extra entries act as mirrors of the default.
 *
 * <p>On first run — or whenever the file is missing, empty or unparseable — the list is
 * seeded with the HuggingFace default and written back, so it is always present and the
 * user can then rename it, edit its URLs, add mirrors, reorder or remove it.
 */
public class DownloadProviderRegistry {

    public static final String CONFIG_FILE = "download_providers.json";

    private static final String DEFAULT_NAME = "HuggingFace (PeakNav)";
    private static final String DEFAULT_ELEVATION_URL =
            "https://huggingface.co/datasets/PeakNav/global-elevation-aster-slippy-tiles-tar-gz/resolve/main/";
    private static final String DEFAULT_MAP_DATA_URL =
            "https://huggingface.co/datasets/PeakNav/global-openstreetmap-extraction-slippy-tiles-tar/resolve/main/";

    private final ProviderStore store;
    private final List<DownloadProvider> providers = new ArrayList<>();
    private boolean loaded = false;

    public DownloadProviderRegistry(ProviderStore store) {
        this.store = store;
        // Deliberately not loaded here. This registry is created while the MapController
        // is built, which on desktop happens before libGDX has initialised Gdx.files, so
        // touching the store now would NPE. The first use (at download or menu time) is
        // well after start-up, so load lazily then.
    }

    private synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        load();
    }

    private void load() {
        providers.clear();
        String json = store.read();
        if (json != null) {
            try {
                DownloadProvider[] parsed = new Gson().fromJson(json, DownloadProvider[].class);
                if (parsed != null) {
                    for (DownloadProvider provider : parsed) {
                        if (provider != null && provider.isUsable()) {
                            providers.add(provider);
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // Corrupt file: fall through to seeding the default rather than crashing.
            }
        }
        boolean changed = ensureBuiltinPresent();
        if (json == null || changed) {
            save();
        }
    }

    /**
     * Guarantees the non-removable HuggingFace default is present. Adopts an existing entry that
     * still has the default URLs (e.g. a file written before the builtin flag existed), otherwise
     * prepends a fresh one so it is tried first.
     *
     * @return true if the list was changed and should be persisted
     */
    private boolean ensureBuiltinPresent() {
        for (DownloadProvider provider : providers) {
            if (provider.builtin) {
                return false;
            }
        }
        for (DownloadProvider provider : providers) {
            if (DEFAULT_ELEVATION_URL.equals(provider.elevationBaseUrl)
                    && DEFAULT_MAP_DATA_URL.equals(provider.mapDataBaseUrl)) {
                provider.builtin = true;
                return true;
            }
        }
        providers.add(0, defaultProvider());
        return true;
    }

    private static DownloadProvider defaultProvider() {
        return new DownloadProvider(DEFAULT_NAME, DEFAULT_ELEVATION_URL, DEFAULT_MAP_DATA_URL, true);
    }

    private void save() {
        store.write(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(providers));
    }

    /** A snapshot of the providers, in the order they are tried. */
    public synchronized List<DownloadProvider> getProviders() {
        ensureLoaded();
        return new ArrayList<>(providers);
    }

    public synchronized int size() {
        ensureLoaded();
        return providers.size();
    }

    /**
     * Adds a provider to the end of the list.
     *
     * @return null when it was added, otherwise a message explaining why it was rejected.
     */
    public synchronized String addProvider(String name, String elevationBaseUrl, String mapDataBaseUrl) {
        ensureLoaded();
        String elev = normalizeUrl(elevationBaseUrl);
        String map = normalizeUrl(mapDataBaseUrl);
        String error = validate(elev, map);
        if (error != null) {
            return error;
        }
        String displayName = name == null ? "" : name.trim();
        if (displayName.isEmpty()) {
            displayName = hostOf(map);
        }
        providers.add(new DownloadProvider(displayName, elev, map));
        save();
        return null;
    }

    /**
     * Replaces the provider at the given index in place (keeping its position in the fallback
     * order), so an entry — including the default — can be renamed or have its URLs edited.
     *
     * @return null when it was updated, otherwise a message explaining why it was rejected.
     */
    public synchronized String updateProvider(int index, String name, String elevationBaseUrl, String mapDataBaseUrl) {
        ensureLoaded();
        if (index < 0 || index >= providers.size()) {
            return "No such provider.";
        }
        String elev = normalizeUrl(elevationBaseUrl);
        String map = normalizeUrl(mapDataBaseUrl);
        String error = validate(elev, map);
        if (error != null) {
            return error;
        }
        String displayName = name == null ? "" : name.trim();
        if (displayName.isEmpty()) {
            displayName = hostOf(map);
        }
        // Editing keeps the builtin flag: the default may be renamed or repointed, not demoted.
        providers.set(index, new DownloadProvider(displayName, elev, map, providers.get(index).builtin));
        save();
        return null;
    }

    /** Removes the provider at the given index. The builtin default is protected and never removed. */
    public synchronized void removeProvider(int index) {
        ensureLoaded();
        if (index < 0 || index >= providers.size() || providers.get(index).builtin) {
            return;
        }
        providers.remove(index);
        save();
    }

    private static String validate(String elevationBaseUrl, String mapDataBaseUrl) {
        if (elevationBaseUrl.isEmpty() || mapDataBaseUrl.isEmpty()) {
            return "Both the elevation and map-data URLs are required.";
        }
        if (!isHttpUrl(elevationBaseUrl) || !isHttpUrl(mapDataBaseUrl)) {
            return "URLs must start with http:// or https://";
        }
        if (HttpsPolicy.isBlockedHttp(elevationBaseUrl) || HttpsPolicy.isBlockedHttp(mapDataBaseUrl)) {
            return HttpsPolicy.HTTP_BLOCKED_MESSAGE;
        }
        return null;
    }

    private static boolean isHttpUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    /** Trims and guarantees a single trailing slash, since the tile path is appended directly. */
    private static String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty() || trimmed.endsWith("/")) {
            return trimmed;
        }
        return trimmed + "/";
    }

    /** A readable fallback label (the host) when the user gives no name. */
    static String hostOf(String url) {
        int start = url.indexOf("://");
        if (start < 0) {
            return url;
        }
        start += 3;
        int end = start;
        while (end < url.length() && url.charAt(end) != '/' && url.charAt(end) != '?') {
            end++;
        }
        String host = url.substring(start, end);
        return host.isEmpty() ? url : host;
    }

    public static List<DownloadProvider> defaultProviders() {
        return Collections.singletonList(defaultProvider());
    }
}
