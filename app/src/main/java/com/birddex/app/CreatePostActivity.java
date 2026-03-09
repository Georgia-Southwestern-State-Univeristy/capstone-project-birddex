package com.birddex.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.birddex.app.databinding.ActivityCreatePostBinding;
import com.bumptech.glide.Glide;
import com.canhub.cropper.CropImageContract;
import com.canhub.cropper.CropImageContractOptions;
import com.canhub.cropper.CropImageOptions;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.UUID;

/**
 * CreatePostActivity handles community post creation.
 *
 * Fixes applied:
 *  - Fix #25: Prevents duplicate posts on rotation via CreatePostViewModel.
 *  - Fix #3:  Prevents orphaned Storage images by persisting uploadedImageUrl in ViewModel.
 *  - Fix POST LIMIT: Added server-side 3-posts-per-day enforcement via `recordForumPost` CF.
 *
 * Why the CF is necessary for the post limit (race condition analysis):
 *   A purely client-side counter can be bypassed: the user could kill + reopen the app,
 *   or two concurrent taps could both read "2 posts today" before either increments to 3.
 *   The `recordForumPost` CF uses a Firestore transaction to atomically read the current
 *   daily count and increment it in one operation, so concurrent calls for the same user
 *   serialize correctly — only one of them reaches count = 3 and is allowed through.
 *
 * Flow on post attempt:
 *   1. Local validation (empty message, length, content filter)
 *   2. recordForumPost CF → server checks + increments daily count atomically
 *   3a. If allowed=false  → show "limit reached" toast, abort
 *   3b. If allowed=true   → upload image (if any) → write Firestore post doc
 *
 * The CF is called BEFORE the image upload so we don't burn Storage quota on a
 * post that would be rejected anyway.
 */
public class CreatePostActivity extends AppCompatActivity {

    private static final String TAG = "CreatePostActivity";
    private static final int REQUEST_READ_EXTERNAL_STORAGE = 101;
    private static final int REQUEST_LOCATION_PERMISSION   = 102;
    private static final String PREFS_NAME          = "BirdDexPrefs";
    private static final String KEY_GRAPHIC_CONTENT = "show_graphic_content";
    private static final int MAX_POST_LENGTH = 500;

    private ActivityCreatePostBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseStorage storage;
    private FirebaseManager firebaseManager;
    private CreatePostViewModel viewModel;
    private View loadingOverlay;

    private Uri selectedImageUri;
    private String currentUsername;
    private String currentUserProfilePicUrl;

    private FusedLocationProviderClient fusedLocationClient;
    private Double currentLatitude  = null;
    private Double currentLongitude = null;

    private ActivityResultLauncher<Intent> pickImageLauncher;
    private ActivityResultLauncher<CropImageContractOptions> cropImageLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCreatePostBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mAuth           = FirebaseAuth.getInstance();
        storage         = FirebaseStorage.getInstance();
        firebaseManager = new FirebaseManager(this);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        loadingOverlay  = findViewById(R.id.loadingOverlay);

        viewModel = new ViewModelProvider(this).get(CreatePostViewModel.class);

        // Stable ID for this post attempt — survives rotation
        if (viewModel.getPendingPostId() == null) {
            viewModel.setPendingPostId(
                    FirebaseFirestore.getInstance().collection("forumThreads").document().getId());
        }

        setupUI();
        loadUserInfo();
        setupImageLaunchers();

        if (hasLocationPermission()) fetchLocation();

        // Rotation resume: if upload finished but Firestore write didn't, retry it
        if (viewModel.isPostInProgress.get()) {
            setPostingUi(true);
            if (viewModel.getUploadedImageUrl() != null) {
                // Limit was already claimed before upload; skip straight to Firestore write
                String msg = binding.etPostMessage.getText().toString().trim();
                createFirestorePost(msg, viewModel.getUploadedImageUrl());
            }
            // If uploadedImageUrl is null we were interrupted before/during upload;
            // the user will need to tap Post again (limit was NOT claimed yet).
        }
    }

    // -------------------------------------------------------------------------
    // UI setup
    // -------------------------------------------------------------------------

    private void setupUI() {
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.btnSelectImage.setOnClickListener(v -> checkPermissionsAndPickImage());

        binding.fabRemoveImage.setOnClickListener(v -> {
            selectedImageUri = null;
            viewModel.setUploadedImageUrl(null);
            binding.ivBirdImagePreview.setVisibility(View.GONE);
            binding.fabRemoveImage.setVisibility(View.GONE);
            binding.tvImagePlaceholder.setVisibility(View.VISIBLE);
        });

        binding.swShowLocation.setOnCheckedChangeListener((b, isChecked) -> {
            if (isChecked && !hasLocationPermission()) requestLocationPermission();
            else if (isChecked) fetchLocation();
        });

        binding.btnPost.setOnClickListener(v -> attemptPost());

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_GRAPHIC_CONTENT, false)) {
            binding.cbHunted.setEnabled(false);
            binding.cbHunted.setAlpha(0.5f);
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
    }

    private void fetchLocation() {
        try {
            fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
                if (location != null) {
                    currentLatitude  = location.getLatitude();
                    currentLongitude = location.getLongitude();
                }
            });
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission error", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int rc, @NonNull String[] p, @NonNull int[] gr) {
        super.onRequestPermissionsResult(rc, p, gr);
        if (rc == REQUEST_LOCATION_PERMISSION) {
            if (gr.length > 0 && gr[0] == PackageManager.PERMISSION_GRANTED) fetchLocation();
            else {
                binding.swShowLocation.setChecked(false);
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void loadUserInfo() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;
        firebaseManager.getUserProfile(user.getUid(), task -> {
            if (isFinishing() || isDestroyed()) return;
            if (task.isSuccessful() && task.getResult() != null) {
                currentUsername           = task.getResult().getString("username");
                currentUserProfilePicUrl  = task.getResult().getString("profilePictureUrl");
                binding.tvCreatorUsername.setText(currentUsername);
                Glide.with(this).load(currentUserProfilePicUrl)
                        .placeholder(R.drawable.ic_profile).into(binding.ivCreatorProfilePicture);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Image picking / cropping
    // -------------------------------------------------------------------------

    private void setupImageLaunchers() {
        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> {
                    if (r.getResultCode() == Activity.RESULT_OK && r.getData() != null) {
                        Uri uri = r.getData().getData();
                        if (uri != null) startImageCropper(uri);
                    }
                });

        cropImageLauncher = registerForActivityResult(new CropImageContract(), r -> {
            if (isFinishing() || isDestroyed()) return;
            if (r.isSuccessful() && r.getUriContent() != null) {
                selectedImageUri = r.getUriContent();
                binding.ivBirdImagePreview.setVisibility(View.VISIBLE);
                binding.fabRemoveImage.setVisibility(View.VISIBLE);
                binding.tvImagePlaceholder.setVisibility(View.GONE);
                Glide.with(this).load(selectedImageUri).into(binding.ivBirdImagePreview);
            }
        });
    }

    private void checkPermissionsAndPickImage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED)
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQUEST_READ_EXTERNAL_STORAGE);
            else pickImage();
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED)
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_READ_EXTERNAL_STORAGE);
            else pickImage();
        }
    }

    private void pickImage() {
        pickImageLauncher.launch(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"));
    }

    private void startImageCropper(Uri uri) {
        CropImageOptions opt = new CropImageOptions();
        opt.fixAspectRatio = true;
        opt.aspectRatioX   = 4;
        opt.aspectRatioY   = 3;
        cropImageLauncher.launch(new CropImageContractOptions(uri, opt));
    }

    // -------------------------------------------------------------------------
    // Post attempt — gated by server-side limit check (Fix POST LIMIT)
    // -------------------------------------------------------------------------

    private void attemptPost() {
        if (viewModel.isPostInProgress.get() || viewModel.isPostFinished.get()) return;

        CharSequence text = binding.etPostMessage.getText();
        String msg = (text != null) ? text.toString().trim() : "";

        if (msg.isEmpty() && selectedImageUri == null) {
            Toast.makeText(this, "Please add a message or an image", Toast.LENGTH_SHORT).show();
            return;
        }
        if (msg.length() > MAX_POST_LENGTH) {
            Toast.makeText(this, "Post too long", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ContentFilter.containsInappropriateContent(msg)) {
            Toast.makeText(this, "Inappropriate language detected.", Toast.LENGTH_LONG).show();
            return;
        }

        viewModel.isPostInProgress.set(true);
        setPostingUi(true);

        // FIX POST LIMIT: Call the CF first, before any upload.
        // This ensures:
        //   (a) We don't waste Storage on a post that will be rejected.
        //   (b) The limit is enforced atomically — two rapid taps cannot both slip through
        //       because the CF uses a Firestore transaction to read+increment in one step.
        checkServerPostLimit(msg);
    }

    /**
     * Calls `recordForumPost` CF to atomically claim a daily post slot.
     * Only proceeds to upload/write if the server returns allowed=true.
     */
    private void checkServerPostLimit(String msg) {
        firebaseManager.recordForumPost(new FirebaseManager.ForumPostLimitListener() {
            @Override public void onAllowed(int remaining) {
                if (isFinishing() || isDestroyed()) return;
                // Slot claimed — proceed with upload or direct Firestore write
                if (viewModel.getUploadedImageUrl() != null) {
                    createFirestorePost(msg, viewModel.getUploadedImageUrl());
                } else if (selectedImageUri != null) {
                    uploadImageAndPost(msg);
                } else {
                    createFirestorePost(msg, null);
                }
            }
            @Override public void onLimitReached() {
                if (isFinishing() || isDestroyed()) return;
                viewModel.isPostInProgress.set(false);
                setPostingUi(false);
                Toast.makeText(CreatePostActivity.this,
                        "You've reached your 3 post limit for today. Try again tomorrow!",
                        Toast.LENGTH_LONG).show();
            }
            @Override public void onFailure(String errorMessage) {
                if (isFinishing() || isDestroyed()) return;
                Log.e(TAG, "recordForumPost failed: " + errorMessage);
                viewModel.isPostInProgress.set(false);
                setPostingUi(false);
                Toast.makeText(CreatePostActivity.this,
                        "Could not verify post limit. Please try again.",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Upload image then write post
    // -------------------------------------------------------------------------

    private void uploadImageAndPost(String msg) {
        String id = UUID.randomUUID().toString();
        StorageReference ref = storage.getReference().child("forum_post_images/" + id + ".jpg");
        ref.putFile(selectedImageUri)
                .addOnSuccessListener(ts -> ref.getDownloadUrl().addOnSuccessListener(uri -> {
                    if (isFinishing() || isDestroyed()) return;
                    // FIX #3: persist URL so rotation doesn't lose it
                    viewModel.setUploadedImageUrl(uri.toString());
                    createFirestorePost(msg, uri.toString());
                }))
                .addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;
                    viewModel.isPostInProgress.set(false);
                    setPostingUi(false);
                    Toast.makeText(this, "Image upload failed", Toast.LENGTH_SHORT).show();
                });
    }

    private void createFirestorePost(String msg, String url) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        ForumPost post = new ForumPost(user.getUid(), currentUsername, currentUserProfilePicUrl, msg, url);
        post.setId(viewModel.getPendingPostId()); // Fix #25: stable ID survives rotation
        post.setSpotted(binding.cbSpotted.isChecked());
        post.setHunted(binding.cbHunted.isChecked());
        post.setShowLocation(binding.swShowLocation.isChecked());
        if (post.isShowLocation()) {
            post.setLatitude(currentLatitude);
            post.setLongitude(currentLongitude);
        }

        firebaseManager.addForumPost(post, task -> {
            if (isFinishing() || isDestroyed()) return;
            if (task.isSuccessful()) {
                viewModel.isPostFinished.set(true);
                viewModel.isPostInProgress.set(false);
                Toast.makeText(this, "Post shared!", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                viewModel.isPostInProgress.set(false);
                setPostingUi(false);
                Toast.makeText(this, "Failed to share post", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // -------------------------------------------------------------------------
    // UI state
    // -------------------------------------------------------------------------

    private void setPostingUi(boolean posting) {
        binding.btnPost.setEnabled(!posting);
        if (loadingOverlay != null) loadingOverlay.setVisibility(posting ? View.VISIBLE : View.GONE);
    }
}