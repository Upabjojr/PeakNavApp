package com.peaknav.compatibility;

import static com.peaknav.utils.PreferencesManager.P;
import static com.peaknav.viewer.MapViewerSingleton.getAppInstance;

import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.widgets.WidgetGetter;


public class PeakNavAppState {

    private static final PeakNavAppState instance = new PeakNavAppState();
    private volatile boolean mapDataDownloadStarted;
    private float mapDataDownloadProgressRatio = 0f;
    private boolean loadingMapData;
    private long lastAnyMapTileUpdateTime = System.currentTimeMillis();

    private PeakNavAppState() {}

    public static PeakNavAppState getAppState() {
        return instance;
    }


    private boolean mapDataDownloaded;

    public boolean isMapDataDownloaded() {
        return mapDataDownloaded;
    }

    public void setMapDataDownloaded(boolean mapDataDownloaded) {
        this.mapDataDownloaded = mapDataDownloaded;
        if (mapDataDownloaded) {
            getAppInstance().introScreen.triggerMapDataDownloaded();
        }
    }

    public void setMapDataDownloadStarted(boolean mapDataDownloadStarted) {
        this.mapDataDownloadStarted = mapDataDownloadStarted;
        if (mapDataDownloadStarted) {
            getAppInstance().introScreen.triggerMapDataDownloadStarted();
        } else {
            mapDataDownloadFinishedTime = System.currentTimeMillis();
        }
    }

    public boolean isMapDataDownloadStarted() {
        return mapDataDownloadStarted;
    }

    private volatile long mapDataDownloadFinishedTime = 0L;

    /**
     * Whether a map data download finished within the given time window. Freshly downloaded tiles
     * take a moment to be written out and picked up, so for a short while afterwards the data can
     * still look missing. Without this, the app asks to download data it has only just fetched.
     */
    public boolean isMapDataDownloadRecentlyFinished(long withinMillis) {
        return mapDataDownloadFinishedTime != 0L
                && System.currentTimeMillis() - mapDataDownloadFinishedTime < withinMillis;
    }

    public void setMapDataDownloadProgressRatio(float mapDataDownloadPercent) {
        this.mapDataDownloadProgressRatio = mapDataDownloadPercent;
        WidgetGetter.TableLocation tableLocation = MapViewerSingleton.getViewerInstance().tableLocation;
        tableLocation.progressBar.setValue(mapDataDownloadPercent);
        tableLocation.progressBarTable.setVisible(!(mapDataDownloadPercent > 0.999f));
    }

    public float getMapDataDownloadProgressRatio() {
        return mapDataDownloadProgressRatio;
    }

    public boolean isLoadingMapData() {
        return loadingMapData;
    }

    public void setLoadingMapData(boolean loadingMapData) {
        this.loadingMapData = loadingMapData;
    }

    public long getLastAnyMapTileUpdateTime() {
        return lastAnyMapTileUpdateTime;
    }

    public void setLastAnyMapTileUpdateTime(long lastAnyMapTileUpdateTime) {
        this.lastAnyMapTileUpdateTime = lastAnyMapTileUpdateTime;
    }

    public void setLastAnyMapTileUpdateTimeToNow() {
        setLastAnyMapTileUpdateTime(System.currentTimeMillis());
    }

    public void waitForLastAnyMapTileUpdateTime(long deltaTime) {
        while (true) {
            long current = System.currentTimeMillis();
            if (current - getLastAnyMapTileUpdateTime() > deltaTime) {
                break;
            }
            try {
                Thread.sleep(550);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
