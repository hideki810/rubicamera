package com.hideki810.rubicamera;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * アプリ内蔵の撮影画面（Camera2）。
 * 外部カメラアプリへは投げず、この画面で撮って指定ファイルへ保存する。
 */
public class CameraActivity extends Activity {
    private static final int REQ_CAMERA_PERMISSION = 100;

    private String outPath;
    private TextureView textureView;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader imageReader;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private Size previewSize;
    private int sensorOrientation = 0;
    private boolean capturing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        outPath = getIntent().getStringExtra("path");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        textureView = new TextureView(this);
        root.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        float density = getResources().getDisplayMetrics().density;

        Button btnCancel = new Button(this);
        btnCancel.setText("キャンセル");
        btnCancel.setTextSize(16);
        btnCancel.setOnClickListener(v -> finishCanceled(false));
        FrameLayout.LayoutParams lpCancel = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpCancel.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
        lpCancel.setMargins((int) (16 * density), 0, 0, (int) (44 * density));
        root.addView(btnCancel, lpCancel);

        // 大きな撮影ボタン（「撮影」の上にルビ「さつえい」を2段で表示）
        android.widget.LinearLayout btnShot = new android.widget.LinearLayout(this);
        btnShot.setOrientation(android.widget.LinearLayout.VERTICAL);
        btnShot.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(0xFF2B6CB0);
        bg.setStroke((int) (3 * density), 0xFFFFFFFF);
        btnShot.setBackground(bg);
        android.widget.TextView rubyText = new android.widget.TextView(this);
        rubyText.setText("さつえい");
        rubyText.setTextSize(11);
        rubyText.setTextColor(0xFFCFE3F5);
        rubyText.setGravity(android.view.Gravity.CENTER);
        btnShot.addView(rubyText);
        android.widget.TextView mainText = new android.widget.TextView(this);
        mainText.setText("撮影");
        mainText.setTextSize(24);
        mainText.setTypeface(null, android.graphics.Typeface.BOLD);
        mainText.setTextColor(0xFFFFFFFF);
        mainText.setGravity(android.view.Gravity.CENTER);
        btnShot.addView(mainText);
        btnShot.setOnClickListener(v -> takePicture());
        FrameLayout.LayoutParams lpShot = new FrameLayout.LayoutParams(
                (int) (108 * density), (int) (108 * density));
        lpShot.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
        lpShot.setMargins(0, 0, 0, (int) (28 * density));
        root.addView(btnShot, lpShot);

        setContentView(root);

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                ensurePermissionAndOpen();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                configureTransform(w, h);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
    }

    private void ensurePermissionAndOpen() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
        } else {
            openCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                Toast.makeText(this, "カメラの許可が必要です", Toast.LENGTH_SHORT).show();
                finishCanceled(true);
            }
        }
    }

    private void openCamera() {
        try {
            bgThread = new HandlerThread("camera-bg");
            bgThread.start();
            bgHandler = new Handler(bgThread.getLooper());

            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            String cameraId = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics cc = manager.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null) {
                cameraId = manager.getCameraIdList()[0];
            }
            CameraCharacteristics cc = manager.getCameraCharacteristics(cameraId);
            Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = (so != null) ? so : 0;
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                finishCanceled(true);
                return;
            }
            Size jpegSize = chooseJpegSize(map.getOutputSizes(ImageFormat.JPEG));
            previewSize = choosePreviewSize(map.getOutputSizes(SurfaceTexture.class), jpegSize);

            imageReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(),
                    ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image == null) {
                    return;
                }
                try {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    File out = new File(outPath);
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write(bytes);
                    }
                    runOnUiThread(() -> {
                        setResult(RESULT_OK);
                        finish();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> finishCanceled(true));
                } finally {
                    image.close();
                }
            }, bgHandler);

            if (textureView.isAvailable()) {
                configureTransform(textureView.getWidth(), textureView.getHeight());
            }
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice cd) {
                    camera = cd;
                    startPreview();
                }

                @Override
                public void onDisconnected(CameraDevice cd) {
                    cd.close();
                    camera = null;
                }

                @Override
                public void onError(CameraDevice cd, int error) {
                    cd.close();
                    camera = null;
                    runOnUiThread(() -> finishCanceled(true));
                }
            }, bgHandler);
        } catch (Exception e) {
            finishCanceled(true);
        }
    }

    private Size chooseJpegSize(Size[] sizes) {
        Size best = sizes[0];
        long bestArea = 0;
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            if (area > bestArea && s.getWidth() <= 4096 && s.getHeight() <= 4096) {
                best = s;
                bestArea = area;
            }
        }
        return best;
    }

    private Size choosePreviewSize(Size[] sizes, Size jpegSize) {
        double targetRatio = (double) jpegSize.getWidth() / jpegSize.getHeight();
        Size best = null;
        long bestArea = 0;
        for (Size s : sizes) {
            double ratio = (double) s.getWidth() / s.getHeight();
            if (Math.abs(ratio - targetRatio) < 0.05) {
                long area = (long) s.getWidth() * s.getHeight();
                if (area > bestArea && s.getWidth() <= 1920) {
                    best = s;
                    bestArea = area;
                }
            }
        }
        return (best != null) ? best : sizes[0];
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (previewSize == null || viewWidth == 0 || viewHeight == 0) {
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / previewSize.getHeight(),
                    (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * ((rotation == Surface.ROTATION_90) ? -1 : 1), centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    private void startPreview() {
        try {
            SurfaceTexture st = textureView.getSurfaceTexture();
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(st);
            CaptureRequest.Builder builder =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            List<Surface> targets = new ArrayList<>(Arrays.asList(surface, imageReader.getSurface()));
            camera.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try {
                        session.setRepeatingRequest(builder.build(), null, bgHandler);
                    } catch (CameraAccessException ignored) {
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    runOnUiThread(() -> finishCanceled(true));
                }
            }, bgHandler);
            configureTransform(textureView.getWidth(), textureView.getHeight());
        } catch (Exception e) {
            finishCanceled(true);
        }
    }

    private void takePicture() {
        if (camera == null || session == null || capturing) {
            return;
        }
        capturing = true;
        try {
            // まずAF/AEをロック方向に寄せてから静止画を撮る
            CaptureRequest.Builder lock = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            lock.addTarget(new Surface(textureView.getSurfaceTexture()));
            lock.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            lock.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            session.capture(lock.build(), null, bgHandler);
        } catch (Exception ignored) {
        }
        bgHandler.postDelayed(() -> {
            try {
                CaptureRequest.Builder shot =
                        camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                shot.addTarget(imageReader.getSurface());
                shot.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                shot.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                shot.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
                session.capture(shot.build(), null, bgHandler);
            } catch (Exception e) {
                capturing = false;
                runOnUiThread(() -> finishCanceled(true));
            }
        }, 600);
    }

    private int jpegOrientation() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0: degrees = 0; break;
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
        }
        return (sensorOrientation - degrees + 360) % 360;
    }

    private void finishCanceled(boolean fallbackToGallery) {
        Intent data = new Intent();
        data.putExtra("fallback_gallery", fallbackToGallery);
        setResult(RESULT_CANCELED, data);
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (session != null) {
                session.close();
                session = null;
            }
            if (camera != null) {
                camera.close();
                camera = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            if (bgThread != null) {
                bgThread.quitSafely();
                bgThread = null;
            }
        } catch (Exception ignored) {
        }
    }
}
