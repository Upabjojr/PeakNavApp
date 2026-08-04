package com.peaknav.viewer;

import static com.peaknav.gesture.MountainInputController.FIELD_OF_VIEW_MAX;
import static com.peaknav.gesture.MountainInputController.FIELD_OF_VIEW_MIN;
import static com.peaknav.utils.PeakNavUtils.getC;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Vector3;

public class PerspectiveCameraExt extends PerspectiveCamera {
    private final static int deltaAnglePixels = 25;
    private float polyXa;
    private float polyXc;
    private float polyYa;
    private float polyYc;
    private float angleForCompass1;
    private float angleForCompass2;
    private float angleForCompassDelta;
    private final float MAX_DIR_Z = 0.95f;

    private final Vector3 tempVect1 = new Vector3();
    private final Vector3 tempVect2 = new Vector3();

    public final PerspectiveCamera camera180degPointNorth;
    public final PerspectiveCamera camera180degPointEast;
    public final PerspectiveCamera camera180degPointSouth;
    public final PerspectiveCamera camera180degPointWest;

    public PerspectiveCameraExt(float baseFieldOfView, int width, int height) {
        super(baseFieldOfView, width, height);

        float fieldOfViewY = 90f / width * height + 5f;

        camera180degPointNorth = new PerspectiveCamera(fieldOfViewY, width, height);
        camera180degPointEast = new PerspectiveCamera(fieldOfViewY, width, height);
        camera180degPointSouth = new PerspectiveCamera(fieldOfViewY, width, height);
        camera180degPointWest = new PerspectiveCamera(fieldOfViewY, width, height);

        camera180degPointNorth.position.set(0, 0, 0);
        camera180degPointEast.position.set(0, 0, 0);
        camera180degPointSouth.position.set(0, 0, 0);
        camera180degPointWest.position.set(0, 0, 0);

        camera180degPointNorth.up.set(0, 0, 1);
        camera180degPointEast.up.set(0, 0, 1);
        camera180degPointSouth.up.set(0, 0, 1);
        camera180degPointWest.up.set(0, 0, 1);

        camera180degPointNorth.direction.set(0, 1, 0);
        camera180degPointEast.direction.set(1, 0, 0);
        camera180degPointSouth.direction.set(0, -1, 0);
        camera180degPointWest.direction.set(-1, 0, 0);

        camera180degPointNorth.update();
        camera180degPointEast.update();
        camera180degPointSouth.update();
        camera180degPointWest.update();
    }

    private float getOutlinePolyAngle(int x1, int y1, int x2, int y2) {
        // may not be initialized yet (if called by super constructor):
        if (tempVect1 == null) {
            return 60f;
        }
        tempVect1.set(getPickRay(x1, y1).direction);
        tempVect2.set(getPickRay(x2, y2).direction);
        return (float) Math.acos(tempVect1.dot(tempVect2));
    }

    private void updatePolyXY() {
        float angle1, angle2, a;
        int shh = Gdx.graphics.getHeight()/2;
        int shw = Gdx.graphics.getWidth()/2;

        angle1 = getOutlinePolyAngle(0, shh, deltaAnglePixels, shh + deltaAnglePixels)/deltaAnglePixels;
        angle2 = getOutlinePolyAngle(shw, shh, shw+deltaAnglePixels, shh + deltaAnglePixels)/deltaAnglePixels;
        a = (angle1 - angle2);
        polyXa = a;
        polyXc = angle2;

        angle1 = getOutlinePolyAngle(shw, 0, shw + deltaAnglePixels, deltaAnglePixels)/deltaAnglePixels;
        angle2 = getOutlinePolyAngle(shw, shh, shw + deltaAnglePixels, shh+deltaAnglePixels)/deltaAnglePixels;
        a = (angle1 - angle2);
        polyYa = a;
        polyYc = angle2;

        // PeakNavUtils.getLogger().error("CAMERA", "polyXYac values: " + polyXa + ", " + polyXc + ", " + polyYa + ", " + polyYc);
    }

    @Override
    public void update(boolean updateFrustum) {
        if (this.up.z < 0)
            this.up.z = -this.up.z;
        if (this.direction.z > MAX_DIR_Z) {
            this.direction.z = MAX_DIR_Z;
            this.direction.nor();
        } else if (this.direction.z < -MAX_DIR_Z) {
            this.direction.z = -MAX_DIR_Z;
            this.direction.nor();
        }
        super.update(updateFrustum);
        updatePolyXY();
        updateAnglesForCompass();

        updateGeographicCameras(updateFrustum);
    }

    /**
     * A pick ray built analytically from the camera's basis, without inverting the
     * projection matrix.
     *
     * <p>{@link #getPickRay} unprojects through the inverse of the combined matrix - and
     * with this camera's extreme depth range (near 0.0001, far 15; a 150000:1 ratio) that
     * inversion loses enough float precision that the resulting ray no longer passes
     * through the clicked pixel: its terrain hit re-projected several pixels away, which
     * put the location pin visibly off the click. The forward projection is
     * well-conditioned; only the inverse is not. Building the ray from direction, up and
     * the field of view avoids the inverse entirely, so hit points re-project onto the
     * click to sub-pixel accuracy.
     *
     * <p>Returns a fresh Ray: unlike {@code getPickRay} there is no shared instance to
     * step on, so it is safe from any thread that sees a consistent camera.
     *
     * @param screenX window x in pixels
     * @param screenY window y in pixels, measured downward (touch convention)
     */
    public Ray getPickRayStable(float screenX, float screenY) {
        float w = Gdx.graphics.getWidth();
        float h = Gdx.graphics.getHeight();
        double ndcX = 2.0 * screenX / w - 1.0;   // -1 left .. +1 right
        double ndcY = 1.0 - 2.0 * screenY / h;   // -1 bottom .. +1 top (screenY is y-down)
        double tanY = Math.tan(Math.toRadians(fieldOfView) * 0.5);
        double tanX = tanY * (w / (double) h);

        Vector3 right = new Vector3(direction).crs(up).nor();
        Vector3 upOrtho = new Vector3(right).crs(direction).nor();
        Vector3 dir = new Vector3(direction)
                .mulAdd(right, (float) (ndcX * tanX))
                .mulAdd(upOrtho, (float) (ndcY * tanY))
                .nor();

        Ray ray = new Ray();
        ray.origin.set(position);
        ray.direction.set(dir);
        return ray;
    }

    public PerspectiveCamera getGeographicCameraForPoint(float x, float y) {
        Vector3 camPos = getC().getMapViewerScreen().cam.position;

        float dx = x - camPos.x;
        float dy = y - camPos.y;

        PerspectiveCamera camera;
        
        if (dy > dx) {
            if (dy > -dx) {
                camera = camera180degPointNorth;
            } else {
                camera = camera180degPointWest;
            }
        } else {
            if (dy > -dx) {
                camera = camera180degPointEast;
            } else {
                camera = camera180degPointSouth;
            }
        }

        return camera;
    }
    
    /**
     * Keeps the four 180° geographic cameras in step with a viewport resize. They receive their
     * viewport and field of view only in the constructor otherwise, so after a window resize the
     * depth pixmaps would be rendered and sampled with the old aspect/FOV — every projected sample
     * lands on the wrong pixel and labels read as occluded (or wrong) until restart.
     */
    public void resizeGeographicCameras(int width, int height) {
        if (width <= 0 || height <= 0 || camera180degPointNorth == null)
            return;
        float fieldOfViewY = 90f / width * height + 5f; // same formula as the constructor

        camera180degPointNorth.fieldOfView = fieldOfViewY;
        camera180degPointEast.fieldOfView = fieldOfViewY;
        camera180degPointSouth.fieldOfView = fieldOfViewY;
        camera180degPointWest.fieldOfView = fieldOfViewY;

        camera180degPointNorth.viewportWidth = width;
        camera180degPointEast.viewportWidth = width;
        camera180degPointSouth.viewportWidth = width;
        camera180degPointWest.viewportWidth = width;

        camera180degPointNorth.viewportHeight = height;
        camera180degPointEast.viewportHeight = height;
        camera180degPointSouth.viewportHeight = height;
        camera180degPointWest.viewportHeight = height;

        updateGeographicCameras(true);
    }

    private void updateGeographicCameras(boolean updateFrustum) {
        if (camera180degPointNorth == null)
            return;

        camera180degPointNorth.position.set(this.position);
        camera180degPointEast.position.set(this.position);
        camera180degPointSouth.position.set(this.position);
        camera180degPointWest.position.set(this.position);
        
        camera180degPointNorth.update(updateFrustum);
        camera180degPointEast.update(updateFrustum);
        camera180degPointSouth.update(updateFrustum);
        camera180degPointWest.update(updateFrustum);
    }

    @Override
    public void update() {
        update(true);
    }

    public void resizeFieldOfViewToBounds() {
        if (getAngleForCompassDelta() <= FIELD_OF_VIEW_MIN) {
            fieldOfView *= 1.1f * FIELD_OF_VIEW_MIN / getAngleForCompassDelta();
            super.update();
            updateAnglesForCompass();
        }
        for (int i = 0; i < 3; i++) {
            if (getAngleForCompassDelta() < FIELD_OF_VIEW_MAX)
                break;
            fieldOfView *= 0.9f * FIELD_OF_VIEW_MAX / getAngleForCompassDelta();
            super.update();
            updateAnglesForCompass();
        }
    }

    private void updateAnglesForCompass() {
        float halfY = Gdx.graphics.getHeight()/2f;
        Vector3 dir1 = getPickRay(0, halfY).direction;
        angleForCompass1 = computeAngleForCompass(dir1);
        Vector3 dir2 = getPickRay(Gdx.graphics.getWidth(), halfY).direction;
        angleForCompass2 = computeAngleForCompass(dir2);

        angleForCompassDelta = angleForCompass2 - angleForCompass1;
        if (angleForCompassDelta < 0)
            angleForCompassDelta += 360.f;
        if (angleForCompassDelta > 180)
            angleForCompassDelta = 360.f - angleForCompassDelta;
    }

    private float computeAngleForCompass(Vector3 direction) {
        return (float) Math.toDegrees(Math.atan2(direction.y, direction.x));
    }

    public float getPolyXa() {
        return polyXa;
    }

    public float getPolyXc() {
        return polyXc;
    }

    public float getPolyYa() {
        return polyYa;
    }

    public float getPolyYc() {
        return polyYc;
    }

    public float getAngleForCompass1() {
        return angleForCompass1;
    }

    public float getAngleForCompass2() {
        return angleForCompass2;
    }

    public float getAngleForCompassDelta() {
        return angleForCompassDelta;
    }

    private final static float smoothZLimit = 0.35f;

    public void smoothDirection() {
        if (Math.abs(direction.z) > smoothZLimit) {
            float c = 1 + (direction.z*direction.z - smoothZLimit*smoothZLimit)
                    / (direction.x*direction.x + direction.y*direction.y);
            c = (float) Math.sqrt(c);
            direction.set(c*direction.x, c*direction.y, ((direction.z > 0)? 1 : -1) * smoothZLimit);
        }
    }
}
