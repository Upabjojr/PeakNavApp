package com.peaknav.utils;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
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

    /**
     * Asks for location access. Both permissions, as Android 12 and later want: asked for the
     * precise one alone, they ignore the request, and asked for both they offer the choice of
     * "precise" or "approximate" - either of which will do.
     */
    public static void checkLocationPermission(AndroidLauncher activity) {
        ActivityCompat.requestPermissions(
                activity,
                new String[]{ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION},
                LOCATION_REQUEST_CODE);
    }

    /** Whether location access is on, precise or approximate. */
    public static boolean isLocationGranted(Activity activity) {
        return ActivityCompat.checkSelfPermission(activity, ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(activity, ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * The answer to {@link #checkLocationPermission}: {@code callback} if access was given;
     * otherwise {@code onDenied}, and unless that has answered the refusal itself (it returns
     * true when it put up a dialog of its own), the usual path towards the settings.
     */
    public static void handleLocationPermission(Activity activity, Runnable callback, Denied onDenied) {
        if (isLocationGranted(activity)) {
            callback.run();
        } else {
            if (onDenied.denied()) {
                P.setLocationPermissionDenied(true);
                return;
            }
            boolean deniedOnce = P.isLocationPermissionDenied();

            if (deniedOnce &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, ACCESS_FINE_LOCATION)) {
                showLocationSettingsDialog(activity);
            } else {
                P.setLocationPermissionDenied(true);
            }
        }
    }

    /** What is done when location access is refused; true if it has told the user itself. */
    public interface Denied {
        boolean denied();
    }
}
