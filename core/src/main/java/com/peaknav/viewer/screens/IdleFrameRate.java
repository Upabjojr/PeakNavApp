package com.peaknav.viewer.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;

/**
 * Draws fewer frames while nothing on the map is happening, to save battery.
 *
 * <p>The map is redrawn every frame whether or not anything changed, so a phone held still on
 * a view drew the same picture 60 times a second - the battery drain a reviewer noticed on an
 * iPod touch. After {@link #IDLE_AFTER_SECONDS} with no touch or key, no camera movement, no
 * interface animation and nothing loading, the frame rate drops to {@link #IDLE_FPS}; the
 * next touch, or anything moving, brings it straight back to {@link #ACTIVE_FPS}.
 *
 * <p>Camera movement covers everything that moves the view without a touch: flights, orbits,
 * the GPX tour and the gyroscope. The road and trail dashes keep flowing while idle, at the
 * lower rate. Off unless a launcher enables it: only iOS does, so the desktop, Android and the
 * headless renderer draw every frame as before.
 */
public final class IdleFrameRate {

    public static final int ACTIVE_FPS = 60;
    public static final int IDLE_FPS = 20;
    public static final float IDLE_AFTER_SECONDS = 3f;

    /** Below this the camera counts as still: far under a centimetre, or a thousandth of a degree. */
    private static final float CAMERA_EPSILON = 1e-6f;

    private static volatile boolean enabled = false;

    /** Turns the saving on for this process; call before the app starts. */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    private float stillSeconds = 0f;
    private int appliedFps = ACTIVE_FPS;
    private final Vector3 lastPosition = new Vector3(Float.NaN, Float.NaN, Float.NaN);
    private final Vector3 lastDirection = new Vector3();
    private final Vector3 lastUp = new Vector3();
    private float lastFieldOfView = Float.NaN;

    /**
     * First in the map's input multiplexer: notes that someone is using the app and passes
     * every event on, so a touch restores the full frame rate before anything handles it.
     */
    public final InputProcessor inputWatcher = new InputAdapter() {
        @Override public boolean keyDown(int keycode) { wake(); return false; }
        @Override public boolean keyUp(int keycode) { wake(); return false; }
        @Override public boolean keyTyped(char character) { wake(); return false; }
        @Override public boolean touchDown(int x, int y, int pointer, int button) { wake(); return false; }
        @Override public boolean touchUp(int x, int y, int pointer, int button) { wake(); return false; }
        @Override public boolean touchCancelled(int x, int y, int pointer, int button) { wake(); return false; }
        @Override public boolean touchDragged(int x, int y, int pointer) { wake(); return false; }
        @Override public boolean mouseMoved(int x, int y) { wake(); return false; }
        @Override public boolean scrolled(float amountX, float amountY) { wake(); return false; }
    };

    /** Back to the full frame rate now, and the idle count starts again. */
    public void wake() {
        stillSeconds = 0f;
        apply(ACTIVE_FPS);
    }

    /** Called once a frame, with what the map is doing in it. */
    public void update(float deltaSeconds, PerspectiveCamera cam, Group stageRoot, boolean loading) {
        if (!enabled) {
            return;
        }
        boolean moved = cameraMoved(cam);
        boolean animating = loading || (stageRoot != null && hasActions(stageRoot));
        apply(nextFps(moved || animating, deltaSeconds));
    }

    /** The frame rate for a frame that was, or was not, active. Package-private for the test. */
    int nextFps(boolean activeThisFrame, float deltaSeconds) {
        if (activeThisFrame) {
            stillSeconds = 0f;
            return ACTIVE_FPS;
        }
        stillSeconds += deltaSeconds;
        return stillSeconds >= IDLE_AFTER_SECONDS ? IDLE_FPS : ACTIVE_FPS;
    }

    private boolean cameraMoved(PerspectiveCamera cam) {
        if (cam == null) {
            return false;
        }
        boolean moved = !cam.position.epsilonEquals(lastPosition, CAMERA_EPSILON)
                || !cam.direction.epsilonEquals(lastDirection, CAMERA_EPSILON)
                || !cam.up.epsilonEquals(lastUp, CAMERA_EPSILON)
                || cam.fieldOfView != lastFieldOfView;
        if (moved) {
            lastPosition.set(cam.position);
            lastDirection.set(cam.direction);
            lastUp.set(cam.up);
            lastFieldOfView = cam.fieldOfView;
        }
        return moved;
    }

    /** Whether a visible widget is running an animation: a toast fading, a button pulsing. */
    private static boolean hasActions(Actor actor) {
        if (actor.hasActions()) {
            return true;
        }
        if (actor instanceof Group) {
            for (Actor child : ((Group) actor).getChildren()) {
                if (child.isVisible() && hasActions(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void apply(int fps) {
        if (!enabled || fps == appliedFps || Gdx.graphics == null) {
            return;
        }
        appliedFps = fps;
        Gdx.graphics.setForegroundFPS(fps);
        if (Gdx.app != null) {
            Gdx.app.debug("IdleFrameRate", fps + " fps");
        }
    }
}
