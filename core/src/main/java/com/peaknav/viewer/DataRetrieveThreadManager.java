package com.peaknav.viewer;

import org.mapsforge.core.model.LatLong;

import com.badlogic.gdx.math.Vector3;
import com.peaknav.utils.PeakNavThreadExecutor;
import com.peaknav.viewer.controller.MapController;
import com.peaknav.viewer.pois.RunnableRetrievePOIs;
import com.peaknav.viewer.pois.RunnableUpdateVisibility;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class DataRetrieveThreadManager {

    private final PeakNavThreadExecutor execRetrieveData = new PeakNavThreadExecutor(1, "dataRetrieveExec");
    private final PeakNavThreadExecutor execUpdateVisibilityFull = new PeakNavThreadExecutor(1, "exUpVsblty1");
    private final PeakNavThreadExecutor execUpdateVisibilityLight = new PeakNavThreadExecutor(1, "exUpVsblty2");
    private final PeakNavThreadExecutor executorLoadGraph = new PeakNavThreadExecutor(1, "executorLoadGraph");

    public enum MapDataUpdateRequest {
        DATA_SORT_POI_LIST_BY_RELEVANCE,
        DATA_VISIBILITY_RECOMPUTE_HIDDEN_BY_MOUNTAINS,
        DATA_VISIBILITY_RECOMPUTE_FRONT_TO_CAMERA,
        DATA_VISIBILITY_RECOMPUTE_LABEL_OVERLAP,
    }

    public final Set<MapDataUpdateRequest> updateRequests = Collections.synchronizedSet(new HashSet<>());

    private LatLong lastLatLong = null;
    private MapController C;
    // private final double DISTANCE_RETRIGGER = 1000; // meters

    public DataRetrieveThreadManager(MapController mapController) {
        C = mapController;
    }

    public void triggerReadData() {
        if (labelUpdatesHeld) {
            // Held means the label SET is frozen between explicit refreshes - and the
            // POI lists are where the set comes from. Re-retrieving on every camera
            // step also meant each retrieve was stopped by the next before finishing.
            // forceLabelUpdateNow() retrieves on the refresh cadence instead.
            return;
        }
        execRetrieveData.stopLoop();
        executorLoadGraph.stopLoop();
        lastLatLong = C.L.getCurrentLatLong();
        execRetrieveData.executeStoppableRunnable(new RunnableRetrievePOIs(C));

        // TODO: restore this line to re-enable navigation:
        // executorLoadGraph.executeStoppableRunnable(new RunnableLoadGraph(C));
    }

    /**
     * While true, none of the trigger methods below schedules a visibility update. For
     * interactive use the triggers are right: a person turns the camera, the labels
     * re-sort themselves, nothing looks amiss. A video is different - the camera moves
     * EVERY frame, so the 5-degree rotation threshold fires every few frames and the
     * labels reshuffle several times a second, which on playback reads as flicker. The
     * headless renderer holds updates and calls {@link #forceLabelUpdateNow()} on a
     * cadence chosen by the caller - twice a second of video, typically.
     */
    private volatile boolean labelUpdatesHeld = false;

    public void setLabelUpdatesHeld(boolean held) {
        labelUpdatesHeld = held;
    }

    public boolean isLabelUpdatesHeld() {
        return labelUpdatesHeld;
    }

    /** Numbers the passes {@link #forceLabelUpdateNow()} requests, so a caller can wait for its own. */
    private final java.util.concurrent.atomic.AtomicLong labelPassSequence =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * The full label pass - overlap, mountain occlusion, relevance sort - hold or no hold.
     * Returns the pass's sequence number: once
     * {@code ResourceStats.labelVisibilityCompletedSequence} reaches it, THIS pass has run to
     * completion. Passes queue behind one another, so an earlier completion is not this one.
     */
    public long forceLabelUpdateNow() {
        // Deliberately NO POI retrieve here. The lazy retrieve calls back once per
        // tile - twenty-odd callbacks each swapping the master lists and running
        // missing-data checks - and firing one per refresh overlapped the storms
        // until the view never went quiet again (every frame then sat out its full
        // tile-wait timeout: a 200x slowdown, measured). Under the frozen-anchor
        // scheme the current position holds still between boots anyway, so the
        // boot's own retrieve already covers everything a chunk can see.
        Vector3 camDir = C.getMapViewerScreen().cam.direction;
        prevCameraAngle = Math.atan2(camDir.y, camDir.x);
        updateRequests.add(MapDataUpdateRequest.DATA_VISIBILITY_RECOMPUTE_LABEL_OVERLAP);
        updateRequests.add(MapDataUpdateRequest.DATA_VISIBILITY_RECOMPUTE_HIDDEN_BY_MOUNTAINS);
        updateRequests.add(MapDataUpdateRequest.DATA_SORT_POI_LIST_BY_RELEVANCE);
        // The area labels re-decide their winners once this pass COMPLETES (they watch
        // ResourceStats.labelVisibilityCompleted), so that the decision is taken against
        // the depth map this pass renders rather than the previous one's.
        long sequence = labelPassSequence.incrementAndGet();
        execUpdateVisibilityFull.executeStoppableRunnable(
                new RunnableUpdateVisibility(C, updateRequests, sequence));
        return sequence;
    }

    public void triggerUpdateVisibilityByZooming() {
        if (labelUpdatesHeld) {
            return;
        }
        updateRequests.add(MapDataUpdateRequest.DATA_VISIBILITY_RECOMPUTE_LABEL_OVERLAP);
        execUpdateVisibilityFull.executeStoppableRunnable(new RunnableUpdateVisibility(C, updateRequests));
    }

    public void triggerUpdateVisibilityPositionChanged() {
        if (labelUpdatesHeld) {
            return;
        }
        updateRequests.add(MapDataUpdateRequest.DATA_SORT_POI_LIST_BY_RELEVANCE);
        // This fires on every frame the camera position changes (a pan or flight holds that true
        // for seconds at a time, up to 120 frames a second), so it needs the same backlog cap as
        // the rotation trigger below.
        if (execUpdateVisibilityFull.getQueue().size() >= MAX_PENDING_VISIBILITY_UPDATES) {
            // Already queued work will pick up the request added above.
            return;
        }
        execUpdateVisibilityFull.executeStoppableRunnable(new RunnableUpdateVisibility(C, updateRequests));
    }

    public void triggerUpdateVisibilityElevationChanged() {
        if (labelUpdatesHeld) {
            return;
        }
        updateRequests.add(MapDataUpdateRequest.DATA_VISIBILITY_RECOMPUTE_HIDDEN_BY_MOUNTAINS);
        execUpdateVisibilityFull.executeStoppableRunnable(new RunnableUpdateVisibility(C, updateRequests));
    }

    private double prevCameraAngle = 0;

    /**
     * How far the camera has to turn before the label overlap pass is recomputed. This used to be
     * 45 degrees, because the overlap pass compared every label against every other one and was
     * far too slow to run often. Labels kept being re-projected while turning but were only
     * de-overlapped every 45 degrees, so overlaps stayed on screen in between. The pass is now
     * indexed by screen cells (see LabelOverlapIndex), so it can afford to run much more often.
     */
    private static final double CAMERA_ROTATION_UPDATE_LIMIT = Math.toRadians(5);

    /**
     * Upper bound on queued visibility updates. Extra runnables are near no-ops (the first one to
     * run drains the shared request set), but each still costs a throttling sleep, so a fast spin
     * of the device should not be able to build an unbounded backlog. Keeping one queued runnable
     * beyond the running one guarantees the request below is always picked up.
     */
    private static final int MAX_PENDING_VISIBILITY_UPDATES = 2;

    public void triggerUpdateVisibilityCameraRotated() {
        if (labelUpdatesHeld) {
            return;
        }
        Vector3 camDir = C.getMapViewerScreen().cam.direction;

        double angle = Math.atan2(camDir.y, camDir.x);

        double angleDiff = Math.abs(Math.IEEEremainder(angle - prevCameraAngle, 2*Math.PI));

        if (angleDiff < CAMERA_ROTATION_UPDATE_LIMIT)  {
            return;
        }

        prevCameraAngle = angle;

        updateRequests.add(MapDataUpdateRequest.DATA_VISIBILITY_RECOMPUTE_LABEL_OVERLAP);
        if (execUpdateVisibilityFull.getQueue().size() >= MAX_PENDING_VISIBILITY_UPDATES) {
            // Already queued work will pick up the request added above.
            return;
        }
        execUpdateVisibilityFull.executeStoppableRunnable(new RunnableUpdateVisibility(C, updateRequests));
    }

    /**
     * Recomputes the label-overlap pass unconditionally (no rotation threshold). Called once the
     * camera has settled after rotating, so the final orientation — reached with a turn smaller
     * than {@link #CAMERA_ROTATION_UPDATE_LIMIT}, which would otherwise be skipped — gets its
     * labels de-overlapped. Resets the rotation baseline so the next turn is measured from here.
     */
    public void triggerUpdateVisibilityLabelOverlap() {
        if (labelUpdatesHeld) {
            return;
        }
        Vector3 camDir = C.getMapViewerScreen().cam.direction;
        prevCameraAngle = Math.atan2(camDir.y, camDir.x);

        updateRequests.add(MapDataUpdateRequest.DATA_VISIBILITY_RECOMPUTE_LABEL_OVERLAP);
        if (execUpdateVisibilityFull.getQueue().size() >= MAX_PENDING_VISIBILITY_UPDATES) {
            // Already queued work will pick up the request added above.
            return;
        }
        execUpdateVisibilityFull.executeStoppableRunnable(new RunnableUpdateVisibility(C, updateRequests));
    }

    public void stopRunnableUpdateVisibility() {
        if (labelUpdatesHeld) {
            // Held mode runs one pass per explicit refresh and captures against its
            // result; a tile update killing that pass mid-publish left a torn label
            // set standing for the whole next window. The races this stop guards
            // against are label-vs-tile churn during interaction - while held, the
            // pass is rare and must finish.
            return;
        }
        execUpdateVisibilityFull.stopLoopByType(RunnableUpdateVisibility.class);
    }

    public LatLong getLastLatLong() {
        return lastLatLong;
    }
}
