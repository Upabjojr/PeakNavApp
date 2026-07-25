package com.peaknav.controller;

import static android.content.Context.WINDOW_SERVICE;

import android.content.Context;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.peaknav.singleton.MapViewerAndroidSingleton;
import com.peaknav.viewer.screens.MapViewerScreen;

/**
 * Feeds the camera a device orientation derived from the platform's <em>fused</em> rotation-vector
 * sensor ({@link Sensor#TYPE_ROTATION_VECTOR}), available since API 9 and present on every device we
 * target. The OS fuses gyroscope + accelerometer + magnetometer internally, which is dramatically
 * steadier than fusing raw accelerometer + magnetometer ourselves (the magnetometer alone is very
 * noisy). On top of that we apply an adaptive quaternion SLERP low-pass:
 *
 * <ul>
 *   <li>When the phone is (nearly) still we blend only a small fraction of each new reading in, so
 *       residual sensor noise is heavily damped and the view stops wiggling.</li>
 *   <li>When the phone turns quickly we blend in most of the new reading, so tracking stays snappy
 *       and lag-free.</li>
 *   <li>Sub-{@link #DEADBAND_DEG} jitter is ignored outright, freezing the view rock-steady while
 *       the user holds the phone against a mountain.</li>
 * </ul>
 *
 * The same tuning works across old and new Android versions because it operates on the OS-fused
 * orientation, not on the raw sensor hardware whose noise characteristics vary between devices.
 */
public class OrientationPointerController implements SensorEventListener {

    private final SensorManager sensorManager;
    private final Context context;
    private final MapViewerScreen mapViewerScreen;

    /** Per-event blend factor when the phone is essentially still: heavy smoothing, kills jitter. */
    private static final float ALPHA_MIN = 0.06f;
    /** Per-event blend factor when the phone is turning fast: light smoothing, stays responsive. */
    private static final float ALPHA_MAX = 0.65f;
    /** At or above this angular step (degrees) between consecutive readings we use ALPHA_MAX. */
    private static final float FAST_STEP_DEG = 6.0f;
    /** Angular steps below this (degrees) are treated as pure noise and dropped entirely. */
    private static final float DEADBAND_DEG = 0.20f;

    // Reused scratch objects — onSensorChanged runs on a sensor thread at up to ~game rate.
    private final float[] rotationVector = new float[4];
    private final Quaternion measured = new Quaternion();
    private final Quaternion smoothed = new Quaternion();
    private final Vector3 axisX = new Vector3();
    private final Vector3 axisZ = new Vector3();
    private boolean haveSmoothed = false;

    public OrientationPointerController(Context context) {
        this.context = context;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        mapViewerScreen = MapViewerAndroidSingleton.getViewerInstance();
    }

    public void start() {
        haveSmoothed = false;
        Sensor rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotationVectorSensor != null) {
            // SENSOR_DELAY_GAME (~50 Hz) is smooth without flooding; the OS fusion runs regardless.
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    public void stop() {
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) {
            return;
        }

        // getQuaternionFromVector fills [w, x, y, z]; libGDX Quaternion is (x, y, z, w).
        SensorManager.getQuaternionFromVector(rotationVector, event.values);
        measured.set(rotationVector[1], rotationVector[2], rotationVector[3], rotationVector[0]);

        if (!haveSmoothed) {
            smoothed.set(measured);
            haveSmoothed = true;
        } else {
            // Angle between the current filtered orientation and the fresh reading, in degrees.
            float dot = Math.abs(smoothed.dot(measured));
            if (dot > 1f) dot = 1f;
            float stepDeg = 2f * MathUtils.acos(dot) * MathUtils.radiansToDegrees;

            if (stepDeg < DEADBAND_DEG) {
                return; // Pure noise while held still: leave the view exactly where it is.
            }

            // Ramp the blend factor from heavy smoothing (still) to light smoothing (fast turn).
            float t = MathUtils.clamp(stepDeg / FAST_STEP_DEG, 0f, 1f);
            float alpha = ALPHA_MIN + (ALPHA_MAX - ALPHA_MIN) * t;
            smoothed.slerp(measured, alpha);
        }

        // Columns of the device->world rotation, matching the previous rotation-matrix convention:
        //   up  = R * (1,0,0)  (device X in world)     dir = -(R * (0,0,1))  (device -Z in world)
        axisX.set(1f, 0f, 0f);
        smoothed.transform(axisX);
        axisZ.set(0f, 0f, 1f);
        smoothed.transform(axisZ);

        boolean landscape = isOrientationLandscape();
        boolean upsideDown = isOrientationUpsideDown();
        mapViewerScreen.pointCameraForGyroscope(
                -axisZ.x, -axisZ.y, -axisZ.z,
                axisX.x, axisX.y, axisX.z,
                landscape, upsideDown);
    }

    private boolean isOrientationLandscape() {
        return context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private boolean isOrientationUpsideDown() {
        WindowManager windowManager = (WindowManager) context.getSystemService(WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        int rotation = display.getRotation();
        if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_90) {
            return false;
        } else if (rotation == Surface.ROTATION_180 || rotation == Surface.ROTATION_270) {
            return true;
        }
        return false;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }
}
