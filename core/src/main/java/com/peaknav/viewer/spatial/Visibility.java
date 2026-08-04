package com.peaknav.viewer.spatial;

import static com.peaknav.utils.Units.convertLatitsToMeters;

import com.badlogic.gdx.math.Vector3;

import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.render_tiles.ImpactPixmap;
import com.peaknav.viewer.controller.MapController;

import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Visibility {

    private final MapController C;
    private final Vector3 cameraPosLatits = new Vector3();

    public Visibility(MapController mapController) {
        this.C = mapController;
    }

    public void updateCameraPosLatits() {
        // The camera may not exist yet, or may be gone again. This runs on the visibility
        // worker, which is started from data arriving rather than from the screen being
        // ready, so it can reach here before the first frame has built a camera - and it
        // did, killing the app on a device:
        // NullPointerException ... Camera.position ... Visibility.updateCameraPosLatits.
        // Keeping the last known position is right: it is what every other frame used, and
        // the pass that follows is redone as soon as the camera moves.
        if (MapViewerSingleton.getViewerInstance() == null
                || MapViewerSingleton.getViewerInstance().cam == null) {
            return;
        }
        ReentrantReadWriteLock rwl = MapViewerSingleton.getViewerInstance().moveCameraAction.camQueueLock;
        rwl.readLock().lock();
        try {
            cameraPosLatits.set(MapViewerSingleton.getViewerInstance().cam.position);
        } finally {
            rwl.readLock().unlock();
        }
    }

    private final Vector3 tempVisibility = new Vector3();

    public boolean checkVisible(Vector3 destination, ImpactPixmap impactPixmap) {
        tempVisibility.set(destination);
        float distanceLatits = tempVisibility.sub(cameraPosLatits).len();
        float distancePseudometers = convertLatitsToMeters(distanceLatits);
        return impactPixmap.checkIfDistanceIsVisible(distancePseudometers, destination);
    }

}
