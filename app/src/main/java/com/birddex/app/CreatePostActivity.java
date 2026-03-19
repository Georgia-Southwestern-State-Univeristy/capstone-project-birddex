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
/**
 * CreatePostActivity: Composer screen for creating a new forum post with text, media, and metadata.
 *
 * Updated for the forum moderation hardening pass:
 * - keeps the frontend content filter for instant feedback
 * - sends the final post write through a callable Cloud Function so backend moderation cannot be bypassed
 * - keeps the existing image upload, rotation recovery, and post-limit flow intact
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

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarHelper.applyStandardNavBar(this);
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        binding = ActivityCreatePostBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mAuth           = FirebaseAuth.getInstance();
        storage         = FirebaseStorage.getInstance();
        // Set up or query the Firebase layer that supplies/stores this feature's data.
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
        // Kick off an asynchronous one-time read; the callbacks below decide how the UI should react.
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

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * This method also reads or writes local device preferences so some state survives app
     * restarts.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private void setupUI() {
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        // Attach the user interaction that should run when this control is tapped.
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

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Main logic block for this part of the feature.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private void requestLocationPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
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

    /**
     * Main logic block for this part of the feature.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    @Override
    public void onRequestPermissionsResult(int rc, @NonNull String[] p, @NonNull int[] gr) {
        super.onRequestPermissionsResult(rc, p, gr);
        if (rc == REQUEST_LOCATION_PERMISSION) {
            if (gr.length > 0 && gr[0] == PackageManager.PERMISSION_GRANTED) fetchLocation();
            else {
                binding.swShowLocation.setChecked(false);
                // Give the user immediate feedback about the result of this action.
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * Image loading happens here, which is why placeholder/error behavior for profile
     * photos/cards/posts usually traces back to this code path.
     */
    private void loadUserInfo() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;
        firebaseManager.getUserProfile(user.getUid(), task -> {
            if (isFinishing() || isDestroyed()) return;
            if (task.isSuccessful() && task.getResult() != null) {
                currentUsername           = task.getResult().getString("username");
                currentUserProfilePicUrl  = task.getResult().getString("profilePictureUrl");
                binding.tvCreatorUsername.setText(currentUsername);
                // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
                Glide.with(this).load(currentUserProfilePicUrl)
                        .placeholder(R.drawable.ic_profile).into(binding.ivCreatorProfilePicture);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Image picking / cropping
    // -------------------------------------------------------------------------

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     * Image loading happens here, which is why placeholder/error behavior for profile
     * photos/cards/posts usually traces back to this code path.
     */
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
                // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
                Glide.with(this).load(selectedImageUri).into(binding.ivBirdImagePreview);
            }
        });
    }

    /**
     * Main logic block for this part of the feature.
     */
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

    /**
     * Main logic block for this part of the feature.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    private void pickImage() {
        pickImageLauncher.launch(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"));
    }

    /**
     * Main logic block for this part of the feature.
     */
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

    /**
     * Main logic block for this part of the feature.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void attemptPost() {
        // Kick off an asynchronous one-time read; the callbacks below decide how the UI should react.
        if (viewModel.isPostInProgress.get() || viewModel.isPostFinished.get()) return;

        CharSequence text = binding.etPostMessage.getText();
        String msg = (text != null) ? text.toString().trim() : "";

        if (msg.isEmpty() && selectedImageUri == null) {
            // Give the user immediate feedback about the result of this action.
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
        if (ForumSubmissionCooldownHelper.isCoolingDown(this)) {
            Toast.makeText(this, ForumSubmissionCooldownHelper.buildCooldownMessage(this), Toast.LENGTH_SHORT).show();
            return;
        }

        // Persist the new state so the action is saved outside the current screen.
        viewModel.isPostInProgress.set(true);
        setPostingUi(true);

        // FIX POST LIMIT: Call the CF first, before any upload.
        // This ensures:
        //   (a) We don't waste Storage on a post that will be rejected.
        //   (b) The limit is enforced atomically — two rapid taps cannot both slip through
        //       because the CF uses a Firestore transaction to read+increment in one step.
        boolean wantsToShowLocation = binding.swShowLocation.isChecked();
        checkServerPostLimit(msg, wantsToShowLocation);
    }

    /**
     * Calls `recordForumPost` CF to atomically claim a daily post slot.
     * Only proceeds to upload/write if the server returns allowed=true.
     */
    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    /**
     * Calls recordForumPost CF.
     * The backend only applies the 3-per-day limit when showLocation == true.
     * Normal posts without map location are always allowed through.
     */
    private void checkServerPostLimit(String msg, boolean wantsToShowLocation) {
        firebaseManager.recordForumPost(wantsToShowLocation, new FirebaseManager.ForumPostLimitListener() {
            @Override
            public void onAllowed(int remaining) {
                if (isFinishing() || isDestroyed()) return;

                // Proceed normally once the backend says this post is allowed.
                if (viewModel.getUploadedImageUrl() != null) {
                    createFirestorePost(msg, viewModel.getUploadedImageUrl());
                } else if (selectedImageUri != null) {
                    uploadImageAndPost(msg);
                } else {
                    createFirestorePost(msg, null);
                }
            }

            @Override
            public void onLimitReached() {
                if (isFinishing() || isDestroyed()) return;

                viewModel.isPostInProgress.set(false);
                setPostingUi(false);

                Toast.makeText(
                        CreatePostActivity.this,
                        "You can only post with map location 3 times per day.",
                        Toast.LENGTH_LONG
                ).show();
            }

            @Override
            public void onFailure(String errorMessage) {
                if (isFinishing() || isDestroyed()) return;

                Log.e(TAG, "recordForumPost failed: " + errorMessage);
                viewModel.isPostInProgress.set(false);
                setPostingUi(false);

                Toast.makeText(
                        CreatePostActivity.this,
                        errorMessage != null && !errorMessage.trim().isEmpty()
                                ? errorMessage
                                : "Could not verify posting limit. Please try again.",
                        Toast.LENGTH_SHORT
                ).show();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Upload image then write post
    // -------------------------------------------------------------------------

    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
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
                    // Persist the new state so the action is saved outside the current screen.
                    viewModel.isPostInProgress.set(false);
                    setPostingUi(false);
                    // Give the user immediate feedback about the result of this action.
                    Toast.makeText(this, "Image upload failed", Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
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

        firebaseManager.createForumPost(post, new FirebaseManager.ForumWriteListener() {
            @Override
            public void onSuccess() {
                if (isFinishing() || isDestroyed()) return;
                ForumSubmissionCooldownHelper.markSubmissionSuccess(CreatePostActivity.this);
                // Persist the new state so the action is saved outside the current screen.
                viewModel.isPostFinished.set(true);
                viewModel.isPostInProgress.set(false);
                // Give the user immediate feedback about the result of this action.
                Toast.makeText(CreatePostActivity.this, "Post shared!", Toast.LENGTH_SHORT).show();
                finish();
            }

            @Override
            public void onFailure(String errorMessage) {
                if (isFinishing() || isDestroyed()) return;
                viewModel.isPostInProgress.set(false);
                setPostingUi(false);
                Toast.makeText(CreatePostActivity.this, errorMessage != null ? errorMessage : "Failed to share post", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // -------------------------------------------------------------------------
    // UI state
    // -------------------------------------------------------------------------

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    private void setPostingUi(boolean posting) {
        binding.btnPost.setEnabled(!posting);
        if (loadingOverlay != null) loadingOverlay.setVisibility(posting ? View.VISIBLE : View.GONE);
    }
}