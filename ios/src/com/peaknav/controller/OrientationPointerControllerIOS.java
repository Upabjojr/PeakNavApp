package com.peaknav.controller;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.peaknav.utils.PeakNavUtils;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.viewer.screens.MapViewerScreen;

import org.robovm.apple.coremotion.CMAttitudeReferenceFrame;
import org.robovm.apple.coremotion.CMDeviceMotion;
import org.robovm.apple.coremotion.CMMagneticFieldCalibrationAccuracy;
import org.robovm.apple.coremotion.CMMotionManager;
import org.robovm.apple.coremotion.CMQuaternion;
import org.robovm.apple.foundation.NSOperationQueue;

/**
 * The iOS twin of the Android {@code OrientationPointerController}: feeds the camera a
 * device orientation from the OS-fused attitude (gyroscope + accelerometer + magnetometer,
 * fused by CoreMotion the way Android's rotation-vector sensor is fused by its OS), run
 * through the identical adaptive quaternion SLERP low-pass. The tuning constants are the
 * Android ones on purpose - they were chosen against the fused orientation, not against any
 * particular hardware, and the fusion is the platform's on both sides.
 *
 * <p>Updates are delivered on {@link NSOperationQueue#getMainQueue()}: on this backend the
 * main thread <em>is</em> the GL thread, so the handler may touch the screen and libGDX
 * input directly, and the scratch objects below need no locking.
 *
 * <p>Two frame conventions meet here, and both are settled in {@link #onDeviceMotion}:
 * <ul>
 *   <li>CoreMotion's attitude rotates device axes into its reference frame
 *       (X = magnetic north, Y = west, Z = up), while the app's world is
 *       x = east, y = north, z = up - so every transformed vector gets the
 *       90-degree swap {@code (x, y, z) -> (-y, x, z)}.</li>
 *   <li>Magnetic rather than true north ({@link CMAttitudeReferenceFrame#XMagneticNorthZVertical}),
 *       matching what Android's rotation vector delivers - and true north would quietly
 *       start CoreLocation on its own to learn the declination.</li>
 * </ul>
 */
public class OrientationPointerControllerIOS {

    /** Per-event blend factor when the phone is essentially still: heavy smoothing, kills jitter. */
    private static final float ALPHA_MIN = 0.06f;
    /** Per-event blend factor when the phone is turning fast: light smoothing, stays responsive. */
    private static final float ALPHA_MAX = 0.65f;
    /** At or above this angular step (degrees) between consecutive readings we use ALPHA_MAX. */
    private static final float FAST_STEP_DEG = 6.0f;
    /** Angular steps below this (degrees) are treated as pure noise and dropped entirely. */
    private static final float DEADBAND_DEG = 0.20f;
    /** ~50 Hz, the same rate Android's SENSOR_DELAY_GAME registers at. */
    private static final double UPDATE_INTERVAL_S = 1.0 / 50.0;

    /** While the compass is uncalibrated, re-show the warning at least this often (ms). */
    private static final long WARN_REFRESH_MS = 900L;
    /** Don't nag during the first moment after start: the fusion needs a beat to settle. */
    private static final long WARN_GRACE_MS = 1500L;

    private final CMMotionManager motionManager = new CMMotionManager();

    // Reused scratch objects - the handler runs at ~50 Hz, always on the main thread.
    private final Quaternion measured = new Quaternion();
    private final Quaternion smoothed = new Quaternion();
    private final Vector3 axisX = new Vector3();
    private final Vector3 axisZ = new Vector3();
    private boolean haveSmoothed = false;

    private MapViewerScreen mapViewerScreen;
    private long startMs = 0L;
    private long lastWarnMs = 0L;

    public void start() {
        if (!motionManager.isDeviceMotionAvailable()) {
            // No motion hardware - the simulator, mainly. Behave as the desktop does:
            // the view stays hand-steered and there is nothing to start.
            return;
        }
        mapViewerScreen = MapViewerSingleton.getViewerInstance();
        haveSmoothed = false;
        startMs = System.currentTimeMillis();
        lastWarnMs = 0L;
        motionManager.setDeviceMotionUpdateInterval(UPDATE_INTERVAL_S);
        motionManager.startDeviceMotionUpdates(
                CMAttitudeReferenceFrame.XMagneticNorthZVertical,
                NSOperationQueue.getMainQueue(),
                (motion, error) -> {
                    if (motion != null) {
                        onDeviceMotion(motion);
                    }
                });
    }

    public void stop() {
        motionManager.stopDeviceMotionUpdates();
    }

    private void onDeviceMotion(CMDeviceMotion motion) {
        maybeWarnCalibration(motion);

        CMQuaternion q = motion.getAttitude().getQuaternion();
        measured.set((float) q.getX(), (float) q.getY(), (float) q.getZ(), (float) q.getW());

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

        // Same extraction as Android: up = R * (1,0,0) (device X), dir = -(R * (0,0,1))
        // (out of the back camera) - but in CoreMotion's reference coordinates, so each
        // vector then takes the reference->world swap (x, y, z) -> (-y, x, z).
        axisX.set(1f, 0f, 0f);
        smoothed.transform(axisX);
        axisZ.set(0f, 0f, 1f);
        smoothed.transform(axisZ);

        boolean landscape = isOrientationLandscape();
        boolean upsideDown = isOrientationUpsideDown();
        mapViewerScreen.pointCameraForGyroscope(
                axisZ.y, -axisZ.x, -axisZ.z,
                -axisX.y, axisX.x, axisX.z,
                landscape, upsideDown);
    }

    // Gdx.input.getRotation() reads the interface orientation, which the backend keeps in
    // step with the four orientations Info.plist allows; degrees match Android's
    // Display.getRotation() quadrants, so the two booleans are derived the same way.

    private boolean isOrientationLandscape() {
        int rotation = Gdx.input.getRotation();
        return rotation == 90 || rotation == 270;
    }

    private boolean isOrientationUpsideDown() {
        int rotation = Gdx.input.getRotation();
        return rotation == 180 || rotation == 270;
    }

    /**
     * If the magnetometer is uncalibrated the fused heading cannot be trusted; ask the user
     * for the figure-8 sweep, re-shown while the condition persists, with the same grace
     * period and cadence as the Android controller.
     */
    private void maybeWarnCalibration(CMDeviceMotion motion) {
        CMMagneticFieldCalibrationAccuracy accuracy = motion.getMagneticField().getAccuracy();
        boolean unreliable = accuracy == CMMagneticFieldCalibrationAccuracy.Uncalibrated
                || accuracy == CMMagneticFieldCalibrationAccuracy.Low;
        if (!unreliable) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - startMs < WARN_GRACE_MS) {
            return;
        }
        if (now - lastWarnMs < WARN_REFRESH_MS) {
            return;
        }
        lastWarnMs = now;
        mapViewerScreen.toast(PeakNavUtils.s("Compass_calibrate"));
    }
}
