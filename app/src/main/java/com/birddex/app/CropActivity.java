package com.birddex.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.canhub.cropper.CropImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CropActivity provides an interface for the user to crop an image before identification.
 * It uses the 'Android-Image-Cropper' library to allow manual cropping to a 1:1 aspect ratio.
 */
/**
 * CropActivity: Activity class for one BirdDex screen. It owns screen setup, user actions, and navigation for this part of the app.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class CropActivity extends AppCompatActivity {

    // Key for passing the image URI via intent extras.
    public static final String EXTRA_IMAGE_URI = "image_uri";
    public static final String EXTRA_AWARD_POINTS = "awardPoints";

    private CropImageView cropImageView;
    
    // FIX: Guard against double-tap starting multiple identification flows
    private final AtomicBoolean identifyClicked = new AtomicBoolean(false);

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);

        // Initialize UI components.
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        cropImageView = findViewById(R.id.cropImageView);
        Button btnCancel = findViewById(R.id.btnCancel);
        Button btnIdentify = findViewById(R.id.btnIdentify);

        // Retrieve the image URI passed from the previous activity (CameraFragment).
        String uriStr = getIntent().getStringExtra(EXTRA_IMAGE_URI);
        if (uriStr == null) {
            finish(); // Exit if no URI is provided.
            return;
        }

        Uri inputUri = Uri.parse(uriStr);

        // Load the image into the cropper asynchronously.
        cropImageView.setImageUriAsync(inputUri);

        // Enforce a square aspect ratio (1:1) for consistency in bird cards.
        cropImageView.setFixedAspectRatio(true);
        cropImageView.setAspectRatio(1, 1);

        // Exit the activity if the user cancels cropping.
        // Attach the user interaction that should run when this control is tapped.
        btnCancel.setOnClickListener(v -> finish());

        // Handle the identify button click: get the cropped bitmap, save it, and move to identification.
        btnIdentify.setOnClickListener(v -> {
            if (!identifyClicked.compareAndSet(false, true)) return;

            // HIGH-RES crop so the card doesn't look pixelated
            Bitmap cropped = cropImageView.getCroppedImage(1600, 1600);
            if (cropped == null) {
                // Persist the new state so the action is saved outside the current screen.
                identifyClicked.set(false);
                return;
            }

            // Save the cropped bitmap to a temporary file to pass its URI to the next activity.
            Uri croppedImageUri = saveBitmapToFile(cropped);

            if (croppedImageUri != null) {
                boolean awardPoints = getIntent().getBooleanExtra(EXTRA_AWARD_POINTS, true);

                Intent intent = new Intent(this, IdentifyingActivity.class);
                intent.putExtra("imageUri", croppedImageUri.toString());
                intent.putExtra("awardPoints", awardPoints);
                // Move into the next screen and pass the identifiers/data that screen needs.
                startActivity(intent);
                finish();
            } else {
                identifyClicked.set(false);
            }
        });
    }

    /**
     * Runs when the screen returns to the foreground, so it often refreshes UI state or restarts
     * listeners.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    @Override
    protected void onResume() {
        super.onResume();
        // Reset guard if the user navigates back to this activity
        // Persist the new state so the action is saved outside the current screen.
        identifyClicked.set(false);
    }

    /**
     * Saves a Bitmap to a temporary file in the cache directory.
     * @param bmp The Bitmap to save.
     * @return The Uri of the saved file, or null if an error occurred.
     */
    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     * Bitmap/rendering work happens here, so this block is shaping the final card/image output
     * rather than just text data.
     */
    private Uri saveBitmapToFile(Bitmap bmp) {
        try {
            // Create a directory in the cache for cropped images.
            File dir = new File(getCacheDir(), "cropped_images");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            // Create a temporary JPG file.
            File file = new File(dir, "cropped_image.jpg");
            FileOutputStream fos = new FileOutputStream(file);
            // Compress the bitmap into the file.
            bmp.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            fos.flush();
            fos.close();
            return Uri.fromFile(file);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}