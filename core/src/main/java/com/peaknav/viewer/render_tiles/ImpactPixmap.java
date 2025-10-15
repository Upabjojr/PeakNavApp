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

    private int getPseudometerPixelDistance(int x, int y) {
        return getPseudometerPixelDistance(x, y, false);
    }

    private int getPseudometerPixelDistance(int x, int y, boolean flipY) {

        tempUnproj.set(x, y, 0.99999f);
        this.cam.unproject(tempUnproj);
        return getPseudometerCoordinateDistance(tempUnproj, flipY, 0);
    }

    private int getPseudometerCoordinateDistance(Vector3 coordPos, boolean flipY, int deltaY) {
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
        return r + 255*g + 255*255*b;
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
        // cam.project(position);
        int distMax = Integer.MIN_VALUE;
        int distMin = Integer.MAX_VALUE;
        // int x = (int) position.x;
        // int y = (int) position.y;
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
        dr.min = distMin;
        dr.max = distMax;
        return dr;
    }

    private final ImpactPixmap.DistanceRange dr = new ImpactPixmap.DistanceRange(0, 1);

    public boolean checkIfDistanceIsVisible(float dist, Vector3 destination) {
        lock.readLock().lock();
        try {
            getPseudoDistanceRangeForDirection(destination);
            dr.addMargin();
            return dr.min < dist && dist < dr.max;
        } finally {
            lock.readLock().unlock();
        }
    }

    private final Vector3 tempUnproj = new Vector3();

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

    public void requestUpdatedImpactPixmap() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        impactPixmapNewRequested = true;
        do {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } while (impactPixmapNewRequested);
    }

}
