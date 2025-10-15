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

    public void triggerUpdateVisibilityCameraRotated() {
        Vector3 camDir = C.getMapViewerScreen().cam.direction;
        double angleLimit = Math.PI / 4;  // Math.toRadians(C.getMapViewerScreen().cam.fieldOfView / 5);

        double angle = Math.atan2(camDir.y, camDir.x);

        double angleDiff = Math.abs(Math.IEEEremainder(angle - prevCameraAngle, 2*Math.PI));

        if (angleDiff < angleLimit)  {
            return;
        }

        prevCameraAngle = angle;

        updateRequests.add(MapDataUpdateRequest.DATA_VISIBILITY_RECOMPUTE_LABEL_OVERLAP);
        execUpdateVisibilityFull.executeStoppableRunnable(new RunnableUpdateVisibility(C, updateRequests));
    }

    public void stopRunnableUpdateVisibility() {
        execUpdateVisibilityFull.stopLoopByType(RunnableUpdateVisibility.class);
    }

    public LatLong getLastLatLong() {
        return lastLatLong;
    }
}
