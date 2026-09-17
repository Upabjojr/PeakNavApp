package com.peaknav.utils;

/**
 * Turns orientations measured against an arbitrary horizontal direction so that the direction a
 * device faced when they started lines up with the direction the camera already faced.
 *
 * <p>Without a magnetometer - an iPod touch has none - the motion sensors cannot tell where
 * north is: their attitude is referenced to some horizontal direction chosen when updates start.
 * Pointing the view by it as if it were north would swing the view to a random direction. So
 * the first reading is paired with the camera's current heading, and every reading after it is
 * turned about the vertical by the same angle: the view then starts where it was and follows
 * the device from there. World coordinates as in the app: x east, y north, z up; a heading is
 * measured from north towards east.
 */
public final class HeadingAlignment {

    /** A direction steeper than this horizontal length (about 78 degrees from level) has no usable heading. */
    static final float MIN_HORIZONTAL = 0.2f;

    private boolean aligned = false;
    private float cos = 1f;
    private float sin = 0f;

    /** Forgets the pairing: the next {@link #align} sets a new one. */
    public void reset() {
        aligned = false;
        cos = 1f;
        sin = 0f;
    }

    public boolean isAligned() {
        return aligned;
    }

    /**
     * Pairs the device's forward direction (deviceX, deviceY) with the camera's (cameraX,
     * cameraY). Returns false, pairing nothing, while either points too steeply up or down to
     * have a heading.
     */
    public boolean align(float deviceX, float deviceY, float cameraX, float cameraY) {
        if (Math.hypot(deviceX, deviceY) < MIN_HORIZONTAL || Math.hypot(cameraX, cameraY) < MIN_HORIZONTAL) {
            return false;
        }
        double delta = Math.atan2(cameraX, cameraY) - Math.atan2(deviceX, deviceY);
        cos = (float) Math.cos(delta);
        sin = (float) Math.sin(delta);
        aligned = true;
        return true;
    }

    /** The x of (x, y) turned about the vertical by the paired angle. */
    public float rotatedX(float x, float y) {
        return x * cos + y * sin;
    }

    /** The y of (x, y) turned about the vertical by the paired angle. */
    public float rotatedY(float x, float y) {
        return y * cos - x * sin;
    }
}
