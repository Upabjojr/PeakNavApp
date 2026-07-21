package com.peaknav.viewer.imgmapprovider;

import com.badlogic.gdx.Preferences;
import com.peaknav.viewer.imgmapprovider.SatelliteImageProvider.SatelliteProviderOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * The satellite sources offered in the options menu: the built-in ones plus any the user added.
 *
 * <p>Custom providers are stored as plain indexed preference keys rather than as one serialised
 * blob, so a URL, name or attribution containing separators or quotes cannot corrupt the list.
 */
public class SatelliteProviderRegistry {

    private static final String CUSTOM_COUNT = "satellite_custom_count";
    private static final String CUSTOM_URL = "satellite_custom_url_";
    private static final String CUSTOM_NAME = "satellite_custom_name_";
    private static final String CUSTOM_ATTRIBUTION = "satellite_custom_attribution_";

    /** Ids of custom providers are prefixed so they can never collide with an enum name. */
    private static final String CUSTOM_ID_PREFIX = "custom:";

    private final Preferences preferences;
    private final List<SatelliteImageProvider> customProviders = new ArrayList<>();

    public SatelliteProviderRegistry(Preferences preferences) {
        this.preferences = preferences;
        load();
    }

    private void load() {
        customProviders.clear();
        int count = preferences.getInteger(CUSTOM_COUNT, 0);
        for (int i = 0; i < count; i++) {
            String url = preferences.getString(CUSTOM_URL + i, "");
            String name = preferences.getString(CUSTOM_NAME + i, "");
            String attribution = preferences.getString(CUSTOM_ATTRIBUTION + i, "");
            if (url.isEmpty() || SatelliteUrlTemplate.validate(url) != null) {
                // Skip anything that no longer parses instead of breaking the whole options menu.
                continue;
            }
            if (name.isEmpty()) {
                name = hostOf(url);
            }
            customProviders.add(SatelliteImageProvider.custom(
                    CUSTOM_ID_PREFIX + url, url, name, attribution));
        }
    }

    private void save() {
        preferences.putInteger(CUSTOM_COUNT, customProviders.size());
        for (int i = 0; i < customProviders.size(); i++) {
            SatelliteImageProvider provider = customProviders.get(i);
            preferences.putString(CUSTOM_URL + i, provider.getUrlTemplate());
            preferences.putString(CUSTOM_NAME + i, provider.getProviderName());
            preferences.putString(CUSTOM_ATTRIBUTION + i, provider.getRawCopyrightNotice());
        }
        preferences.flush();
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
        // Indexed keys are rewritten from scratch, so drop the now unused trailing entry.
        preferences.remove(CUSTOM_URL + customProviders.size());
        preferences.remove(CUSTOM_NAME + customProviders.size());
        preferences.remove(CUSTOM_ATTRIBUTION + customProviders.size());
        save();
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
