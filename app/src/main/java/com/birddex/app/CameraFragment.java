package com.birddex.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageButton;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;

/**
 * CameraFragment: Camera capture screen that takes or imports images for identification.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class CameraFragment extends Fragment {

    private static final String TAG = "BirdDexCam";
    private static final String PREFS_NAME = "BirdDexPrefs";
    private static final String KEY_SHOW_CAMERA_TIP = "show_camera_tip";
    private static final int BURST_FRAME_COUNT = 3;
    private static final long BURST_FRAME_DELAY_MS = 90L;

    private PreviewView previewView;
    private ImageButton btnFlip, btnCapture, btnFlash, btnBack;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ImageCapture imageCapture;
    private ScaleGestureDetector scaleGestureDetector;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private final Handler burstHandler = new Handler(Looper.getMainLooper());

    private enum FlashState { OFF, ON, AUTO }
    private FlashState flashState = FlashState.OFF;

    public CameraFragment() { }

    /**
     * Android calls this to inflate the Fragment's XML and return the root view that will be shown
     * on screen.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        View v = inflater.inflate(R.layout.fragment_camera, container, false);
        previewView = v.findViewById(R.id.previewView);
        btnFlip = v.findViewById(R.id.btnFlip);
        btnCapture = v.findViewById(R.id.btnCapture);
        btnFlash = v.findViewById(R.id.btnFlash);
        btnBack = v.findViewById(R.id.btnBack);

        scaleGestureDetector = new ScaleGestureDetector(requireContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (camera == null) return false;
                ZoomState zs = camera.getCameraInfo().getZoomState().getValue();
                if (zs == null) return false;
                float newZoom = Math.max(zs.getMinZoomRatio(), Math.min(zs.getZoomRatio() * detector.getScaleFactor(), zs.getMaxZoomRatio()));
                camera.getCameraControl().setZoomRatio(newZoom);
                return true;
            }
        });

        previewView.setOnTouchListener((view, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP) {
                tapToFocus(event.getX(), event.getY());
            }
            return true;
        });

        cameraPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) startCamera();
                // Give the user immediate feedback about the result of this action.
            else MessagePopupHelper.show(requireContext(), "Camera permission denied.");
        });

        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA);

        // Attach the user interaction that should run when this control is tapped.
        btnFlip.setOnClickListener(view -> {
            btnFlip.setEnabled(false);
            lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK) ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
            bindCameraUseCases();
        });

        if (btnFlash != null) {
            btnFlash.setOnClickListener(view -> {
                if (flashState == FlashState.OFF) flashState = FlashState.ON;
                else if (flashState == FlashState.ON) flashState = FlashState.AUTO;
                else flashState = FlashState.OFF;
                applyFlashState();
            });
        }

        if (btnCapture != null) {
            btnCapture.setOnClickListener(view -> {
                // FIX: Disable button to prevent multiple captures in rapid succession
                btnCapture.setEnabled(false);
                btnCapture.setAlpha(0.4f);
                takePhoto();
            });
        }

        if (btnBack != null) {
            btnBack.setOnClickListener(view -> {
                if (getActivity() != null) {
                    getActivity().onBackPressed();
                }
            });
        }

        showCameraTipIfNeeded();

        return v;
    }

    private void showCameraTipIfNeeded() {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean showTip = prefs.getBoolean(KEY_SHOW_CAMERA_TIP, true);

        if (showTip) {
            View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_camera_tip, null);
            CheckBox checkBox = dialogView.findViewById(R.id.checkBoxDontShow);

            new MaterialAlertDialogBuilder(requireContext())
                    .setView(dialogView)
                    .setPositiveButton(R.string.ok, (dialog, which) -> {
                        if (checkBox.isChecked()) {
                            prefs.edit().putBoolean(KEY_SHOW_CAMERA_TIP, false).apply();
                        }
                    })
                    .show();
        }
    }

    /**
     * Main logic block for this part of the feature.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(requireContext());
        future.addListener(() -> {
            // Kick off an asynchronous one-time read; the callbacks below decide how the UI should react.
            try { cameraProvider = future.get(); bindCameraUseCases(); }
            // Give the user immediate feedback about the result of this action.
            catch (ExecutionException | InterruptedException e) { MessagePopupHelper.show(requireContext(), "Failed to start camera."); }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    /**
     * Connects already-fetched data to views so the user can see the current state.
     */
    @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalZeroShutterLag.class)
    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();

        CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();

        // Use 4:3 Aspect Ratio and Highest Available Resolution for maximum sensor data
        ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build();

        Preview preview = new Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // ZERO_SHUTTER_LAG provides high quality without breaking the speed of the anti-cheat burst
        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG)
                .setResolutionSelector(resolutionSelector)
                .setJpegQuality(100)
                .build();

        camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
        if (btnFlash != null) btnFlash.setEnabled(camera.getCameraInfo().hasFlashUnit());
        applyFlashState();
        if (btnFlip != null) btnFlip.setEnabled(true);
    }

    private void tapToFocus(float x, float y) {
        if (camera == null) return;
        MeteringPointFactory factory = new SurfaceOrientedMeteringPointFactory(
                (float) previewView.getWidth(), (float) previewView.getHeight());
        MeteringPoint point = factory.createPoint(x, y);
        FocusMeteringAction action = new FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE).build();
        camera.getCameraControl().startFocusAndMetering(action);
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void applyFlashState() {
        if (camera == null || imageCapture == null) return;
        if (!camera.getCameraInfo().hasFlashUnit()) { flashState = FlashState.OFF; camera.getCameraControl().enableTorch(false); imageCapture.setFlashMode(ImageCapture.FLASH_MODE_OFF); return; }
        switch (flashState) {
            case OFF: camera.getCameraControl().enableTorch(false); imageCapture.setFlashMode(ImageCapture.FLASH_MODE_OFF); break;
            case ON: camera.getCameraControl().enableTorch(true); imageCapture.setFlashMode(ImageCapture.FLASH_MODE_ON); break;
            case AUTO: camera.getCameraControl().enableTorch(false); imageCapture.setFlashMode(ImageCapture.FLASH_MODE_AUTO); break;
        }
    }

    /**
     * Main logic block for this part of the feature.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void takePhoto() {
        cleanupBurstFrameCache();
        if (imageCapture == null) {
            restoreCaptureButton();
            return;
        }

        captureBurstFrame(new ArrayList<>(), new ArrayList<>(), 0);
    }

    private void captureBurstFrame(@NonNull ArrayList<Uri> frameUris,
                                   @NonNull ArrayList<Long> captureTimesMs,
                                   int frameIndex) {
        if (!isAdded() || imageCapture == null) {
            restoreCaptureButton();
            return;
        }

        File outputFile = createBurstFrameFile(frameIndex);
        if (outputFile == null) {
            restoreCaptureButton();
            MessagePopupHelper.show(requireContext(), "Capture failed.");
            return;
        }

        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(outputFile).build();
        final Uri outputUri = Uri.fromFile(outputFile);

        imageCapture.takePicture(options, ContextCompat.getMainExecutor(requireContext()), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                frameUris.add(outputUri);
                captureTimesMs.add(System.currentTimeMillis());

                if (frameUris.size() < BURST_FRAME_COUNT) {
                    burstHandler.postDelayed(() -> captureBurstFrame(frameUris, captureTimesMs, frameIndex + 1), BURST_FRAME_DELAY_MS);
                } else {
                    finalizeBurstCapture(frameUris, captureTimesMs);
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exc) {
                Log.w(TAG, "Burst frame capture failed at index " + frameIndex, exc);
                if (!frameUris.isEmpty()) {
                    finalizeBurstCapture(frameUris, captureTimesMs);
                } else {
                    restoreCaptureButton();
                    MessagePopupHelper.show(requireContext(), "Capture failed.");
                }
            }
        });
    }


    private void cleanupBurstFrameCache() {
        if (!isAdded()) return;

        try {
            File dir = new File(requireContext().getCacheDir(), "camera_burst_frames");
            if (!dir.exists()) return;

            File[] files = dir.listFiles();
            if (files == null) return;

            for (File file : files) {
                if (file != null && file.exists() && !file.delete()) {
                    Log.w(TAG, "Failed to delete stale burst frame: " + file.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to clear burst frame cache.", e);
        }
    }


    @Nullable
    private File createBurstFrameFile(int frameIndex) {
        try {
            File dir = new File(requireContext().getCacheDir(), "camera_burst_frames");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            return new File(dir, "burst_" + System.currentTimeMillis() + "_" + frameIndex + ".jpg");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create burst frame file", e);
            return null;
        }
    }

    private void finalizeBurstCapture(@NonNull ArrayList<Uri> frameUris,
                                      @NonNull ArrayList<Long> captureTimesMs) {
        if (!isAdded() || frameUris.isEmpty()) {
            restoreCaptureButton();
            return;
        }

        Context appContext = requireContext().getApplicationContext();
        new Thread(() -> {
            CaptureGuardHelper.GuardReport guardReport = frameUris.size() >= 2
                    ? CaptureGuardHelper.analyzeBurst(appContext, frameUris, captureTimesMs)
                    : CaptureGuardHelper.buildFallbackReport(CaptureGuardHelper.CAPTURE_SOURCE_CAMERA_BURST, frameUris.size());

            int selectedIndex = Math.max(0, Math.min(guardReport.selectedFrameIndex, frameUris.size() - 1));
            Uri selectedFrameUri = frameUris.get(selectedIndex);

            if (!isAdded()) return;

            requireActivity().runOnUiThread(() -> {
                Intent cropIntent = new Intent(requireContext(), CropActivity.class)
                        .putExtra(CropActivity.EXTRA_IMAGE_URI, selectedFrameUri.toString())
                        .putExtra(CropActivity.EXTRA_AWARD_POINTS, true)
                        .putExtra(CaptureGuardHelper.EXTRA_CAPTURE_SOURCE, CaptureGuardHelper.CAPTURE_SOURCE_CAMERA_BURST);
                CaptureGuardHelper.putGuardExtras(cropIntent, guardReport);
                // Move into the next screen and pass the identifiers/data that screen needs.
                startActivity(cropIntent);
                restoreCaptureButton();
            });
        }).start();
    }

    private void restoreCaptureButton() {
        if (!isAdded()) return;
        if (btnCapture != null) {
            btnCapture.setEnabled(true);
            btnCapture.setAlpha(1f);
        }
    }
}
