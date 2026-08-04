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

    // ------------------------------------------------------------------
    // Render-completeness signals.
    //
    // "The view has finished filling in" is not one event in this app: tiles,
    // satellite imagery and labels all arrive on their own threads. These fields
    // are written at the points where that work actually happens, so a caller
    // (the headless renderer above all, but equally a test or a loading
    // indicator) can wait on facts instead of inferring readiness from
    // timestamps going quiet.
    // ------------------------------------------------------------------

    /** Satellite tile fetches currently in flight; see TileRendererRunnerSatellite. */
    private final java.util.concurrent.atomic.AtomicInteger pendingSatelliteWork =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Labels drawn in the most recent frame; see LabelRenderer.render. */
    private volatile int visibleLabelCount;

    public void satelliteWorkStarted() {
        pendingSatelliteWork.incrementAndGet();
    }

    public void satelliteWorkFinished() {
        pendingSatelliteWork.decrementAndGet();
        setLastAnyMapTileUpdateTimeToNow();
    }

    /** 0 means no satellite tile is being fetched or drawn right now. */
    public int getPendingSatelliteWork() {
        return pendingSatelliteWork.get();
    }

    public void setVisibleLabelCount(int count) {
        visibleLabelCount = count;
    }

    /** How many labels the last frame actually drew (0 while they are still being prepared). */
    public int getVisibleLabelCount() {
        return visibleLabelCount;
    }

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

    /**
     * Waits for a lull in tile updates before heavy work - BOUNDED. This used to wait
     * for ever, and "for ever" is exactly what it did: the waiting worker's own tiles
     * never finished, so the welding queue kept re-marking updates, which kept the
     * worker waiting - a self-sustaining park that stalled whole rendering runs (and
     * could do the same interactively). Politeness is worth five seconds; after that
     * the work proceeds regardless, because a paced pipeline that never runs paces
     * nothing.
     */
    public void waitForLastAnyMapTileUpdateTime(long deltaTime) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            long current = System.currentTimeMillis();
            if (current - getLastAnyMapTileUpdateTime() > deltaTime) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
