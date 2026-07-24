package com.peaknav.viewer.controller;

import static com.peaknav.compatibility.PeakNavAppState.getAppState;
import static com.peaknav.database.CheckMissingData.checkMissingElevationForCoord;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PreferencesManager.P;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.util.LatLongUtils;

import com.badlogic.gdx.math.Vector3;
import com.peaknav.viewer.MapViewerSingleton;

import java.util.LinkedList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class CurrentLocation {

    // public final Vector3 targetPosition = new Vector3();

    private volatile boolean currentLocationNotSet;

    private volatile float currentLatitude;
    private volatile float currentLongitude;
    private Double targetAfterUpdateLat = null;
    private Double targetAfterUpdateLon = null;

    private enum LocationState {
        NEVER_SET,
        TARGETING,
        WAITING_FOR_ELEVATION,
        ELEVATION_SET
    }
    private volatile LocationState currentTerrainEleFired = LocationState.NEVER_SET;

    public void setCurrentTerrainEle(float currentTerrainEle) {
        // if (currentTerrainEleFired != LocationState.WAITING_FOR_ELEVATION)
        //     return;
        currentTerrainEleFired = LocationState.ELEVATION_SET;
        this.currentTerrainEle = currentTerrainEle;
        getC().getMapViewerScreen().setCurrentCoordLocation(
                currentLongitude,
                currentLatitude,
                currentTerrainEle
        );
    }

    private volatile float currentTerrainEle;

    /**
     * Sets the ground-elevation reference (used by the elevation bar and the height readout)
     * without the side effects of {@link #setCurrentTerrainEle} — no re-entrant location callback,
     * no camera move. Used when the camera is deliberately decoupled from the target, e.g. while
     * surveying a GPX track from high above it, so the bar's range and readout track where the
     * camera actually is.
     */
    public void setCurrentTerrainEleQuiet(float ele) {
        this.currentTerrainEle = ele;
    }

    private float targetLatitude;
    private float targetLongitude;

    public final Executor executorSavePreferences = Executors.newSingleThreadExecutor();
    private volatile boolean firstRun = true;
    private boolean targetSetFromGPS = false;
    // private Tile highwaysTileCenter;
    // private final int highwaysTileRange = 8;

    public CurrentLocation() {
        currentLocationNotSet = true;
    }

    public void setCurrentTargetCoords(double lat, double lon) {
        setCurrentTargetCoords(lat, lon, true);
    }

    public void setCurrentTargetCoordsFromGPS(double lat, double lon) {
        setCurrentTargetCoords(lat, lon, true, true);
    }

    public void setCurrentTargetCoords(double lat, double lon, boolean checkMissing) {
        setCurrentTargetCoords(lat, lon, checkMissing, false);
    }

    public void setCurrentTargetCoords(double lat, double lon, boolean checkMissing, boolean fromGps) {
        currentTerrainEleFired = LocationState.TARGETING;

        setTargetSetFromGPS(fromGps);

        targetLatitude = (float) lat;
        targetLongitude = (float) lon;

        if (checkMissing && shouldAskToDownloadMissingData(lat, lon)) {
            // Remember that this area has been asked about before showing the dialog, so a moving
            // GPS fix (which re-targets on every update) cannot raise it again and again.
            getC().checkMissingData.dismiss(lat, lon);
            getNativeScreenCaller().askForDownloadScreen(lat, lon);
        }
        getC().elevationImageProviderManager.setProviderForTargetCoords(targetLatitude, targetLongitude);

        getC().tileManager.updateMapTiles();

        currentTerrainEleFired = LocationState.WAITING_FOR_ELEVATION;
    }

    /**
     * How long after a download finishes the "data is missing" dialog stays suppressed. Tiles are
     * still being written and re-read for a moment after the download ends, so without this the
     * app asks to download data it has just fetched.
     */
    private static final long DOWNLOAD_SETTLE_MILLIS = 30_000L;

    /**
     * Whether to raise the modal "download missing data?" dialog. This is deliberately much more
     * reluctant than the in-app banner (see MapViewerScreen/TableDownloadData), which stays
     * visible whenever data is missing and is the non-intrusive way to offer the download.
     */
    private boolean shouldAskToDownloadMissingData(double lat, double lon) {
        if (getNativeScreenCaller() == null) {
            // iOS does not provide one.
            return false;
        }
        if (getAppState().isMapDataDownloadStarted()
                || getAppState().isMapDataDownloadRecentlyFinished(DOWNLOAD_SETTLE_MILLIS)) {
            // A download is already running or has just covered this; asking now is the
            // "it keeps asking even though I am downloading" case.
            return false;
        }
        return getC().checkMissingData.checkMissingElevationIfNotDismissed(lat, lon);
    }

    public void setCurrentFinalCoords(double lat, double lon, double elevation) {
        currentLatitude = (float) lat;
        currentLongitude = (float) lon;
        currentTerrainEle = (float) elevation;

        currentLocationNotSet = false;

        saveCoordinatesToPreferences(currentLatitude, currentLongitude);

        getC().dataRetrieveThreadManager.stopRunnableUpdateVisibility();
        LatLong lastLatLong = getC().dataRetrieveThreadManager.getLastLatLong();
        if (lastLatLong == null || LatLongUtils.distance(
                lastLatLong, new LatLong(lat, lon)) > 0.05) {
            getC().O.applyToAllListsOfPOIs((listOfPeaks, listOfNonPeaks) -> {
                listOfPeaks.clear();
                listOfNonPeaks.clear();
            });
            getC().redactAll();
        } else {
            getC().dataRetrieveThreadManager.triggerUpdateVisibilityPositionChanged();
        }
        getC().O.setDisplayablePoiList(null);
        getC().O.setVisiblePoiList(null);

        if (firstRun) {
            synchronized (this) {
                if (firstRun) {
                    MapViewerSingleton.getViewerInstance().controller.target = new Vector3((float) getC().L.getCurrentLongitude(), (float) getC().L.getCurrentLatitude(), 0.05f);
                    firstRun = false;
                }
            }
        }

        // TODO: MapViewerScreen callback
        getC().getMapViewerScreen().setCurrentCoordLocation(
                lon, lat, elevation
        );

        getC().mapDataManager.getMultiMapDataStore().resetCache();

    }

    public void saveCoordinatesToPreferences(double lat, double lon) {
        executorSavePreferences.execute(() -> {
            P.setLastLatitude(lat);
            P.setLastLongitude(lon);
            P.setCoordinatesFirstTime(false);
        });
    }

    /*
    public void setCurrentTerrainEle(double ele) {
        currentTerrainEle = (float)ele;
        getC().mapViewerScreen.cam.position.z = currentTerrainEle;
        getC().mapViewerScreen.cam.update();
    }
     */

    public double getCurrentLatitude() {
        if (currentLocationNotSet) {
            return targetLatitude;
        }
        return currentLatitude;
    }

    public double getCurrentLongitude() {
        if (currentLocationNotSet) {
            return targetLongitude;
        }
        return currentLongitude;
    }

    public double getCurrentTerrainEle() {
        return currentTerrainEle;
    }

    public LatLong getCurrentLatLong() {
        return new LatLong(currentLatitude, currentLongitude);
    }

    public void loadCoordsFromLastPreferences() {
        // don't load coordinates if app never used before:
        if (!P.getCoordinatesFirstTime()) {
            double lat = P.getLastLatitude();
            double lon = P.getLastLongitude();
            setCurrentTargetCoords(lat, lon);
        }
    }

    public float getTargetLatitude() {
        return targetLatitude;
    }

    public float getTargetLongitude() {
        return targetLongitude;
    }

    public boolean isCurrentLocationNotSet() {
        return currentLocationNotSet;
    }

    /*public void computeMapHighwaysDataBoundingBox() {
        byte zoomHighways = 14;
        TileId tileId = Units.getTileNumber(targetLatitude, targetLongitude, zoomHighways);
        highwaysTileCenter = new Tile(tileId.x, tileId.y, zoomHighways, 1);
    }*/

    /*public Tile getHighwaysTileCenter() {
        return highwaysTileCenter;
    }

    public int getHighwaysTileRange() {
        return highwaysTileRange;
    }*/

    public boolean isTargetSetFromGPS() {
        return targetSetFromGPS;
    }

    private void setTargetSetFromGPS(boolean targetSetFromGPS) {
        this.targetSetFromGPS = targetSetFromGPS;
    }

    public LatLong getTargetLatLong() {
        return new LatLong(getTargetLatitude(), getTargetLongitude());
    }

    public void setCurrentTargetCoordsAfterTileUpdates(double lat, double lon) {
        this.targetAfterUpdateLat = lat;
        this.targetAfterUpdateLon = lon;
    }

    public void checkTargetCoordsAfterTileUpdates() {
        if (targetAfterUpdateLat != null && targetAfterUpdateLon != null) {
            setCurrentTargetCoords(targetAfterUpdateLat, targetAfterUpdateLon);
        }
        targetAfterUpdateLon = null;
        targetAfterUpdateLat = null;
    }

}
