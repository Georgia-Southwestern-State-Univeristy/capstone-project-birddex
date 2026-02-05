package com.example.birddex;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.canhub.cropper.CropImageView;

import java.io.OutputStream;

public class CropActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_URI = "image_uri";

    private CropImageView cropImageView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);

        cropImageView = findViewById(R.id.cropImageView);
        Button btnCancel = findViewById(R.id.btnCancel);
        Button btnSave = findViewById(R.id.btnSave);

        String uriStr = getIntent().getStringExtra(EXTRA_IMAGE_URI);
        if (uriStr == null) {
            finish();
            return;
        }

        Uri inputUri = Uri.parse(uriStr);

        // Load image into crop view
        cropImageView.setImageUriAsync(inputUri);

        // Force square crop for cards
        cropImageView.setFixedAspectRatio(true);
        cropImageView.setAspectRatio(1, 1);

        btnCancel.setOnClickListener(v -> finish());

        btnSave.setOnClickListener(v -> {
            Bitmap cropped = cropImageView.getCroppedImage();
            if (cropped == null) return;

            Uri saved = saveBitmapToGallery(cropped);

            // return result to caller if you want
            Intent data = new Intent();
            if (saved != null) data.setData(saved);
            setResult(RESULT_OK, data);

            finish();
        });
    }

    private Uri saveBitmapToGallery(Bitmap bmp) {
        try {
            ContentResolver resolver = getContentResolver();

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "BIRDDEX_CROP_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BirdDex");

            Uri outUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (outUri == null) return null;

            try (OutputStream out = resolver.openOutputStream(outUri)) {
                if (out == null) return null;
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, out);
                out.flush();
            }

            return outUri;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
