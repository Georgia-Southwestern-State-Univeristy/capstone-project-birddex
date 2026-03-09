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
import java.util.UUID;

public class EditProfileActivity extends AppCompatActivity implements NetworkMonitor.NetworkStatusListener {

    private static final String TAG = "EditProfileActivity";
    private static final int REQUEST_READ_EXTERNAL_STORAGE = 100;
    private static final int MAX_BIO_LENGTH = 90;

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
    private String uploadedProfilePictureDownloadUrl = null;

    private ActivityResultLauncher<Intent> pickImageLauncher;
    private ActivityResultLauncher<CropImageContractOptions> cropImageLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_profile);

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        storage = FirebaseStorage.getInstance();
        storageRef = storage.getReference();
        firebaseManager = new FirebaseManager(this);
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
                    uploadedProfilePictureDownloadUrl = null;
                }
            }
        });

        btnChangePhoto.setOnClickListener(v -> checkPermissionsAndPickImage());

        btnSave.setOnClickListener(v -> {
            if (isSaveInProgress) return;
            if (!networkMonitor.isConnected()) {
                Toast.makeText(this, "No internet connection.", Toast.LENGTH_LONG).show();
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
                Toast.makeText(this, "Bio too long.", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!ContentFilter.isSafe(this, newUsername, "Username")) return;
            if (!ContentFilter.isSafe(this, cleanedBio, "Bio")) return;

            boolean pfpChanged = (imageUri != null);

            isSaveInProgress = true;
            loadingOverlay.setVisibility(View.VISIBLE);

            if (pfpChanged) {
                String pfpChangeId = UUID.randomUUID().toString();
                firebaseManager.recordPfpChange(pfpChangeId, new FirebaseManager.PfpChangeLimitListener() {
                    @Override
                    public void onSuccess(int remaining, Date reset) {
                        if (isFinishing() || isDestroyed()) return;
                        tvPfpChangesRemaining.setText("PFP changes remaining today: " + remaining);
                        String base64 = encodeImage(imageUri);
                        if (base64 == null) {
                            resetSaveState();
                            return;
                        }
                        firebaseManager.callOpenAiImageModeration(base64, new FirebaseManager.OpenAiModerationListener() {
                            @Override
                            public void onSuccess(boolean appropriate, String reason) {
                                if (isFinishing() || isDestroyed()) return;
                                if (appropriate) uploadImageToFirebase(imageUri, newUsername, cleanedBio);
                                else {
                                    resetSaveState();
                                    Toast.makeText(EditProfileActivity.this, "PFP rejected: " + reason, Toast.LENGTH_LONG).show();
                                }
                            }
                            @Override public void onFailure(String err) { resetSaveState(); }
                        });
                    }
                    @Override public void onFailure(String err) { resetSaveState(); }
                    @Override public void onLimitExceeded() { resetSaveState(); }
                });
            } else {
                saveProfileChanges(newUsername, cleanedBio, initialProfilePictureUrl);
            }
        });

        tvCancel.setOnClickListener(v -> finish());
    }

    private void resetSaveState() {
        isSaveInProgress = false;
        loadingOverlay.setVisibility(View.GONE);
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
                        Glide.with(EditProfileActivity.this).load(initialProfilePictureUrl).into(ivPfpPreview);
                    }
                }
            }
        });
    }

    private void fetchPfpChangesRemaining() {
        String uid = mAuth.getUid();
        if (uid == null) return;
        db.collection("users").document(uid).get().addOnSuccessListener(doc -> {
            if (isFinishing() || isDestroyed()) return;
            Long rem = doc.getLong("pfpChangesToday");
            if (rem != null) {
                tvPfpChangesRemaining.setText("PFP changes remaining today: " + rem);
                if (rem <= 0) btnChangePhoto.setEnabled(false);
            }
        });
    }

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

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        pickImageLauncher.launch(intent);
    }

    private void startImageCropper(Uri uri) {
        CropImageOptions opt = new CropImageOptions();
        opt.cropShape = CropImageView.CropShape.OVAL;
        opt.fixAspectRatio = true;
        cropImageLauncher.launch(new CropImageContractOptions(uri, opt));
    }

    private void uploadImageToFirebase(Uri uri, String user, String bio) {
        String uid = mAuth.getUid();
        StorageReference ref = storageRef.child("profile_pictures/" + uid + "_" + System.currentTimeMillis() + ".jpg");
        ref.putFile(uri).addOnSuccessListener(ts -> ref.getDownloadUrl().addOnSuccessListener(dl -> saveProfileChanges(user, bio, dl.toString())))
                .addOnFailureListener(e -> resetSaveState());
    }

    private String encodeImage(Uri uri) {
        try {
            Bitmap bm = (Build.VERSION.SDK_INT >= 28)
                    ? ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri))
                    : MediaStore.Images.Media.getBitmap(getContentResolver(), uri);

            // Resize to max 512x512 before encoding — prevents OpenAI 500 errors from oversized payloads
            bm = scaleBitmap(bm, 512);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 60, baos);
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (IOException e) { return null; }
    }

    private Bitmap scaleBitmap(Bitmap original, int maxSize) {
        int width = original.getWidth();
        int height = original.getHeight();
        float ratio = Math.min((float) maxSize / width, (float) maxSize / height);
        if (ratio >= 1f) return original;
        return Bitmap.createScaledBitmap(original, (int)(width * ratio), (int)(height * ratio), true);
    }

    private void saveProfileChanges(String newUsername, String cleanedBio, String finalPfp) {
        // FIX #13: use updateUserProfileAtomic to ensure username uniqueness via Cloud Function
        User user = new User(mAuth.getUid(), null, newUsername, null, null);
        user.setBio(cleanedBio);
        user.setProfilePictureUrl(finalPfp);

        firebaseManager.updateUserProfileAtomic(user, new FirebaseManager.AuthListener() {
            @Override
            public void onSuccess(com.google.firebase.auth.FirebaseUser u) {
                if (isFinishing() || isDestroyed()) return;
                resetSaveState();
                setResult(RESULT_OK, new Intent().putExtra("newUsername", newUsername).putExtra("newBio", cleanedBio).putExtra("newProfilePictureUrl", finalPfp));
                finish();
            }
            @Override
            public void onFailure(String err) { resetSaveState(); Toast.makeText(EditProfileActivity.this, "Update failed.", Toast.LENGTH_SHORT).show(); }
            @Override
            public void onUsernameTaken() { resetSaveState(); etUsername.setError("Username taken."); }
            @Override public void onEmailTaken() { resetSaveState(); }
        });
    }

    @Override protected void onPause() { super.onPause(); networkMonitor.unregister(); if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE); }
    @Override protected void onResume() { super.onResume(); networkMonitor.register(); if (isSaveInProgress && loadingOverlay != null) loadingOverlay.setVisibility(View.VISIBLE); }
    @Override public void onNetworkAvailable() {}
    @Override public void onNetworkLost() { runOnUiThread(() -> { if (loadingOverlay != null && loadingOverlay.getVisibility() == View.VISIBLE) resetSaveState(); }); }
}
