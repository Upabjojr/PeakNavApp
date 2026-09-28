package com.peaknav.compatibility;

import static com.peaknav.utils.PreferencesManager.P;
import static com.peaknav.viewer.MapViewerSingleton.getAppInstance;

import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.widgets.WidgetGetter;


public class PeakNavAppState {

    private static final PeakNavAppState instance = new PeakNavAppState();
    /**
     * How many downloads are running. A flag, set by one and cleared by whichever ended first,
     * said "none" while another still ran: the app then offered to download what was already
     * downloading.
     */
    private final java.util.concurrent.atomic.AtomicInteger mapDataDownloadsRunning =
            new java.util.concurrent.atomic.AtomicInteger();
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
            // The search and download screen, if it is open, shades what is now on the device.
            com.peaknav.viewer.mapscreens.MapScreens.downloadedDataChanged();
        }
    }

    /**
     * A download has ended, one way or another: says what it came to, and "downloaded" only if
     * something was.
     *
     * @param outcome what the queue's run came to; null if it threw
     * @param asked   whether someone asked for this download just now. One taken up at the
     *                start says nothing of failing: the device is offline, as it usually is
     *                where the app is used, and what was not fetched is still in the queue.
     */
    public void mapDataDownloadEnded(com.peaknav.network.PeakNavDownloadManager.Outcome outcome,
                                     boolean asked) {
        boolean nothing = outcome == null || outcome.nothingFetched();
        if (nothing) {
            if (asked) {
                com.badlogic.gdx.Gdx.app.postRunnable(
                        () -> getAppInstance().introScreen.triggerMapDataDownloadFailed());
                com.peaknav.utils.PeakNavUtils.getNativeScreenCaller().makeToast(
                        com.peaknav.utils.PeakNavUtils.s("Download_failed"));
            }
            return;
        }
        setMapDataDownloaded(true);
        if (outcome.failed > 0 && asked) {
            com.peaknav.utils.PeakNavUtils.getNativeScreenCaller().makeToast(
                    com.peaknav.utils.PeakNavUtils.s("Download_incomplete"));
        }
    }

    /** A download began (true) or ended (false); each start is paired with one end. */
    public void setMapDataDownloadStarted(boolean mapDataDownloadStarted) {
        if (mapDataDownloadStarted) {
            mapDataDownloadsRunning.incrementAndGet();
        } else {
            // Never below none: an end with no start (the tests reset the state this way).
            int left;
            do {
                left = mapDataDownloadsRunning.get();
            } while (left > 0 && !mapDataDownloadsRunning.compareAndSet(left, left - 1));
            if (left > 1) {
                return;   // another is still running
            }
        }
        if (mapDataDownloadStarted) {
            onRenderThread(() -> getAppInstance().introScreen.triggerMapDataDownloadStarted());
        } else {
            mapDataDownloadFinishedTime = System.currentTimeMillis();
        }
    }

    /** Posted to the render thread; run at once where there is no app (the tests). */
    private static void onRenderThread(Runnable work) {
        if (com.badlogic.gdx.Gdx.app != null) {
            com.badlogic.gdx.Gdx.app.postRunnable(work);
        } else {
            work.run();
        }
    }

    public boolean isMapDataDownloadStarted() {
        return mapDataDownloadsRunning.get() > 0;
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
        tableLocation.setDownloadProgress(mapDataDownloadPercent);
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
