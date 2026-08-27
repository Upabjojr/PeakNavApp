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
        if (pixmapNorth != null && cam != null) {
            renderedCameraPosition.set(cam.position);
            renderedCameraKnown = true;
        }

        lock.writeLock().unlock();
    }

    private final Vector3 renderedCameraPosition = new Vector3();
    private boolean renderedCameraKnown = false;

    /**
     * Where the camera stood when the current depth maps were rendered, or false if none
     * have been. The maps describe the terrain from THAT point; a reader comparing them
     * against a camera that has since moved far is reading the wrong picture.
     */
    public boolean renderedCameraPosition(Vector3 out) {
        lock.readLock().lock();
        try {
            if (!renderedCameraKnown)
                return false;
            out.set(renderedCameraPosition);
            return true;
        } finally {
            lock.readLock().unlock();
        }
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

    /**
     * How far along the clicked ray to take the point that selects the depth-map pixel. Any
     * distance does: the geographic cameras sit exactly where the main camera does (see
     * PerspectiveCameraExt.updateGeographicCameras), so every point on the ray projects to the
     * same pixel and only the direction matters. ~111 km is well clear of the near plane
     * without approaching the far one, where float precision thins out.
     */
    private static final float RAY_SAMPLE_LATITS = 1f;

    /**
     * No depth sample here - the point falls outside the depth map altogether. Negative so
     * it can never be mistaken for a distance; the callers that scan for a usable range
     * already ignore anything below 5, and the picker treats it as "nothing was hit".
     */
    private static final int NO_READING = -1;

    private int getPseudometerPixelDistance(int x, int y, boolean flipY) {
        Vector3 tempUnproj = scratch.get().unproj;
        // A point along the clicked ray, built analytically.
        //
        // This used to be cam.unproject(x, y, 0.99999f). Inverting this camera's matrix at a
        // near/far ratio of 150000:1 is imprecise - the same reason the ray below is not
        // cam.getPickRay - and the error tilted the sampled direction slightly upwards. The
        // ray that placed the hit was already exact, so the two disagreed: the distance came
        // from a pixel above the one that was clicked. On a distant summit that pixel is the
        // sky behind it, so the mountain had to be clicked below its top to select it.
        Ray ray = cam.getPickRayStable(x, y);
        tempUnproj.set(ray.direction).scl(RAY_SAMPLE_LATITS).add(ray.origin);
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
        // Outside the depth map there is no reading, and saying so matters. The four
        // geographical cameras look along the horizon and reach only about 28 degrees
        // above and below it (their vertical field of view; see PerspectiveCameraExt), so
        // anything steeply below the viewer projects off the bottom of the map. libGDX
        // answers an out-of-bounds getPixel with 0 rather than complaining (measured), and
        // 0 decodes to a distance of ZERO - which put the impact point exactly at the
        // camera, where it projects to nowhere and no pin is ever shown. An unknown
        // distance must not masquerade as a very near one.
        if (screenX < 0 || screenY < 0
                || screenX >= pixmap.getWidth() || screenY >= pixmap.getHeight()) {
            return NO_READING;
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
            min = nearMargin(min);
            max = Math.min(Math.round(marginMaxPerc*max), max + distMargin);
        }

        /** The near end of the range after the margin, for a single reading. */
        static int nearMargin(int min) {
            return Math.max(Math.round(marginMinPerc*min), min - distMargin);
        }
    }

    public DistanceRange getPseudoDistanceRangeForDirection(Vector3 position) {
        return getPseudoDistanceRangeForDirection(position, Float.NaN);
    }

    /**
     * How far above the rendered terrain a point may stand and still be taken as sitting
     * on it. A summit's coordinates come from the map data and its rendered top from the
     * elevation grid, and on a sharp peak the grid's highest cell falls well short of the
     * true summit: the Matterhorn's 4478 m point stood some 60 m above its own rendered
     * top, seen from the 12 km orbit. Metres of height at the point's distance, converted
     * to depth-map rows below. Generous, because the shortfall grows with the coarseness
     * of the elevation grid drawn at the point's distance: from the 12 km orbit the
     * Matterhorn's rendered top sat 250 m under its summit.
     */
    private static final float SELF_TOLERANCE_METERS = 500f;
    private static final int MAX_SELF_ROWS = 120;

    /**
     * The depth readings around a point's screen position, as a [nearest, farthest] range.
     *
     * <p>The window is four rows above to four below; with no reading in it (sky), it
     * extends down to 22 rows looking for any terrain at all. And when {@code pointDist}
     * is given and every reading in the window is FARTHER than the point - nothing nearer
     * occludes it, it stands above the rendered terrain against a backdrop - the window
     * extends down again, for the point's own body: a summit whose map elevation exceeds
     * the elevation grid's top projects into the mountains behind it, and at a 1600x900
     * depth map's 13 m per row (12 km away) the four rows below did not reach its rendered
     * top, so the range held only the backdrop and the peak was reported hidden - in the
     * middle of a video orbiting it. Scanning down until the range reaches the point's
     * distance (or {@link #SELF_TOLERANCE_METERS} below it) finds that body. It cannot
     * turn a hidden point visible: readings further down a column are nearer or equal,
     * so they can only lower the near end of the range, and a range whose FAR end is
     * already nearer than the point stays that way.
     */
    public DistanceRange getPseudoDistanceRangeForDirection(Vector3 position, float pointDist) {
        int distMax = Integer.MIN_VALUE;
        int distMin = Integer.MAX_VALUE;
        // How far down to look for the point's own terrain, in the sky case as in the
        // backdrop case: the same height tolerance, so a skyline summit close by, whose
        // rows are bigger in metres, is given the same benefit as a far one.
        int rows = Float.isNaN(pointDist) ? 22
                : Math.max(22, rowsBelowForSelfTolerance(pointDist));
        // TODO: only return first distance found... useless to have such long loop:
        for (int i = 4; (i > -4 || distMax < 0) && (i > -rows); i -= 1) {
            int dist = getPseudometerCoordinateDistance(position, false, i);
            if (dist > 1e6 || dist < 5)
                continue;
            if (dist < distMin)
                distMin = dist;
            if (dist > distMax)
                distMax = dist;
        }
        if (!Float.isNaN(pointDist) && distMax >= 0
                && DistanceRange.nearMargin(distMin) > pointDist) {
            for (int i = -5; i >= -rows; i -= 1) {
                int dist = getPseudometerCoordinateDistance(position, false, i);
                if (dist > 1e6 || dist < 5)
                    continue;
                if (dist < distMin)
                    distMin = dist;
                if (dist > distMax)
                    distMax = dist;
                if (DistanceRange.nearMargin(distMin) <= pointDist)
                    break;
            }
        }
        DistanceRange dr = scratch.get().dr;
        dr.min = distMin;
        dr.max = distMax;
        return dr;
    }

    /** Depth-map rows spanning {@link #SELF_TOLERANCE_METERS} of height at this distance. */
    private int rowsBelowForSelfTolerance(float pointDist) {
        Pixmap any = pixmapNorth;
        if (any == null || pointDist <= 0f)
            return 4;
        double radiansPerRow = Math.toRadians(cam.camera180degPointNorth.fieldOfView)
                / any.getHeight();
        double rows = Math.ceil(SELF_TOLERANCE_METERS / pointDist / radiansPerRow);
        return (int) Math.max(4, Math.min(MAX_SELF_ROWS, rows));
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
            DistanceRange dr = getPseudoDistanceRangeForDirection(destination, dist);
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
            if (distanceMeters <= 0) {
                // Nothing was hit, or the click was outside what the depth maps cover -
                // steeply downward, in practice. Returning a point anyway would place it
                // on top of the camera; the caller shows no pin, which is at least honest.
                return null;
            }
            float distanceLatits = Units.convertMetersToLatits(distanceMeters);
            // The stable (analytic) ray: the matrix-inverting getPickRay is too imprecise
            // at this camera's near/far ratio, and its hits re-projected off the click.
            Ray pickRay = cam.getPickRayStable(screenX, screenY);
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
