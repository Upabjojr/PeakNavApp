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

    // Anonymous subclass rather than ThreadLocal.withInitial, as in ImpactPixmap: one scratch
    // vector was shared by the visibility worker and the render thread (the markers), which
    // ask at the same time - one's point could be measured from the other's.
    private final ThreadLocal<Vector3> origin = new ThreadLocal<Vector3>() {
        @Override
        protected Vector3 initialValue() {
            return new Vector3();
        }
    };

    /**
     * Whether a point can be seen, by the depth maps: its distance against what the maps hold
     * in its direction. The distance is measured from where the maps were taken, which is the
     * only place they describe the terrain from - the camera's last noted position when there
     * are no maps yet, and nothing is hidden. Measured from the noted position alone, a pass
     * whose note was older than its maps (one begun before a move to a distant place) found
     * every label of the new place "hidden".
     */
    public boolean checkVisible(Vector3 destination, ImpactPixmap impactPixmap) {
        Vector3 from = origin.get();
        if (!impactPixmap.renderedCameraPosition(from)) {
            from.set(cameraPosLatits);
        }
        float distancePseudometers = convertLatitsToMeters(destination.dst(from));
        return impactPixmap.checkIfDistanceIsVisible(distancePseudometers, destination);
    }

}
