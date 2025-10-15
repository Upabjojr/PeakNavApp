package com.peaknav.gesture;

import com.badlogic.gdx.graphics.g3d.utils.CameraInputController;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;

import java.util.ArrayList;

import com.peaknav.viewer.PerspectiveCameraExt;
import com.peaknav.viewer.screens.MapViewerScreen;

public class MountainInputController extends CameraInputController {

    public PerspectiveCameraExt perspectiveCamera;
    private final ArrayList<PositionChangeListener> positionChangeListeners;

    private final Vector3 tmpV1 = new Vector3();
    private final Vector3 tmpV2 = new Vector3();

    public final float rotationFactorBase = 0.2f;
    public float rotationFactor = rotationFactorBase;

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
