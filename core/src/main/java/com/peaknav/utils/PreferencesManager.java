package com.peaknav.utils;

import static com.peaknav.utils.Constants.PREFERENCES.COLLECT_ANONYMOUS_STATS;
// import static com.peaknav.utils.Constants.PREFERENCES.COLLECT_ANONYMOUS_STATS_PROMPTED;
import static com.peaknav.utils.Constants.PREFERENCES.COLLECT_ANONYMOUS_STATS_QUERIED;
import static com.peaknav.utils.Constants.PREFERENCES.COLLECT_DOWNLOAD_INFO;
import static com.peaknav.utils.Constants.PREFERENCES.COORDINATES_FIRST_TIME;
import static com.peaknav.utils.Constants.PREFERENCES.FIRST_TIME_APP_RUN;
import static com.peaknav.utils.Constants.PREFERENCES.LAST_CAMERA_DIRECTION_X;
import static com.peaknav.utils.Constants.PREFERENCES.LAST_CAMERA_DIRECTION_Y;
import static com.peaknav.utils.Constants.PREFERENCES.LAST_CAMERA_DIRECTION_Z;
import static com.peaknav.utils.Constants.PREFERENCES.LAST_CAMERA_UP_X;
import static com.peaknav.utils.Constants.PREFERENCES.LAST_CAMERA_UP_Y;
import static com.peaknav.utils.Constants.PREFERENCES.LAST_CAMERA_UP_Z;
import static com.peaknav.utils.Constants.PREFERENCES.LAST_LATITUDE;
import static com.peaknav.utils.Constants.PREFERENCES.LAST_LONGITUDE;
import static com.peaknav.utils.Constants.PREFERENCES.LOCATION_PERMISSION_DENIED;
import static com.peaknav.utils.Constants.PREFERENCES.PREF_NAME;
import static com.peaknav.utils.Constants.PREFERENCES.UNDERLAY_IMAGE_PROVIDER;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_LARGE_FONTS;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_LAYER_VISIBLE_BASE_ROADS;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_LAYER_VISIBLE_UNDERLAY_LAYER;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_SUN_SHADING;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_HORIZON_COMPASS;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_COMPASS_LOCATION;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_SHOW_COORDINATES;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_CORNER_COMPASS;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_SKY;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_SKY_CONSTELLATIONS;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_SKY_ECLIPTIC;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_SKY_GRID;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_SKY_LABELS;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_SKY_TIME_LABEL;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_SKY_STAR_NAMES;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_SKY_MODE;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_SHOW_ALPINE_HUTS;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_SHOW_ISLANDS;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_SHOW_CITIES;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_SHOW_MOUNTAIN_RANGES;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_SHOW_LAKES;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_SHOW_PEAKS;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_SHOW_PISTES;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_SHOW_PLACE_NAMES;
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_UNIT_SYSTEM;
import static com.peaknav.viewer.render_tiles.PixmapLayerName.BASE_ROADS;
import static com.peaknav.viewer.render_tiles.PixmapLayerName.SKI_SLOPES;
import static com.peaknav.viewer.render_tiles.PixmapLayerName.UNDERLAY_LAYER;
import static com.peaknav.viewer.imgmapprovider.SatelliteImageProvider.SatelliteProviderOptions;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.Vector3;
import com.peaknav.config.JsonConfigStore;
import com.peaknav.viewer.imgmapprovider.SatelliteImageProvider;
import com.peaknav.viewer.imgmapprovider.SatelliteProviderRegistry;
import com.peaknav.viewer.render_tiles.PixmapLayerName;

import java.util.Map;
import java.util.TreeMap;

public class PreferencesManager {

    public static PreferencesManager P;

    private final Preferences preferences;

    private boolean pisteVisible;
    private boolean peakVisible;
    private boolean visiblePlaceNames;
    private boolean visibleAlpineHuts;
    private boolean visibleIslands;
    private boolean visibleCities;
    private boolean visibleMountainRanges;
    private boolean visibleLakes;
    private boolean largeFonts;
    private boolean layerVisibleUnderlayLayer;
    private boolean viewerLayerVisibleBaseRoads;
    private boolean layerVisibleNavigation;
    private Map<PixmapLayerName, Long> lastChange = new TreeMap<>();
    private boolean layerVisibleOpenStreetMap;
    private boolean sunShading;
    private boolean horizonCompass;
    private boolean compassLocation;
    private boolean showCoordinates;
    private boolean cornerCompass;
    private boolean skyView;
    private boolean skyConstellations;
    private boolean skyGrid;
    private boolean skyEcliptic;
    private boolean skyStarNames;
    private boolean skyLabels;
    private boolean skyTimeLabel;
    /** 0 = follow local time, 1 = force day, 2 = force night. */
    private int skyMode;
    private SatelliteImageProvider underlayImageProvider;
    private SatelliteProviderRegistry satelliteProviderRegistry;
    private boolean locationPermissionDenied;
    private boolean collectDownloadInfo;
    private boolean firstTimeAppRun;
    // private boolean collectAnonymousStatsPrompted;

    public boolean isCollectDownloadInfo() {
        return collectDownloadInfo;
    }

    public boolean isLocationPermissionDenied() {
        return locationPermissionDenied;
    }

    public void setLocationPermissionDenied(boolean locationPermissionDenied) {
        this.locationPermissionDenied = locationPermissionDenied;
        preferences.putBoolean(LOCATION_PERMISSION_DENIED, locationPermissionDenied);
        preferences.flush();
    }

    public void setCollectDownloadInfo(boolean collectDownloadInfo) {
        this.collectDownloadInfo = collectDownloadInfo;
        preferences.putBoolean(COLLECT_DOWNLOAD_INFO, collectDownloadInfo);
        preferences.flush();
    }

    public boolean isFirstTimeAppRun() {
        return firstTimeAppRun;
    }

    public void setFirstTimeAppRun(boolean firstTimeAppRun) {
        this.firstTimeAppRun = firstTimeAppRun;
        preferences.putBoolean(FIRST_TIME_APP_RUN, firstTimeAppRun);
        preferences.flush();
    }

    /*
    public boolean isCollectAnonymousStatsPrompted() {
        return collectAnonymousStatsPrompted;
    }

    public void setCollectAnonymousStatsPrompted(boolean collectAnonymousStatsPrompted) {
        preferences.putBoolean(COLLECT_ANONYMOUS_STATS_PROMPTED, collectAnonymousStatsPrompted);
        this.collectAnonymousStatsPrompted = collectAnonymousStatsPrompted;
    }
     */

    public enum UnitSystem {
        METRIC,
        IMPERIAL
    };

    public UnitSystem getUnitSystem() {
        return unitSystem;
    }

    public void setUnitSystemNoPersist(UnitSystem unitSystem) {
        this.unitSystem = unitSystem;
    }

    public void setUnitSystem(UnitSystem unitSystem) {
        this.unitSystem = unitSystem;
        this.preferences.putString(VIEWER_UNIT_SYSTEM, unitSystem.name());
        this.preferences.flush();
    }

    private UnitSystem unitSystem;

    /**
     * Whether the next manager keeps its changes in memory instead of writing them out. Set by
     * the headless renderer before the app starts; the interactive launchers leave it alone, so
     * the app itself is unaffected.
     */
    private static boolean ephemeral = false;

    /**
     * Makes preferences read-through and write-nowhere from here on: settings are still read
     * from the stored file, and every change is kept in memory and dropped when the process
     * exits. Call before the app is created - the manager reads the flag once, in its
     * constructor. Only settings are affected; downloaded map data is untouched and stays
     * shared with the interactive app.
     */
    public static void setEphemeral(boolean value) {
        ephemeral = value;
    }

    /** Whether preferences changes are being kept in memory only. */
    public static boolean isEphemeral() {
        return ephemeral;
    }

    public PreferencesManager() {
        Preferences stored = Gdx.app.getPreferences(PREF_NAME);
        preferences = ephemeral ? new EphemeralPreferences(stored) : stored;
        updatePreferences();
    }

    public void updatePreferences() {

        firstTimeAppRun = preferences.getBoolean(FIRST_TIME_APP_RUN, true);

        peakVisible = preferences.getBoolean(VIEWER_SHOW_PEAKS, true);
        visiblePlaceNames = preferences.getBoolean(VIEWER_SHOW_PLACE_NAMES, true);
        visibleAlpineHuts = preferences.getBoolean(VIEWER_SHOW_ALPINE_HUTS, true);
        visibleIslands = preferences.getBoolean(VIEWER_SHOW_ISLANDS, true);
        visibleCities = preferences.getBoolean(VIEWER_SHOW_CITIES, true);
        visibleMountainRanges = preferences.getBoolean(VIEWER_SHOW_MOUNTAIN_RANGES, true);
        visibleLakes = preferences.getBoolean(VIEWER_SHOW_LAKES, true);
        pisteVisible = preferences.getBoolean(VIEWER_SHOW_PISTES, true);
        layerVisibleUnderlayLayer = preferences.getBoolean(VIEWER_LAYER_VISIBLE_UNDERLAY_LAYER, true);
        sunShading = preferences.getBoolean(VIEWER_SUN_SHADING, true);
        horizonCompass = preferences.getBoolean(VIEWER_HORIZON_COMPASS, true);
        // All three compass-and-location items default to on for a fresh install.
        compassLocation = preferences.getBoolean(VIEWER_COMPASS_LOCATION, true);
        showCoordinates = preferences.getBoolean(VIEWER_SHOW_COORDINATES, true);
        cornerCompass = preferences.getBoolean(VIEWER_CORNER_COMPASS, true);
        skyView = preferences.getBoolean(VIEWER_SKY, true);
        skyConstellations = preferences.getBoolean(VIEWER_SKY_CONSTELLATIONS, true);
        // Reference overlays, off unless asked for.
        skyGrid = preferences.getBoolean(VIEWER_SKY_GRID, false);
        skyEcliptic = preferences.getBoolean(VIEWER_SKY_ECLIPTIC, false);
        // On, as the app has always drawn them; a scripted render can turn them off.
        skyStarNames = preferences.getBoolean(VIEWER_SKY_STAR_NAMES, true);
        skyLabels = preferences.getBoolean(VIEWER_SKY_LABELS, true);
        skyTimeLabel = preferences.getBoolean(VIEWER_SKY_TIME_LABEL, true);
        skyMode = preferences.getInteger(VIEWER_SKY_MODE, 0);
        // Set to "true" for subscribed users:
        viewerLayerVisibleBaseRoads = preferences.getBoolean(VIEWER_LAYER_VISIBLE_BASE_ROADS, true);
        largeFonts = preferences.getBoolean(VIEWER_LARGE_FONTS, false);
        // layerVisibleNavigation = preferences.getBoolean(VIEWER_LAYER_VISIBLE_NAVIGATION, false);

        collectDownloadInfo = preferences.getBoolean(COLLECT_DOWNLOAD_INFO, true);
        // collectAnonymousStatsPrompted = preferences.getBoolean(COLLECT_ANONYMOUS_STATS_PROMPTED, false);
        locationPermissionDenied = preferences.getBoolean(LOCATION_PERMISSION_DENIED, false);

        satelliteProviderRegistry = new SatelliteProviderRegistry(
                new JsonConfigStore(SatelliteProviderRegistry.CONFIG_FILE));
        migrateCustomSatelliteProvidersFromPreferences();
        String satPrefName = preferences.getString(
                UNDERLAY_IMAGE_PROVIDER, "");
        // The stored value is a provider id: a built-in enum name, or a custom provider's id.
        underlayImageProvider = satelliteProviderRegistry.findById(satPrefName);
        if (underlayImageProvider == null) {
            underlayImageProvider = SatelliteProviderOptions.LANDSAT.getSatelliteImageProvider();
        }

        String prefUnitSystem = preferences.getString(VIEWER_UNIT_SYSTEM, UnitSystem.METRIC.name());
        try {
            unitSystem = UnitSystem.valueOf(prefUnitSystem);
        } catch (IllegalArgumentException iae) {
            unitSystem = UnitSystem.METRIC;
            P.setUnitSystem(unitSystem);
        }
    }

    public String getPropertyNameFromPixmapLayerName(PixmapLayerName pixmapLayerName) {
        switch (pixmapLayerName) {
            case SKI_SLOPES:
                return VIEWER_SHOW_PISTES;
            case BASE_ROADS:
                return VIEWER_LAYER_VISIBLE_BASE_ROADS;
            case UNDERLAY_LAYER:
                return VIEWER_LAYER_VISIBLE_UNDERLAY_LAYER;
            default:
                return null;
        }
    }

    public boolean isPixmapLayerNameVisible(PixmapLayerName pixmapLayerName) {
        switch (pixmapLayerName) {
            case SKI_SLOPES:
                return getPisteVisible();
            case BASE_ROADS:
                return isViewerLayerVisibleBaseRoads();
            case UNDERLAY_LAYER:
                return isLayerVisibleUnderlayLayer();
            case NAVIGATION_LAYER:
                return getLayerVisibleNavigation();
            default:
                return false;
        }
    }

    public boolean getLayerVisibleNavigation() {
        return layerVisibleNavigation;
    }

    public void setLayerVisibleNavigation(boolean visible) {
        layerVisibleNavigation = visible;
        // preferences.putBoolean(VIEWER_LAYER_VISIBLE_NAVIGATION, visible);
        // preferences.flush();
    }

    public boolean getPisteVisible() {
        return pisteVisible;
    }

    public void setPisteVisible(boolean visible) {
        pisteVisible = visible;
        preferences.putBoolean(VIEWER_SHOW_PISTES, visible);
        lastChange.put(SKI_SLOPES, System.currentTimeMillis());
        preferences.flush();
    }

    public boolean isPeakVisible() {
        return peakVisible;
    }

    public void setPeakVisible(boolean visible) {
        peakVisible = visible;
        preferences.putBoolean(VIEWER_SHOW_PEAKS, visible);
        preferences.flush();
    }

    public boolean isVisiblePlaceNames() { return visiblePlaceNames; }

    public void setVisiblePlaceNames(boolean visible) {
        visiblePlaceNames = visible;
        preferences.putBoolean(VIEWER_SHOW_PLACE_NAMES, visible);
        preferences.flush();
    }

    public void setVisibleAlpineHuts(boolean visible) {
        this.visibleAlpineHuts = visible;
        preferences.putBoolean(VIEWER_SHOW_ALPINE_HUTS, visible);
        preferences.flush();
    }

    public boolean isVisibleAlpineHuts() {
        return visibleAlpineHuts;
    }

    public boolean isVisibleIslands() { return visibleIslands; }

    public void setVisibleIslands(boolean visible) {
        visibleIslands = visible;
        preferences.putBoolean(VIEWER_SHOW_ISLANDS, visible);
        preferences.flush();
    }

    public boolean isVisibleCities() { return visibleCities; }

    public void setVisibleCities(boolean visible) {
        visibleCities = visible;
        preferences.putBoolean(VIEWER_SHOW_CITIES, visible);
        preferences.flush();
    }

    public boolean isVisibleMountainRanges() { return visibleMountainRanges; }

    public void setVisibleMountainRanges(boolean visible) {
        visibleMountainRanges = visible;
        preferences.putBoolean(VIEWER_SHOW_MOUNTAIN_RANGES, visible);
        preferences.flush();
    }

    public boolean isVisibleLakes() { return visibleLakes; }

    public void setVisibleLakes(boolean visible) {
        visibleLakes = visible;
        preferences.putBoolean(VIEWER_SHOW_LAKES, visible);
        preferences.flush();
    }

    public boolean getViewLargeFonts() {
        return largeFonts;
    }

    public void setViewLargeFonts(boolean visible) {
        largeFonts = visible;
        preferences.putBoolean(VIEWER_LARGE_FONTS, visible);
        preferences.flush();
    }

    public boolean isLayerVisibleUnderlayLayer() {
        return layerVisibleUnderlayLayer;
    }

    public void setLayerVisibleUnderlayLayer(boolean visible) {
        layerVisibleUnderlayLayer = visible;
        preferences.putBoolean(VIEWER_LAYER_VISIBLE_UNDERLAY_LAYER, visible);
        lastChange.put(UNDERLAY_LAYER, System.currentTimeMillis());
        preferences.flush();
    }

    public SatelliteImageProvider getUnderlayImageProvider() {
        return underlayImageProvider;
    }

    public SatelliteProviderRegistry getSatelliteProviderRegistry() {
        return satelliteProviderRegistry;
    }

    /**
     * Older builds stored custom satellite sources as indexed preference keys. Move them into the
     * JSON registry once (only when it is still empty), then drop the old keys so we do not import
     * twice or leave stale data behind.
     */
    private void migrateCustomSatelliteProvidersFromPreferences() {
        // This one writes to the provider config file as well as to the preferences, so an
        // ephemeral session skips it: it must leave no trace outside its own memory.
        if (ephemeral) {
            return;
        }
        int count = preferences.getInteger("satellite_custom_count", 0);
        if (count <= 0) {
            return;
        }
        java.util.List<String[]> legacy = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            legacy.add(new String[]{
                    preferences.getString("satellite_custom_url_" + i, ""),
                    preferences.getString("satellite_custom_name_" + i, ""),
                    preferences.getString("satellite_custom_attribution_" + i, "")});
        }
        satelliteProviderRegistry.importIfEmpty(legacy);

        preferences.remove("satellite_custom_count");
        for (int i = 0; i < count; i++) {
            preferences.remove("satellite_custom_url_" + i);
            preferences.remove("satellite_custom_name_" + i);
            preferences.remove("satellite_custom_attribution_" + i);
        }
        preferences.flush();
    }

    public void setUnderlayImageProvider(SatelliteProviderOptions uip) {
        setUnderlayImageProvider(uip.getSatelliteImageProvider());
    }

    public void setUnderlayImageProvider(SatelliteImageProvider uip) {
        underlayImageProvider = uip;
        preferences.putString(UNDERLAY_IMAGE_PROVIDER, uip.getId());
        lastChange.put(UNDERLAY_LAYER, System.currentTimeMillis());
        preferences.flush();
    }

    /**
     * Falls back to a built-in source when the selected custom provider is removed, so the
     * satellite layer can never be left pointing at a provider that no longer exists.
     */
    public void onCustomProviderRemoved(SatelliteImageProvider removed) {
        if (underlayImageProvider != null
                && underlayImageProvider.getId().equals(removed.getId())) {
            setUnderlayImageProvider(SatelliteProviderOptions.LANDSAT);
        }
    }

    /** Whether the terrain is lit by the sun; when off it gets flat, non directional light. */
    public boolean isSunShading() {
        return sunShading;
    }

    /** Whether the cardinal-direction markers (N, NE, E, …) are drawn on the sky horizon. */
    public boolean isHorizonCompass() {
        return horizonCompass;
    }

    public void setHorizonCompass(boolean enabled) {
        horizonCompass = enabled;
        preferences.putBoolean(VIEWER_HORIZON_COMPASS, enabled);
        preferences.flush();
    }

    /**
     * Master toggle over the compass-and-location group, mirroring how {@link #isSkyView()}
     * gates all sky drawing: nothing in the group (horizon markers, corner compass,
     * on-screen coordinates) is drawn while this is off, whatever the per-item toggles say.
     */
    public boolean isCompassLocation() {
        return compassLocation;
    }

    public void setCompassLocation(boolean enabled) {
        compassLocation = enabled;
        preferences.putBoolean(VIEWER_COMPASS_LOCATION, enabled);
        preferences.flush();
    }

    /** Whether the current coordinates are written on screen. */
    public boolean isShowCoordinates() {
        return showCoordinates;
    }

    public void setShowCoordinates(boolean enabled) {
        showCoordinates = enabled;
        preferences.putBoolean(VIEWER_SHOW_COORDINATES, enabled);
        preferences.flush();
    }

    /** Whether the compass rose in the top-right corner is drawn. */
    public boolean isCornerCompass() {
        return cornerCompass;
    }

    public void setCornerCompass(boolean enabled) {
        cornerCompass = enabled;
        preferences.putBoolean(VIEWER_CORNER_COMPASS, enabled);
        preferences.flush();
    }

    public boolean isSkyView() {
        return skyView;
    }

    public void setSkyView(boolean enabled) {
        skyView = enabled;
        preferences.putBoolean(VIEWER_SKY, enabled);
        preferences.flush();
    }

    public boolean isSkyConstellations() {
        return skyConstellations;
    }

    public void setSkyConstellations(boolean enabled) {
        skyConstellations = enabled;
        preferences.putBoolean(VIEWER_SKY_CONSTELLATIONS, enabled);
        preferences.flush();
    }

    /** The equatorial grid over the sky: meridians of right ascension and parallels of
     *  declination. Off by default - it is a reference overlay, not scenery. */
    public boolean isSkyGrid() {
        return skyGrid;
    }

    public void setSkyGrid(boolean enabled) {
        skyGrid = enabled;
        preferences.putBoolean(VIEWER_SKY_GRID, enabled);
        preferences.flush();
    }

    /**
     * Master switch over every caption drawn on the sky: the constellation names, the bright
     * star names, and the names beside the Sun, the Moon and the planets. Off means a sky with
     * nothing written across it, whatever the individual settings say.
     *
     * <p>It gates rather than replaces the finer settings, so switching it back on restores
     * exactly the labels that were showing before. A constellation name still needs
     * {@link #isSkyConstellations()} as well - the names belong to the lines, and captioning
     * figures whose lines are not drawn would label empty sky.
     */
    public boolean isSkyLabels() {
        return skyLabels;
    }

    public void setSkyLabels(boolean enabled) {
        skyLabels = enabled;
        preferences.putBoolean(VIEWER_SKY_LABELS, enabled);
        preferences.flush();
    }

    /**
     * The date-and-time pill shown while the sky is frozen at a chosen instant. On in the app,
     * where it is the only sign that the sky is not the live one - switching it off is for
     * renders, which want the picture and not the read-out.
     */
    public boolean isSkyTimeLabel() {
        return skyTimeLabel;
    }

    public void setSkyTimeLabel(boolean enabled) {
        skyTimeLabel = enabled;
        preferences.putBoolean(VIEWER_SKY_TIME_LABEL, enabled);
        preferences.flush();
    }

    /** Names of the brightest stars, drawn beside them. On, as in the app. */
    public boolean isSkyStarNames() {
        return skyStarNames;
    }

    public void setSkyStarNames(boolean enabled) {
        skyStarNames = enabled;
        preferences.putBoolean(VIEWER_SKY_STAR_NAMES, enabled);
        preferences.flush();
    }

    /** The ecliptic: the lane the Sun, Moon and planets travel along. Off by default. */
    public boolean isSkyEcliptic() {
        return skyEcliptic;
    }

    public void setSkyEcliptic(boolean enabled) {
        skyEcliptic = enabled;
        preferences.putBoolean(VIEWER_SKY_ECLIPTIC, enabled);
        preferences.flush();
    }

    /** 0 = follow local time, 1 = force day, 2 = force night. */
    public int getSkyMode() {
        return skyMode;
    }

    public void setSkyMode(int mode) {
        skyMode = ((mode % 3) + 3) % 3;
        preferences.putInteger(VIEWER_SKY_MODE, skyMode);
        preferences.flush();
    }

    public void setSunShading(boolean enabled) {
        sunShading = enabled;
        preferences.putBoolean(VIEWER_SUN_SHADING, enabled);
        preferences.flush();
    }

    public boolean isViewerLayerVisibleBaseRoads() {
        return viewerLayerVisibleBaseRoads;
    }

    public void setViewerLayerVisibleBaseRoads(boolean visible) {
        viewerLayerVisibleBaseRoads = visible;
        preferences.putBoolean(VIEWER_LAYER_VISIBLE_BASE_ROADS, visible);
        lastChange.put(BASE_ROADS, System.currentTimeMillis());
        preferences.flush();
    }

    public long getLastChangeTimestamp(PixmapLayerName key) {
        if (lastChange.containsKey(key))
            return lastChange.get(key);
        else
            return 0;
    }

    public float getLastLatitude() {
        return preferences.getFloat(LAST_LATITUDE, 0);
    }

    public void setLastLatitude(double val) {
        preferences.putFloat(LAST_LATITUDE, (float)val);
        preferences.flush();
    }

    public float getLastLongitude() {
        return preferences.getFloat(LAST_LONGITUDE, 0);
    }

    public void setLastLongitude(double val) {
        preferences.putFloat(LAST_LONGITUDE, (float)val);
        preferences.flush();
    }

    public boolean getCoordinatesFirstTime() {
        return preferences.getBoolean(COORDINATES_FIRST_TIME, true);
    }

    public void setCoordinatesFirstTime(boolean val) {
        preferences.putBoolean(COORDINATES_FIRST_TIME, val);
        preferences.flush();
    }

    public Vector3 getLastCameraDirection() {
        return new Vector3(
                preferences.getFloat(LAST_CAMERA_DIRECTION_X, 1f),
                preferences.getFloat(LAST_CAMERA_DIRECTION_Y, 0f),
                preferences.getFloat(LAST_CAMERA_DIRECTION_Z, 0f)
        );
    }

    public void setLastCameraDirection(Vector3 direction) {
        preferences.putFloat(LAST_CAMERA_DIRECTION_X, direction.x);
        preferences.putFloat(LAST_CAMERA_DIRECTION_Y, direction.y);
        preferences.putFloat(LAST_CAMERA_DIRECTION_Z, direction.z);
    }

    public Vector3 getLastCameraUp() {
        return new Vector3(
                preferences.getFloat(LAST_CAMERA_UP_X, 0f),
                preferences.getFloat(LAST_CAMERA_UP_Y, 0f),
                preferences.getFloat(LAST_CAMERA_UP_Z, 1f)
        );
    }

    public void setLastCameraUp(Vector3 up) {
        preferences.putFloat(LAST_CAMERA_UP_X, up.x);
        preferences.putFloat(LAST_CAMERA_UP_Y, up.y);
        preferences.putFloat(LAST_CAMERA_UP_Z, up.z);
    }

    public void setLastCameraOrientation(Camera cam) {
        setLastCameraDirection(cam.direction);
        setLastCameraUp(cam.up);
        preferences.flush();
        try {
            // DO NOT FLUSH TOO MUCH!
            Thread.sleep(150);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public Vector3 getLastCameraDirectionFlat() {
        Vector3 lastCameraDir = getLastCameraDirection();
        lastCameraDir.z = 0;
        lastCameraDir.nor();
        return lastCameraDir;
    }

    public SatelliteImageProvider getUnderlayImageProviderObject() {
        return underlayImageProvider;
    }

}
