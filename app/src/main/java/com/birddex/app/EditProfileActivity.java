package com.birddex.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
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
import com.google.firebase.storage.StorageMetadata;
import com.bumptech.glide.Glide;

import com.canhub.cropper.CropImageContract;
import com.canhub.cropper.CropImageContractOptions;
import com.canhub.cropper.CropImageOptions;
import com.canhub.cropper.CropImageView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.UUID;

/**
 * EditProfileActivity: Screen that edits profile fields and writes the changes back to the user record.
 * The screen still does instant frontend filtering, and backend profile filtering now backs it up on save.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class EditProfileActivity extends AppCompatActivity implements NetworkMonitor.NetworkStatusListener {

    private static final String TAG = "EditProfileActivity";
    private static final int REQUEST_READ_EXTERNAL_STORAGE = 100;
    private static final int MAX_BIO_LENGTH = 100;

    private TextInputEditText etUsername;
    private TextInputEditText etBio;
    private TextView tvPfpChangesRemaining;
    private String initialUsername;
    private String initialBio;
    private String initialProfilePictureUrl;

    // Firebase
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseStorage storage;
    private StorageReference storageRef;
    private FirebaseManager firebaseManager;
    private FrameLayout loadingOverlay;
    private NetworkMonitor networkMonitor;

    // UI elements
    private ImageView ivPfpPreview;
    private MaterialButton btnChangePhoto;

    private Uri imageUri;
    private boolean isSaveInProgress = false;
    private String pendingPfpChangeId = null;
    private StorageReference pendingUploadedPfpRef = null;

    private ActivityResultLauncher<Intent> pickImageLauncher;
    private ActivityResultLauncher<CropImageContractOptions> cropImageLauncher;

    private interface PreparedProfileImageListener {
        void onSuccess(String moderationBase64, byte[] uploadBytes);
        void onFailure(String errorMessage);
    }

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_profile);

        mAuth = FirebaseAuth.getInstance();
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db = FirebaseFirestore.getInstance();
        storage = FirebaseStorage.getInstance();
        storageRef = storage.getReference();
        firebaseManager = new FirebaseManager(this);
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        loadingOverlay = findViewById(R.id.loadingOverlay);
        networkMonitor = new NetworkMonitor(this, this);

        ivPfpPreview = findViewById(R.id.ivPfpPreview);
        btnChangePhoto = findViewById(R.id.btnChangePhoto);
        etUsername = findViewById(R.id.etUsername);
        etBio = findViewById(R.id.etBio);
        tvPfpChangesRemaining = findViewById(R.id.tvPfpChangesRemaining);

        initialBio = getIntent().getStringExtra("bio");
        initialProfilePictureUrl = getIntent().getStringExtra("profilePictureUrl");

        etBio.setText(initialBio);
        fetchUserProfileData();

        if (initialProfilePictureUrl != null && !initialProfilePictureUrl.isEmpty()) {
            // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
            Glide.with(this).load(initialProfilePictureUrl).into(ivPfpPreview);
        } else {
            ivPfpPreview.setImageResource(R.drawable.ic_profile);
        }

        MaterialButton btnSave = findViewById(R.id.btnSave);
        TextView tvCancel = findViewById(R.id.tvCancel);

        fetchPfpChangesRemaining();

        pickImageLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri selectedImageUri = result.getData().getData();
                        if (selectedImageUri != null) {
                            startImageCropper(selectedImageUri);
                        }
                    }
                });

        cropImageLauncher = registerForActivityResult(new CropImageContract(), result -> {
            if (result.isSuccessful()) {
                imageUri = result.getUriContent();
                if (imageUri != null) {
                    if (!isFinishing() && !isDestroyed()) Glide.with(this).load(imageUri).into(ivPfpPreview);
                }
            }
        });

        // Attach the user interaction that should run when this control is tapped.
        btnChangePhoto.setOnClickListener(v -> checkPermissionsAndPickImage());

        btnSave.setOnClickListener(v -> {
            if (isSaveInProgress) return;
            if (!networkMonitor.isConnected()) {
                MessagePopupHelper.show(this, "No internet connection.");
                return;
            }

            String newUsername = etUsername.getText().toString().trim();
            String rawBio = etBio.getText().toString().trim();
            String cleanedBio = rawBio.replaceAll("\n{2,}", "\n").replaceAll(" +", " ");

            if (newUsername.isEmpty()) {
                etUsername.setError("Username cannot be empty");
                return;
            }

            if (cleanedBio.length() > MAX_BIO_LENGTH) {
                MessagePopupHelper.show(this, "Bio too long.");
                return;
            }

            if (!ContentFilter.isSafe(this, newUsername, "Username")) {
                firebaseManager.logFilteredContentAttempt("username_profile_update_client_block", "username", newUsername, null, null);
                return;
            }
            if (!ContentFilter.isSafe(this, cleanedBio, "Bio")) {
                firebaseManager.logFilteredContentAttempt("bio_profile_update_client_block", "bio", cleanedBio, null, null);
                return;
            }

            boolean pfpChanged = (imageUri != null);

            isSaveInProgress = true;
            loadingOverlay.setVisibility(View.VISIBLE);

            if (pfpChanged) {
                pendingPfpChangeId = UUID.randomUUID().toString();
                firebaseManager.recordPfpChange(pendingPfpChangeId, new FirebaseManager.PfpChangeLimitListener() {
                    @Override
                    public void onSuccess(int remaining, Date reset) {
                        if (isFinishing() || isDestroyed()) return;
                        tvPfpChangesRemaining.setText("PFP changes remaining today: " + remaining);
                        prepareProfileImageAsync(imageUri, new PreparedProfileImageListener() {
                            @Override
                            public void onSuccess(String moderationBase64, byte[] uploadBytes) {
                                if (isFinishing() || isDestroyed()) return;
                                firebaseManager.callOpenAiImageModeration(moderationBase64, new FirebaseManager.OpenAiModerationListener() {
                                    @Override
                                    public void onSuccess(boolean appropriate, String reason) {
                                        if (isFinishing() || isDestroyed()) return;
                                        if (appropriate) {
                                            uploadPreparedImageToFirebase(uploadBytes, newUsername, cleanedBio, pendingPfpChangeId);
                                        } else {
                                            String message = (reason != null && !reason.trim().isEmpty())
                                                    ? "PFP rejected: " + reason
                                                    : "PFP rejected by moderation.";
                                            rollbackPendingPfpChange(message);
                                        }
                                    }

                                    @Override
                                    public void onFailure(String err) {
                                        String message = (err != null && !err.trim().isEmpty())
                                                ? err
                                                : "Moderation check failed.";
                                        rollbackPendingPfpChange(message);
                                    }
                                });
                            }

                            @Override
                            public void onFailure(String errorMessage) {
                                rollbackPendingPfpChange(errorMessage);
                            }
                        });
                    }

                    @Override
                    public void onFailure(String err) {
                        resetSaveState();
                        String message = (err != null && !err.trim().isEmpty()) ? err : "Failed to reserve profile picture change.";
                        MessagePopupHelper.show(EditProfileActivity.this, message);
                    }

                    @Override
                    public void onLimitExceeded() {
                        resetSaveState();
                        MessagePopupHelper.show(EditProfileActivity.this, "No profile picture changes remaining today.");
                    }
                });
            } else {
                saveProfileChanges(newUsername, cleanedBio, initialProfilePictureUrl, null);
            }
        });

        tvCancel.setOnClickListener(v -> finish());
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void resetSaveState() {
        isSaveInProgress = false;
        pendingPfpChangeId = null;
        pendingUploadedPfpRef = null;
        if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
    }

    /**
     * Rolls back the pending profile-picture change reservation so failed or rejected attempts do
     * not consume the user's daily profile picture quota.
     */
    private void rollbackPendingPfpChange(String toastMessage) {
        String changeId = pendingPfpChangeId;
        pendingPfpChangeId = null;
        if (changeId == null) {
            resetSaveState();
            if (toastMessage != null && !toastMessage.trim().isEmpty()) {
                MessagePopupHelper.show(this, toastMessage);
            }
            return;
        }

        firebaseManager.rollbackPfpChange(changeId, new FirebaseManager.SimpleListener() {
            @Override
            public void onSuccess() {
                if (isFinishing() || isDestroyed()) return;
                fetchPfpChangesRemaining();
                resetSaveState();
                if (toastMessage != null && !toastMessage.trim().isEmpty()) {
                    MessagePopupHelper.show(EditProfileActivity.this, toastMessage);
                }
            }

            @Override
            public void onFailure(String errorMessage) {
                if (isFinishing() || isDestroyed()) return;
                Log.e(TAG, "rollbackPfpChange failed: " + errorMessage);
                fetchPfpChangesRemaining();
                resetSaveState();
                String message = (toastMessage != null && !toastMessage.trim().isEmpty())
                        ? toastMessage
                        : "Profile picture update failed.";
                MessagePopupHelper.show(EditProfileActivity.this, message);
            }
        });
    }

    /**
     * Deletes a just-uploaded profile picture if the later profile update fails so storage does not
     * keep orphaned files from failed attempts.
     */
    private void cleanupUploadedPfpAndRollback(String toastMessage) {
        StorageReference uploadedRef = pendingUploadedPfpRef;
        pendingUploadedPfpRef = null;
        if (uploadedRef == null) {
            rollbackPendingPfpChange(toastMessage);
            return;
        }

        uploadedRef.delete()
                .addOnCompleteListener(task -> rollbackPendingPfpChange(toastMessage));
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * Image loading happens here, which is why placeholder/error behavior for profile
     * photos/cards/posts usually traces back to this code path.
     */
    private void fetchUserProfileData() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) return;
        firebaseManager.getUserProfile(currentUser.getUid(), task -> {
            if (isFinishing() || isDestroyed()) return;
            if (task.isSuccessful() && task.getResult() != null) {
                User user = task.getResult().toObject(User.class);
                if (user != null) {
                    initialUsername = user.getUsername();
                    initialProfilePictureUrl = user.getProfilePictureUrl();

                    // Only pre-fill username if user hasn't started editing it yet
                    if (etUsername.getText().toString().isEmpty()) {
                        etUsername.setText(initialUsername);
                    }

                    // CRITICAL: Only pre-fill bio from Firestore if the field is still
                    // showing the value passed via Intent (i.e. user hasn't typed anything).
                    // If we unconditionally call etBio.setText() here, we wipe whatever
                    // the user typed in the window between onCreate and this async callback.
                    String currentBioText = etBio.getText().toString();
                    String intentBio = initialBio != null ? initialBio : "";
                    if (currentBioText.equals(intentBio)) {
                        initialBio = user.getBio() != null ? user.getBio() : "";
                        etBio.setText(initialBio);
                    }
                    // Always update initialBio so save button uses the fresh value
                    // even if we didn't overwrite the EditText
                    if (!currentBioText.equals(intentBio)) {
                        initialBio = user.getBio() != null ? user.getBio() : "";
                    }

                    if (initialProfilePictureUrl != null && !initialProfilePictureUrl.isEmpty()) {
                        // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
                        Glide.with(EditProfileActivity.this).load(initialProfilePictureUrl).into(ivPfpPreview);
                    }
                }
            }
        });
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    private void fetchPfpChangesRemaining() {
        String uid = mAuth.getUid();
        if (uid == null) return;
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("users").document(uid).get().addOnSuccessListener(doc -> {
            if (isFinishing() || isDestroyed()) return;
            Long rem = doc.getLong("pfpChangesToday");
            if (rem != null) {
                tvPfpChangesRemaining.setText("PFP changes remaining today: " + rem);
                if (rem <= 0) btnChangePhoto.setEnabled(false);
            }
        });
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void checkPermissionsAndPickImage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQUEST_READ_EXTERNAL_STORAGE);
            } else pickImage();
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_READ_EXTERNAL_STORAGE);
            } else pickImage();
        }
    }

    /**
     * Main logic block for this part of the feature.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        pickImageLauncher.launch(intent);
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void startImageCropper(Uri uri) {
        CropImageOptions opt = new CropImageOptions();
        opt.cropShape = CropImageView.CropShape.OVAL;
        opt.fixAspectRatio = true;
        cropImageLauncher.launch(new CropImageContractOptions(uri, opt));
    }

    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     */
    private void uploadPreparedImageToFirebase(byte[] uploadBytes, String user, String bio, String changeId) {
        String uid = mAuth.getUid();
        if (uid == null) {
            rollbackPendingPfpChange("Not signed in.");
            return;
        }

        pendingUploadedPfpRef = storageRef.child("profile_pictures/" + uid + "/profile_" + System.currentTimeMillis() + ".jpg");
        StorageMetadata metadata = new StorageMetadata.Builder()
                .setContentType("image/jpeg")
                .build();

        pendingUploadedPfpRef.putBytes(uploadBytes, metadata)
                .addOnSuccessListener(ts -> pendingUploadedPfpRef.getDownloadUrl()
                        .addOnSuccessListener(dl -> saveProfileChanges(user, bio, dl.toString(), changeId))
                        .addOnFailureListener(e -> cleanupUploadedPfpAndRollback("Failed to get uploaded profile picture URL.")))
                .addOnFailureListener(e -> rollbackPendingPfpChange("Profile picture upload failed."));
    }

    /**
     * Prepares both the moderation payload and the smaller upload bytes off the main thread so the
     * actual upload finishes faster and the UI is not doing bitmap work on the UI thread.
     */
    private void prepareProfileImageAsync(Uri uri, PreparedProfileImageListener listener) {
        new Thread(() -> {
            try {
                Bitmap original = (Build.VERSION.SDK_INT >= 28)
                        ? ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri))
                        : MediaStore.Images.Media.getBitmap(getContentResolver(), uri);

                Bitmap moderationBitmap = scaleBitmap(original, 768);
                ByteArrayOutputStream moderationStream = new ByteArrayOutputStream();
                moderationBitmap.compress(Bitmap.CompressFormat.JPEG, 80, moderationStream);
                String moderationBase64 = Base64.encodeToString(moderationStream.toByteArray(), Base64.NO_WRAP);

                Bitmap uploadBitmap = scaleBitmap(original, 960);
                ByteArrayOutputStream uploadStream = new ByteArrayOutputStream();
                uploadBitmap.compress(Bitmap.CompressFormat.JPEG, 82, uploadStream);
                byte[] uploadBytes = uploadStream.toByteArray();

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    listener.onSuccess(moderationBase64, uploadBytes);
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    listener.onFailure("Could not prepare profile picture for upload.");
                });
            }
        }).start();
    }

    /**
     * Main logic block for this part of the feature.
     * Bitmap/rendering work happens here, so this block is shaping the final card/image output
     * rather than just text data.
     */
    private Bitmap scaleBitmap(Bitmap original, int maxSize) {
        int width = original.getWidth();
        int height = original.getHeight();
        float ratio = Math.min((float) maxSize / width, (float) maxSize / height);
        if (ratio >= 1f) return original;
        return Bitmap.createScaledBitmap(original, (int)(width * ratio), (int)(height * ratio), true);
    }

    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void saveProfileChanges(String newUsername, String cleanedBio, String finalPfp, String changeId) {
        User user = new User(mAuth.getUid(), null, newUsername, null, null);
        user.setBio(cleanedBio);
        user.setProfilePictureUrl(finalPfp);

        firebaseManager.updateUserProfileAtomic(user, changeId, new FirebaseManager.AuthListener() {
            @Override
            public void onSuccess(com.google.firebase.auth.FirebaseUser u) {
                if (isFinishing() || isDestroyed()) return;
                resetSaveState();
                setResult(RESULT_OK, new Intent()
                        .putExtra("newUsername", newUsername)
                        .putExtra("newBio", cleanedBio)
                        .putExtra("newProfilePictureUrl", finalPfp));
                finish();
            }

            @Override
            public void onFailure(String err) {
                if (changeId != null) {
                    cleanupUploadedPfpAndRollback((err != null && !err.trim().isEmpty()) ? err : "Update failed.");
                } else {
                    resetSaveState();
                    String message = (err != null && !err.trim().isEmpty()) ? err : "Update failed.";
                    MessagePopupHelper.show(EditProfileActivity.this, message);
                }
            }

            @Override
            public void onUsernameTaken() {
                if (changeId != null) {
                    cleanupUploadedPfpAndRollback("Username taken.");
                } else {
                    resetSaveState();
                    etUsername.setError("Username taken.");
                }
            }

            @Override
            public void onEmailTaken() {
                if (changeId != null) {
                    cleanupUploadedPfpAndRollback("Email taken.");
                } else {
                    resetSaveState();
                }
            }
        });
    }

    @Override protected void onPause() { super.onPause(); networkMonitor.unregister(); if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE); }
    @Override protected void onResume() { super.onResume(); networkMonitor.register(); if (isSaveInProgress && loadingOverlay != null) loadingOverlay.setVisibility(View.VISIBLE); }
    @Override public void onNetworkAvailable() {}
    @Override public void onNetworkLost() { runOnUiThread(() -> { if (loadingOverlay != null && loadingOverlay.getVisibility() == View.VISIBLE) resetSaveState(); }); }
}
