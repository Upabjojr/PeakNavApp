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

import com.peaknav.singleton.MapViewerAndroidSingleton;
import com.peaknav.viewer.screens.MapViewerScreen;

public class OrientationPointerController implements SensorEventListener {

    private final SensorManager sensorManager;
    private Context context;

    private final MapViewerScreen mapViewerScreen;

    final float smoothingAccAlpha = 0.7f;
    final int smoothingAccExp = 3;

    final float smoothingMagAlpha = 0.0001f;
    final int smoothingMagExp = 5;

    final float smoothingRotationMatrixAlpha = 0.5f;
    final int smoothingRotationMatrixExp = 3;

    private final float[] accelerometerReadingCurrent = new float[3];
    private final float[] magnetometerReadingCurrent = new float[3];

    private final float[] accelerometerReadingPrev = new float[3];
    private final float[] magnetometerReadingPrev = new float[3];

    private final float[] rotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];

    private final float[] rotationMatrixPrev = new float[9];

    public OrientationPointerController(Context context) {
        this.context = context;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        mapViewerScreen = MapViewerAndroidSingleton.getViewerInstance();
    }

    public void start() {
        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer,
                    SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI);
        }
        Sensor magneticField = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (magneticField != null) {
            sensorManager.registerListener(this, magneticField,
                    SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI);
        }

    }

    public void stop() {
        sensorManager.unregisterListener(this);
    }

    private void getDiff(float[] prev, float[] current, float smoothingAlpha, int smoothingExp) {
        for (int i = 0; i < current.length; i++) {
            float difference = current[i] - prev[i];
            float posDiff = Math.abs(difference);
            float cor = (float) (smoothingAlpha * ((difference > 0)? 1.f : -1.f) * Math.pow(posDiff, smoothingExp));
            cor = (cor > posDiff || cor < -posDiff)? difference : cor;
            current[i] = prev[i] + cor;
        }
    }

    private void updateValues(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(accelerometerReadingCurrent, 0, accelerometerReadingPrev,
                    0, accelerometerReadingCurrent.length);
            System.arraycopy(event.values, 0, accelerometerReadingCurrent,
                    0, accelerometerReadingCurrent.length);
            getDiff(accelerometerReadingPrev, accelerometerReadingCurrent, smoothingAccAlpha, smoothingAccExp);
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(magnetometerReadingCurrent, 0, magnetometerReadingPrev,
                    0, magnetometerReadingCurrent.length);
            System.arraycopy(event.values, 0, magnetometerReadingCurrent,
                    0, magnetometerReadingCurrent.length);
            getDiff(magnetometerReadingPrev, magnetometerReadingCurrent, smoothingMagAlpha, smoothingMagExp);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        updateValues(event);

        SensorManager.getRotationMatrix(rotationMatrix, null,
                accelerometerReadingCurrent, magnetometerReadingCurrent);

        // TODO: remove?
        SensorManager.getOrientation(rotationMatrix, orientationAngles);

        getDiff(rotationMatrixPrev, rotationMatrix, smoothingRotationMatrixAlpha, smoothingRotationMatrixExp);

        System.out.println("onSensorChanged finished.");

        boolean landscape = isOrientationLandscape();
        boolean upsideDown = isOrientationUpsideDown();
        mapViewerScreen.pointCameraForGyroscope(
                -rotationMatrix[2], -rotationMatrix[5], -rotationMatrix[8],
                rotationMatrix[0], rotationMatrix[3], rotationMatrix[6],
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
