package com.peaknav.utils;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static com.peaknav.compatibility.NativeScreenCallerAndroid.showLocationSettingsDialog;
import static com.peaknav.utils.PreferencesManager.P;

import android.app.Activity;
import android.content.pm.PackageManager;

import androidx.core.app.ActivityCompat;

import com.peaknav.singleton.MapViewerAndroidSingleton;
import com.peaknav.viewer.MapApp;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.views.AndroidLauncher;


public class PeakNavPermissions {
    public static final int LOCATION_REQUEST_CODE = 40;

    public static void checkLocationPermission(AndroidLauncher activity) {
        ActivityCompat.requestPermissions(
                activity,
                new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                LOCATION_REQUEST_CODE);
    }

    public static void handleLocationPermission(Activity activity, int[] grantResults, Runnable callback) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            callback.run();
        } else {
            boolean deniedOnce = P.isLocationPermissionDenied();

            if (deniedOnce &&
                    ActivityCompat.checkSelfPermission(activity, ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, ACCESS_FINE_LOCATION)) {
                showLocationSettingsDialog(activity);
            } else {
                P.setLocationPermissionDenied(true);
            }
        }
    }
}
