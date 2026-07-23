package com.peaknav.gesture;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.g3d.utils.CameraInputController;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;

import java.util.ArrayList;

import com.peaknav.viewer.PerspectiveCameraExt;
import com.peaknav.viewer.screens.MapViewerScreen;

public class MountainInputController extends CameraInputController {

    public PerspectiveCameraExt perspectiveCamera;
    private final ArrayList<PositionChangeListener> positionChangeListeners;
    private final MapViewerScreen mapViewerScreen;

    private final Vector3 tmpV1 = new Vector3();
    private final Vector3 tmpV2 = new Vector3();

    public final float rotationFactorBase = 0.2f;
    public float rotationFactor = rotationFactorBase;

    /**
     * How far the arrow keys sweep the view in one second, measured in screen widths,
     * which is the unit a mouse drag works in. Because rotationFactor already tracks
     * the field of view, aiming automatically gets finer as the user zooms in.
     */
    public float lookScreensPerSecond = 0.8f;
    /** Multiplier applied while the modifier key is held, for fine aiming. */
    public float lookSlowFactor = 0.25f;

    /**
     * How much of the elevation bar the altitude keys travel in one second. The bar is
     * deliberately not linear in metres, so this stays gentle near the ground and
     * speeds up higher up, the same way dragging it does.
     */
    public float altitudeBarsPerSecond = 0.4f;

    public int lookLeftKey = Input.Keys.LEFT;
    public int lookRightKey = Input.Keys.RIGHT;
    public int lookUpKey = Input.Keys.UP;
    public int lookDownKey = Input.Keys.DOWN;
    public int lookSlowKey = Input.Keys.SHIFT_LEFT;
    public int altitudeUpKey = Input.Keys.PAGE_UP;
    public int altitudeDownKey = Input.Keys.PAGE_DOWN;

    private boolean lookLeftPressed;
    private boolean lookRightPressed;
    private boolean lookUpPressed;
    private boolean lookDownPressed;
    private boolean lookSlowPressed;
    private boolean altitudeUpPressed;
    private boolean altitudeDownPressed;

    public static class MountainGestureListener extends CameraGestureListener {
        private final Vector2 tmpV1 = new Vector2();
        private final Vector2 tmpV2 = new Vector2();
        private final MapViewerScreen mapViewerScreen;
        private final PerspectiveCameraExt camera;

        MountainGestureListener(MapViewerScreen mapViewerScreen, PerspectiveCameraExt camera) {
            this.mapViewerScreen = mapViewerScreen;
            this.camera = camera;
        }

        @Override
        public boolean tap(float x, float y, int count, int button) {

            mapViewerScreen.impact = mapViewerScreen.detectClicked3DPosition(x, y);
            boolean valid = mapViewerScreen.updateImpact();
            if (valid) {
                mapViewerScreen.impactToastDistance();
            }
            return valid;
        }

        private final static float rotFactor = 1f/500.f;

        @Override
        public boolean pinch(Vector2 initialPointer1, Vector2 initialPointer2, Vector2 pointer1, Vector2 pointer2) {
            tmpV1.set(initialPointer2).sub(initialPointer1).nor();
            tmpV2.set(pointer2).sub(pointer1).nor();
            float rotationDeg = (float) Math.toDegrees(Math.asin(tmpV1.crs(tmpV2)));

            if (Math.abs(rotationDeg) > 3.0) {
                camera.rotateAround(camera.position, camera.direction, -rotationDeg*rotFactor);
                // Maximum tilt about 8 degrees:
                if (camera.up.z < 0.995f) {
                    camera.rotateAround(camera.position, camera.direction, rotationDeg*rotFactor);
                    camera.up.z = 0.99501f;
                    return true;
                }
                return false;
            } else {
                return true;
            }
        }

    }

    private MountainInputController(MountainGestureListener listener, PerspectiveCameraExt camera, ArrayList<PositionChangeListener> positionChangeListeners, MapViewerScreen mapViewerScreen) {
        super(listener, camera);
        this.perspectiveCamera = camera;
        this.positionChangeListeners = positionChangeListeners;
        this.mapViewerScreen = mapViewerScreen;
    }

    public static MountainInputController getInstance(PerspectiveCameraExt camera, ArrayList<PositionChangeListener> positionChangeListeners, MapViewerScreen mapViewerScreen) {
        MountainGestureListener listener = new MountainGestureListener(mapViewerScreen, camera);
        return new MountainInputController(listener, camera, positionChangeListeners, mapViewerScreen);
    }

    protected boolean process(float deltaX, float deltaY, int button) {
        boolean processed = false;
        if (button == rotateButton) {
            tmpV1.set(camera.direction).crs(camera.up);
            camera.rotateAround(camera.position, tmpV1, -deltaY * rotationFactor * rotateAngle);
            if (camera.up.z < 0) {
                // Undo rotation
                camera.rotateAround(camera.position, tmpV1, deltaY * rotationFactor * rotateAngle);
            } else {
                processed = true;
            }
            camera.rotateAround(camera.position, Vector3.Z, deltaX * rotationFactor * rotateAngle);
        } else if (button == translateButton) {
            /*
            camera.translate(tmpV1.set(0, 0, -1).crs(camera.up).nor().scl(-deltaX * translateUnits));
            tmpV2.set(camera.up);
            tmpV2.z = 0;
            tmpV2.nor();
            camera.translate(tmpV2.scl(-deltaY * translateUnits));
             */
            camera.translate(tmpV1.set(camera.direction).crs(camera.up).nor().scl(-deltaX * translateUnits));
            camera.translate(tmpV2.set(camera.up).scl(-deltaY * translateUnits));
            if (translateTarget) target.add(tmpV1).add(tmpV2);
            processed = true;
        } else if (button == forwardButton) {
            camera.translate(tmpV1.set(camera.direction).scl(deltaY * translateUnits));
            if (forwardTarget) target.add(tmpV1);
            processed = false;
        }
        if (autoUpdate) camera.update();
        return processed;
    }

    /*
    @Override
    public boolean scrolled (float amountX, float amountY) {
        return zoom(amountY * scrollFactor * translateUnits);
    }
     */

    /**
     * Drives the camera from the keyboard, so the view can be aimed and raised without
     * a mouse. The arrows move the camera rather than the landscape, which is the
     * opposite of dragging, hence the flipped signs below.
     */
    @Override
    public void update() {
        super.update();

        float slow = lookSlowPressed ? lookSlowFactor : 1f;
        float deltaTime = Gdx.graphics.getDeltaTime();

        updateKeyboardLook(slow * deltaTime);
        updateKeyboardAltitude(slow * deltaTime);
    }

    private void updateKeyboardLook(float scaledDeltaTime) {
        float deltaX = (lookLeftPressed ? 1f : 0f) - (lookRightPressed ? 1f : 0f);
        float deltaY = (lookDownPressed ? 1f : 0f) - (lookUpPressed ? 1f : 0f);
        if (deltaX == 0f && deltaY == 0f)
            return;

        float amount = lookScreensPerSecond * scaledDeltaTime;
        // Reuse the drag path so that the guard against tipping the camera over the
        // vertical applies to the keyboard exactly as it does to the mouse.
        process(deltaX * amount, deltaY * amount, rotateButton);
    }

    private void updateKeyboardAltitude(float scaledDeltaTime) {
        float delta = (altitudeUpPressed ? 1f : 0f) - (altitudeDownPressed ? 1f : 0f);
        if (delta == 0f || mapViewerScreen == null)
            return;

        mapViewerScreen.nudgeCameraElevationBar(delta * altitudeBarsPerSecond * scaledDeltaTime);
    }

    private boolean setLookKeyPressed(int keycode, boolean pressed) {
        if (keycode == lookLeftKey) {
            lookLeftPressed = pressed;
        } else if (keycode == lookRightKey) {
            lookRightPressed = pressed;
        } else if (keycode == lookUpKey) {
            lookUpPressed = pressed;
        } else if (keycode == lookDownKey) {
            lookDownPressed = pressed;
        } else if (keycode == altitudeUpKey) {
            altitudeUpPressed = pressed;
        } else if (keycode == altitudeDownKey) {
            altitudeDownPressed = pressed;
        } else if (keycode == lookSlowKey) {
            lookSlowPressed = pressed;
            // The modifier alone points nothing, so let it through to the other processors.
            return false;
        } else {
            return false;
        }
        return true;
    }

    @Override
    public boolean keyDown(int keycode) {
        boolean handled = setLookKeyPressed(keycode, true);
        return super.keyDown(keycode) || handled;
    }

    @Override
    public boolean keyUp(int keycode) {
        boolean handled = setLookKeyPressed(keycode, false);
        return super.keyUp(keycode) || handled;
    }

    /**
     * Releases every held key. A window that loses focus mid-keypress never delivers
     * the matching keyUp, which would otherwise leave the camera spinning by itself.
     */
    public void clearKeyboardLook() {
        lookLeftPressed = false;
        lookRightPressed = false;
        lookUpPressed = false;
        lookDownPressed = false;
        lookSlowPressed = false;
        altitudeUpPressed = false;
        altitudeDownPressed = false;
    }

    public static final float FIELD_OF_VIEW_MAX = 135.f;
    public static final float FIELD_OF_VIEW_MIN = 5.f;

    final float pinchZoomFactor2 = 0.01f;
    final float pinchZoomFactor3 = 50.f;

    @Override
    protected boolean pinchZoom (float amount) {
        return zoom(pinchZoomFactor2 * amount);
    }

    @Override
    public boolean zoom (float amount) {
        amount *= pinchZoomFactor3;
        float delta = -perspectiveCamera.getAngleForCompassDelta()*amount;
        float newFieldOfView = perspectiveCamera.getAngleForCompassDelta() + delta;
        if (newFieldOfView > FIELD_OF_VIEW_MAX || newFieldOfView < FIELD_OF_VIEW_MIN) {
            if (camera instanceof  PerspectiveCameraExt)
                ((PerspectiveCameraExt)camera).resizeFieldOfViewToBounds();
            return false;
        }
        perspectiveCamera.fieldOfView += delta * perspectiveCamera.fieldOfView / perspectiveCamera.getAngleForCompassDelta();
        for (PositionChangeListener positionChangeListener : positionChangeListeners) {
            positionChangeListener.onZoomChanged(newFieldOfView);
        }
        if (autoUpdate) camera.update();
        return true;
    }

}
