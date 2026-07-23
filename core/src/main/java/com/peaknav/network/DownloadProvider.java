package com.peaknav.network;

/**
 * A source the app can fetch terrain and OpenStreetMap tiles from. It is just two base
 * URLs — anything that mirrors the PeakNav tile layout under them works, so the app is
 * no longer tied to HuggingFace. Serialised to JSON, hence the plain public fields and
 * the no-arg constructor Gson needs.
 */
public class DownloadProvider {

    public String name;
    /** Base URL the elevation ({@code .tar.gz}) tiles hang off; must end with '/'. */
    public String elevationBaseUrl;
    /** Base URL the OpenStreetMap ({@code .tar}) tiles hang off; must end with '/'. */
    public String mapDataBaseUrl;
    /**
     * The default HuggingFace source. It can be renamed or have its URLs edited, but not removed,
     * so the app always keeps a working fallback. Additional providers the user adds are not builtin.
     */
    public boolean builtin;

    public DownloadProvider() {
    }

    public DownloadProvider(String name, String elevationBaseUrl, String mapDataBaseUrl) {
        this(name, elevationBaseUrl, mapDataBaseUrl, false);
    }

    public DownloadProvider(String name, String elevationBaseUrl, String mapDataBaseUrl, boolean builtin) {
        this.name = name;
        this.elevationBaseUrl = elevationBaseUrl;
        this.mapDataBaseUrl = mapDataBaseUrl;
        this.builtin = builtin;
    }

    /** The base URL for the given tile kind, or null when this provider has none. */
    public String baseUrlFor(boolean elevation) {
        String base = elevation ? elevationBaseUrl : mapDataBaseUrl;
        return (base == null || base.isEmpty()) ? null : base;
    }

    public boolean isUsable() {
        return elevationBaseUrl != null && !elevationBaseUrl.isEmpty()
                && mapDataBaseUrl != null && !mapDataBaseUrl.isEmpty();
    }
}
