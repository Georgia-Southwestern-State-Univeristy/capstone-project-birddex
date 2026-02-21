package com.birddex.app;

import android.Manifest;
import android.content.ContentValues;
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
 * CameraFragment handles the camera functionality using the CameraX library.
 * It allows users to preview the camera feed, flip between front and back cameras,
 * toggle flash modes, and capture photos.
 */
public class CameraFragment extends Fragment {

    private PreviewView previewView;
    private ImageButton btnFlip, btnCapture, btnFlash;

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ImageCapture imageCapture;
    private ScaleGestureDetector scaleGestureDetector;

    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    // Flash states: OFF -> ON -> AUTO
    private enum FlashState { OFF, ON, AUTO }
    private FlashState flashState = FlashState.OFF;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_camera, container, false);

        // Initialize UI components for the camera interface.
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


        Log.d("BirdDexCam", "CameraFragment onCreateView()");

        // Register a launcher for requesting camera permission.
        cameraPermissionLauncher =
                registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        Log.d("BirdDexCam", "Camera permission granted");
                        startCamera();
                    } else {
                        Log.d("BirdDexCam", "Camera permission denied");
                    }
                });

        // Request camera permission if not already granted, otherwise start the camera.
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            Log.d("BirdDexCam", "Camera permission already granted");
            startCamera();
        } else {
            Log.d("BirdDexCam", "Requesting camera permission");
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }

        // Handle camera flip (front/back) action.
        btnFlip.setOnClickListener(view -> {
            Log.d("BirdDexCam", "Flip clicked");
            lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK)
                    ? CameraSelector.LENS_FACING_FRONT
                    : CameraSelector.LENS_FACING_BACK;
            bindCameraUseCases(); // Re-bind use cases with the new lens facing.
        });

        // Handle flash mode cycling.
        btnFlash.setOnClickListener(view -> {
            Log.d("BirdDexCam", "Flash clicked");

            if (flashState == FlashState.OFF) flashState = FlashState.ON;
            else if (flashState == FlashState.ON) flashState = FlashState.AUTO;
            else flashState = FlashState.OFF;

            applyFlashState();
        });

        // Handle photo capture action.
        btnCapture.setOnClickListener(view -> {
            Log.d("BirdDexCam", "Capture clicked");

            // Brief visual feedback for the capture click.
            btnCapture.setAlpha(0.4f);
            btnCapture.postDelayed(() -> btnCapture.setAlpha(1f), 150);

            takePhoto();
        });

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
                Log.e("BirdDexCam", "Error starting camera", e);
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

        // Set up the preview use case.
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // Set up the image capture use case with latency optimization.
        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build();

        // Bind use cases to the fragment's lifecycle.
        camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
        
        // Enable/disable flash button based on hardware capability.
        btnFlash.setEnabled(camera.getCameraInfo().hasFlashUnit());

        // Apply current flash state to the camera.
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
        if (imageCapture == null) return;

        // Generate a unique file name based on the current timestamp.
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String name = "BIRDDEX_" + timeStamp;

        // Set up metadata for saving the image in MediaStore.
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

        // Execute the image capture.
        imageCapture.takePicture(
                options,
                ContextCompat.getMainExecutor(requireContext()),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        Uri savedUri = output.getSavedUri();

                        if (savedUri == null) {
                            Log.e("BirdDexCam", "Saved URI is null (cannot open crop page).");
                            return;
                        }

                        Log.d("BirdDexCam", "onImageSaved(): " + savedUri);

                        // Navigate to CropActivity to allow the user to crop the captured image.
                        android.content.Intent i = new android.content.Intent(requireContext(), CropActivity.class);
                        i.putExtra(CropActivity.EXTRA_IMAGE_URI, savedUri.toString());
                        startActivity(i);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exc) {
                        Log.e("BirdDexCam", "Capture failed: " + exc.getMessage(), exc);
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
