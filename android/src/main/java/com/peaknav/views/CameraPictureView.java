package com.peaknav.views;

import static android.app.Activity.RESULT_OK;
import static androidx.core.content.ContextCompat.getSystemService;
import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PeakNavUtils.s;
import static com.peaknav.utils.PeakNavUtils.setBytesAsBackgroundImage;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.hardware.camera2.CameraDevice;
import android.media.ImageReader;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;

import com.badlogic.gdx.graphics.Pixmap;
import com.peaknav.R;
import com.peaknav.compatibility.NativeScreenCallerAndroid;
import com.peaknav.viewer.MapViewerSingleton;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class CameraPictureView extends Fragment {

    private static final int PICK_IMAGE = 159;
    /**
     * The longest edge a photo may have, in pixels. Twice the width of the widest phone screen,
     * and four times what the skyline match looks at, which is as much detail as anything here
     * can use; see the note in surfaceCreated for what the size is really guarding against.
     */
    private static final int MAX_PICTURE_EDGE = 2048;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private ImageReader imageReader;
    private View view;

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                // The map's "Loading..." screen while the worker reads and decodes.
                if (getC() != null && getC().getMapViewerScreen() != null) {
                    getC().getMapViewerScreen().setPhotoLoading(true);
                }
                getC().submitExecutorGeneric(() -> {
                    FragmentActivity activity = getActivity();
                    if (activity == null) {
                        if (getC().getMapViewerScreen() != null) {
                            getC().getMapViewerScreen().setPhotoLoading(false);
                        }
                        return;
                    }
                    // Any failure here (detached fragment, unreadable URI, corrupt
                    // image) must not escape: an uncaught exception on this worker
                    // thread would trip the app's global handler and System.exit.
                    try (InputStream inputStream =
                                 activity.getContentResolver().openInputStream(data.getData())) {
                        if (inputStream == null) {
                            return;
                        }
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

                        int numRead;
                        byte[] d = new byte[16384];

                        while ((numRead = inputStream.read(d, 0, d.length)) != -1) {
                            buffer.write(d, 0, numRead);
                        }

                        setBytesAsBackgroundImage(buffer.toByteArray());
                        com.peaknav.viewer.PhotoSkylineAligner.photoTakenHere();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
            finish();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // private TextureView textureView;
    private CameraDevice cameraDevice;
    private Handler handler;
    private Camera camera;
    /** Set by the first tap of the shutter, so the second one is ignored rather than obeyed. */
    private boolean pictureRequested = false;
    private int w = 640, h = 480;

    public CameraPictureView() {
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        view = inflater.inflate(R.layout.fragment_camera_picture_view, container, false);


        if (getActivity() == null || ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finish();
            return view;
        }

        handler = new Handler();

/*
        textureView = view.findViewById(R.id.texture_view);
        textureView.setSurfaceTextureListener(
                new TextureView.SurfaceTextureListener() {
                    @Override
                    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                        //
                    }

                    @Override
                    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {

                    }

                    @Override
                    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                        return false;
                    }

                    @Override
                    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {

                    }
                }
        );
        */

        surfaceView = view.findViewById(R.id.surface_view);

        DisplayMetrics displayMetrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int height = displayMetrics.heightPixels;
        int width = displayMetrics.widthPixels;

        ViewGroup.LayoutParams layoutParams = surfaceView.getLayoutParams();
        layoutParams.width = (int) Math.floor(640./480.*height);
        layoutParams.height = height;
        surfaceView.setLayoutParams(layoutParams);

        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                camera = Camera.open();
                if (camera == null) {
                    // Camera unavailable or in use by another app.
                    finish();
                    return;
                }
                Camera.Parameters param = camera.getParameters();
                // The size of the photo has to come from the list of photo sizes. This used to
                // read getSupportedPreviewSizes() instead, and on most phones the two lists
                // overlap enough that the largest preview size is a legal photo size as well -
                // so the mistake stayed invisible here. On a phone where it is not, the driver
                // accepts the parameters and then refuses to take the picture, and takePicture
                // throws "takePicture failed" from native code. That crash was reported from
                // the field in August 2026.
                //
                // Of those sizes the biggest is not wanted either. The photo is decoded whole
                // and handed to the GL side as one texture, so a 50 Mpx sensor would ask for a
                // 8160x6120 texture - 200 MB of pixels, above the largest texture a good many
                // phones will make at all. Nothing needs that: BackgroundPicManager draws the
                // picture scaled to the screen, and PhotoSkylineAligner reduces it to 480 px
                // wide before matching it. The bytes are kept only for their Exif tags. So the
                // size is capped well below what the sensor can do, which also keeps a photo
                // costing about what it cost when this code was reading the preview sizes.
                List<Camera.Size> pictureSizes = param.getSupportedPictureSizes();
                if (pictureSizes != null && !pictureSizes.isEmpty()) {
                    int maxWidth = 0;
                    for (Camera.Size size : pictureSizes) {
                        if (size.width > MAX_PICTURE_EDGE || size.height > MAX_PICTURE_EDGE)
                            continue;
                        if (size.width <= maxWidth)
                            continue;
                        w = size.width;
                        maxWidth = w;
                        h = size.height;
                    }
                    if (maxWidth == 0) {
                        // Every size offered is above the ceiling: take the smallest of them,
                        // which is the closest thing to a size this phone can be asked for.
                        Camera.Size smallest = pictureSizes.get(0);
                        for (Camera.Size size : pictureSizes) {
                            if (size.width < smallest.width) {
                                smallest = size;
                            }
                        }
                        w = smallest.width;
                        h = smallest.height;
                    }
                    param.setPictureSize(w, h);
                }
                try {
                    camera.setParameters(param);
                } catch (RuntimeException refused) {
                    // A driver may reject a size it listed itself. Its own default is always
                    // one it can honour, so the picture is worth taking at whatever size that
                    // turns out to be, rather than not at all.
                    refused.printStackTrace();
                }

                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(0, info);
                Display display = ((WindowManager) getActivity().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
                int rotation = (info.orientation - 90*display.getRotation() + 360) % 360;
                camera.setDisplayOrientation(rotation);

                try {
                    camera.setPreviewDisplay(surfaceHolder);
                    camera.startPreview();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                // The surface outlives the camera: these two run whether or not Camera.open()
                // above gave us one, and surfaceDestroyed runs again on the way out.
                if (camera == null) {
                    return;
                }
                camera.stopPreview();
                try {
                    camera.setPreviewDisplay(holder);
                    camera.startPreview();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                if (camera == null) {
                    return;
                }
                camera.stopPreview();
                camera.release();
                camera = null;
            }
        });

        // deprecated setting, but required on Android versions prior to 3.0:
        // surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        /*
        CameraManager cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(id);
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }
                Size[] previewSize = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
                int width = 640;
                int height = 480;
                if (previewSize != null && 0 < previewSize.length) {
                    width = previewSize[0].getWidth();
                    height = previewSize[0].getHeight();
                }
                imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
                // imageReader.getSurface();
                imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(ImageReader reader) {
                        Image image = reader.acquireLatestImage();
                        surfaceView.
                    }
                }, handler);

                cameraManager.openCamera(id, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice camera) {
                        cameraDevice = camera;
                        Vector<Surface> v = new Vector<>();
                        v.add(surfaceHolder.getSurface());
                        try {
                            cameraDevice.createCaptureSession(
                                    v, new CameraCaptureSession.StateCallback() {
                                        @Override
                                        public void onConfigured(@NonNull CameraCaptureSession session) {
                                            try {
                                                CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(TEMPLATE_PREVIEW);
                                                builder.addTarget(surfaceHolder.getSurface());
                                                session.setRepeatingRequest(builder.build(), null, null);
                                            } catch (CameraAccessException e) {
                                                throw new RuntimeException(e);
                                            }
                                        }

                                        @Override
                                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {

                                        }
                                    }, handler);
                        } catch (CameraAccessException e) {
                            throw new RuntimeException(e);
                        }

                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice camera) {
                        cameraDevice.close();
                    }

                    @Override
                    public void onError(@NonNull CameraDevice camera, int error) {
                        cameraDevice.close();
                        cameraDevice = null;
                    }
                }, null);
                break;
            }
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }

         */

        // if (imageReader == null)
            // finish();

        Button back = view.findViewById(R.id.button_camera_back);
        back.setText(s("Back"));
        back.setOnClickListener(v -> finish());

        Button click = view.findViewById(R.id.button_camera_click);
        click.setText(s("Click"));
        pictureRequested = false;
        click.setOnClickListener(v -> {
            // A photo can only be asked for once. Taking one stops the preview, and asking a
            // stopped preview for another throws "takePicture failed" - which is what a second
            // tap did, and a second tap is what a shutter button invites while the phone spends
            // the better part of a second focusing and showing nothing for it.
            if (pictureRequested || camera == null) {
                return;
            }
            pictureRequested = true;
            v.setEnabled(false);
            try {
                camera.takePicture(null, null, (data, camera) -> {
                    // Back to the map at once, with its "Loading..." screen up while the
                    // picture is turned upright, encoded and decoded on a worker.
                    final int rotation = getRotationDegrees();
                    if (getC() != null && getC().getMapViewerScreen() != null) {
                        getC().getMapViewerScreen().setPhotoLoading(true);
                    }
                    finish();
                    getC().submitExecutorGeneric(() -> {
                        try {
                            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                            Matrix matrix = new Matrix();
                            matrix.postRotate(rotation);
                            Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

                            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream);

                            byte[] bytesJpeg = outputStream.toByteArray();
                            setBytesAsBackgroundImage(bytesJpeg);
                            com.peaknav.viewer.PhotoSkylineAligner.photoTakenHere();
                        } catch (RuntimeException e) {
                            e.printStackTrace();
                            if (getC() != null && getC().getMapViewerScreen() != null) {
                                getC().getMapViewerScreen().setPhotoLoading(false);
                            }
                        }
                    });
                });
            } catch (RuntimeException driverRefused) {
                // Whatever the camera's reason, it is not worth the app for: this runs on the
                // UI thread, where an exception goes straight to the global handler and takes
                // the process with it. Back to the map instead, where everything else still
                // works and the picture can be picked from the gallery.
                driverRefused.printStackTrace();
                pictureRequested = false;
                v.setEnabled(true);
                finish();
            }
        });

        Button chooseFromGallery = view.findViewById(R.id.button_choose_from_gallery);
        chooseFromGallery.setText(s("Gallery"));
        chooseFromGallery.setOnClickListener(v -> {
            Intent intent = new Intent();
            intent.setType("image/*");
            intent.setAction(Intent.ACTION_GET_CONTENT);
            startActivityForResult(Intent.createChooser(intent, "Select Picture"), PICK_IMAGE);
        });

        return view;
    }

    private void finish() {
        ((NativeScreenCallerAndroid) getNativeScreenCaller()).popStack();
    }

    private int getRotationDegrees() {
        Display display = getSystemService(getActivity(), WindowManager.class).getDefaultDisplay();
        int screenRot = display.getRotation();
        switch (screenRot) {
            case Surface.ROTATION_0:
                return 90;
            case Surface.ROTATION_90:
                return 0;
            case Surface.ROTATION_180:
                return 270;
            case Surface.ROTATION_270:
                return 180;
        }
        return -1;
    }
}