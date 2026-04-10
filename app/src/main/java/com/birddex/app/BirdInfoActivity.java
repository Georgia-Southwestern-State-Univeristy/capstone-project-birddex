package com.birddex.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
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
    private String currentCaptureSource;

    private Double currentLatitude;
    private Double currentLongitude;
    private String currentLocalityName;
    private String currentState;
    private String currentCountry;

    private ArrayList<Bundle> modelAlternatives = new ArrayList<>();

    private RadioGroup rgQuantity;
    private View layoutQuantity;
    private Button btnStore;
    private TextView tvHeatmapDisclaimer;
    private TextView commonNameTextView, scientificNameTextView, speciesTextView, familyTextView;
    private TextView referenceImageStatusTextView;
    private TextView referenceAttributionTextView;
    private TextView notMyBirdLockedHintTextView;
    private View referenceImageProgressBar;
    private ImageView birdImageView;
    private ImageView referenceBirdImageView;
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
                        currentCommonName = selectedCommon;
                        currentScientificName = selectedSci;
                        currentSpecies = selectedSpec;
                        currentFamily = selectedFam;
                        currentSelectionSource = selectedSource;

                        if (selectedBirdId != null && !selectedBirdId.trim().isEmpty()) {
                            currentBirdId = selectedBirdId;
                            useReferenceNameLookupOnly = false;
                        } else {
                            currentBirdId = null;
                            useReferenceNameLookupOnly = true;
                        }

                        updateBirdUi();
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
        commonNameTextView = findViewById(R.id.commonNameTextView);
        scientificNameTextView = findViewById(R.id.scientificNameTextView);
        speciesTextView = findViewById(R.id.speciesTextView);
        familyTextView = findViewById(R.id.familyTextView);
        btnStore = findViewById(R.id.btnStore);
        Button btnNotMyBird = findViewById(R.id.btnNotMyBird);
        Button btnDiscard = findViewById(R.id.btnDiscard);
        rgQuantity = findViewById(R.id.rgQuantity);
        layoutQuantity = findViewById(R.id.layoutQuantity);
        tvHeatmapDisclaimer = findViewById(R.id.tvHeatmapDisclaimer);
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
        currentCaptureSource = CaptureGuardHelper
                .readReportFromIntent(getIntent(), awardPoints)
                .captureSource;
        pointAwardBlockReason = getIntent().getStringExtra("pointAwardBlockReason");
        pointAwardUserMessage = getIntent().getStringExtra("pointAwardUserMessage");
        if (savedInstanceState != null) {
            pointAwardStatusPopupShown = savedInstanceState.getBoolean("pointAwardStatusPopupShown", false);
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

        updateBirdUi();

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
            tvQuality.setText("AI Feedback: " + currentQualityAssessment);
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
        loadReferenceBirdImage();
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        storeClicked.set(false);
    }

    private void configureQuantityUi() {
        if (isGalleryImportFlow()) {
            // Collection-upload flow: hide quantity chooser completely.
            if (layoutQuantity != null) {
                layoutQuantity.setVisibility(View.GONE);
            }
            if (tvHeatmapDisclaimer != null) {
                tvHeatmapDisclaimer.setVisibility(View.GONE);
            }
            btnStore.setEnabled(true);
            return;
        }

        if (layoutQuantity != null) {
            layoutQuantity.setVisibility(View.VISIBLE);
        }
        if (tvHeatmapDisclaimer != null) {
            tvHeatmapDisclaimer.setVisibility(View.VISIBLE);
        }

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

    private boolean isGalleryImportFlow() {
        return CaptureGuardHelper.CAPTURE_SOURCE_GALLERY_IMPORT.equals(currentCaptureSource);
    }
}