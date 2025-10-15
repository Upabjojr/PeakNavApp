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
