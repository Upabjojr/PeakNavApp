package com.peaknav.compatibility;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static com.peaknav.utils.PeakNavPermissions.checkLocationPermission;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.views.AndroidLauncher.CAMERA_PERMISSION;
import static com.peaknav.views.AndroidLauncher.CAMERA_REQUEST_CODE;
import static com.peaknav.views.AndroidLauncher.MEDIA_LOCATION_REQUEST_CODE;
import static com.peaknav.views.AndroidLauncher.PICK_GPX;
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
import android.os.Build;
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
import com.peaknav.ui.TextFieldsCallback;
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
    public void promptForTextFields(
            String title, String message, String[] labels, String[] initialValues, TextFieldsCallback callback) {
        mainActivity.runOnUiThread(() -> {
            android.widget.LinearLayout layout = new android.widget.LinearLayout(mainActivity);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            int padding = Math.round(16 * mainActivity.getResources().getDisplayMetrics().density);
            layout.setPadding(padding, padding, padding, padding);

            if (message != null && !message.isEmpty()) {
                android.widget.TextView help = new android.widget.TextView(mainActivity);
                help.setText(message);
                help.setPadding(0, 0, 0, padding);
                layout.addView(help);
            }

            android.widget.EditText[] fields = new android.widget.EditText[labels.length];
            for (int i = 0; i < labels.length; i++) {
                android.widget.TextView label = new android.widget.TextView(mainActivity);
                label.setText(labels[i]);
                layout.addView(label);

                android.widget.EditText field = new android.widget.EditText(mainActivity);
                field.setSingleLine(true);
                if (initialValues != null && i < initialValues.length && initialValues[i] != null) {
                    field.setText(initialValues[i]);
                }
                fields[i] = field;
                layout.addView(field);
            }

            android.widget.ScrollView scrollView = new android.widget.ScrollView(mainActivity);
            scrollView.addView(layout);

            AlertDialog.Builder builder = new AlertDialog.Builder(mainActivity);
            builder.setTitle(title)
                    .setView(scrollView)
                    .setPositiveButton(s("OK"), (dialogInterface, i) -> {
                        String[] values = new String[fields.length];
                        for (int f = 0; f < fields.length; f++) {
                            values[f] = fields[f].getText().toString();
                        }
                        callback.onEntered(values);
                    })
                    .setNegativeButton(s("Cancel"), (dialogInterface, i) -> callback.onCancelled())
                    .setOnCancelListener(dialogInterface -> callback.onCancelled());
            builder.create().show();
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
        // On Android 10+ the OS strips GPS EXIF from imported images unless the app holds
        // ACCESS_MEDIA_LOCATION. Ask for it first, then open the picker either way — the
        // import warns if the location turns out to be unreadable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasMediaLocationPermission()) {
            ActivityCompat.requestPermissions(
                    mainActivity,
                    new String[]{Manifest.permission.ACCESS_MEDIA_LOCATION},
                    MEDIA_LOCATION_REQUEST_CODE);
        } else {
            launchGalleryPicker();
        }
    }

    @Override
    public void pickGpxFile() {
        // GPX has no single agreed MIME type, so accept any openable document and let the user
        // pick the .gpx. AndroidLauncher.onActivityResult reads the stream and hands it to the
        // GpxManager.
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResultAndPause(Intent.createChooser(intent, "Select GPX"), PICK_GPX);
    }

    /** Launches the image chooser. Public so the launcher can call it after the permission prompt. */
    public void launchGalleryPicker() {
        Intent intent = new Intent();
        intent.setType("image/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        startActivityForResultAndPause(Intent.createChooser(intent, "Select Picture"), PICK_IMAGE);
    }

    private boolean hasMediaLocationPermission() {
        return ContextCompat.checkSelfPermission(
                mainActivity,
                Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void warnCannotReadImageLocation() {
        mainActivity.runOnUiThread(() -> {
            AlertDialog.Builder alertBuilder = new AlertDialog.Builder(mainActivity);
            alertBuilder.setTitle(s("Image_location_missing_title"))
                    .setMessage(s("Image_location_missing"))
                    .setPositiveButton(android.R.string.ok, null);
            alertBuilder.create().show();
        });
    }

    @Override
    public void promptGoToImageLocation(double lat, double lon) {
        mainActivity.runOnUiThread(() -> {
            AlertDialog.Builder alertBuilder = new AlertDialog.Builder(mainActivity);
            alertBuilder.setTitle(s("Image_location_found"))
                    .setMessage(s("Go_to_image_location_prompt"))
                    .setPositiveButton(s("Yes"),
                            (dialogInterface, i) -> getC().L.setCurrentTargetCoords(lat, lon))
                    .setNegativeButton(s("No"), null);
            alertBuilder.create().show();
        });
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
    public void chooseSkyTime() {
        mainActivity.runOnUiThread(() -> {
            com.peaknav.sky.SkyModel sky = com.peaknav.utils.PeakNavUtils.getC().skyModel;
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(sky.currentTimeMillis());
            // Force the OS Material dialog theme so the pickers render as the modern calendar and
            // clock-face widgets, not the spinner "buttons" the app's GdxTheme falls back to. Follow
            // the device's dark/light setting on Android 10+ (Q); a light dialog before that.
            int pickerTheme = (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
                    && (mainActivity.getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES)
                    ? android.R.style.Theme_Material_Dialog
                    : android.R.style.Theme_Material_Light_Dialog;
            android.app.DatePickerDialog dateDlg = new android.app.DatePickerDialog(mainActivity, pickerTheme,
                    (view, year, month, day) -> {
                        android.app.TimePickerDialog timeDlg = new android.app.TimePickerDialog(mainActivity,
                                pickerTheme,
                                (tv, hour, minute) -> {
                                    java.util.Calendar c = java.util.Calendar.getInstance();
                                    c.set(year, month, day, hour, minute, 0);
                                    c.set(java.util.Calendar.MILLISECOND, 0);
                                    sky.setCustomTimeMillis(c.getTimeInMillis());
                                },
                                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true);
                        timeDlg.show();
                    },
                    cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH),
                    cal.get(java.util.Calendar.DAY_OF_MONTH));
            dateDlg.setButton(android.app.DatePickerDialog.BUTTON_NEUTRAL,
                    s("Sky_time_device_clock"), (dialog, which) -> sky.clearCustomTime());
            dateDlg.show();
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
