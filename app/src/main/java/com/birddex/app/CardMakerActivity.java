package com.birddex.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.io.File;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CardMakerActivity handles saving the bird to the user's permanent collection.
 * It uploads a separate copy of the image to 'userCollectionImages'.
 *
 * Race condition fixes applied:
 *  - Fix D:   isSaveInProgress / isSaveFinished moved to CardMakerViewModel (survives rotation).
 *  - Fix 16 & 17: All core writes (location, userBird, userBirdImage, and collectionSlot)
 *    handled in a SINGLE atomic Firestore transaction.
 *  - Fix SIGHTING: Removed saveUserBirdSightingIfAllowed() (client-side cooldown).
 *    Replaced with recordSightingViaCloudFunction() which calls the `recordBirdSighting` CF.
 *    The CF enforces 1 sighting per user per species per 24 h inside a server-side Firestore
 *    transaction, closing two race conditions the old code had:
 *      (a) Two devices for the same user saving simultaneously both passed the client-side
 *          cooldown check before either wrote the cooldown timestamp.
 *      (b) A crash after writing the cooldown but before writing the sighting doc (or vice
 *          versa) left state inconsistent. The CF commits both in one atomic transaction.
 */
/**
 * CardMakerActivity: Screen used to build a card from a selected bird image and metadata before saving it.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class CardMakerActivity extends AppCompatActivity {

    private static final String TAG = "CardMakerActivity";

    public static final String EXTRA_IMAGE_URI       = "imageUri";
    public static final String EXTRA_BIRD_NAME       = "birdName";
    public static final String EXTRA_SCI_NAME        = "sciName";
    public static final String EXTRA_CONFIDENCE      = "confidence";
    public static final String EXTRA_RARITY          = "rarity";
    public static final String EXTRA_BIRD_ID         = "birdId";
    public static final String EXTRA_SPECIES         = "species";
    public static final String EXTRA_FAMILY          = "family";
    public static final String EXTRA_LOCALITY        = "localityName";
    public static final String EXTRA_STATE           = "state";
    public static final String EXTRA_CAUGHT_TIME     = "caughtTime";
    public static final String EXTRA_QUANTITY        = "quantity";
    public static final String EXTRA_RECORD_SIGHTING = "recordSighting";
    public static final String EXTRA_LATITUDE        = "latitude";
    public static final String EXTRA_LONGITUDE       = "longitude";
    public static final String EXTRA_COUNTRY         = "country";
    public static final String EXTRA_IDENTIFICATION_LOG_ID = "identificationLogId";
    public static final String EXTRA_IDENTIFICATION_ID     = "identificationId";
    public static final String EXTRA_POINT_AWARD_BLOCK_REASON = "pointAwardBlockReason";
    public static final String EXTRA_POINT_AWARD_USER_MESSAGE = "pointAwardUserMessage";

    private CardMakerViewModel viewModel;
    private FirebaseManager firebaseManager;
    private FrameLayout loadingOverlay;
    private Button btnSave;
    private Button btnCancel;
    private CharSequence defaultSaveButtonText;

    private Uri originalImageUri;
    private String currentBirdId;
    private String currentCommonName;
    private String currentRarity;
    private String currentScientificName;
    private String currentSpecies;
    private String currentFamily;
    private String currentConfidence;
    private String currentLocality;
    private String currentState;
    private long currentCaughtTime;
    private String currentQuantity;
    private boolean shouldRecordSighting;
    private Double currentLatitude;
    private Double currentLongitude;
    private String currentCountry;
    @Nullable private String currentIdentificationLogId;
    @Nullable private String currentIdentificationId;
    @Nullable private String currentPointAwardBlockReason;
    @Nullable private String currentPointAwardUserMessage;
    private boolean currentSuspicious;
    public static final String EXTRA_AWARD_POINTS = "awardPoints";
    private boolean shouldAwardPoints;
    private final Handler pointAwardStatusHandler = new Handler(Looper.getMainLooper());
    private StorageReference uploadedCollectionImageStorageRef;
    private String uploadedCollectionImageDownloadUrl;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_maker);

        viewModel       = new ViewModelProvider(this).get(CardMakerViewModel.class);
        firebaseManager = new FirebaseManager(this);
        loadingOverlay  = findViewById(R.id.loadingOverlay);
        btnSave         = findViewById(R.id.btnSaveCard);
        btnCancel       = findViewById(R.id.btnCancelCard);
        defaultSaveButtonText = btnSave.getText();

        TextView txtBirdName   = findViewById(R.id.txtBirdName);
        TextView txtScientific = findViewById(R.id.txtScientific);
        TextView txtLocation   = findViewById(R.id.txtLocation);
        TextView txtDateCaught = findViewById(R.id.txtDateCaught);
        TextView txtFooter     = findViewById(R.id.txtFooter);
        ImageView imgBird      = findViewById(R.id.imgBird);

        String originalImageUriStr = getIntent().getStringExtra(EXTRA_IMAGE_URI);
        if (originalImageUriStr != null) originalImageUri = Uri.parse(originalImageUriStr);

        currentCommonName     = getIntent().getStringExtra(EXTRA_BIRD_NAME);
        currentScientificName = getIntent().getStringExtra(EXTRA_SCI_NAME);
        currentConfidence     = getIntent().getStringExtra(EXTRA_CONFIDENCE);
        currentRarity = CardRarityHelper.normalizeRarity(getIntent().getStringExtra(EXTRA_RARITY));
        currentBirdId         = getIntent().getStringExtra(EXTRA_BIRD_ID);
        currentSpecies        = getIntent().getStringExtra(EXTRA_SPECIES);
        currentFamily         = getIntent().getStringExtra(EXTRA_FAMILY);
        currentLocality       = getIntent().getStringExtra(EXTRA_LOCALITY);
        currentState          = getIntent().getStringExtra(EXTRA_STATE);
        currentCaughtTime     = getIntent().getLongExtra(EXTRA_CAUGHT_TIME, System.currentTimeMillis());
        currentQuantity       = getIntent().getStringExtra(EXTRA_QUANTITY);
        shouldRecordSighting  = getIntent().getBooleanExtra(EXTRA_RECORD_SIGHTING, false);
        currentLatitude       = getIntent().hasExtra(EXTRA_LATITUDE)
                ? getIntent().getDoubleExtra(EXTRA_LATITUDE, 0.0) : null;
        currentLongitude      = getIntent().hasExtra(EXTRA_LONGITUDE)
                ? getIntent().getDoubleExtra(EXTRA_LONGITUDE, 0.0) : null;
        currentCountry        = getIntent().getStringExtra(EXTRA_COUNTRY);
        currentIdentificationLogId = getIntent().getStringExtra(EXTRA_IDENTIFICATION_LOG_ID);
        currentIdentificationId    = getIntent().getStringExtra(EXTRA_IDENTIFICATION_ID);
        currentPointAwardBlockReason = getIntent().getStringExtra(EXTRA_POINT_AWARD_BLOCK_REASON);
        currentPointAwardUserMessage = getIntent().getStringExtra(EXTRA_POINT_AWARD_USER_MESSAGE);

        currentSuspicious = getIntent().getBooleanExtra(CaptureGuardHelper.EXTRA_CAPTURE_GUARD_SUSPICIOUS, false);

        shouldAwardPoints = getIntent().getBooleanExtra(EXTRA_AWARD_POINTS, true);

        if (originalImageUri == null) {
            MessagePopupHelper.show(this, "No image passed.");
            finish();
            return;
        }

        if (currentCommonName != null && !currentCommonName.trim().isEmpty())
            txtBirdName.setText(currentCommonName);
        else if (currentScientificName != null && !currentScientificName.trim().isEmpty())
            txtBirdName.setText(currentScientificName);
        else
            txtBirdName.setText("Unknown Bird");

        txtScientific.setText((currentScientificName != null && !currentScientificName.trim().isEmpty())
                ? currentScientificName : "--");
        txtLocation.setText(CardFormatUtils.formatLocation(currentState, currentLocality));
        txtDateCaught.setText(CardFormatUtils.formatCaughtDate(new Date(currentCaughtTime)));
        txtFooter.setText("Preview only • Original photo will be saved");

        imgBird.setImageDrawable(null);
        Glide.with(this)
                .load(originalImageUri)
                .signature(new ObjectKey(originalImageUri.toString() + System.currentTimeMillis()))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .fitCenter()
                .into(imgBird);

        btnCancel.setOnClickListener(v -> { if (!viewModel.isSaveInProgress.get()) finish(); });

        if (viewModel.isSaveInProgress.get() && !viewModel.isSaveFinished.get()) setSavingUi(true);

        btnSave.setOnClickListener(v -> processAndSaveBirdDiscovery(originalImageUri));
    }

    private String getOrCreateSaveOperationId() {
        if (viewModel.saveOperationId == null || viewModel.saveOperationId.trim().isEmpty()) {
            viewModel.saveOperationId = UUID.randomUUID().toString();
        }
        return viewModel.saveOperationId;
    }

    private String getOrCreatePendingUploadPath(String userId) {
        if (viewModel.pendingUploadPath == null || viewModel.pendingUploadPath.trim().isEmpty()) {
            viewModel.pendingUploadPath = "userCollectionImages/" + userId + "/SAVE_" + getOrCreateSaveOperationId() + ".jpg";
        }
        return viewModel.pendingUploadPath;
    }

    private void processAndSaveBirdDiscovery(Uri imageUriToSave) {
        if (viewModel.isSaveInProgress.get() || viewModel.isSaveFinished.get()) {
            Log.d(TAG, "Ignoring duplicate save tap.");
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            MessagePopupHelper.show(this, "Error: No user logged in. Please log in again.");
            return;
        }

        viewModel.isSaveInProgress.set(true);
        setSavingUi(true);
        MessagePopupHelper.show(this, "Saving to collection...");

        String userId   = user.getUid();
        String fileName = getOrCreatePendingUploadPath(userId);
        viewModel.pendingUploadCleanupRequired = false;

        StorageReference storageRef = FirebaseStorage.getInstance().getReference().child(fileName);
        uploadedCollectionImageStorageRef = storageRef;
        uploadedCollectionImageDownloadUrl = null;

        storageRef.putFile(imageUriToSave)
                .addOnSuccessListener(ts -> storageRef.getDownloadUrl()
                        .addOnSuccessListener(uri -> {
                            if (viewModel.isSaveFinished.get()) return;

                            uploadedCollectionImageDownloadUrl = uri.toString();
                            viewModel.pendingUploadCleanupRequired = true;

                            final String resolvedImageUrl = uri.toString();
                            final Double latitudeForSave = currentLatitude;
                            final Double longitudeForSave = currentLongitude;

                            firebaseManager.createOrGetLocation(
                                    latitudeForSave,
                                    longitudeForSave,
                                    currentLocality,
                                    currentState,
                                    currentCountry,
                                    new FirebaseManager.LocationIdListener() {
                                        @Override public void onSuccess(String locationId) {
                                            if (viewModel.isSaveFinished.get()) return;
                                            storeBirdDiscoveryAtomic(resolvedImageUrl, locationId);
                                        }

                                        @Override public void onFailure(String errorMessage) {
                                            handleSaveFailure(errorMessage, null);
                                        }
                                    }
                            );
                        })
                        .addOnFailureListener(e -> handleSaveFailure("Failed to save collection image link.", e)))
                .addOnFailureListener(e -> handleSaveFailure("Failed to upload image to your collection.", e));
    }

    private void storeBirdDiscoveryAtomic(String originalImageUrl, @NonNull String locationId) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) { handleSaveFailure("Error: No user logged in.", null); return; }

        String userId               = user.getUid();
        FirebaseFirestore db        = FirebaseFirestore.getInstance();
        String operationId          = getOrCreateSaveOperationId();
        String userBirdId           = "UB_" + operationId;
        String userBirdImageId      = "UBI_" + operationId;
        String deterministicSlotId  = userId + "_" + currentBirdId;
        Date now = currentCaughtTime > 0 ? new Date(currentCaughtTime) : new Date();

        DocumentReference userBirdRef = db.collection("userBirds").document(userBirdId);
        DocumentReference userBirdImageRef = db.collection("users").document(userId)
                .collection("userBirdImage").document(userBirdImageId);
        DocumentReference slotRef = db.collection("users").document(userId)
                .collection("collectionSlot").document(deterministicSlotId);
        DocumentReference slotCounterRef = db.collection("users").document(userId)
                .collection("collectionMeta").document("slotCounter");

        final String capturedUserBirdId = userBirdId;
        final Date capturedNow = now;

        db.runTransaction(transaction -> {
            DocumentSnapshot slotSnap    = transaction.get(slotRef);
            DocumentSnapshot counterSnap = transaction.get(slotCounterRef);

            Map<String, Object> ubData = new HashMap<>();
            ubData.put("id", capturedUserBirdId);
            ubData.put("userId", userId);
            ubData.put("birdSpeciesId", currentBirdId);
            ubData.put("timeSpotted", capturedNow);
            ubData.put("locationId", locationId);
            ubData.put("imageUrl", originalImageUrl);
            ubData.put("imageCount", 1);
            ubData.put("awardPoints", shouldAwardPoints);
            if (currentIdentificationLogId != null && !currentIdentificationLogId.trim().isEmpty()) {
                ubData.put("identificationLogId", currentIdentificationLogId);
            }
            if (currentIdentificationId != null && !currentIdentificationId.trim().isEmpty()) {
                ubData.put("identificationId", currentIdentificationId);
            }
            transaction.set(userBirdRef, ubData);

            Map<String, Object> ubiData = new HashMap<>();
            ubiData.put("id", userBirdImageId);
            ubiData.put("userId", userId);
            ubiData.put("birdId", currentBirdId);
            ubiData.put("imageUrl", originalImageUrl);
            ubiData.put("timestamp", capturedNow);
            ubiData.put("userBirdRefId", capturedUserBirdId);
            transaction.set(userBirdImageRef, ubiData);

            if (!slotSnap.exists()) {
                long nextIndex = (counterSnap.exists() && counterSnap.getLong("nextSlotIndex") != null)
                        ? counterSnap.getLong("nextSlotIndex") : 0L;
                transaction.set(slotCounterRef,
                        Collections.singletonMap("nextSlotIndex", nextIndex + 1), SetOptions.merge());

                Map<String, Object> slotData = new HashMap<>();
                slotData.put("id", deterministicSlotId);
                slotData.put("userBirdId", capturedUserBirdId);
                slotData.put("birdId", currentBirdId);
                slotData.put("timestamp", capturedNow);
                slotData.put("state", currentState);
                slotData.put("locality", currentLocality);
                slotData.put("imageUrl", originalImageUrl);
                slotData.put("rarity", CardRarityHelper.normalizeRarity(currentRarity));
                slotData.put("slotIndex", (int) nextIndex);
                slotData.put("commonName", currentCommonName);
                slotData.put("scientificName", currentScientificName);
                slotData.put("isFavorite", false);
                transaction.set(slotRef, slotData);
            } else {
                String existingRarity = slotSnap.getString("rarity");
                if (!CardRarityHelper.isValidRarity(existingRarity)) {
                    transaction.update(slotRef, "rarity", CardRarityHelper.COMMON);
                }
            }

            return null;

        }).addOnSuccessListener(result -> {
            Log.d(TAG, "Discovery saved atomically.");
            markCollectionImageAsKept();
            cleanupLocalTempImageIfOwned();
            if (shouldRecordSighting) {
                recordSightingViaCloudFunction(capturedUserBirdId, capturedNow);
            } else {
                handleSaveSuccess(capturedUserBirdId);
            }
        }).addOnFailureListener(e -> handleSaveFailure("Error saving discovery. Please try again.", e));
    }

    private void recordSightingViaCloudFunction(String userBirdId, Date timestamp) {
        firebaseManager.recordBirdSighting(
                currentBirdId,
                currentCommonName,
                userBirdId,
                currentLatitude  != null ? currentLatitude  : 0.0,
                currentLongitude != null ? currentLongitude : 0.0,
                currentState,
                currentLocality,
                currentCountry,
                currentQuantity,
                timestamp.getTime(),
                currentSuspicious,
                new FirebaseManager.BirdSightingListener() {
                    @Override public void onRecorded() {
                        if (isFinishing() || isDestroyed()) return;
                        handleSaveSuccess(userBirdId);
                    }
                    @Override public void onCooldown() {
                        if (isFinishing() || isDestroyed()) return;
                        handleSaveSuccess(userBirdId);
                    }
                    @Override public void onFailure(String errorMessage) {
                        Log.w(TAG, "recordBirdSighting failed (non-fatal): " + errorMessage);
                        if (isFinishing() || isDestroyed()) return;
                        handleSaveSuccess(userBirdId);
                    }
                }
        );
    }

    private void handleSaveSuccess(@Nullable String userBirdId) {
        if (viewModel.isSaveFinished.get() || isFinishing() || isDestroyed()) return;

        if (!shouldAwardPoints) {
            completeSaveWithoutPointOutcomeDialog();
            return;
        }

        if (userBirdId == null || userBirdId.trim().isEmpty()) {
            completeSaveAndShowOutcomeDialog("Saved to your collection", buildFallbackPointAwardMessage());
            return;
        }

        waitForPointAwardStatus(userBirdId, 0);
    }

    private void waitForPointAwardStatus(@NonNull String userBirdId, int attempt) {
        FirebaseFirestore.getInstance()
                .collection("userBirds")
                .document(userBirdId)
                .get()
                .addOnSuccessListener(snapshot -> {
                    if (isFinishing() || isDestroyed()) return;

                    boolean hasComputedPointFields = snapshot.exists() && (
                            snapshot.contains("pointsEarned")
                                    || snapshot.contains("pointAwardBlockedReason")
                                    || snapshot.contains("pointAwardCaptureEligible")
                                    || snapshot.contains("pointCooldownBlocked")
                    );

                    if (hasComputedPointFields || attempt >= 7) {
                        completeSaveAndShowOutcomeDialog(
                                "Saved to your collection",
                                buildPointAwardOutcomeMessage(snapshot)
                        );
                        return;
                    }

                    pointAwardStatusHandler.postDelayed(
                            () -> waitForPointAwardStatus(userBirdId, attempt + 1),
                            400L
                    );
                })
                .addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;
                    Log.w(TAG, "Failed to read point-award outcome after save", e);

                    if (attempt >= 7) {
                        completeSaveAndShowOutcomeDialog("Saved to your collection", buildFallbackPointAwardMessage());
                        return;
                    }

                    pointAwardStatusHandler.postDelayed(
                            () -> waitForPointAwardStatus(userBirdId, attempt + 1),
                            400L
                    );
                });
    }

    private String buildPointAwardOutcomeMessage(@Nullable DocumentSnapshot snapshot) {
        if (snapshot == null || !snapshot.exists()) {
            return buildFallbackPointAwardMessage();
        }

        Long pointsEarnedValue = snapshot.getLong("pointsEarned");
        long pointsEarned = pointsEarnedValue != null ? pointsEarnedValue : 0L;
        boolean pointCooldownBlocked = Boolean.TRUE.equals(snapshot.getBoolean("pointCooldownBlocked"));
        boolean pointAwardCaptureEligible = Boolean.TRUE.equals(snapshot.getBoolean("pointAwardCaptureEligible"));
        String pointAwardBlockedReason = snapshot.getString("pointAwardBlockedReason");

        if (pointsEarned > 0) {
            return "Your bird was saved to your collection.\n\nYou earned " + pointsEarned + " point" + (pointsEarned == 1 ? "" : "s") + " for this save.";
        }

        if (pointCooldownBlocked || "species_point_cooldown".equals(pointAwardBlockedReason)) {
            return "Your bird was saved to your collection.\n\nNo points were awarded because this species already earned points within the last 5 minutes.";
        }

        if (!pointAwardCaptureEligible || !shouldAwardPoints) {
            return "Your bird was saved to your collection.\n\n" + buildBlockedPointReasonMessage(pointAwardBlockedReason);
        }

        return "Your bird was saved to your collection.\n\nNo points were awarded for this save.";
    }

    private String buildFallbackPointAwardMessage() {
        if (shouldAwardPoints) {
            return "Your bird was saved to your collection.\n\nBirdDex is still processing whether this save earned points.";
        }
        return "Your bird was saved to your collection.\n\n" + buildBlockedPointReasonMessage(currentPointAwardBlockReason);
    }

    private String buildBlockedPointReasonMessage(@Nullable String pointAwardBlockedReason) {
        if (currentPointAwardUserMessage != null && !currentPointAwardUserMessage.trim().isEmpty()) {
            return currentPointAwardUserMessage.trim();
        }

        if ("camera_burst_incomplete".equals(pointAwardBlockedReason)) {
            return "No points were awarded because BirdDex did not receive a full live burst from the in-app camera.";
        }
        if ("screen_photo_suspected".equals(pointAwardBlockedReason)) {
            return "No points were awarded because the capture looked too much like a photo of a phone or computer screen.";
        }
        if ("points_require_camera_burst".equals(pointAwardBlockedReason)) {
            return "No points were awarded because only trusted in-app camera burst captures can earn points.";
        }
        if ("species_point_cooldown".equals(pointAwardBlockedReason)) {
            return "No points were awarded because this species already earned points recently.";
        }
        if ("client_award_points_disabled".equals(pointAwardBlockedReason)) {
            return "No points were awarded because points were disabled for this save.";
        }

        return "No points were awarded for this save.";
    }

    private void completeSaveAndShowOutcomeDialog(@NonNull String title, @NonNull String message) {
        if (viewModel.isSaveFinished.get() || isFinishing() || isDestroyed()) return;

        viewModel.isSaveFinished.set(true);
        viewModel.isSaveInProgress.set(false);
        viewModel.pendingUploadPath = null;
        viewModel.saveOperationId = null;
        setSavingUi(false);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("OK", (dialog, which) -> navigateHomeAfterSave())
                .show();
    }

    private void completeSaveWithoutPointOutcomeDialog() {
        if (viewModel.isSaveFinished.get() || isFinishing() || isDestroyed()) return;

        viewModel.isSaveFinished.set(true);
        viewModel.isSaveInProgress.set(false);
        viewModel.pendingUploadPath = null;
        viewModel.saveOperationId = null;
        setSavingUi(false);
        MessagePopupHelper.show(this, "Saved to your collection!");
        navigateHomeAfterSave();
    }

    private void navigateHomeAfterSave() {
        if (isFinishing() || isDestroyed()) return;
        Intent home = new Intent(CardMakerActivity.this, HomeActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        home.putExtra("openTab", "collection");
        startActivity(home);
        finish();
    }

    private void handleSaveFailure(String userMessage, @Nullable Exception e) {
        if (viewModel.isSaveFinished.get() || isFinishing() || isDestroyed()) return;
        Log.e(TAG, userMessage, e);
        deletePendingCollectionImageIfNeeded();
        viewModel.isSaveInProgress.set(false);
        setSavingUi(false);
        MessagePopupHelper.show(CardMakerActivity.this, userMessage);
    }

    private void setSavingUi(boolean saving) {
        if (loadingOverlay != null) loadingOverlay.setVisibility(saving ? View.VISIBLE : View.GONE);
        if (btnSave != null) {
            btnSave.setEnabled(!saving);
            btnSave.setClickable(!saving);
            btnSave.setText(saving ? "Saving..." : defaultSaveButtonText);
        }
        if (btnCancel != null) {
            btnCancel.setEnabled(!saving);
            btnCancel.setClickable(!saving);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!viewModel.isSaveFinished.get() && loadingOverlay != null)
            loadingOverlay.setVisibility(View.GONE);
    }

    private void deletePendingCollectionImageIfNeeded() {
        if (viewModel == null || !viewModel.pendingUploadCleanupRequired) {
            return;
        }

        final StorageReference ref = uploadedCollectionImageStorageRef;
        if (ref == null) {
            return;
        }

        viewModel.pendingUploadCleanupRequired = false;
        uploadedCollectionImageStorageRef = null;
        uploadedCollectionImageDownloadUrl = null;

        ref.delete()
                .addOnSuccessListener(unused ->
                        Log.d(TAG, "Deleted orphaned collection image after failed save."))
                .addOnFailureListener(e ->
                        Log.w(TAG, "Failed to delete orphaned collection image after failed save.", e));
    }

    private void markCollectionImageAsKept() {
        if (viewModel != null) {
            viewModel.pendingUploadCleanupRequired = false;
        }
    }

    private void cleanupLocalTempImageIfOwned() {
        if (originalImageUri == null) {
            return;
        }

        try {
            String scheme = originalImageUri.getScheme();
            String path = originalImageUri.getPath();

            if (!"file".equalsIgnoreCase(scheme) || path == null || path.trim().isEmpty()) {
                return;
            }

            File file = new File(path);
            File cacheDir = getCacheDir();
            if (cacheDir == null) {
                return;
            }

            String cacheRoot = cacheDir.getAbsolutePath();
            String filePath = file.getAbsolutePath();

            boolean isOwnedTempFile =
                    filePath.startsWith(new File(cacheDir, "cropped_images").getAbsolutePath()) ||
                            filePath.startsWith(new File(cacheDir, "camera_burst_frames").getAbsolutePath());

            if (!isOwnedTempFile || !file.exists()) {
                return;
            }

            if (file.delete()) {
                Log.d(TAG, "Deleted local temp image after successful collection save: " + filePath);
            } else {
                Log.w(TAG, "Failed to delete local temp image after successful collection save: " + filePath);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error while deleting local temp image after successful collection save.", e);
        }
    }
}