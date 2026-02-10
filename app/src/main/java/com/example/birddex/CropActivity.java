package com.example.birddex;

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

/**
 * CropActivity provides an interface for the user to crop an image before identification.
 * It uses the 'Android-Image-Cropper' library to allow manual cropping to a 1:1 aspect ratio.
 */
public class CropActivity extends AppCompatActivity {

    // Key for passing the image URI via intent extras.
    public static final String EXTRA_IMAGE_URI = "image_uri";

    private CropImageView cropImageView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);

        // Initialize UI components.
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
        btnCancel.setOnClickListener(v -> finish());

        // Handle the identify button click: get the cropped bitmap, save it, and move to identification.
        btnIdentify.setOnClickListener(v -> {
            Bitmap cropped = cropImageView.getCroppedImage();
            if (cropped == null) return;

            // Save the cropped bitmap to a temporary file to pass its URI to the next activity.
            Uri croppedImageUri = saveBitmapToFile(cropped);

            if (croppedImageUri != null) {
                Intent intent = new Intent(this, IdentifyingActivity.class);
                intent.putExtra("imageUri", croppedImageUri.toString());
                startActivity(intent);
                finish();
            }
        });
    }

    /**
     * Saves a Bitmap to a temporary file in the cache directory.
     * @param bmp The Bitmap to save.
     * @return The Uri of the saved file, or null if an error occurred.
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
