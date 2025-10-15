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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class CameraPictureView extends Fragment {

    private static final int PICK_IMAGE = 159;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private ImageReader imageReader;
    private View view;

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK) {
            if (data != null) {
                getC().submitExecutorGeneric(() -> {
                    try {
                    InputStream inputStream = getActivity().getContentResolver().openInputStream(data.getData());

                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();

                    int numRead;
                    byte[] d = new byte[16384];

                    while ((numRead = inputStream.read(d, 0, d.length)) != -1) {
                        buffer.write(d, 0, numRead);
                    }

                    byte[] b = buffer.toByteArray();

                    setBytesAsBackgroundImage(b);
                    } catch (FileNotFoundException e) {
                        throw new RuntimeException(e);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
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
    private int w = 640, h = 480;

    public CameraPictureView() {
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        view = inflater.inflate(R.layout.fragment_camera_picture_view, container, false);


        if (ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finish();
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
                Camera.Parameters param = camera.getParameters();
                List<Camera.Size> previewSizes = param.getSupportedPreviewSizes();
                int maxWidth = 0;
                for (Camera.Size size : previewSizes) {
                    if (size.width <= maxWidth)
                        continue;
                    w = size.width;
                    maxWidth = w;
                    h = size.height;
                }
                param.setPictureSize(w, h);
                camera.setParameters(param);

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
                camera.stopPreview();
                camera.release();
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
        click.setOnClickListener(v -> {
            camera.takePicture(null, null, (data, camera) -> {
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);

                int rotation = getRotationDegrees();
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream);

                byte[] bytesJpeg = outputStream.toByteArray();
                setBytesAsBackgroundImage(bytesJpeg);

                finish();
            });
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