package com.peaknav.viewer.render_tiles;

import static com.peaknav.utils.PeakNavUtils.getC;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.peaknav.utils.Units;
import com.peaknav.viewer.PerspectiveCameraExt;

import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ImpactPixmap {

    public volatile boolean impactPixmapNewRequested = false;
    public Pixmap pixmapNorth = null;
    public Pixmap pixmapEast = null;
    public Pixmap pixmapSouth = null;
    public Pixmap pixmapWest = null;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final PerspectiveCameraExt cam;

    public ImpactPixmap(PerspectiveCameraExt cam) {
        this.cam = cam;
    }

    public void setPixmapGeographical(
            Pixmap pixmapNorth,
            Pixmap pixmapEast,
            Pixmap pixmapSouth,
            Pixmap pixmapWest
    ) {
        lock.writeLock().lock();

        if (this.pixmapNorth != null) {
            this.pixmapNorth.dispose();
        }
        if (this.pixmapEast != null) {
            this.pixmapEast.dispose();
        }
        if (this.pixmapSouth != null) {
            this.pixmapSouth.dispose();
        }
        if (this.pixmapWest != null) {
            this.pixmapWest.dispose();
        }

        this.pixmapNorth = pixmapNorth;
        this.pixmapEast = pixmapEast;
        this.pixmapSouth = pixmapSouth;
        this.pixmapWest = pixmapWest;

        lock.writeLock().unlock();
    }

    /**
     * Per-thread scratch state. This class is queried concurrently by the render thread (area
     * labels, every frame) and the visibility worker (POI sweep) under a shared READ lock, so
     * shared mutable temporaries would let one thread sample the depth pixmaps at the other
     * thread's coordinates (labels randomly flickering hidden/visible).
     */
    private static final class Scratch {
        final Vector3 unproj = new Vector3();
        final DistanceRange dr = new DistanceRange(0, 1);
    }

    // Anonymous subclass rather than ThreadLocal.withInitial: that is an API 26+ method on
    // Android and this project's minSdk is 21.
    private final ThreadLocal<Scratch> scratch = new ThreadLocal<Scratch>() {
        @Override
        protected Scratch initialValue() {
            return new Scratch();
        }
    };

    private int getPseudometerPixelDistance(int x, int y, boolean flipY) {
        Vector3 tempUnproj = scratch.get().unproj;
        tempUnproj.set(x, y, 0.99999f);
        this.cam.unproject(tempUnproj);
        return getPseudometerCoordinateDistance(tempUnproj, flipY, 0);
    }

    private int getPseudometerCoordinateDistance(Vector3 coordPos, boolean flipY, int deltaY) {
        Vector3 tempUnproj = scratch.get().unproj;
        tempUnproj.set(coordPos);

        Vector3 camPos = this.cam.position;

        float dx = coordPos.x - camPos.x;
        float dy = coordPos.y - camPos.y;

        PerspectiveCamera geoCam = this.cam.getGeographicCameraForPoint(tempUnproj.x, tempUnproj.y);

        geoCam.project(tempUnproj);

        Pixmap pixmap;

        if (dy > dx) {
            if (dy > -dx) {
                pixmap = pixmapNorth;
            } else {
                pixmap = pixmapWest;
            }
        } else {
            if (dy > -dx) {
                pixmap = pixmapEast;
            } else {
                pixmap = pixmapSouth;
            }
        }

        int screenX = Math.round(tempUnproj.x);
        int screenY = Math.round(tempUnproj.y) + deltaY;

        if (flipY) {
            screenY = pixmap.getHeight() - 1 - screenY;
        }
        int pixel = pixmap.getPixel(screenX, screenY); // int pixel = pixmap.getPixel(pixmap.getWidth() - 1 - x, pixmap.getHeight() - 1 - y);
        return getPseudometersDistanceFromColor(pixel);
    }

    private int getPseudometersDistanceFromColor(int pixel) {
        int r = pixel >>> 24;
        int g = (pixel & 0xFF0000) >>> 16;
        int b = (pixel & 0xFF00) >>> 8;
        if (g % 2 == 1) {
            r = 255 - r;
        }
        if (b % 2 == 1) {
            g = 255 - g;
        }
        // The bytes are base 256, not base 255 (see fragment_shader_pseudodistances.glsl).
        return r + 256*g + 65536*b;
    }

    public static class DistanceRange {
        public int min, max;
        private final static int distMargin = 1000;
        private final static float marginMinPerc = 0.9f;
        private final static float marginMaxPerc = 1.1f;

        public DistanceRange(int min, int max) {
            this.min = min;
            this.max = max;
        }

        public void addMargin() {
            min = Integer.max(Math.round(marginMinPerc*min), min - distMargin);
            max = Integer.min(Math.round(marginMaxPerc*max), max + distMargin);
        }
    }

    public DistanceRange getPseudoDistanceRangeForDirection(Vector3 position) {
        int distMax = Integer.MIN_VALUE;
        int distMin = Integer.MAX_VALUE;
        // TODO: only return first distance found... useless to have such long loop:
        for (int i = 4; (i > -4 || distMax < 0) && (i > -22); i -= 1) {
            int dist = getPseudometerCoordinateDistance(position, false, i);
            if (dist > 1e6 || dist < 5)
                continue;
            if (dist < distMin)
                distMin = dist;
            if (dist > distMax)
                distMax = dist;
        }
        DistanceRange dr = scratch.get().dr;
        dr.min = distMin;
        dr.max = distMax;
        return dr;
    }

    /** True once all four geographical depth pixmaps have been rendered at least once. */
    public boolean isReady() {
        lock.readLock().lock();
        try {
            return pixmapNorth != null && pixmapEast != null
                    && pixmapSouth != null && pixmapWest != null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean checkIfDistanceIsVisible(float dist, Vector3 destination) {
        lock.readLock().lock();
        try {
            if (pixmapNorth == null || pixmapEast == null || pixmapSouth == null || pixmapWest == null)
                return true; // no depth information yet — don't hide anything
            DistanceRange dr = getPseudoDistanceRangeForDirection(destination);
            // An empty range (no usable depth sample in the column) deliberately falls through
            // and reports "hidden": min stays Integer.MAX_VALUE and max Integer.MIN_VALUE, so the
            // comparison below is false.
            //
            // It must NOT be treated as "silhouetted against open sky, therefore visible". No
            // depth sample means the depth pass drew no terrain at that screen position — which
            // for a far-away feature normally means its own terrain is not loaded or rendered, not
            // that the line of sight is clear. Answering "visible" there let distant labels
            // (Ötztaler Urkund seen from Lake Como, ~200 km away) float on top of the mountains
            // that actually occlude them. With no information, staying hidden is the safe answer.
            dr.addMargin();
            return dr.min < dist && dist < dr.max;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Vector3 findPointOfImpactForScreenCoords(int screenX, int screenY) {
        lock.readLock().lock();
        try {
            if (pixmapNorth == null || pixmapEast == null || pixmapSouth == null || pixmapWest == null)
                return null;
            int distanceMeters = getPseudometerPixelDistance(screenX, screenY, false);
            float distanceLatits = Units.convertMetersToLatits(distanceMeters);
            Ray pickRay = cam.getPickRay(screenX, screenY);
            Vector3 dest = new Vector3();
            pickRay.getEndPoint(dest, distanceLatits);
            return dest;
        } finally {
            lock.readLock().unlock();
        }
    }

    /*
    public Camera getCamera() {
        return cam;
    }
     */

    /** How long a worker will wait for the render thread to service a depth-pixmap request. */
    private static final long REQUEST_TIMEOUT_MILLIS = 4000;

    public void requestUpdatedImpactPixmap() {
        impactPixmapNewRequested = true;
        // Wait (bounded) for the render thread to clear the flag. When rendering is paused or a
        // different screen is up, no frame will ever service the request — without the deadline
        // this spun at 40 Hz forever. On timeout the caller just works with the previous depth
        // data, which is the best available anyway.
        long deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MILLIS;
        while (impactPixmapNewRequested && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                // Preserve the stop request instead of blowing up the worker with a
                // RuntimeException (which defeated the StoppableRunnable interruption path).
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Frees the four native depth pixmaps. Safe to call more than once. */
    public void dispose() {
        setPixmapGeographical(null, null, null, null);
    }

}
