package com.peaknav.views;

import static com.peaknav.compatibility.NativeScreenCallerAndroid.showCameraSettingsDialog;
import static com.peaknav.utils.PeakNavPermissions.LOCATION_REQUEST_CODE;
import static com.peaknav.utils.PeakNavPermissions.handleLocationPermission;
import static com.peaknav.utils.PeakNavUtils.getLoadFactory;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.setBytesAsBackgroundImage;
import static com.peaknav.viewer.controller.MapController.setNumOfCpuCores;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;

import com.badlogic.gdx.backends.android.AndroidFragmentApplication;
import com.badlogic.gdx.maps.MapLayers;
import com.peaknav.R;
import com.peaknav.utils.AndroidUI;
import com.peaknav.singleton.MapViewerAndroidSingleton;
import com.peaknav.utils.StopThreadException;
import com.peaknav.viewer.MapApp;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.Queue;


public class AndroidLauncher extends FragmentActivity implements AndroidFragmentApplication.Callbacks {

	public static final int PICK_IMAGE = 324;
	public Queue<Runnable> locationPermissionCallbacks = new LinkedList();
	// This variable CANNOT be static, otherwise Android will not deserialize
	// the graphics contents correctly:
	private MapApp mapApp;

	public static final String[] CAMERA_PERMISSION = new String[]{Manifest.permission.CAMERA};
	public static final int CAMERA_REQUEST_CODE = 10;

	@Override
	public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);

		if (requestCode == CAMERA_REQUEST_CODE) {
			if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				getNativeScreenCaller().openCameraPictureView();
			} else {
				if (ActivityCompat.checkSelfPermission(
						this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED &&
					!ActivityCompat.shouldShowRequestPermissionRationale(
							this, Manifest.permission.CAMERA)) {
					showCameraSettingsDialog(this);
				}
			}
		} else if (requestCode == LOCATION_REQUEST_CODE) {
			handleLocationPermission(this, grantResults, () -> {
				while (!locationPermissionCallbacks.isEmpty()) {
					Runnable runnable = locationPermissionCallbacks.poll();
					runnable.run();
				}
			});
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_main);

		Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler()
		{

			@Override
			public void uncaughtException(Thread thread, Throwable e) {
				if (e instanceof StopThreadException) {
					return;
				}
				try {
					handleUncaughtException(thread, e);
				} catch (IOException ex) {
					ex.printStackTrace();
				}
			}

			public void handleUncaughtException(Thread thread, Throwable e) throws IOException {
				if (e instanceof StopThreadException) {
					return;
				}
				// CrashLogger crashLogger = getLoadFactory().getCrashLogger(e, "crash");
				System.exit(1);
			}
		});

		setNumOfCpuCores(Runtime.getRuntime().availableProcessors());

		AndroidUI.setInstance(this);

		MapViewerAndroidSingleton.initializeAndroidLoadFactory(getApplicationContext(),this);
		mapApp = new MapApp(MapViewerAndroidSingleton.getLoadFactory());
		MapViewerAndroidSingleton.setAppInstance(mapApp);

		MapViewerAndroidSingleton.getAppInstance();

		setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR);

		FrameLayout mapContainer = findViewById(R.id.map_container);

		AndroidMainFragment fragment = new AndroidMainFragment(mapApp);

		/*
		AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
		config.useAccelerometer = true;
		config.useCompass = true;

		mapContainer.addView(fragment.initializeForView(mapApp, config));
		 */

		FragmentTransaction trans = getSupportFragmentManager().beginTransaction();
		trans.replace(R.id.map_container, fragment);
		trans.commit();

	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		if (requestCode == PICK_IMAGE && resultCode == RESULT_OK) {
			try {
				if (data != null) {
					InputStream inputStream = getContentResolver().openInputStream(data.getData());

					ByteArrayOutputStream buffer = new ByteArrayOutputStream();

					int numRead;
					byte[] d = new byte[16384];

					while ((numRead = inputStream.read(d, 0, d.length)) != -1) {
						buffer.write(d, 0, numRead);
					}

					byte[] b = buffer.toByteArray();

					setBytesAsBackgroundImage(b);
				}
			} catch (FileNotFoundException e) {
				throw new RuntimeException(e);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	@Override
	public void exit() {

	}

	@Override
	public void onPointerCaptureChanged(boolean hasCapture) {
		super.onPointerCaptureChanged(hasCapture);
	}
}
