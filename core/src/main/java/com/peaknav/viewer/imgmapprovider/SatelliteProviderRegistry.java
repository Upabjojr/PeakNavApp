package com.peaknav.viewer.imgmapprovider;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.peaknav.config.ProviderStore;
import com.peaknav.viewer.imgmapprovider.SatelliteImageProvider.SatelliteProviderOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * The satellite sources offered in the options menu: the built-in ones plus any the user added.
 *
 * <p>The user's custom sources are persisted as JSON (see {@link com.peaknav.config.JsonConfigStore}),
 * in {@value #CONFIG_FILE} alongside the map-data providers.
 */
public class SatelliteProviderRegistry {

    public static final String CONFIG_FILE = "imagery_providers.json";

    /** Ids of custom providers are prefixed so they can never collide with an enum name. */
    private static final String CUSTOM_ID_PREFIX = "custom:";

    private final ProviderStore store;
    private final List<SatelliteImageProvider> customProviders = new ArrayList<>();

    public SatelliteProviderRegistry(ProviderStore store) {
        this.store = store;
        load();
    }

    /** The plain shape persisted per custom provider. Public no-arg + fields for Gson. */
    private static class Entry {
        String url;
        String name;
        String attribution;

        Entry() {
        }

        Entry(String url, String name, String attribution) {
            this.url = url;
            this.name = name;
            this.attribution = attribution;
        }
    }

    private void load() {
        customProviders.clear();
        String json = store.read();
        if (json == null) {
            return;
        }
        try {
            Entry[] entries = new Gson().fromJson(json, Entry[].class);
            if (entries != null) {
                for (Entry entry : entries) {
                    addFromEntry(entry);
                }
            }
        } catch (RuntimeException ignored) {
            // Corrupt file: start with no custom providers rather than breaking the menu.
        }
    }

    private void addFromEntry(Entry entry) {
        if (entry == null || entry.url == null) {
            return;
        }
        String url = entry.url;
        if (url.isEmpty() || SatelliteUrlTemplate.validate(url) != null) {
            // Skip anything that no longer parses instead of breaking the whole options menu.
            return;
        }
        String name = (entry.name == null || entry.name.isEmpty()) ? hostOf(url) : entry.name;
        String attribution = entry.attribution == null ? "" : entry.attribution;
        customProviders.add(SatelliteImageProvider.custom(CUSTOM_ID_PREFIX + url, url, name, attribution));
    }

    private void save() {
        List<Entry> entries = new ArrayList<>();
        for (SatelliteImageProvider provider : customProviders) {
            entries.add(new Entry(provider.getUrlTemplate(), provider.getProviderName(),
                    provider.getRawCopyrightNotice()));
        }
        store.write(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(entries));
    }

    /** Built-in providers first, then the user's own, in the order they were added. */
    public List<SatelliteImageProvider> getAllProviders() {
        List<SatelliteImageProvider> all = new ArrayList<>();
        for (SatelliteProviderOptions option : SatelliteProviderOptions.values()) {
            all.add(option.getSatelliteImageProvider());
        }
        all.addAll(customProviders);
        return all;
    }

    public List<SatelliteImageProvider> getCustomProviders() {
        return new ArrayList<>(customProviders);
    }

    public SatelliteImageProvider findById(String id) {
        if (id == null) {
            return null;
        }
        for (SatelliteImageProvider provider : getAllProviders()) {
            if (provider.getId().equals(id)) {
                return provider;
            }
        }
        return null;
    }

    /**
     * Adds a custom provider.
     *
     * @return null when it was added, otherwise the reason it was rejected.
     */
    public String addCustomProvider(String urlTemplate, String name, String attribution) {
        String url = urlTemplate == null ? "" : urlTemplate.trim();
        String error = SatelliteUrlTemplate.validate(url);
        if (error != null) {
            return error;
        }
        for (SatelliteImageProvider existing : customProviders) {
            if (existing.getUrlTemplate().equals(url)) {
                return "That URL template has already been added.";
            }
        }
        String displayName = name == null ? "" : name.trim();
        if (displayName.isEmpty()) {
            displayName = hostOf(url);
        }
        customProviders.add(SatelliteImageProvider.custom(
                CUSTOM_ID_PREFIX + url, url, displayName,
                attribution == null ? "" : attribution.trim()));
        save();
        return null;
    }

    public void removeCustomProvider(SatelliteImageProvider provider) {
        for (int i = 0; i < customProviders.size(); i++) {
            if (customProviders.get(i).getId().equals(provider.getId())) {
                customProviders.remove(i);
                break;
            }
        }
        save();
    }

    /**
     * One-time import of custom providers that an older version stored in preferences. The
     * supplied entries (url, name, attribution triples) are added only when the JSON file has
     * none yet, so it never clobbers newer data.
     *
     * @return true if anything was imported (the caller may then clear the old preference keys)
     */
    public boolean importIfEmpty(List<String[]> legacyEntries) {
        if (!customProviders.isEmpty() || legacyEntries == null || legacyEntries.isEmpty()) {
            return false;
        }
        boolean imported = false;
        for (String[] entry : legacyEntries) {
            if (entry == null || entry.length < 1) {
                continue;
            }
            addFromEntry(new Entry(entry[0],
                    entry.length > 1 ? entry[1] : null,
                    entry.length > 2 ? entry[2] : null));
            imported = true;
        }
        if (imported) {
            save();
        }
        return imported;
    }

    /** Falls back to a readable label when the user does not supply a name. */
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
}
