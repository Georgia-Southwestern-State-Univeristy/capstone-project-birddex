package com.example.birddex;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * IdentifyingActivity orchestrates the bird identification process.
 * Order: Identify (Cloud) -> Verify (Cloud) -> Upload (Storage) ONLY if verified.
 */
public class IdentifyingActivity extends AppCompatActivity {

    private static final String TAG = "IdentifyingActivity";
    private Uri localImageUri;
    private OpenAiApi openAiApi;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_identifying);

        ImageView identifyingImageView = findViewById(R.id.identifyingImageView);
        String uriStr = getIntent().getStringExtra("imageUri");
        if (uriStr == null) {
            finish();
            return;
        }

        localImageUri = Uri.parse(uriStr);
        identifyingImageView.setImageURI(localImageUri);

        openAiApi = new OpenAiApi();

        // 1. Identify first using local image
        startIdentification(localImageUri);
    }

    private void startIdentification(Uri imageUri) {
        String base64Image = encodeImage(imageUri);
        if (base64Image == null) {
            Toast.makeText(this, "Failed to process image.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        openAiApi.identifyBirdFromImage(base64Image, new OpenAiApi.OpenAiCallback() {
            @Override
            public void onSuccess(String response, boolean isVerified) {
                if (!isVerified) {
                    Toast.makeText(IdentifyingActivity.this, "Bird not recognized in Georgia regional data. Image not saved.", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }

                // 2. ONLY if verified, upload the image to Firebase Storage
                uploadVerifiedImage(response);
            }

            @Override
            public void onFailure(Exception e, String message) {
                Log.e(TAG, "Identification failed: " + message, e);
                Toast.makeText(IdentifyingActivity.this, "Identification failed. Check internet.", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private void uploadVerifiedImage(String identificationResult) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        String userId = user.getUid();
        String fileName = "images/" + userId + "/" + UUID.randomUUID().toString() + ".jpg";
        StorageReference storageRef = FirebaseStorage.getInstance().getReference().child(fileName);

        storageRef.putFile(localImageUri)
                .addOnSuccessListener(taskSnapshot -> {
                    storageRef.getDownloadUrl().addOnSuccessListener(downloadUri -> {
                        // 3. Move to result screen with the cloud URL
                        proceedToInfoActivity(identificationResult, downloadUri.toString());
                    });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Storage upload failed", e);
                    // Even if upload fails, we show result but without cloud URL
                    proceedToInfoActivity(identificationResult, null);
                });
    }

    private void proceedToInfoActivity(String contentStr, String downloadUrl) {
        String[] lines = contentStr.split("\n");
        String commonName = "Unknown";
        String scientificName = "Unknown";
        String species = "Unknown";
        String family = "Unknown";

        for (String line : lines) {
            if (line.startsWith("Common Name: ")) commonName = line.replace("Common Name: ", "").trim();
            else if (line.startsWith("Scientific Name: ")) scientificName = line.replace("Scientific Name: ", "").trim();
            else if (line.startsWith("Species: ")) species = line.replace("Species: ", "").trim();
            else if (line.startsWith("Family: ")) family = line.replace("Family: ", "").trim();
        }

        Intent intent = new Intent(IdentifyingActivity.this, BirdInfoActivity.class);
        intent.putExtra("imageUri", localImageUri.toString());
        intent.putExtra("commonName", commonName);
        intent.putExtra("scientificName", scientificName);
        intent.putExtra("species", species);
        intent.putExtra("family", family);
        intent.putExtra("imageUrl", downloadUrl);
        startActivity(intent);
        finish();
    }

    private String encodeImage(Uri imageUri) {
        try {
            Bitmap bitmap;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(this.getContentResolver(), imageUri));
            } else {
                bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
            }
            int maxWidth = 512;
            int maxHeight = 512;
            float ratio = Math.min((float) maxWidth / bitmap.getWidth(), (float) maxHeight / bitmap.getHeight());
            if (ratio < 1.0f) {
                bitmap = Bitmap.createScaledBitmap(bitmap, Math.round(ratio * bitmap.getWidth()), Math.round(ratio * bitmap.getHeight()), true);
            }
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, byteArrayOutputStream);
            return Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP);
        } catch (IOException e) {
            return null;
        }
    }
}
