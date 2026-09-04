package com.peaknav.gesture;

import com.badlogic.gdx.math.Vector3;

/**
 * A point of the picture pinned to the terrain behind it.
 *
 * <p>While a photo is shown behind the terrain, a quick double tap pins the terrain
 * direction under the finger to that spot of the screen. From then on dragging rotates the view
 * <em>around</em> the pin - the camera turns about the pinned direction, so the ridge
 * under the finger stays put and the rest of the terrain swings around it - and
 * pinching zooms while the pin is kept in place, which is how a photo gets lined up:
 * fix one summit, then turn and stretch the terrain until the others fall in. The next
 * double tap releases the pin and hands the usual gestures back; a single tap keeps
 * measuring.
 *
 * <p>One pin, app-wide, like the background picture it serves. Screen coordinates are
 * the touch convention (y down).
 */
public final class PhotoPin {

    private static boolean active;
    private static float screenX;
    private static float screenY;
    private static final Vector3 direction = new Vector3();

    private PhotoPin() {
    }

    /** Pins the world direction {@code dir} to screen point ({@code x}, {@code y}). */
    public static synchronized void set(float x, float y, Vector3 dir) {
        screenX = x;
        screenY = y;
        direction.set(dir).nor();
        active = true;
    }

    public static synchronized void clear() {
        active = false;
    }

    public static synchronized boolean isActive() {
        return active;
    }

    public static synchronized float getScreenX() {
        return screenX;
    }

    public static synchronized float getScreenY() {
        return screenY;
    }

    /** Copies the pinned world direction into {@code out} and returns it. */
    public static synchronized Vector3 getDirection(Vector3 out) {
        return out.set(direction);
    }
}
