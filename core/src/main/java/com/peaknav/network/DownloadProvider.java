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

    public DownloadProvider() {
    }

    public DownloadProvider(String name, String elevationBaseUrl, String mapDataBaseUrl) {
        this.name = name;
        this.elevationBaseUrl = elevationBaseUrl;
        this.mapDataBaseUrl = mapDataBaseUrl;
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
