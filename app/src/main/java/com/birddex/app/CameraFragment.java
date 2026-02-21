package com.birddex.app;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.common.util.concurrent.ListenableFuture;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/**
 * CameraFragment shows the CameraX preview and lets the user capture an image.
 * Supports: pinch-to-zoom, flip front/back, flash OFF/ON/AUTO.
 * After capture, it launches CropActivity to crop to a 1:1 square before identification.
 */
public class CameraFragment extends Fragment {

    private static final String TAG = "BirdDexCam";

    private PreviewView previewView;
    private ImageButton btnFlip, btnCapture, btnFlash;

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ImageCapture imageCapture;
    private ScaleGestureDetector scaleGestureDetector;

    private int lensFacing = CameraSelector.LENS_FACING_BACK;

    // Permission
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    // Flash states: OFF -> ON -> AUTO
    private enum FlashState { OFF, ON, AUTO }
    private FlashState flashState = FlashState.OFF;

    public CameraFragment() { }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_camera, container, false);

        // UI
        previewView = v.findViewById(R.id.previewView);
        btnFlip = v.findViewById(R.id.btnFlip);
        btnCapture = v.findViewById(R.id.btnCapture);
        btnFlash = v.findViewById(R.id.btnFlash);

        // Pinch-to-zoom on the live camera preview
        scaleGestureDetector = new ScaleGestureDetector(requireContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (camera == null) return false;

                        ZoomState zoomState = camera.getCameraInfo().getZoomState().getValue();
                        if (zoomState == null) return false;

                        float current = zoomState.getZoomRatio();
                        float delta = detector.getScaleFactor();

                        float min = zoomState.getMinZoomRatio();
                        float max = zoomState.getMaxZoomRatio();

                        float newZoom = current * delta;
                        newZoom = Math.max(min, Math.min(newZoom, max));

                        camera.getCameraControl().setZoomRatio(newZoom);
                        return true;
                    }
                });

        previewView.setOnTouchListener((view, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            return true; // consume touch so pinch works reliably
        });

        Log.d(TAG, "CameraFragment onCreateView()");

        // Permission launcher (modern)
        cameraPermissionLauncher =
                registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        Log.d(TAG, "Camera permission granted");
                        startCamera();
                    } else {
                        Log.d(TAG, "Camera permission denied");
                        Toast.makeText(requireContext(), "Camera permission denied.", Toast.LENGTH_LONG).show();
                    }
                });

        // Request camera permission if not already granted, otherwise start the camera.
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Camera permission already granted");
            startCamera();
        } else {
            Log.d(TAG, "Requesting camera permission");
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }

        // Flip front/back
        if (btnFlip != null) {
            btnFlip.setOnClickListener(view -> {
                Log.d(TAG, "Flip clicked");
                lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK)
                        ? CameraSelector.LENS_FACING_FRONT
                        : CameraSelector.LENS_FACING_BACK;
                bindCameraUseCases();
            });
        }

        // Flash cycle OFF -> ON -> AUTO
        if (btnFlash != null) {
            btnFlash.setOnClickListener(view -> {
                Log.d(TAG, "Flash clicked");

                if (flashState == FlashState.OFF) flashState = FlashState.ON;
                else if (flashState == FlashState.ON) flashState = FlashState.AUTO;
                else flashState = FlashState.OFF;

                applyFlashState();
            });
        }

        // Capture photo
        if (btnCapture != null) {
            btnCapture.setOnClickListener(view -> {
                Log.d(TAG, "Capture clicked");

                // Brief visual feedback for the capture click.
                btnCapture.setAlpha(0.4f);
                btnCapture.postDelayed(() -> btnCapture.setAlpha(1f), 150);

                takePhoto();
            });
        }

        return v;
    }

    /**
     * Initializes the CameraX ProcessCameraProvider.
     */
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(requireContext());

        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error starting camera", e);
                Toast.makeText(requireContext(), "Failed to start camera.", Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    /**
     * Binds camera use cases (Preview and ImageCapture) to the lifecycle.
     */
    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        // Unbind previous use cases before binding new ones.
        cameraProvider.unbindAll();

        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build();

        // Preview
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // ImageCapture
        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build();

        // Bind
        camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);

        // Enable/disable flash button based on hardware capability.
        if (btnFlash != null) {
            btnFlash.setEnabled(camera.getCameraInfo().hasFlashUnit());
        }

        applyFlashState();
    }

    /**
     * Applies the current FlashState (OFF, ON, AUTO) to the camera control and image capture use case.
     */
    private void applyFlashState() {
        if (camera == null || imageCapture == null) return;

        boolean hasFlash = camera.getCameraInfo().hasFlashUnit();

        if (!hasFlash) {
            flashState = FlashState.OFF;
            camera.getCameraControl().enableTorch(false);
            imageCapture.setFlashMode(ImageCapture.FLASH_MODE_OFF);
            return;
        }

        switch (flashState) {
            case OFF:
                camera.getCameraControl().enableTorch(false);
                imageCapture.setFlashMode(ImageCapture.FLASH_MODE_OFF);
                break;

            case ON:
                camera.getCameraControl().enableTorch(true);
                imageCapture.setFlashMode(ImageCapture.FLASH_MODE_ON);
                break;

            case AUTO:
                camera.getCameraControl().enableTorch(false);
                imageCapture.setFlashMode(ImageCapture.FLASH_MODE_AUTO);
                break;
        }
    }

    /**
     * Captures a photo and saves it to the MediaStore.
     * On success, it navigates to the CropActivity with the saved image URI.
     */
    private void takePhoto() {
        if (imageCapture == null) {
            Toast.makeText(requireContext(), "Camera not ready.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Unique name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String name = "BIRDDEX_" + timeStamp;

        // Save to MediaStore (Pictures/BirdDex)
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BirdDex");

        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(
                        requireContext().getContentResolver(),
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values
                ).build();

        imageCapture.takePicture(
                options,
                ContextCompat.getMainExecutor(requireContext()),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        Uri savedUri = output.getSavedUri();

                        if (savedUri == null) {
                            Log.e(TAG, "Saved URI is null (cannot open crop page).");
                            Toast.makeText(requireContext(),
                                    "Saved image URI missing. Try again.",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }

                        Log.d(TAG, "onImageSaved(): " + savedUri);

                        Intent i = new Intent(requireContext(), CropActivity.class);
                        i.putExtra(CropActivity.EXTRA_IMAGE_URI, savedUri.toString());
                        startActivity(i);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exc) {
                        Log.e(TAG, "Capture failed: " + exc.getMessage(), exc);
                        Toast.makeText(requireContext(),
                                "Capture failed: " + exc.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    /**
     * Utility method to check if the app is running on an emulator.
     */
    private boolean isEmulator() {
        String fp = android.os.Build.FINGERPRINT;
        String model = android.os.Build.MODEL;
        String brand = android.os.Build.BRAND;
        String device = android.os.Build.DEVICE;
        String product = android.os.Build.PRODUCT;

        return fp != null && (fp.contains("generic") || fp.contains("unknown"))
                || model != null && (model.contains("google_sdk") || model.contains("Emulator") || model.contains("Android SDK built for x86"))
                || brand != null && brand.startsWith("generic")
                || device != null && device.startsWith("generic")
                || product != null && product.contains("sdk");
    }
}