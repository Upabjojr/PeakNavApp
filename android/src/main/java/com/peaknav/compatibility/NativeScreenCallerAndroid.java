package com.peaknav.compatibility;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static com.peaknav.utils.PeakNavPermissions.checkLocationPermission;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.views.AndroidLauncher.CAMERA_PERMISSION;
import static com.peaknav.views.AndroidLauncher.CAMERA_REQUEST_CODE;
import static com.peaknav.views.AndroidLauncher.PICK_IMAGE;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.peaknav.R;
import com.peaknav.controller.OrientationPointerController;
import com.peaknav.gesture.OrientationPointerListener;
import com.peaknav.ui.CurrentLocationCallback;
import com.peaknav.ui.CurrentLocationListener;
import com.peaknav.utils.AndroidUI;
import com.peaknav.viewer.GoToDownloadDialog;
import com.peaknav.viewer.MapViewerSingleton;
import com.peaknav.views.AndroidLauncher;
import com.peaknav.views.AppInfoAndroidView;
import com.peaknav.views.AppTutorialAndroidView;
import com.peaknav.views.CameraPictureView;
import com.peaknav.views.MapDataDownloadChooser;
import com.peaknav.views.SearchMenu;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public class NativeScreenCallerAndroid extends NativeScreenCaller {

    private static AlertDialog locationSettingsDialog = null;
    private final Context context;
    private final AndroidLauncher mainActivity;

    public NativeScreenCallerAndroid(Context context, AndroidLauncher mainActivity) {
        this.context = context;
        this.mainActivity = mainActivity;
    }

    private void startActivityAndPause(Intent intent) {
        MapViewerSingleton.getAppInstance().pause();
        context.startActivity(intent);
    }

    private void startActivityForResultAndPause(Intent intent, int requestCode) {
        MapViewerSingleton.getAppInstance().pause();
        mainActivity.startActivityForResult(intent, requestCode);
    }

    @Override
    public void ensureLocationPermissions() {
        checkLocationPermission(mainActivity);
    }

    @Override
    public void comingSoon() {
        mainActivity.runOnUiThread(() ->
            AndroidUI.alertMessage(s("Coming_soon"), this.mainActivity, false)
        );
    }

    @Override
    public void alertMessage(String message) {
        mainActivity.runOnUiThread(() -> {
            AlertDialog.Builder alertBuilder = new AlertDialog.Builder(mainActivity);
            alertBuilder.setMessage(message)
                    .setPositiveButton(s("OK"), (dialogInterface, i) -> {})
                    .setCancelable(false);
            AlertDialog alert = alertBuilder.create();
            alert.show();
        });
    }

    @Override
    public long getTotalMemory() {
        ActivityManager activityManager = (ActivityManager) mainActivity.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);

        return memoryInfo.totalMem;
    }

    public void popStack() {
        FrameLayout overlay = mainActivity.findViewById(R.id.ui_overlay);
        overlay.setVisibility(View.INVISIBLE);

        FrameLayout mapOverlay = mainActivity.findViewById(R.id.map_container);
        mapOverlay.setVisibility(View.VISIBLE);

        mainActivity.getSupportFragmentManager().popBackStack();

        MapViewerSingleton.getAppInstance().resume();
    }

    @Override
    public void getCallOnUIThread(Runnable runnable) {
        mainActivity.runOnUiThread(runnable);
    }

    @Override
    public void openMapDataDownloadChooser(double lat, double lon, boolean goToAfterDownload) {
        runOnUiThread(() -> {
            MapDataDownloadChooser fragment = new MapDataDownloadChooser(lat, lon, goToAfterDownload, false);
            openFragmentWithTransaction(fragment, "map_data_download_chooser");
        });
    }

    @Override
    public void openMapDataDownloadChooserWizard() {
        runOnUiThread(() -> {
            MapDataDownloadChooser fragment = new MapDataDownloadChooser(0, 0, false, true);
            openFragmentWithTransaction(fragment, "map_data_download_chooser");
        });
    }

    private void openFragmentWithTransaction(Fragment fragment, String name) {
        FragmentManager fm = mainActivity.getSupportFragmentManager();

        FrameLayout overlay = mainActivity.findViewById(R.id.ui_overlay);
        overlay.setVisibility(View.VISIBLE);


        FrameLayout mapOverlay = mainActivity.findViewById(R.id.map_container);
        mapOverlay.setVisibility(View.INVISIBLE);

        fm.beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .add(R.id.ui_overlay, fragment, name)
                .addToBackStack(null)
                .commit();

        MapViewerSingleton.getAppInstance().pause();
    }

    @Override
    public void openScreenSearchLocation(com.peaknav.ui.ClickCallback callback) {
        runOnUiThread(() -> {
            SearchMenu fragment = new SearchMenu();
            openFragmentWithTransaction(fragment, "search_menu");
        });
    }

    private boolean checkCameraHardware() {
        return mainActivity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(
                mainActivity,
                Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private void  requestCameraPermission() {
        ActivityCompat.requestPermissions(
                mainActivity,
                CAMERA_PERMISSION,
                CAMERA_REQUEST_CODE
        );
    }

    private void openCameraPictureViewLocal() {
        runOnUiThread(() -> {
            CameraPictureView fragment = new CameraPictureView();
            openFragmentWithTransaction(fragment, "camera_picture_view");
        });
    }

    public void openCameraPictureViewWithPermissionCheck() {
        if (!checkCameraHardware()) {
            return;
        }
        if (hasCameraPermission()) {
            openCameraPictureViewLocal();
        } else {
            requestCameraPermission();
        }
    }

    @Override
    public void openCameraPictureView() {
        openCameraPictureViewWithPermissionCheck();
    }

    @Override
    public void openGalleryPick() {
        Intent intent = new Intent();
        intent.setType("image/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        startActivityForResultAndPause(Intent.createChooser(intent, "Select Picture"), PICK_IMAGE);
    }

    @Override
    public void openAppInfoScreen() {
        runOnUiThread(() -> {
            AppInfoAndroidView fragment = new AppInfoAndroidView();
            openFragmentWithTransaction(fragment, "app_info");
        });
    }

    @Override
    public void openAppTutorial() {
        runOnUiThread(() -> {
            AppTutorialAndroidView fragment = new AppTutorialAndroidView();
            openFragmentWithTransaction(fragment, "app_tutorial");
        });
    }

    private OrientationPointerController orientationPointerController;
    private OrientationPointerListener orientationPointerListener;

    @Override
    public OrientationPointerListener getOrientationPointerListener() {
        if (orientationPointerListener == null) {
            orientationPointerController = new OrientationPointerController(context);

            orientationPointerListener = new OrientationPointerListener() {

                /*
                private MoveCameraAction getMoveCameraAction() {
                    return MapViewerSingleton.getViewerInstance().moveCameraAction;
                }
                 */

                @Override
                public void start() {
                    // getMoveCameraAction().setContinuousTracking(true);
                    orientationPointerController.start();
                }

                @Override
                public void stop() {
                    // getMoveCameraAction().setContinuousTracking(false);
                    orientationPointerController.stop();
                }
            };
        }
        return orientationPointerListener;
    }

    private volatile LocationManager locationManager;

    private void ensureLocationManager() {
        if (locationManager == null) {
            synchronized (this) {
                if (locationManager == null) {
                    locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                }
            }
        }
    }

    public void promptIfLocationNotEnabled(Context locContext) {
        ensureLocationManager();

        boolean has_gps_loc = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean has_network_loc = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        if(!(has_gps_loc || has_network_loc)) {
            new AlertDialog.Builder((locContext == null)? mainActivity : locContext)
                    .setMessage(s("Location_not_enabled"))
                    .setPositiveButton(
                            s("ask_open_location_settings"),
                            (paramDialogInterface, paramInt) -> {
                                Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                context.startActivity(intent);
                            })
                    .setNegativeButton(s("Cancel"), null)
                    .show();
        }
    }

    @Override
    public void openMapDataDownloadChooser() {
        super.openMapDataDownloadChooser();
    }

    @Override
    public CurrentLocationListener getCurrentLocationListener() {
        return getCurrentLocationListener(null);
    }

    public static void showLocationSettingsDialog(Context context) {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(context);
        dialogBuilder.setTitle(s("Location_permission_missing"));
        dialogBuilder.setMessage(s("Location_permissions_in_device_settings_are_advised_to_use_app"));
        dialogBuilder.setPositiveButton(s("Open_settings"), (dialog, which) -> {
            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            context.startActivity(intent);
        });
        dialogBuilder.setNegativeButton(s("Cancel"), (dialog, which) -> dialog.dismiss());
        locationSettingsDialog = dialogBuilder.show();
    }

    public static void showCameraSettingsDialog(Context context) {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(context);
        dialogBuilder.setTitle(s("Camera_permission_missing"));
        dialogBuilder.setMessage(s("Camera_permission_needed_to_take_pictures"));
        dialogBuilder.setPositiveButton(s("Open_settings"), (dialog, which) -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", context.getPackageName(), null);
            intent.setData(uri);
            context.startActivity(intent);
        });
        dialogBuilder.setNegativeButton(s("Cancel"), (dialog, which) -> dialog.dismiss());
        dialogBuilder.show();
    }

    public CurrentLocationListener getCurrentLocationListener(Activity locContext) {
        ensureLocationManager();

        CurrentLocationListener currentLocationListener = new CurrentLocationListener() {

            @Override
            public void getCurrentLocation(CurrentLocationCallback currentLocationCallback) {
                if (ActivityCompat.checkSelfPermission(
                        context, ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                        && ActivityCompat.checkSelfPermission(context, ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                    return;
                }

                promptIfLocationNotEnabled(locContext);

                if (locationSettingsDialog != null) {
                    locationSettingsDialog.hide();
                }

                LocationListener locationListener = new LocationListener() {
                    @Override
                    public void onLocationChanged(@NonNull Location location) {
                        float lon = (float) location.getLongitude();
                        float lat = (float) location.getLatitude();
                        currentLocationCallback.setCurrentLocation(lon, lat);
                        if (location.getProvider().equals(LocationManager.GPS_PROVIDER)) {
                            locationManager.removeUpdates(this);
                        }
                    }

                    @Override
                    public void onFlushComplete(int requestCode) {
                    }

                    @Override
                    public void onLocationChanged(@NonNull List<Location> locations) {
                        final int size = locations.size();
                        for (int i = 0; i < size; i++) {
                            onLocationChanged(locations.get(i));
                        }
                    }

                    @Override
                    public void onProviderEnabled(@NonNull String provider) {
                    }

                    @Override
                    public void onProviderDisabled(@NonNull String provider) {
                    }

                    @Override
                    public void onStatusChanged(String provider, int status, Bundle extras) {
                    }
                };
                Location lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (lastKnownLocation == null) {
                    lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }
                if (lastKnownLocation != null) {
                    locationListener.onLocationChanged(lastKnownLocation);
                }
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, locationListener, null);
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, locationListener, null);
            }

        };
        return currentLocationListener;
    }

    @Override
    public void askForDownloadScreen(double lat, double lon) {
        runOnUiThread(() -> {
            GoToDownloadDialog fragment = new GoToDownloadDialog((float) lat, (float) lon);
            openFragmentWithTransaction(fragment, "go_to_download_dialog");
        });
    }

    @Override
    public void makeToast(String message) {
        mainActivity.runOnUiThread(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }

    @Override
    public void shareSnapshot(Pixmap pixmap) {
        PixmapIO.PNG writer = new PixmapIO.PNG(pixmap.getWidth() * pixmap.getHeight());
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            writer.write(outputStream, pixmap);
            writer.dispose();
            pixmap.dispose();
            byte[] bytesPng = outputStream.toByteArray();
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytesPng, 0, bytesPng.length);

            Intent intentShare = new Intent(Intent.ACTION_SEND);
            intentShare.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intentShare.setType("image/jpeg");

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.TITLE, "title");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values);

            OutputStream bytes = context.getContentResolver().openOutputStream(uri);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bytes);

            intentShare.putExtra(Intent.EXTRA_STREAM, uri);
            Intent intentChooser = Intent.createChooser(intentShare, "Share This Image");
            intentChooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndPause(intentChooser);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void runOnUiThread(Runnable action) {
        mainActivity.runOnUiThread(action);
    }
}
