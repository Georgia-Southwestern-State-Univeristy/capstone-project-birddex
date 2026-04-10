package com.birddex.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class BirdInfoActivity extends AppCompatActivity {

    private static final String TAG = "BirdInfoActivity";

    private String currentImageUriStr;
    private String currentImageUrl;
    private String currentBirdId;
    private String currentCommonName;
    private String currentScientificName;
    private String currentSpecies;
    private String currentFamily;
    private String currentQualityAssessment;
    private String identificationLogId;
    private String identificationId;
    private String currentSelectionSource;

    private String originalBirdId;
    private String originalCommonName;
    private String originalScientificName;
    private String originalSpecies;
    private String originalFamily;
    private String originalSelectionSource;
    private boolean originalUseReferenceNameLookupOnly = false;

    private String updatedBirdId;
    private String updatedCommonName;
    private String updatedScientificName;
    private String updatedSpecies;
    private String updatedFamily;
    private String updatedSelectionSource;
    private boolean updatedUseReferenceNameLookupOnly = false;
    private boolean hasUpdatedBird = false;
    private boolean showingOriginalBird = false;

    private Double currentLatitude;
    private Double currentLongitude;
    private String currentLocalityName;
    private String currentState;
    private String currentCountry;

    private ArrayList<Bundle> modelAlternatives = new ArrayList<>();

    private RadioGroup rgQuantity;
    private View layoutQuantity;
    private Button btnStore;
    private TextView commonNameTextView, scientificNameTextView, speciesTextView, familyTextView;
    private TextView referenceImageStatusTextView;
    private TextView referenceAttributionTextView;
    private TextView notMyBirdLockedHintTextView;
    private View referenceImageProgressBar;
    private ImageView birdImageView;
    private ImageView referenceBirdImageView;
    private ImageButton btnToggleReferenceBird;
    private TextView tvReferenceToggleStatus;
    private boolean awardPoints = true;
    private boolean useReferenceNameLookupOnly = false;
    private boolean notMyBirdAllowed = true;
    @Nullable
    private Double modelTop1Confidence;
    @Nullable
    private Double modelTop2Confidence;
    @Nullable
    private Double modelConfidenceMargin;
    @Nullable
    private String notMyBirdBlockMessage;
    @Nullable
    private String pointAwardBlockReason;
    @Nullable
    private String pointAwardUserMessage;
    private boolean pointAwardStatusPopupShown = false;
    private final OpenAiApi openAiApi = new OpenAiApi();

    private final AtomicBoolean storeClicked = new AtomicBoolean(false);

    private final ActivityResultLauncher<Intent> birdSelectionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String selectedCommon = result.getData().getStringExtra("selectedCommonName");
                    String selectedSci = result.getData().getStringExtra("selectedScientificName");
                    String selectedSpec = result.getData().getStringExtra("selectedSpecies");
                    String selectedFam = result.getData().getStringExtra("selectedFamily");
                    String selectedBirdId = result.getData().getStringExtra("selectedBirdId");
                    String selectedSource = result.getData().getStringExtra("selectedSource");

                    if (selectedCommon != null) {
                        boolean selectedUsesNameLookupOnly = selectedBirdId == null || selectedBirdId.trim().isEmpty();

                        setUpdatedBirdSnapshot(
                                selectedBirdId,
                                selectedCommon,
                                selectedSci,
                                selectedSpec,
                                selectedFam,
                                selectedSource,
                                selectedUsesNameLookupOnly
                        );

                        applyUpdatedBirdAsActive();
                        showUpdatePopup(selectedCommon);
                    }
                }
            }
    );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bird_info);

        birdImageView = findViewById(R.id.birdImageView);
        referenceBirdImageView = findViewById(R.id.referenceBirdImageView);
        referenceImageStatusTextView = findViewById(R.id.referenceImageStatusTextView);
        referenceAttributionTextView = findViewById(R.id.referenceAttributionTextView);
        notMyBirdLockedHintTextView = findViewById(R.id.tvNotMyBirdLockedHint);
        referenceImageProgressBar = findViewById(R.id.referenceImageProgressBar);
        btnToggleReferenceBird = findViewById(R.id.btnToggleReferenceBird);
        tvReferenceToggleStatus = findViewById(R.id.tvReferenceToggleStatus);
        commonNameTextView = findViewById(R.id.commonNameTextView);
        scientificNameTextView = findViewById(R.id.scientificNameTextView);
        speciesTextView = findViewById(R.id.speciesTextView);
        familyTextView = findViewById(R.id.familyTextView);
        btnStore = findViewById(R.id.btnStore);
        Button btnNotMyBird = findViewById(R.id.btnNotMyBird);
        Button btnDiscard = findViewById(R.id.btnDiscard);
        rgQuantity = findViewById(R.id.rgQuantity);
        layoutQuantity = findViewById(R.id.layoutQuantity);
        TextView tvSubmitFeedback = findViewById(R.id.tvSubmitFeedback);

        currentImageUriStr = getIntent().getStringExtra("imageUri");
        currentImageUrl = getIntent().getStringExtra("imageUrl");
        currentBirdId = getIntent().getStringExtra("birdId");
        currentCommonName = getIntent().getStringExtra("commonName");
        currentScientificName = getIntent().getStringExtra("scientificName");
        currentSpecies = getIntent().getStringExtra("species");
        currentFamily = getIntent().getStringExtra("family");
        currentQualityAssessment = getIntent().getStringExtra("qualityAssessment");
        identificationLogId = getIntent().getStringExtra("identificationLogId");
        identificationId = getIntent().getStringExtra("identificationId");
        currentSelectionSource = getIntent().getStringExtra("selectionSource");
        awardPoints = getIntent().getBooleanExtra("awardPoints", true);

        useReferenceNameLookupOnly = currentBirdId == null || currentBirdId.trim().isEmpty();
        setOriginalBirdSnapshot(
                currentBirdId,
                currentCommonName,
                currentScientificName,
                currentSpecies,
                currentFamily,
                currentSelectionSource,
                useReferenceNameLookupOnly
        );
        pointAwardBlockReason = getIntent().getStringExtra("pointAwardBlockReason");
        pointAwardUserMessage = getIntent().getStringExtra("pointAwardUserMessage");
        if (savedInstanceState != null) {
            pointAwardStatusPopupShown = savedInstanceState.getBoolean("pointAwardStatusPopupShown", false);
            hasUpdatedBird = savedInstanceState.getBoolean("hasUpdatedBird", false);
            showingOriginalBird = savedInstanceState.getBoolean("showingOriginalBird", false);

            originalBirdId = savedInstanceState.getString("originalBirdId");
            originalCommonName = savedInstanceState.getString("originalCommonName");
            originalScientificName = savedInstanceState.getString("originalScientificName");
            originalSpecies = savedInstanceState.getString("originalSpecies");
            originalFamily = savedInstanceState.getString("originalFamily");
            originalSelectionSource = savedInstanceState.getString("originalSelectionSource");
            originalUseReferenceNameLookupOnly = savedInstanceState.getBoolean("originalUseReferenceNameLookupOnly", originalUseReferenceNameLookupOnly);

            updatedBirdId = savedInstanceState.getString("updatedBirdId");
            updatedCommonName = savedInstanceState.getString("updatedCommonName");
            updatedScientificName = savedInstanceState.getString("updatedScientificName");
            updatedSpecies = savedInstanceState.getString("updatedSpecies");
            updatedFamily = savedInstanceState.getString("updatedFamily");
            updatedSelectionSource = savedInstanceState.getString("updatedSelectionSource");
            updatedUseReferenceNameLookupOnly = savedInstanceState.getBoolean("updatedUseReferenceNameLookupOnly", false);
        }

        currentLatitude = getIntent().hasExtra("latitude") ? getIntent().getDoubleExtra("latitude", 0.0) : null;
        currentLongitude = getIntent().hasExtra("longitude") ? getIntent().getDoubleExtra("longitude", 0.0) : null;
        currentLocalityName = getIntent().getStringExtra("localityName");
        currentState = getIntent().getStringExtra("state");
        currentCountry = getIntent().getStringExtra("country");
        notMyBirdAllowed = getIntent().getBooleanExtra("notMyBirdAllowed", true);
        notMyBirdBlockMessage = getIntent().getStringExtra("notMyBirdBlockMessage");
        modelTop1Confidence = getIntent().hasExtra("modelTop1Confidence") ? getIntent().getDoubleExtra("modelTop1Confidence", 0.0) : null;
        modelTop2Confidence = getIntent().hasExtra("modelTop2Confidence") ? getIntent().getDoubleExtra("modelTop2Confidence", 0.0) : null;
        modelConfidenceMargin = getIntent().hasExtra("modelConfidenceMargin") ? getIntent().getDoubleExtra("modelConfidenceMargin", 0.0) : null;

        ArrayList<Bundle> incomingAlternatives = getIntent().getParcelableArrayListExtra("modelAlternatives");
        if (incomingAlternatives != null) {
            modelAlternatives = incomingAlternatives;
        }

        if (currentImageUriStr != null && birdImageView != null) {
            Glide.with(this)
                    .load(Uri.parse(currentImageUriStr))
                    .into(birdImageView);
        }

        if (btnToggleReferenceBird != null) {
            btnToggleReferenceBird.setOnClickListener(v -> toggleActiveBird());
        }

        if (hasUpdatedBird) {
            if (showingOriginalBird) {
                applyOriginalBirdAsActive();
            } else {
                applyUpdatedBirdAsActive();
            }
        } else {
            applyOriginalBirdAsActive();
        }

        updateReferenceToggleUi();

        rgQuantity.setOnCheckedChangeListener((group, checkedId) -> {
            if (awardPoints) {
                btnStore.setEnabled(checkedId != -1);
            }
        });

        configureQuantityUi();
        applyNotMyBirdButtonState(btnNotMyBird);
        showPointAwardStatusPopupIfNeeded();

        TextView tvQuality = findViewById(R.id.tvQualityAssessment);
        if (currentQualityAssessment != null && !currentQualityAssessment.trim().isEmpty() && !"clear".equalsIgnoreCase(currentQualityAssessment)) {
            tvQuality.setText("Image Feedback: " + currentQualityAssessment);
            tvQuality.setVisibility(View.VISIBLE);
        } else {
            tvQuality.setVisibility(View.GONE);
        }

        tvSubmitFeedback.setOnClickListener(v -> IdentificationFeedbackHelper.showFeedbackDialog(
                this,
                (feedbackText, callback) -> IdentificationFeedbackHelper.submitFeedback(
                        openAiApi,
                        this,
                        identificationLogId,
                        identificationId,
                        "bird_info",
                        feedbackText,
                        callback
                )
        ));

        btnStore.setOnClickListener(v -> {
            if (!storeClicked.compareAndSet(false, true)) return;

            openAiApi.syncIdentificationFeedback(
                    identificationLogId,
                    identificationId,
                    "confirm_final_choice",
                    currentBirdId,
                    currentSelectionSource,
                    null,
                    currentCommonName,
                    currentScientificName,
                    currentSpecies,
                    currentFamily
            );

            String quantity = getSelectedQuantity();
            long caughtTime = System.currentTimeMillis();

            Intent i = new Intent(BirdInfoActivity.this, CardMakerActivity.class);
            i.putExtra(CardMakerActivity.EXTRA_IMAGE_URI, currentImageUriStr);
            i.putExtra(CardMakerActivity.EXTRA_LOCALITY, currentLocalityName);
            i.putExtra(CardMakerActivity.EXTRA_STATE, currentState);
            i.putExtra(CardMakerActivity.EXTRA_CAUGHT_TIME, caughtTime);
            i.putExtra(CardMakerActivity.EXTRA_BIRD_NAME, currentCommonName);
            i.putExtra(CardMakerActivity.EXTRA_SCI_NAME, currentScientificName);
            i.putExtra(CardMakerActivity.EXTRA_CONFIDENCE, "--");
            i.putExtra(CardMakerActivity.EXTRA_RARITY, CardRarityHelper.COMMON);
            i.putExtra(CardMakerActivity.EXTRA_BIRD_ID, currentBirdId);
            i.putExtra(CardMakerActivity.EXTRA_SPECIES, currentSpecies);
            i.putExtra(CardMakerActivity.EXTRA_FAMILY, currentFamily);
            i.putExtra(CardMakerActivity.EXTRA_QUANTITY, quantity);
            i.putExtra(CardMakerActivity.EXTRA_RECORD_SIGHTING, true);
            i.putExtra(CardMakerActivity.EXTRA_AWARD_POINTS, awardPoints);
            i.putExtra(CardMakerActivity.EXTRA_POINT_AWARD_BLOCK_REASON, pointAwardBlockReason);
            i.putExtra(CardMakerActivity.EXTRA_POINT_AWARD_USER_MESSAGE, pointAwardUserMessage);
            i.putExtra(CardMakerActivity.EXTRA_IDENTIFICATION_LOG_ID, identificationLogId);
            i.putExtra(CardMakerActivity.EXTRA_IDENTIFICATION_ID, identificationId);
            if (currentLatitude != null) i.putExtra(CardMakerActivity.EXTRA_LATITUDE, currentLatitude);
            if (currentLongitude != null) i.putExtra(CardMakerActivity.EXTRA_LONGITUDE, currentLongitude);
            i.putExtra(CardMakerActivity.EXTRA_COUNTRY, currentCountry);

            // Anti-cheat: Forward CaptureGuard report
            CaptureGuardHelper.putGuardExtras(i, CaptureGuardHelper.readReportFromIntent(getIntent(), awardPoints));

            startActivity(i);
        });

        btnNotMyBird.setOnClickListener(v -> {
            if (!notMyBirdAllowed) {
                openAiApi.syncIdentificationFeedback(
                        identificationLogId,
                        identificationId,
                        "blocked_not_my_bird_press",
                        currentBirdId,
                        currentSelectionSource,
                        buildBlockedNotMyBirdNote(),
                        currentCommonName,
                        currentScientificName,
                        currentSpecies,
                        currentFamily
                );
                showNotMyBirdLockedPopup();
                return;
            }

            openAiApi.syncIdentificationFeedback(
                    identificationLogId,
                    identificationId,
                    "reject_initial_result",
                    currentBirdId,
                    currentSelectionSource,
                    null,
                    currentCommonName,
                    currentScientificName,
                    currentSpecies,
                    currentFamily
            );

            Intent intent = new Intent(BirdInfoActivity.this, NotMyBirdActivity.class);
            intent.putExtra("imageUri", currentImageUriStr);
            intent.putExtra("imageUrl", currentImageUrl);
            intent.putExtra("birdId", currentBirdId);
            intent.putExtra("commonName", currentCommonName);
            intent.putExtra("scientificName", currentScientificName);
            intent.putExtra("species", currentSpecies);
            intent.putExtra("family", currentFamily);
            intent.putExtra("identificationLogId", identificationLogId);
            intent.putExtra("identificationId", identificationId);
            intent.putExtra("selectionSource", currentSelectionSource);
            intent.putParcelableArrayListExtra("modelAlternatives", modelAlternatives);

            // Anti-cheat: Forward CaptureGuard report
            CaptureGuardHelper.putGuardExtras(intent, CaptureGuardHelper.readReportFromIntent(getIntent(), awardPoints));

            birdSelectionLauncher.launch(intent);
        });

        btnDiscard.setOnClickListener(v -> {
            openAiApi.syncIdentificationFeedback(
                    identificationLogId,
                    identificationId,
                    "discarded",
                    null,
                    currentSelectionSource,
                    null,
                    currentCommonName,
                    currentScientificName,
                    currentSpecies,
                    currentFamily
            );

            deleteIdentificationImageFromStorage(currentImageUrl);
            deleteLocalTempImageIfNeeded(currentImageUriStr);

            Intent home = new Intent(BirdInfoActivity.this, HomeActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(home);
            finish();
        });
    }


    private void deleteIdentificationImageFromStorage(@Nullable String downloadUrl) {
        if (downloadUrl == null || downloadUrl.trim().isEmpty()) {
            return;
        }

        try {
            StorageReference ref = FirebaseStorage.getInstance().getReferenceFromUrl(downloadUrl);
            ref.delete()
                    .addOnSuccessListener(unused -> Log.d(TAG, "Discarded identification image deleted."))
                    .addOnFailureListener(e -> Log.w(TAG, "Failed to delete discarded identification image.", e));
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve identification image URL for deletion.", e);
        }
    }

    private void deleteLocalTempImageIfNeeded(@Nullable String uriString) {
        if (uriString == null || uriString.trim().isEmpty()) {
            return;
        }

        try {
            Uri uri = Uri.parse(uriString);
            if (uri.getPath() == null) {
                return;
            }

            File file = new File(uri.getPath());
            String cacheRoot = getCacheDir().getAbsolutePath();
            String filePath = file.getAbsolutePath();

            if (!filePath.startsWith(cacheRoot)) {
                return;
            }
            if (!file.exists()) {
                return;
            }

            if (file.delete()) {
                Log.d(TAG, "Deleted local temp BirdInfo image: " + filePath);
            } else {
                Log.w(TAG, "Failed to delete local temp BirdInfo image: " + filePath);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to delete local temp BirdInfo image.", e);
        }
    }


    private void updateBirdUi() {
        commonNameTextView.setText("Common Name: " + (currentCommonName != null ? currentCommonName : "N/A"));
        scientificNameTextView.setText("Scientific Name: " + (currentScientificName != null ? currentScientificName : "N/A"));
        speciesTextView.setText("Species: " + (currentSpecies != null ? currentSpecies : "N/A"));
        familyTextView.setText("Family: " + (currentFamily != null ? currentFamily : "N/A"));
        updateReferenceToggleUi();
        loadReferenceBirdImage();
    }

    private void setOriginalBirdSnapshot(
            @Nullable String birdId,
            @Nullable String commonName,
            @Nullable String scientificName,
            @Nullable String species,
            @Nullable String family,
            @Nullable String selectionSource,
            boolean usesReferenceNameLookupOnly
    ) {
        originalBirdId = birdId;
        originalCommonName = commonName;
        originalScientificName = scientificName;
        originalSpecies = species;
        originalFamily = family;
        originalSelectionSource = selectionSource;
        originalUseReferenceNameLookupOnly = usesReferenceNameLookupOnly;
    }

    private void setUpdatedBirdSnapshot(
            @Nullable String birdId,
            @Nullable String commonName,
            @Nullable String scientificName,
            @Nullable String species,
            @Nullable String family,
            @Nullable String selectionSource,
            boolean usesReferenceNameLookupOnly
    ) {
        updatedBirdId = birdId;
        updatedCommonName = commonName;
        updatedScientificName = scientificName;
        updatedSpecies = species;
        updatedFamily = family;
        updatedSelectionSource = selectionSource;
        updatedUseReferenceNameLookupOnly = usesReferenceNameLookupOnly;
        hasUpdatedBird = !isSameBirdAsOriginal(
                birdId,
                commonName,
                scientificName,
                selectionSource,
                usesReferenceNameLookupOnly
        );
    }

    private boolean isSameBirdAsOriginal(
            @Nullable String birdId,
            @Nullable String commonName,
            @Nullable String scientificName,
            @Nullable String selectionSource,
            boolean usesReferenceNameLookupOnly
    ) {
        return safeEquals(trimToNull(originalBirdId), trimToNull(birdId))
                && safeEquals(trimToNull(originalCommonName), trimToNull(commonName))
                && safeEquals(trimToNull(originalScientificName), trimToNull(scientificName))
                && safeEquals(trimToNull(originalSelectionSource), trimToNull(selectionSource))
                && originalUseReferenceNameLookupOnly == usesReferenceNameLookupOnly;
    }

    private void applyOriginalBirdAsActive() {
        showingOriginalBird = true;
        currentBirdId = originalBirdId;
        currentCommonName = originalCommonName;
        currentScientificName = originalScientificName;
        currentSpecies = originalSpecies;
        currentFamily = originalFamily;
        currentSelectionSource = originalSelectionSource;
        useReferenceNameLookupOnly = originalUseReferenceNameLookupOnly;
        updateBirdUi();
    }

    private void applyUpdatedBirdAsActive() {
        if (!hasUpdatedBird) {
            applyOriginalBirdAsActive();
            return;
        }

        showingOriginalBird = false;
        currentBirdId = updatedBirdId;
        currentCommonName = updatedCommonName;
        currentScientificName = updatedScientificName;
        currentSpecies = updatedSpecies;
        currentFamily = updatedFamily;
        currentSelectionSource = updatedSelectionSource;
        useReferenceNameLookupOnly = updatedUseReferenceNameLookupOnly;
        updateBirdUi();
    }

    private void toggleActiveBird() {
        if (!hasUpdatedBird) {
            return;
        }

        if (showingOriginalBird) {
            applyUpdatedBirdAsActive();
        } else {
            applyOriginalBirdAsActive();
        }
    }

    private void updateReferenceToggleUi() {
        if (btnToggleReferenceBird == null || tvReferenceToggleStatus == null) {
            return;
        }

        if (!hasUpdatedBird) {
            btnToggleReferenceBird.setVisibility(View.GONE);
            tvReferenceToggleStatus.setVisibility(View.GONE);
            return;
        }

        btnToggleReferenceBird.setVisibility(View.VISIBLE);
        tvReferenceToggleStatus.setVisibility(View.VISIBLE);

        if (showingOriginalBird) {
            btnToggleReferenceBird.setContentDescription("Switch to updated bird");
            tvReferenceToggleStatus.setText("Tap to Swap Between Original Bird and Your Chosen Bird");
        } else {
            btnToggleReferenceBird.setContentDescription("Switch to original AI bird");
            tvReferenceToggleStatus.setText("Tap to Swap Between Original Bird and Your Chosen Bird");        }
    }

    @Nullable
    private String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean safeEquals(@Nullable String left, @Nullable String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private void loadReferenceBirdImage() {
        String lookupBirdId = useReferenceNameLookupOnly ? null : currentBirdId;

        referenceBirdImageView.setImageDrawable(null);
        referenceBirdImageView.setVisibility(View.INVISIBLE);
        if (referenceImageProgressBar != null) {
            referenceImageProgressBar.setVisibility(View.VISIBLE);
        }
        if (referenceAttributionTextView != null) {
            referenceAttributionTextView.setText("");
            referenceAttributionTextView.setVisibility(View.GONE);
        }
        referenceImageStatusTextView.setText("Loading reference photo...");
        referenceImageStatusTextView.setVisibility(View.VISIBLE);

        BirdImageLoader.loadBirdImageIntoWithFetch(
                this,
                referenceBirdImageView,
                referenceImageProgressBar,
                referenceImageStatusTextView,
                lookupBirdId,
                currentCommonName,
                currentScientificName,
                new BirdImageLoader.MetadataLoadCallback() {
                    @Override
                    public void onLoaded(@Nullable BirdImageLoader.ImageMetadata metadata) {
                        if (referenceAttributionTextView == null || isFinishing() || isDestroyed()) return;
                        BirdImageLoader.applyAttributionText(referenceAttributionTextView, metadata);
                    }

                    @Override
                    public void onNotFound() {
                        if (referenceAttributionTextView != null) {
                            referenceAttributionTextView.setText("");
                            referenceAttributionTextView.setVisibility(View.GONE);
                        }
                    }
                }
        );
    }

    private void showUpdatePopup(String birdName) {
        new AlertDialog.Builder(this)
                .setTitle("Bird Selection Updated")
                .setMessage("You have successfully updated the identification to: " + birdName)
                .setPositiveButton("OK", null)
                .show();
    }


    private void applyNotMyBirdButtonState(Button btnNotMyBird) {
        if (btnNotMyBird == null) {
            return;
        }
        btnNotMyBird.setAlpha(notMyBirdAllowed ? 1f : 0.45f);
        if (notMyBirdLockedHintTextView != null) {
            notMyBirdLockedHintTextView.setVisibility(notMyBirdAllowed ? View.GONE : View.VISIBLE);
            notMyBirdLockedHintTextView.setText("BirdDex confidence is high on this result");
        }
    }

    private void showNotMyBirdLockedPopup() {
        new AlertDialog.Builder(this)
                .setTitle("BirdDex Result Locked")
                .setMessage(notMyBirdBlockMessage != null && !notMyBirdBlockMessage.trim().isEmpty()
                        ? notMyBirdBlockMessage
                        : "were more than confident on the current result")
                .setPositiveButton("OK", null)
                .show();
    }

    @NonNull
    private String buildBlockedNotMyBirdNote() {
        StringBuilder sb = new StringBuilder("not_my_bird_locked_press");
        if (modelTop1Confidence != null) {
            sb.append(" | top1Confidence=").append(String.format(java.util.Locale.US, "%.4f", modelTop1Confidence));
        }
        if (modelTop2Confidence != null) {
            sb.append(" | top2Confidence=").append(String.format(java.util.Locale.US, "%.4f", modelTop2Confidence));
        }
        if (modelConfidenceMargin != null) {
            sb.append(" | margin=").append(String.format(java.util.Locale.US, "%.4f", modelConfidenceMargin));
        }
        if (currentBirdId != null && !currentBirdId.trim().isEmpty()) {
            sb.append(" | currentBirdId=").append(currentBirdId.trim());
        }
        return sb.toString();
    }

    private void showPointAwardStatusPopupIfNeeded() {
        if (awardPoints || pointAwardStatusPopupShown || isFinishing() || isDestroyed()) {
            return;
        }

        pointAwardStatusPopupShown = true;

        new AlertDialog.Builder(this)
                .setTitle("Points disabled for this save")
                .setMessage(buildPointAwardBlockedMessage())
                .setPositiveButton("OK", null)
                .show();
    }

    private String buildPointAwardBlockedMessage() {
        if (pointAwardUserMessage != null && !pointAwardUserMessage.trim().isEmpty()) {
            return pointAwardUserMessage.trim();
        }

        if ("camera_burst_incomplete".equals(pointAwardBlockReason)) {
            return "This identification can still be saved, but it will not earn points because BirdDex did not receive a full live burst from the in-app camera.";
        }
        if ("screen_photo_suspected".equals(pointAwardBlockReason)) {
            return "This identification can still be saved, but it will not earn points because the capture looked too much like a photo of a phone or computer screen.";
        }
        if ("points_require_camera_burst".equals(pointAwardBlockReason)) {
            return "This identification can still be saved, but it will not earn points because only trusted in-app camera burst captures can earn points.";
        }
        if ("species_point_cooldown".equals(pointAwardBlockReason)) {
            return "This identification can still be saved, but it will not earn points because this species already earned points recently.";
        }
        if ("client_award_points_disabled".equals(pointAwardBlockReason)) {
            return "This identification can still be saved, but points are disabled for this save.";
        }

        return "This identification can still be saved, but no points will be awarded for it.";
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("pointAwardStatusPopupShown", pointAwardStatusPopupShown);
        outState.putBoolean("hasUpdatedBird", hasUpdatedBird);
        outState.putBoolean("showingOriginalBird", showingOriginalBird);

        outState.putString("originalBirdId", originalBirdId);
        outState.putString("originalCommonName", originalCommonName);
        outState.putString("originalScientificName", originalScientificName);
        outState.putString("originalSpecies", originalSpecies);
        outState.putString("originalFamily", originalFamily);
        outState.putString("originalSelectionSource", originalSelectionSource);
        outState.putBoolean("originalUseReferenceNameLookupOnly", originalUseReferenceNameLookupOnly);

        outState.putString("updatedBirdId", updatedBirdId);
        outState.putString("updatedCommonName", updatedCommonName);
        outState.putString("updatedScientificName", updatedScientificName);
        outState.putString("updatedSpecies", updatedSpecies);
        outState.putString("updatedFamily", updatedFamily);
        outState.putString("updatedSelectionSource", updatedSelectionSource);
        outState.putBoolean("updatedUseReferenceNameLookupOnly", updatedUseReferenceNameLookupOnly);
    }

    @Override
    protected void onResume() {
        super.onResume();
        storeClicked.set(false);
    }

    private void configureQuantityUi() {
        if (awardPoints) {
            if (layoutQuantity != null) layoutQuantity.setAlpha(1f);
            setQuantityOptionsEnabled(true);
            btnStore.setEnabled(rgQuantity.getCheckedRadioButtonId() != -1);
        } else {
            rgQuantity.clearCheck();
            if (layoutQuantity != null) layoutQuantity.setAlpha(0.45f);
            setQuantityOptionsEnabled(false);
            btnStore.setEnabled(true);
        }
    }

    private void setQuantityOptionsEnabled(boolean enabled) {
        rgQuantity.setEnabled(enabled);
        for (int i = 0; i < rgQuantity.getChildCount(); i++) {
            View child = rgQuantity.getChildAt(i);
            child.setEnabled(enabled);
            child.setClickable(enabled);
        }
    }

    private String getSelectedQuantity() {
        if (!awardPoints) {
            return null;
        }

        int checkedId = rgQuantity.getCheckedRadioButtonId();
        if (checkedId != -1) {
            RadioButton rb = findViewById(checkedId);
            return rb.getText().toString();
        }
        return "1-3";
    }
}