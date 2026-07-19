package com.peaknav.views;

import static com.peaknav.compatibility.NativeScreenCallerAndroid.showCameraSettingsDialog;
import static com.peaknav.utils.PeakNavPermissions.LOCATION_REQUEST_CODE;
import static com.peaknav.utils.PeakNavPermissions.handleLocationPermission;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getLoadFactory;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.checkImageGpsAndPrompt;
import static com.peaknav.utils.PeakNavUtils.setBytesAsBackgroundImage;
import static com.peaknav.viewer.controller.MapController.setNumOfCpuCores;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
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
import java.io.InputStream;
import java.util.LinkedList;
import java.util.Queue;


public class AndroidLauncher extends FragmentActivity implements AndroidFragmentApplication.Callbacks {

	public static final int PICK_IMAGE = 324;
	public static final int PICK_GPX = 325;
	public Queue<Runnable> locationPermissionCallbacks = new LinkedList();
	// This variable CANNOT be static, otherwise Android will not deserialize
	// the graphics contents correctly:
	private MapApp mapApp;

	public static final String[] CAMERA_PERMISSION = new String[]{Manifest.permission.CAMERA};
	public static final int CAMERA_REQUEST_CODE = 10;
	public static final int MEDIA_LOCATION_REQUEST_CODE = 41;

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
		} else if (requestCode == MEDIA_LOCATION_REQUEST_CODE) {
			// Open the picker whether or not media-location access was granted. If it was
			// denied, the import will simply warn that the image location cannot be read.
			com.peaknav.compatibility.NativeScreenCallerAndroid nsc =
					(com.peaknav.compatibility.NativeScreenCallerAndroid) getNativeScreenCaller();
			if (nsc != null) {
				nsc.launchGalleryPicker();
			}
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_main);

		// The previous handler silently System.exit(1)'d on ANY uncaught exception on ANY thread,
		// without logging: a transient worker-thread failure during startup closed the app
		// immediately with no trace. Now every crash is written to a log file, main/GL-thread
		// crashes are handed to the platform's own handler (proper crash dialog + system report),
		// and a background worker's exception only kills that worker, not the whole app.
		final Thread.UncaughtExceptionHandler previousHandler =
				Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
			if (e instanceof StopThreadException) {
				return;
			}
			e.printStackTrace();
			try {
				if (getLoadFactory() != null) {
					getLoadFactory().getCrashLogger(e, "crash").logToFile();
				}
			} catch (Throwable ignored) {
				// Logging must never turn a survivable crash into a fatal one.
			}
			boolean fatalThread = thread == Looper.getMainLooper().getThread()
					|| thread.getName().startsWith("GLThread");
			if (fatalThread) {
				if (previousHandler != null) {
					previousHandler.uncaughtException(thread, e);
				} else {
					System.exit(1);
				}
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

		// A photo or GPX may have launched us via the share sheet.
		handleIncomingShare(getIntent());
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		// singleTask: a share that arrives while we're already running comes in here.
		setIntent(intent);
		handleIncomingShare(intent);
	}

	private final Handler shareHandler = new Handler(Looper.getMainLooper());
	private byte[] pendingShareData;
	private boolean pendingShareIsGpx;

	/**
	 * Handle an ACTION_SEND / ACTION_VIEW of an image or GPX. The content is read straight away
	 * (the sender may revoke the URI grant soon), then applied once the map is up.
	 */
	private void handleIncomingShare(Intent intent) {
		if (intent == null) {
			return;
		}
		String action = intent.getAction();
		Uri uri = null;
		if (Intent.ACTION_SEND.equals(action)) {
			uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
		} else if (Intent.ACTION_VIEW.equals(action)) {
			uri = intent.getData();
		}
		if (uri == null) {
			return;
		}
		byte[] data = readShareBytes(uri);
		if (data == null || data.length == 0) {
			return;
		}
		pendingShareData = data;
		// The bytes themselves are the reliable signal: JPEG/PNG magic means image, anything else
		// (GPX is XML text) is treated as a track.
		pendingShareIsGpx = !looksLikeImage(data);
		processPendingShare(0);
	}

	/** Applies the pending share once the map controller and its screen exist, retrying briefly. */
	private void processPendingShare(final int attempt) {
		if (pendingShareData == null) {
			return;
		}
		if (getC() == null || getC().getMapViewerScreen() == null) {
			if (attempt < 60) {
				shareHandler.postDelayed(() -> processPendingShare(attempt + 1), 250);
			}
			return;
		}
		byte[] data = pendingShareData;
		boolean isGpx = pendingShareIsGpx;
		pendingShareData = null;
		try {
			if (isGpx) {
				getC().gpxManager.loadFromXml(new String(data, java.nio.charset.StandardCharsets.UTF_8));
			} else {
				setBytesAsBackgroundImage(data);
				checkImageGpsAndPrompt(data);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static boolean looksLikeImage(byte[] d) {
		if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
			return true; // JPEG
		}
		return d.length >= 4 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'; // PNG
	}

	private byte[] readShareBytes(Uri uri) {
		Uri readUri = uri;
		// On Android 10+ ask for the un-redacted original so an image's GPS EXIF survives; harmless
		// (and reversible) if the URI isn't a MediaStore item.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && "content".equals(uri.getScheme())) {
			try {
				readUri = MediaStore.setRequireOriginal(uri);
			} catch (Exception ignored) {
				readUri = uri;
			}
		}
		byte[] bytes = readAllBytes(readUri);
		if (bytes == null && readUri != uri) {
			bytes = readAllBytes(uri); // fall back to the plain URI
		}
		return bytes;
	}

	private byte[] readAllBytes(Uri uri) {
		try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
			if (inputStream == null) {
				return null;
			}
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[16384];
			int numRead;
			while ((numRead = inputStream.read(chunk, 0, chunk.length)) != -1) {
				buffer.write(chunk, 0, numRead);
			}
			return buffer.toByteArray();
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		if (requestCode == PICK_IMAGE && resultCode == RESULT_OK
				&& data != null && data.getData() != null) {

			// Android 10+ redacts the GPS EXIF from the stream it hands back. Ask for the
			// original bytes so the location survives; this needs ACCESS_MEDIA_LOCATION and
			// only works for MediaStore uris, so fall back to the plain uri otherwise.
			Uri imageUri = data.getData();
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				try {
					imageUri = MediaStore.setRequireOriginal(imageUri);
				} catch (Exception ignored) {
					imageUri = data.getData();
				}
			}

			// A deleted file, revoked permission, or corrupt image must fail
			// gracefully here rather than crash the app.
			try (InputStream inputStream = getContentResolver().openInputStream(imageUri)) {
				if (inputStream == null) {
					return;
				}
				ByteArrayOutputStream buffer = new ByteArrayOutputStream();

				int numRead;
				byte[] d = new byte[16384];

				while ((numRead = inputStream.read(d, 0, d.length)) != -1) {
					buffer.write(d, 0, numRead);
				}

				byte[] b = buffer.toByteArray();

				setBytesAsBackgroundImage(b);
				checkImageGpsAndPrompt(b);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		if (requestCode == PICK_GPX && resultCode == RESULT_OK
				&& data != null && data.getData() != null) {
			try (InputStream inputStream = getContentResolver().openInputStream(data.getData())) {
				if (inputStream == null) {
					return;
				}
				ByteArrayOutputStream buffer = new ByteArrayOutputStream();
				int numRead;
				byte[] d = new byte[16384];
				while ((numRead = inputStream.read(d, 0, d.length)) != -1) {
					buffer.write(d, 0, numRead);
				}
				String xml = new String(buffer.toByteArray(),
						java.nio.charset.StandardCharsets.UTF_8);
				getC().gpxManager.loadFromXml(xml);
			} catch (Exception e) {
				e.printStackTrace();
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
