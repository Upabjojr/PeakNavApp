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
import static com.peaknav.utils.Constants.PREFERENCES.VIEWER_SHOW_ALPINE_HUTS;
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
import com.peaknav.viewer.imgmapprovider.SatelliteImageProvider;
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
    private boolean largeFonts;
    private boolean layerVisibleUnderlayLayer;
    private boolean viewerLayerVisibleBaseRoads;
    private boolean layerVisibleNavigation;
    private Map<PixmapLayerName, Long> lastChange = new TreeMap<>();
    private boolean layerVisibleOpenStreetMap;
    private SatelliteProviderOptions underlayImageProvider;
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

    public PreferencesManager() {
        preferences = Gdx.app.getPreferences(PREF_NAME);
        updatePreferences();
    }

    public void updatePreferences() {

        firstTimeAppRun = preferences.getBoolean(FIRST_TIME_APP_RUN, true);

        peakVisible = preferences.getBoolean(VIEWER_SHOW_PEAKS, true);
        visiblePlaceNames = preferences.getBoolean(VIEWER_SHOW_PLACE_NAMES, true);
        visibleAlpineHuts = preferences.getBoolean(VIEWER_SHOW_ALPINE_HUTS, true);
        pisteVisible = preferences.getBoolean(VIEWER_SHOW_PISTES, true);
        layerVisibleUnderlayLayer = preferences.getBoolean(VIEWER_LAYER_VISIBLE_UNDERLAY_LAYER, true);
        // Set to "true" for subscribed users:
        viewerLayerVisibleBaseRoads = preferences.getBoolean(VIEWER_LAYER_VISIBLE_BASE_ROADS, true);
        largeFonts = preferences.getBoolean(VIEWER_LARGE_FONTS, false);
        // layerVisibleNavigation = preferences.getBoolean(VIEWER_LAYER_VISIBLE_NAVIGATION, false);

        collectDownloadInfo = preferences.getBoolean(COLLECT_DOWNLOAD_INFO, true);
        // collectAnonymousStatsPrompted = preferences.getBoolean(COLLECT_ANONYMOUS_STATS_PROMPTED, false);
        locationPermissionDenied = preferences.getBoolean(LOCATION_PERMISSION_DENIED, false);

        String satPrefName = preferences.getString(
                UNDERLAY_IMAGE_PROVIDER, "");
        try {
            underlayImageProvider = SatelliteProviderOptions.valueOf(
                    satPrefName);
        } catch (IllegalArgumentException iae) {
            underlayImageProvider = SatelliteProviderOptions.LANDSAT;
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

    public SatelliteProviderOptions getUnderlayImageProvider() {
        return underlayImageProvider;
    }

    public void setUnderlayImageProvider(SatelliteProviderOptions uip) {
        underlayImageProvider = uip;
        preferences.putString(UNDERLAY_IMAGE_PROVIDER, uip.toString());
        lastChange.put(UNDERLAY_LAYER, System.currentTimeMillis());
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
        return underlayImageProvider.getSatelliteImageProvider();
    }

}
