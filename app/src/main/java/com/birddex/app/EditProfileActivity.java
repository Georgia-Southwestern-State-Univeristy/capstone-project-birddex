package com.birddex.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.bumptech.glide.Glide;

import com.canhub.cropper.CropImageContract;
import com.canhub.cropper.CropImageContractOptions;
import com.canhub.cropper.CropImageOptions;
import com.canhub.cropper.CropImageView;


import java.util.HashMap;
import java.util.Map;

public class EditProfileActivity extends AppCompatActivity {

    private static final String TAG = "EditProfileActivity";
    private static final int REQUEST_READ_EXTERNAL_STORAGE = 100;

    private TextInputEditText etUsername;
    private TextInputEditText etBio;
    private String initialUsername;
    private String initialBio;
    private String initialProfilePictureUrl; // This will hold the URL from the intent, and then the new uploaded one

    // Firebase
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseStorage storage;
    private StorageReference storageRef;

    // UI elements
    private ImageView ivPfpPreview;
    private MaterialButton btnChangePhoto;

    // Image handling
    private Uri imageUri;
    private String uploadedProfilePictureDownloadUrl = null; // To store the URL after successful upload

    // Activity Result Launchers
    private ActivityResultLauncher<Intent> pickImageLauncher;
    private ActivityResultLauncher<CropImageContractOptions> cropImageLauncher;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_profile);

        // Initialize Firebase
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        storage = FirebaseStorage.getInstance();
        storageRef = storage.getReference();

        // Initialize UI
        ivPfpPreview = findViewById(R.id.ivPfpPreview);
        btnChangePhoto = findViewById(R.id.btnChangePhoto);
        etUsername = findViewById(R.id.etUsername);
        etBio = findViewById(R.id.etBio);

        // Get initial data from intent
        initialUsername = getIntent().getStringExtra("username");
        initialBio = getIntent().getStringExtra("bio");
        initialProfilePictureUrl = getIntent().getStringExtra("profilePictureUrl");

        etUsername.setText(initialUsername);
        etBio.setText(initialBio);

        // Load existing profile picture
        if (initialProfilePictureUrl != null && !initialProfilePictureUrl.isEmpty()) {
            Glide.with(this).load(initialProfilePictureUrl).into(ivPfpPreview);
        } else {
            ivPfpPreview.setImageResource(R.drawable.ic_profile);
        }

        MaterialButton btnSave = findViewById(R.id.btnSave);
        TextView tvCancel = findViewById(R.id.tvCancel);

        // Register for image picking result
        pickImageLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri selectedImageUri = result.getData().getData();
                    if (selectedImageUri != null) {
                        startImageCropper(selectedImageUri);
                    } else {
                        Toast.makeText(this, "Failed to get image URI", Toast.LENGTH_SHORT).show();
                    }
                }
            });

        // Register for image cropping result
        cropImageLauncher = registerForActivityResult(new CropImageContract(), result -> {
            if (result.isSuccessful()) {
                imageUri = result.getUriContent();
                if (imageUri != null) {
                    Log.d(TAG, "Cropped image URI: " + imageUri.toString());
                    Glide.with(this).load(imageUri).into(ivPfpPreview);
                    uploadedProfilePictureDownloadUrl = null; // Reset to null until uploaded
                } else {
                    Log.e(TAG, "Cropped image URI is null");
                    Toast.makeText(this, "Failed to crop image", Toast.LENGTH_SHORT).show();
                }
            } else {
                Exception error = result.getError();
                Log.e(TAG, "Image cropping failed: " + (error != null ? error.getMessage() : "Unknown error"), error);
                Toast.makeText(this, "Image cropping failed: " + (error != null ? error.getMessage() : "Unknown error"), Toast.LENGTH_LONG).show();
            }
        });


        btnChangePhoto.setOnClickListener(v -> {
            checkPermissionsAndPickImage();
        });

        btnSave.setOnClickListener(v -> {
            String newUsername = etUsername.getText().toString().trim();
            String newBio = etBio.getText().toString();

            if (newUsername.isEmpty()) {
                etUsername.setError("Username cannot be empty");
                return;
            }

            // If a new image was cropped (imageUri is not null), upload it now
            if (imageUri != null) {
                Log.d(TAG, "Save button clicked, imageUri is not null. Starting upload.");
                uploadImageToFirebase(imageUri, newUsername, newBio); // Pass username and bio here
            } else {
                Log.d(TAG, "Save button clicked, imageUri is null. Skipping image upload.");
                // If no new image, but username/bio changed, save those
                // If uploadedProfilePictureDownloadUrl is not null, it means an image was already uploaded successfully
                saveProfileChanges(newUsername, newBio, uploadedProfilePictureDownloadUrl);
            }

        });

        tvCancel.setOnClickListener(v -> {
            Log.d(TAG, "Cancel button clicked.");
            // Revert the preview to the initial profile picture if a new one was selected but not saved.

            if (initialProfilePictureUrl != null && !initialProfilePictureUrl.isEmpty()) {
                Glide.with(this).load(initialProfilePictureUrl).into(ivPfpPreview);
            } else {
                ivPfpPreview.setImageResource(R.drawable.ic_profile);
            }
            imageUri = null; // Clear the temporary imageUri
            uploadedProfilePictureDownloadUrl = null; // Clear any uploaded URL
            finish();
        });
    }

    private void checkPermissionsAndPickImage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQUEST_READ_EXTERNAL_STORAGE);
            } else {
                pickImage();
            }
        }
        else if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_READ_EXTERNAL_STORAGE);
        } else {
            pickImage();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_READ_EXTERNAL_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pickImage();
            } else {
                Toast.makeText(this, "Permission denied to read external storage", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void pickImage() {
        Intent galleryIntent = new Intent(Intent.ACTION_GET_CONTENT);
        galleryIntent.setType("image/*");
        pickImageLauncher.launch(galleryIntent);
    }

    private void startImageCropper(Uri imageUriToCrop) {
        CropImageOptions options = new CropImageOptions();
        options.cropShape = CropImageView.CropShape.OVAL;
        options.fixAspectRatio = true;
        options.aspectRatioX = 1;
        options.aspectRatioY = 1;

        cropImageLauncher.launch(new CropImageContractOptions(imageUriToCrop, options));
    }


    private void uploadImageToFirebase(Uri newImageUri, String newUsername, String newBio) {
        Log.d(TAG, "Starting uploadImageToFirebase for new URI: " + newImageUri);
        if (mAuth.getCurrentUser() == null) {
            Log.e(TAG, "User not logged in. Cannot upload image.");
            Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show();
            return;
        }

        String userId = mAuth.getCurrentUser().getUid();

        // Step 1: Delete the old profile picture if it exists
        if (initialProfilePictureUrl != null && !initialProfilePictureUrl.isEmpty() &&
            (initialProfilePictureUrl.startsWith("gs://") || initialProfilePictureUrl.startsWith("https://firebasestorage.googleapis.com"))) {
            try {
                StorageReference oldImageRef = storage.getReferenceFromUrl(initialProfilePictureUrl);
                Log.d(TAG, "Attempting to delete old image from: " + oldImageRef.getPath());
                oldImageRef.delete().addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Old profile picture deleted successfully from Storage.");
                }).addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to delete old profile picture from Storage: " + e.getMessage(), e);
                    // Continue with new upload even if old deletion fails
                });
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid Firebase Storage URL for old profile picture: " + initialProfilePictureUrl + ". Error: " + e.getMessage());
            }
        }

        // Step 2: Upload the new profile picture
        StorageReference newProfileImageRef = storageRef.child("profile_pictures/" + userId + ".jpg");
        Log.d(TAG, "Uploading new image to path: " + newProfileImageRef.getPath());

        newProfileImageRef.putFile(newImageUri)
            .addOnSuccessListener(taskSnapshot -> {
                Log.d(TAG, "New image uploaded successfully to Firebase Storage.");
                newProfileImageRef.getDownloadUrl().addOnSuccessListener(uri -> {
                    String downloadUrl = uri.toString();
                    Log.d(TAG, "Download URL obtained for new image: " + downloadUrl);
                    uploadedProfilePictureDownloadUrl = downloadUrl; // Store the uploaded URL
                    saveProfileChanges(newUsername, newBio, uploadedProfilePictureDownloadUrl); // Save changes with new URL
                    Toast.makeText(this, "Profile picture updated!", Toast.LENGTH_SHORT).show();
                }).addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to get download URL for new image: " + e.getMessage(), e);
                    Toast.makeText(this, "Failed to get download URL: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "Failed to upload new image to Firebase Storage: " + e.getMessage(), e);
                Toast.makeText(this, "Failed to upload image: " + e.getMessage(), Toast.LENGTH_LONG).show();
            });
    }

    private void saveProfileChanges(String newUsername, String newBio, String uploadedProfilePictureDownloadUrl) {
        String userId = mAuth.getCurrentUser().getUid();
        Log.d(TAG, "Saving profile changes for user: " + userId + " with uploaded URL: " + uploadedProfilePictureDownloadUrl);

        Map<String, Object> updates = new HashMap<>();
        boolean hasChanges = false;

        // Check for username change
        if (!newUsername.equals(initialUsername)) {
            hasChanges = true;
            // Perform username uniqueness check
            db.collection("users")
                .whereEqualTo("username", newUsername)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        QuerySnapshot documentSnapshot = task.getResult();
                        if (documentSnapshot != null && !documentSnapshot.isEmpty()) {
                            // Check if the found username belongs to the current user
                            boolean isCurrentUser = false;
                            if (mAuth.getCurrentUser() != null) {
                                for (com.google.firebase.firestore.DocumentSnapshot doc : documentSnapshot.getDocuments()) {
                                    if (doc.getId().equals(userId)) {
                                        isCurrentUser = true;
                                        break;
                                    }
                                }
                            }
                            if (!isCurrentUser) {
                                etUsername.setError("This username is already taken. Please choose a different one.");
                                Toast.makeText(this, "Username is already taken.", Toast.LENGTH_SHORT).show();
                                return; // Stop the save process
                            }
                        }
                        // If username is unique or belongs to the current user, proceed with updates
                        updates.put("username", newUsername);
                        // Now continue with other updates and actual save if username is valid
                        continueSavingProfile(newUsername, newBio, uploadedProfilePictureDownloadUrl, updates, true);

                    } else {
                        Log.e(TAG, "Error checking username uniqueness: " + task.getException().getMessage());
                        Toast.makeText(this, "Error checking username. Please try again.", Toast.LENGTH_LONG).show();
                    }
                });
            return; // Exit here, continueSavingProfile will be called asynchronously
        }

        // Check for bio change (only if username wasn't changed or if it passed the check)
        if (!newBio.equals(initialBio)) {
            updates.put("bio", newBio);
            hasChanges = true;
        }

        // Always put the uploadedProfilePictureDownloadUrl if it's present.
        // This ensures the profilePictureUrl in Firestore is updated if a new image was uploaded.
        if (uploadedProfilePictureDownloadUrl != null && !uploadedProfilePictureDownloadUrl.isEmpty()) {
            // Only update if it's genuinely new or different from the *initial* URL the activity started with
            if (initialProfilePictureUrl == null || !uploadedProfilePictureDownloadUrl.equals(initialProfilePictureUrl)) {
                 updates.put("profilePictureUrl", uploadedProfilePictureDownloadUrl);
                 hasChanges = true;
            }
        }
        // If uploadedProfilePictureDownloadUrl is null, it means no new image was uploaded.
        // In this case, we don't put "profilePictureUrl" in the 'updates' map,
        // which means it will retain its existing value in Firestore.
        // This is the desired behavior if the user didn't change the PFP.

        // If username didn't change, call continueSavingProfile directly
        continueSavingProfile(newUsername, newBio, uploadedProfilePictureDownloadUrl, updates, hasChanges);
    }

    private void continueSavingProfile(String newUsername, String newBio, String uploadedProfilePictureDownloadUrl, Map<String, Object> updates, boolean hasChanges) {
        String userId = mAuth.getCurrentUser().getUid();

        if (hasChanges) {
            db.collection("users").document(userId)
                .update(updates)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Firestore profile updated successfully.");
                    // Update local 'initial' values to reflect what's now in Firestore
                    initialUsername = newUsername;
                    initialBio = newBio;
                    // If a new image was uploaded, update initialProfilePictureUrl with the new one
                    if (uploadedProfilePictureDownloadUrl != null) {
                        initialProfilePictureUrl = uploadedProfilePictureDownloadUrl;
                    }

                    Intent resultIntent = new Intent();
                    resultIntent.putExtra("newUsername", newUsername);
                    resultIntent.putExtra("newBio", newBio);
                    resultIntent.putExtra("newProfilePictureUrl", initialProfilePictureUrl); // Pass the now-current URL
                    setResult(RESULT_OK, resultIntent);
                    finish();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to update profile in Firestore: " + e.getMessage(), e);
                    Toast.makeText(this, "Failed to update profile: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
        } else {
            Log.d(TAG, "No profile changes detected to save to Firestore. Finishing activity.");
            Intent resultIntent = new Intent();
            resultIntent.putExtra("newUsername", newUsername);
            resultIntent.putExtra("newBio", newBio);
            resultIntent.putExtra("newProfilePictureUrl", initialProfilePictureUrl); // Pass the original/current PFP URL
            setResult(RESULT_OK, resultIntent);
            finish();
        }
    }
}