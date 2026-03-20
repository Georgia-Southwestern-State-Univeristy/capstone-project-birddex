package com.birddex.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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
        binding = ActivityCreatePostBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mAuth = FirebaseAuth.getInstance();
        storage = FirebaseStorage.getInstance();
        firebaseManager = new FirebaseManager(this);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        loadingOverlay = findViewById(R.id.loadingOverlay);

        viewModel = new ViewModelProvider(this).get(CreatePostViewModel.class);

        if (viewModel.getPendingPostId() == null) {
            viewModel.setPendingPostId(
                    FirebaseFirestore.getInstance().collection("forumThreads").document().getId());
        }

        setupUI();
        loadUserInfo();
        setupImageLaunchers();
        syncInitialImagePreviewState();

        if (hasLocationPermission()) fetchLocation();

        if (viewModel.isPostInProgress.get()) {
            setPostingUi(true);
            if (viewModel.getUploadedImageUrl() != null) {
                showImagePreview(viewModel.getUploadedImageUrl());
                String msg = binding.etPostMessage.getText().toString().trim();
                createFirestorePost(msg, viewModel.getUploadedImageUrl());
            }
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
        binding.btnSelectImage.setOnClickListener(v -> checkPermissionsAndPickImage());

        binding.messageInputLayout.setHelperText("Forum moderation blocks profanity, links, contact info, and sensitive data.");
        binding.etPostMessage.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearPostWarning();
            }

            @Override
            public void afterTextChanged(Editable s) { }
        });

        binding.fabRemoveImage.setOnClickListener(v -> {
            selectedImageUri = null;
            viewModel.setUploadedImageUrl(null);
            hideImagePreview();
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
                    currentLatitude = location.getLatitude();
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
            if (gr.length > 0 && gr[0] == PackageManager.PERMISSION_GRANTED) {
                fetchLocation();
            } else {
                binding.swShowLocation.setChecked(false);
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
                currentUsername = task.getResult().getString("username");
                currentUserProfilePicUrl = task.getResult().getString("profilePictureUrl");
                binding.tvCreatorUsername.setText(currentUsername);
                Glide.with(this)
                        .load(currentUserProfilePicUrl)
                        .placeholder(R.drawable.ic_profile)
                        .into(binding.ivCreatorProfilePicture);
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
                showImagePreview(selectedImageUri);
            }
        });
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void checkPermissionsAndPickImage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQUEST_READ_EXTERNAL_STORAGE);
            } else {
                pickImage();
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_READ_EXTERNAL_STORAGE);
            } else {
                pickImage();
            }
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
        opt.aspectRatioX = 4;
        opt.aspectRatioY = 3;
        cropImageLauncher.launch(new CropImageContractOptions(uri, opt));
    }

    /**
     * Keeps the preview hidden until the user actually has an image selected or already uploaded.
     */
    private void syncInitialImagePreviewState() {
        if (viewModel.getUploadedImageUrl() != null && !viewModel.getUploadedImageUrl().trim().isEmpty()) {
            showImagePreview(viewModel.getUploadedImageUrl());
        } else {
            hideImagePreview();
        }
    }

    /**
     * Shows the preview area only when there is a real image to display.
     */
    private void showImagePreview(Object imageSource) {
        binding.emptyPreviewSpacer.setVisibility(View.GONE);
        binding.imagePreviewContainer.setVisibility(View.VISIBLE);
        binding.ivBirdImagePreview.setVisibility(View.VISIBLE);
        binding.fabRemoveImage.setVisibility(View.VISIBLE);
        binding.tvImagePlaceholder.setVisibility(View.GONE);

        Glide.with(this)
                .load(imageSource)
                .into(binding.ivBirdImagePreview);
    }

    /**
     * Completely hides the preview area when no image has been added.
     */
    private void hideImagePreview() {
        binding.emptyPreviewSpacer.setVisibility(View.VISIBLE);
        binding.imagePreviewContainer.setVisibility(View.GONE);
        binding.ivBirdImagePreview.setVisibility(View.GONE);
        binding.fabRemoveImage.setVisibility(View.GONE);
        binding.tvImagePlaceholder.setVisibility(View.GONE);
        binding.ivBirdImagePreview.setImageDrawable(null);
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
        if (viewModel.isPostInProgress.get() || viewModel.isPostFinished.get()) return;

        CharSequence text = binding.etPostMessage.getText();
        String msg = (text != null) ? text.toString().trim() : "";

        if (msg.isEmpty() && selectedImageUri == null && viewModel.getUploadedImageUrl() == null) {
            Toast.makeText(this, "Please add a message or an image", Toast.LENGTH_SHORT).show();
            return;
        }
        if (msg.length() > MAX_POST_LENGTH) {
            Toast.makeText(this, "Post too long", Toast.LENGTH_SHORT).show();
            return;
        }

        String moderationReason = ContentFilter.getInappropriateReason(msg);
        if (moderationReason != null) {
            showPostWarning(buildModerationWarningFromReason("Post", moderationReason));
            firebaseManager.logFilteredContentAttempt("forum_post_create_client_block", "post", msg, null, null);
            return;
        }
        clearPostWarning();

        if (ForumSubmissionCooldownHelper.isCoolingDown(this)) {
            Toast.makeText(this, ForumSubmissionCooldownHelper.buildCooldownMessage(this), Toast.LENGTH_SHORT).show();
            return;
        }

        viewModel.isPostInProgress.set(true);
        setPostingUi(true);

        boolean wantsToShowLocation = binding.swShowLocation.isChecked();
        checkServerPostLimit(msg, wantsToShowLocation);
    }

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
                    viewModel.setUploadedImageUrl(uri.toString());
                    showImagePreview(uri.toString());
                    createFirestorePost(msg, uri.toString());
                }))
                .addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;
                    viewModel.isPostInProgress.set(false);
                    setPostingUi(false);
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
        post.setId(viewModel.getPendingPostId());
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
                viewModel.isPostFinished.set(true);
                viewModel.isPostInProgress.set(false);
                Toast.makeText(CreatePostActivity.this, "Post shared!", Toast.LENGTH_SHORT).show();
                finish();
            }

            @Override
            public void onFailure(String errorMessage) {
                if (isFinishing() || isDestroyed()) return;
                viewModel.isPostInProgress.set(false);
                setPostingUi(false);

                if (isModerationErrorMessage(errorMessage)) {
                    showPostWarning(errorMessage);
                } else {
                    clearPostWarning();
                }

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
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(posting ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Shows a concise inline moderation warning under the post composer.
     */
    private void showPostWarning(String message) {
        binding.messageInputLayout.setError(message);
        binding.messageInputLayout.setErrorEnabled(true);
    }

    /**
     * Clears any inline moderation warning once the user changes the post text.
     */
    private void clearPostWarning() {
        binding.messageInputLayout.setError(null);
        binding.messageInputLayout.setErrorEnabled(false);
    }

    /**
     * Builds a concise moderation message for inline composer warnings.
     */
    private String buildModerationWarningFromReason(String fieldName, String reason) {
        String safeFieldName = fieldName == null || fieldName.trim().isEmpty() ? "content" : fieldName.trim();
        if (reason == null || reason.trim().isEmpty()) {
            return "Your " + safeFieldName.toLowerCase() + " could not be submitted. Please review it and try again.";
        }

        switch (reason) {
            case "inappropriate language":
                return "Your " + safeFieldName.toLowerCase() + " includes language that is not allowed. Please remove it and try again.";
            case "glitch text":
                return "Your " + safeFieldName.toLowerCase() + " includes glitch-style text that can break the forum layout. Please remove the special characters and try again.";
            case "an email address":
            case "a phone number":
            case "external links":
            case "sensitive financial data":
            case "excessive character repetition":
                return "Your " + safeFieldName.toLowerCase() + " includes " + reason + ". Please remove it before posting.";
            default:
                return "Your " + safeFieldName.toLowerCase() + " includes " + reason + ". Please remove it before posting.";
        }
    }

    /**
     * Returns true when an error message likely came from moderation/filtering logic.
     */
    private boolean isModerationErrorMessage(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("could not be submitted")
                || lower.contains("contains inappropriate")
                || lower.contains("contains external links")
                || lower.contains("contains an email address")
                || lower.contains("contains a phone number")
                || lower.contains("contains sensitive financial data")
                || lower.contains("contains excessive character repetition")
                || lower.contains("contains glitch text")
                || lower.contains("language that is not allowed");
    }
}