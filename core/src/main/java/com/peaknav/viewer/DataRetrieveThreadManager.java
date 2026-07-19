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
        execRetrieveData.stopLoop();
        executorLoadGraph.stopLoop();
        lastLatLong = C.L.getCurrentLatLong();
        execRetrieveData.executeStoppableRunnable(new RunnableRetrievePOIs(C));

        // TODO: restore this line to re-enable navigation:
        // executorLoadGraph.executeStoppableRunnable(new RunnableLoadGraph(C));
    }

    public void triggerUpdateVisibilityByZooming() {
        updateRequests.add(MapDataUpdateRequest.DATA_VISIBILITY_RECOMPUTE_LABEL_OVERLAP);
        execUpdateVisibilityFull.executeStoppableRunnable(new RunnableUpdateVisibility(C, updateRequests));
    }

    public void triggerUpdateVisibilityPositionChanged() {
        updateRequests.add(MapDataUpdateRequest.DATA_SORT_POI_LIST_BY_RELEVANCE);
        execUpdateVisibilityFull.executeStoppableRunnable(new RunnableUpdateVisibility(C, updateRequests));
    }

    public void triggerUpdateVisibilityElevationChanged() {
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
        execUpdateVisibilityFull.stopLoopByType(RunnableUpdateVisibility.class);
    }

    public LatLong getLastLatLong() {
        return lastLatLong;
    }
}
