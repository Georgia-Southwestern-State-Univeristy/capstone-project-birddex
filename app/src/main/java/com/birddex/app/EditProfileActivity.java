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
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.bumptech.glide.Glide;

import com.canhub.cropper.CropImageContract;
import com.canhub.cropper.CropImageContractOptions;
import com.canhub.cropper.CropImageOptions;
import com.canhub.cropper.CropImageView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Date;

public class EditProfileActivity extends AppCompatActivity implements NetworkMonitor.NetworkStatusListener {

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
    private FirebaseManager firebaseManager;
    private LoadingDialog loadingDialog;
    private NetworkMonitor networkMonitor;

    // UI elements
    private ImageView ivPfpPreview;
    private MaterialButton btnChangePhoto;

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
        firebaseManager = new FirebaseManager(this);
        loadingDialog = new LoadingDialog(this);
        networkMonitor = new NetworkMonitor(this, this);

        // Initialize UI
        ivPfpPreview = findViewById(R.id.ivPfpPreview);
        btnChangePhoto = findViewById(R.id.btnChangePhoto);
        etUsername = findViewById(R.id.etUsername);
        etBio = findViewById(R.id.etBio);
        tvPfpChangesRemaining = findViewById(R.id.tvPfpChangesRemaining);

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
            if (!networkMonitor.isConnected()) {
                Toast.makeText(this, "No internet connection. Please check your network and try again.", Toast.LENGTH_LONG).show();
                return;
            }

            String newUsername = etUsername.getText().toString().trim();
            String newBio = etBio.getText().toString();

            if (newUsername.isEmpty()) {
                etUsername.setError("Username cannot be empty");
                return;
            }

            if (ContentFilter.containsInappropriateContent(newUsername)) {
                etUsername.setError("Inappropriate username");
                Toast.makeText(this, "Username contains inappropriate language", Toast.LENGTH_SHORT).show();
                return;
            }

            if (ContentFilter.containsInappropriateContent(newBio)) {
                etBio.setError("Inappropriate bio");
                Toast.makeText(this, "Bio contains inappropriate language", Toast.LENGTH_SHORT).show();
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
                                    Toast.makeText(EditProfileActivity.this, "PFP rejected: " + moderationReason, Toast.LENGTH_LONG).show();
                                    if (initialProfilePictureUrl != null && !initialProfilePictureUrl.isEmpty()) {
                                        Glide.with(EditProfileActivity.this).load(initialProfilePictureUrl).into(ivPfpPreview);
                                    } else {
                                        ivPfpPreview.setImageResource(R.drawable.ic_profile);
                                    }
                                    imageUri = null;
                                }
                            }

                            @Override
                            public void onFailure(String errorMessage) {
                                if (isFinishing() || isDestroyed()) return;
                                loadingDialog.dismiss();
                                Log.e(TAG, "Moderation failed: " + errorMessage);
                                Toast.makeText(EditProfileActivity.this, "Moderation failed.", Toast.LENGTH_LONG).show();
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
                        Log.w(TAG, "PFP limit exceeded.");
                        Toast.makeText(EditProfileActivity.this, "Daily limit reached.", Toast.LENGTH_LONG).show();
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
            imageUri = null;
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    private void fetchUserProfileData() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) return;

        firebaseManager.getUserProfile(currentUser.getUid(), task -> {
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
        if (userId == null) return;

        db.collection("users").document(userId).get().addOnSuccessListener(documentSnapshot -> {
            if (isFinishing() || isDestroyed()) return;
            if (documentSnapshot.exists()) {
                // The Cloud Function 'checkUsernameAndEmailAvailability' should ensure these fields exist and are up-to-date
                Long pfpChangesTodayLong = documentSnapshot.getLong("pfpChangesToday");
                int pfpChangesToday = pfpChangesTodayLong != null ? pfpChangesTodayLong.intValue() : 0;
                tvPfpChangesRemaining.setText("PFP changes remaining today: " + pfpChangesToday);
                if (pfpChangesToday <= 0) btnChangePhoto.setEnabled(false);
            }
        });
    }

    private void checkPermissionsAndPickImage() {
        String userId = mAuth.getCurrentUser() != null ? mAuth.getCurrentUser().getUid() : null;
        if (userId == null) return;
        db.collection("users").document(userId).get().addOnSuccessListener(documentSnapshot -> {
            if (isFinishing() || isDestroyed()) return;
            if (documentSnapshot.exists()) {
                Long pfpChangesTodayLong = documentSnapshot.getLong("pfpChangesToday");
                if (pfpChangesTodayLong != null && pfpChangesTodayLong <= 0) {
                    Toast.makeText(this, "Daily limit reached.", Toast.LENGTH_LONG).show();
                    return;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQUEST_READ_EXTERNAL_STORAGE);
                    } else pickImage();
                } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_READ_EXTERNAL_STORAGE);
                } else pickImage();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_READ_EXTERNAL_STORAGE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) pickImage();
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
        if (mAuth.getCurrentUser() == null || newImageUri == null) {
            saveProfileChanges(newUsername, newBio, initialProfilePictureUrl);
            return;
        }

        String userId = mAuth.getCurrentUser().getUid();
        if (initialProfilePictureUrl != null && !initialProfilePictureUrl.isEmpty() && initialProfilePictureUrl.contains("firebasestorage")) {
            try {
                storage.getReferenceFromUrl(initialProfilePictureUrl).delete();
            } catch (Exception ignored) {}
        }

        StorageReference newProfileImageRef = storageRef.child("profile_pictures/" + userId + ".jpg");
        newProfileImageRef.putFile(newImageUri)
            .addOnSuccessListener(taskSnapshot -> {
                if (isFinishing() || isDestroyed()) return;
                newProfileImageRef.getDownloadUrl().addOnSuccessListener(uri -> {
                    if (isFinishing() || isDestroyed()) return;
                    saveProfileChanges(newUsername, newBio, uri.toString());
                }).addOnFailureListener(e -> {
                    loadingDialog.dismiss();
                    Toast.makeText(this, "Failed to get URL", Toast.LENGTH_SHORT).show();
                });
            })
            .addOnFailureListener(e -> {
                loadingDialog.dismiss();
                Toast.makeText(this, "Upload failed", Toast.LENGTH_SHORT).show();
            });
    }

    private String encodeImage(Uri imageUri) {
        try {
            Bitmap bitmap = (Build.VERSION.SDK_INT >= 28) ? ImageDecoder.decodeBitmap(ImageDecoder.createSource(this.getContentResolver(), imageUri)) : MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
            int maxWidth = 512, maxHeight = 512;
            float ratio = Math.min((float) maxWidth / bitmap.getWidth(), (float) maxHeight / bitmap.getHeight());
            if (ratio < 1.0f) bitmap = Bitmap.createScaledBitmap(bitmap, Math.round(ratio * bitmap.getWidth()), Math.round(ratio * bitmap.getHeight()), true);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos);
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (IOException e) { return null; }
    }

    private void saveProfileChanges(String newUsername, String newBio, String finalProfilePictureUrl) {
        boolean hasChanges = !newUsername.equals(initialUsername) || !newBio.equals(initialBio) || (finalProfilePictureUrl != null && !finalProfilePictureUrl.equals(initialProfilePictureUrl));

        if (hasChanges) {
            User userToUpdate = new User(mAuth.getCurrentUser().getUid(), null, newUsername, null, null);
            userToUpdate.setBio(newBio);
            userToUpdate.setProfilePictureUrl(finalProfilePictureUrl);

            firebaseManager.updateUserProfile(userToUpdate, new FirebaseManager.AuthListener() {
                @Override
                public void onSuccess(com.google.firebase.auth.FirebaseUser user) {
                    if (isFinishing() || isDestroyed()) return;
                    loadingDialog.dismiss();
                    setResult(RESULT_OK, new Intent().putExtra("newUsername", newUsername).putExtra("newBio", newBio).putExtra("newProfilePictureUrl", finalProfilePictureUrl));
                    finish();
                }

                @Override
                public void onFailure(String errorMessage) {
                    loadingDialog.dismiss();
                    Toast.makeText(EditProfileActivity.this, "Update failed: " + errorMessage, Toast.LENGTH_LONG).show();
                }

                @Override
                public void onUsernameTaken() {
                    loadingDialog.dismiss();
                    etUsername.setError("Username taken.");
                }

                @Override public void onEmailTaken() { loadingDialog.dismiss(); }
            });
        } else {
            loadingDialog.dismiss();
            setResult(RESULT_OK, new Intent().putExtra("newUsername", initialUsername).putExtra("newBio", initialBio).putExtra("newProfilePictureUrl", initialProfilePictureUrl));
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        networkMonitor.register();
    }

    @Override
    protected void onPause() {
        super.onPause();
        networkMonitor.unregister();
        if (loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
        }
    }

    @Override
    public void onNetworkAvailable() {
        Log.d(TAG, "Network available.");
    }

    @Override
    public void onNetworkLost() {
        Log.w(TAG, "Network lost.");
        runOnUiThread(() -> {
            if (loadingDialog != null && loadingDialog.isShowing()) {
                loadingDialog.dismiss();
                Toast.makeText(this, "Network connection lost. Please try again.", Toast.LENGTH_LONG).show();
            }
        });
    }
}
