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
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.util.Base64;
import android.provider.MediaStore;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Date;

public class EditProfileActivity extends AppCompatActivity {

    private static final String TAG = "EditProfileActivity";
    private static final int REQUEST_READ_EXTERNAL_STORAGE = 100;

    private TextInputEditText etUsername;
    private TextInputEditText etBio;
    private TextView tvPfpChangesRemaining; // New TextView to display PFP changes remaining
    private String initialUsername;
    private String initialBio;
    private String initialProfilePictureUrl; // This will hold the URL from the intent, and then the new uploaded one

    // Firebase
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseStorage storage;
    private StorageReference storageRef;
    private FirebaseManager firebaseManager; // Added FirebaseManager
    private LoadingDialog loadingDialog; // Added LoadingDialog

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
        firebaseManager = new FirebaseManager(this); // Initialize FirebaseManager
        loadingDialog = new LoadingDialog(this); // Initialize LoadingDialog

        // Initialize UI
        ivPfpPreview = findViewById(R.id.ivPfpPreview);
        btnChangePhoto = findViewById(R.id.btnChangePhoto);
        etUsername = findViewById(R.id.etUsername);
        etBio = findViewById(R.id.etBio);
        tvPfpChangesRemaining = findViewById(R.id.tvPfpChangesRemaining); // Assuming you add this TextView to activity_edit_profile.xml

        // Get initial data from intent (only bio and profile picture URL are passed via intent now)
        // Username will be fetched from Firebase
        initialBio = getIntent().getStringExtra("bio");
        initialProfilePictureUrl = getIntent().getStringExtra("profilePictureUrl");

        etBio.setText(initialBio);

        // Fetch user profile data including username
        fetchUserProfileData();

        // Load existing profile picture
        if (initialProfilePictureUrl != null && !initialProfilePictureUrl.isEmpty()) {
            Glide.with(this).load(initialProfilePictureUrl).into(ivPfpPreview);
        } else {
            ivPfpPreview.setImageResource(R.drawable.ic_profile);
        }

        MaterialButton btnSave = findViewById(R.id.btnSave);
        TextView tvCancel = findViewById(R.id.tvCancel);

        // Fetch and display PFP changes remaining
        fetchPfpChangesRemaining();

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
                    if (!isFinishing() && !isDestroyed()) {
                        Glide.with(this).load(imageUri).into(ivPfpPreview);
                    }
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

            // Check if a new image was cropped OR if the old URL is different from current imageUri
            boolean pfpChanged = (imageUri != null) || (uploadedProfilePictureDownloadUrl != null && !uploadedProfilePictureDownloadUrl.equals(initialProfilePictureUrl));

            if (pfpChanged) {
                Log.d(TAG, "PFP changed, showing loading dialog and calling recordPfpChange Cloud Function.");
                loadingDialog.show();
                firebaseManager.recordPfpChange(new FirebaseManager.PfpChangeLimitListener() {
                    @Override
                    public void onSuccess(int pfpChangesToday, Date pfpCooldownResetTimestamp) { // Updated signature
                        if (isFinishing() || isDestroyed()) return;
                        Log.d(TAG, "PFP change recorded. Remaining: " + pfpChangesToday);
                        tvPfpChangesRemaining.setText("PFP changes remaining today: " + pfpChangesToday);
                        // The pfpCooldownResetTimestamp can be used to display when the limit resets,
                        // but for now, we'll just log it.
                        Log.d(TAG, "PFP Cooldown Reset Timestamp: " + pfpCooldownResetTimestamp);
                        
                        // --- MODERATION INTEGRATION START ---
                        // Before uploading, call the moderation function
                        String base64Image = encodeImage(imageUri);
                        if (base64Image == null) {
                            loadingDialog.dismiss();
                            Toast.makeText(EditProfileActivity.this, "Failed to encode image for moderation.", Toast.LENGTH_LONG).show();
                            return;
                        }

                        Log.d(TAG, "Calling moderation CF...");
                        firebaseManager.callOpenAiImageModeration(base64Image, new FirebaseManager.OpenAiModerationListener() {
                            @Override
                            public void onSuccess(boolean isAppropriate, String moderationReason) {
                                if (isFinishing() || isDestroyed()) return;
                                if (isAppropriate) {
                                    Log.d(TAG, "PFP appropriate, proceeding with uploadImageToFirebase.");
                                    uploadImageToFirebase(imageUri, newUsername, newBio); // Proceed with upload
                                } else {
                                    loadingDialog.dismiss();
                                    Toast.makeText(EditProfileActivity.this, "Profile picture rejected: " + moderationReason, Toast.LENGTH_LONG).show();
                                    Log.w(TAG, "PFP moderation failed: " + moderationReason);
                                    // Optionally, revert the PFP preview or clear the selected image
                                    if (initialProfilePictureUrl != null && !initialProfilePictureUrl.isEmpty()) {
                                        Glide.with(EditProfileActivity.this).load(initialProfilePictureUrl).into(ivPfpPreview);
                                    } else {
                                        ivPfpPreview.setImageResource(R.drawable.ic_profile);
                                    }
                                    imageUri = null; // Clear the temporary imageUri
                                    uploadedProfilePictureDownloadUrl = null; // Clear any uploaded URL
                                }
                            }

                            @Override
                            public void onFailure(String errorMessage) {
                                if (isFinishing() || isDestroyed()) return;
                                loadingDialog.dismiss();
                                Toast.makeText(EditProfileActivity.this, "Image moderation failed: " + errorMessage, Toast.LENGTH_LONG).show();
                                Log.e(TAG, "Error during PFP image moderation: " + errorMessage);
                            }
                        });
                        // --- MODERATION INTEGRATION END ---
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        if (isFinishing() || isDestroyed()) return;
                        loadingDialog.dismiss();
                        Log.e(TAG, "Failed to record PFP change: " + errorMessage);
                        Toast.makeText(EditProfileActivity.this, "Error: " + errorMessage, Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onLimitExceeded() {
                        if (isFinishing() || isDestroyed()) return;
                        loadingDialog.dismiss();
                        Log.w(TAG, "PFP change limit exceeded.");
                        Toast.makeText(EditProfileActivity.this, "You have reached your daily limit for profile picture changes.", Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                Log.d(TAG, "PFP not changed. Showing loading dialog and saving profile text changes.");
                loadingDialog.show();
                // If no new image, and username/bio changed, save those
                saveProfileChanges(newUsername, newBio, initialProfilePictureUrl); // Pass current PFP URL
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
            setResult(RESULT_CANCELED); // Set result to canceled
            finish();
        });
    }

    private void fetchUserProfileData() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            Log.e(TAG, "User not logged in. Cannot fetch profile data.");
            // Optionally, redirect to login or show an error
            return;
        }

        String userId = currentUser.getUid();
        firebaseManager.getUserProfile(userId, task -> {
            if (isFinishing() || isDestroyed()) return;
            if (task.isSuccessful() && task.getResult() != null) {
                User user = task.getResult().toObject(User.class);
                if (user != null) {
                    initialUsername = user.getUsername();
                    etUsername.setText(initialUsername);
                    // Also update initialBio and initialProfilePictureUrl if they are to be fetched from Firestore
                    // For now, only username is changed as per the request.
                    initialBio = user.getBio();
                    etBio.setText(initialBio);
                    if (user.getProfilePictureUrl() != null && !user.getProfilePictureUrl().isEmpty()) {
                        initialProfilePictureUrl = user.getProfilePictureUrl();
                        Glide.with(this).load(initialProfilePictureUrl).into(ivPfpPreview);
                    } else {
                        ivPfpPreview.setImageResource(R.drawable.ic_profile);
                        initialProfilePictureUrl = null;
                    }
                } else {
                    Log.e(TAG, "User document found but could not be parsed to User object.");
                    Toast.makeText(this, "Error loading profile data.", Toast.LENGTH_SHORT).show();
                }
            } else {
                Log.e(TAG, "Failed to fetch user profile: " + (task.getException() != null ? task.getException().getMessage() : "Unknown error"));
                Toast.makeText(this, "Failed to load profile data.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void fetchPfpChangesRemaining() {
        String userId = mAuth.getCurrentUser() != null ? mAuth.getCurrentUser().getUid() : null;
        if (userId == null) {
            tvPfpChangesRemaining.setText("Not logged in.");
            return;
        }

        db.collection("users").document(userId).get().addOnSuccessListener(documentSnapshot -> {
            if (isFinishing() || isDestroyed()) return;
            if (documentSnapshot.exists()) {
                // The Cloud Function 'checkUsernameAndEmailAvailability' should ensure these fields exist and are up-to-date
                Long pfpChangesTodayLong = documentSnapshot.getLong("pfpChangesToday");
                int pfpChangesToday = pfpChangesTodayLong != null ? pfpChangesTodayLong.intValue() : 0;
                tvPfpChangesRemaining.setText("PFP changes remaining today: " + pfpChangesToday);
                if (pfpChangesToday <= 0) {
                    btnChangePhoto.setEnabled(false);
                    Toast.makeText(this, "You have no PFP changes left today.", Toast.LENGTH_LONG).show();
                }
            } else {
                tvPfpChangesRemaining.setText("User data not found.");
            }
        }).addOnFailureListener(e -> {
            if (isFinishing() || isDestroyed()) return;
            Log.e(TAG, "Error fetching pfpChangesToday: " + e.getMessage(), e);
            tvPfpChangesRemaining.setText("Error loading PFP limits.");
        });
    }

    private void checkPermissionsAndPickImage() {
        // Only allow picking if changes are remaining
        String userId = mAuth.getCurrentUser() != null ? mAuth.getCurrentUser().getUid() : null;
        if (userId == null) {
            Toast.makeText(this, "Please log in to change profile picture.", Toast.LENGTH_SHORT).show();
            return;
        }
        db.collection("users").document(userId).get().addOnSuccessListener(documentSnapshot -> {
            if (isFinishing() || isDestroyed()) return;
            if (documentSnapshot.exists()) {
                Long pfpChangesTodayLong = documentSnapshot.getLong("pfpChangesToday");
                int pfpChangesToday = pfpChangesTodayLong != null ? pfpChangesTodayLong.intValue() : 0;
                if (pfpChangesToday <= 0) {
                    Toast.makeText(this, "You have reached your daily limit for profile picture changes.", Toast.LENGTH_LONG).show();
                    return;
                }
                // Proceed if limit is not exceeded
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
            } else {
                Toast.makeText(this, "User data not found to check limits.", Toast.LENGTH_SHORT).show();
            }
        }).addOnFailureListener(e -> {
            if (isFinishing() || isDestroyed()) return;
            Log.e(TAG, "Error checking PFP limits before picking image: " + e.getMessage(), e);
            Toast.makeText(this, "Error checking PFP limits. Try again later.", Toast.LENGTH_LONG).show();
        });
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
            loadingDialog.dismiss();
            Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Only attempt to upload if newImageUri is not null. Otherwise, it means the PFP wasn't changed via image picker.
        if (newImageUri == null) {
            Log.d(TAG, "No new image URI to upload. Proceeding to save profile changes with existing PFP URL.");
            saveProfileChanges(newUsername, newBio, initialProfilePictureUrl);
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
                if (isFinishing() || isDestroyed()) return;
                Log.d(TAG, "New image uploaded successfully to Firebase Storage.");
                newProfileImageRef.getDownloadUrl().addOnSuccessListener(uri -> {
                    if (isFinishing() || isDestroyed()) return;
                    String downloadUrl = uri.toString();
                    Log.d(TAG, "Download URL obtained for new image: " + downloadUrl);
                    uploadedProfilePictureDownloadUrl = downloadUrl; // Store the uploaded URL
                    saveProfileChanges(newUsername, newBio, uploadedProfilePictureDownloadUrl); // Save changes with new URL
                    Toast.makeText(this, "Profile picture updated!", Toast.LENGTH_SHORT).show();
                }).addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;
                    loadingDialog.dismiss();
                    Log.e(TAG, "Failed to get download URL for new image: " + e.getMessage(), e);
                    Toast.makeText(this, "Failed to get download URL: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            })
            .addOnFailureListener(e -> {
                if (isFinishing() || isDestroyed()) return;
                loadingDialog.dismiss();
                Log.e(TAG, "Failed to upload new image to Firebase Storage: " + e.getMessage(), e);
                Toast.makeText(this, "Failed to upload image: " + e.getMessage(), Toast.LENGTH_LONG).show();
            });
    }

    /**
     * Encodes a given image URI to a Base64 string.
     * @param imageUri The URI of the image to encode.
     * @return A Base64 encoded string of the image, or null if an error occurs.
     */
    private String encodeImage(Uri imageUri) {
        try {
            Bitmap bitmap;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                bitmap = ImageDecoder.decodeBitmap(
                        ImageDecoder.createSource(this.getContentResolver(), imageUri));
            } else {
                bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
            }

            // Scale down bitmap to a reasonable size for moderation to save on bandwidth and processing time
            int maxWidth = 512;
            int maxHeight = 512;
            float ratio = Math.min((float) maxWidth / bitmap.getWidth(),
                    (float) maxHeight / bitmap.getHeight());

            if (ratio < 1.0f) { // Only scale down if image is larger than max dimensions
                bitmap = Bitmap.createScaledBitmap(bitmap,
                        Math.round(ratio * bitmap.getWidth()),
                        Math.round(ratio * bitmap.getHeight()),
                        true);
            }

            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, byteArrayOutputStream);
            return Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP);

        } catch (IOException e) {
            Log.e(TAG, "Error encoding image for moderation", e);
            return null;
        }
    }

    private void saveProfileChanges(String newUsername, String newBio, String finalProfilePictureUrl) {
        String userId = mAuth.getCurrentUser().getUid();
        Log.d(TAG, "Saving profile changes for user: " + userId + ", PFP URL: " + finalProfilePictureUrl);

        Map<String, Object> updates = new HashMap<>();
        boolean hasChanges = false;

        // Check for username change
        if (!newUsername.equals(initialUsername)) {
            hasChanges = true;
            // Perform username uniqueness check is done implicitly by the `checkUsernameAvailability` CF
            // in `FirebaseManager.updateUserProfile` when the username changes.
            updates.put("username", newUsername);
        }

        // Check for bio change
        if (!newBio.equals(initialBio)) {
            updates.put("bio", newBio);
            hasChanges = true;
        }

        // Update profile picture URL only if it was genuinely changed (new image uploaded or existing cleared)
        if (finalProfilePictureUrl != null && !finalProfilePictureUrl.equals(initialProfilePictureUrl)) {
            updates.put("profilePictureUrl", finalProfilePictureUrl);
            hasChanges = true;
        } else if (initialProfilePictureUrl != null && finalProfilePictureUrl == null) {
            updates.put("profilePictureUrl", null);
            hasChanges = true;
        }

        // Use FirebaseManager to update user profile, which handles username uniqueness
        if (hasChanges) {
            // Create a temporary User object with only the fields to be updated + userId
            User userToUpdate = new User(userId, null, newUsername, null, null);
            userToUpdate.setBio(newBio);
            userToUpdate.setProfilePictureUrl(finalProfilePictureUrl); // This will be merged into Firestore

            firebaseManager.updateUserProfile(userToUpdate, new FirebaseManager.AuthListener() {
                @Override
                public void onSuccess(com.google.firebase.auth.FirebaseUser user) {
                    if (isFinishing() || isDestroyed()) return;
                    Log.d(TAG, "Firestore profile updated successfully via FirebaseManager.");
                    loadingDialog.dismiss();
                    // Update local 'initial' values to reflect what's now in Firestore
                    initialUsername = newUsername;
                    initialBio = newBio;
                    initialProfilePictureUrl = finalProfilePictureUrl; // Update with the final URL

                    Intent resultIntent = new Intent();
                    resultIntent.putExtra("newUsername", newUsername);
                    resultIntent.putExtra("newBio", newBio);
                    resultIntent.putExtra("newProfilePictureUrl", initialProfilePictureUrl); // Pass the now-current URL
                    setResult(RESULT_OK, resultIntent);
                    finish();
                }

                @Override
                public void onFailure(String errorMessage) {
                    if (isFinishing() || isDestroyed()) return;
                    loadingDialog.dismiss();
                    Log.e(TAG, "Failed to update profile in Firestore via FirebaseManager: " + errorMessage);
                    Toast.makeText(EditProfileActivity.this, "Failed to update profile: " + errorMessage, Toast.LENGTH_LONG).show();
                }

                @Override
                public void onUsernameTaken() {
                    if (isFinishing() || isDestroyed()) return;
                    loadingDialog.dismiss();
                    etUsername.setError("This username is already taken. Please choose a different one.");
                    Toast.makeText(EditProfileActivity.this, "Username is already taken.", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onEmailTaken() {
                    if (isFinishing() || isDestroyed()) return;
                    loadingDialog.dismiss();
                    Log.w(TAG, "onEmailTaken called unexpectedly in EditProfileActivity.");
                    Toast.makeText(EditProfileActivity.this, "Email is already in use by another account.", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            Log.d(TAG, "No profile changes detected to save to Firestore. Finishing activity.");
            loadingDialog.dismiss();
            Intent resultIntent = new Intent();
            resultIntent.putExtra("newUsername", initialUsername);
            resultIntent.putExtra("newBio", initialBio);
            resultIntent.putExtra("newProfilePictureUrl", initialProfilePictureUrl); // Pass the original/current PFP URL
            setResult(RESULT_OK, resultIntent);
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
        }
    }
}
