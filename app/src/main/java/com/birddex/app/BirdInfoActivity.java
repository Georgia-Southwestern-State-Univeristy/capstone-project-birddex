package com.birddex.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BirdInfoActivity: Activity class for one BirdDex screen. It owns screen setup, user actions, and navigation for this part of the app.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class BirdInfoActivity extends AppCompatActivity {

    private String currentImageUriStr;
    private String currentBirdId;
    private String currentCommonName;
    private String currentScientificName;
    private String currentSpecies;
    private String currentFamily;

    private Double currentLatitude;
    private Double currentLongitude;
    private String currentLocalityName;
    private String currentState;
    private String currentCountry;

    private RadioGroup rgQuantity;
    private Button btnStore;
    private TextView commonNameTextView, scientificNameTextView, speciesTextView, familyTextView;
    private TextView referenceImageStatusTextView;
    private ImageView birdImageView;
    private ImageView referenceBirdImageView;
    private boolean awardPoints = true;
    private boolean useReferenceNameLookupOnly = false;

    // FIX: Guard against double-tap launching multiple activities
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

                    if (selectedCommon != null) {
                        currentCommonName = selectedCommon;
                        currentScientificName = selectedSci;
                        currentSpecies = selectedSpec;
                        currentFamily = selectedFam;

                        if (selectedBirdId != null && !selectedBirdId.trim().isEmpty()) {
                            currentBirdId = selectedBirdId;
                            useReferenceNameLookupOnly = false;
                        } else {
                            useReferenceNameLookupOnly = true;
                        }

                        updateBirdUi();
                        showUpdatePopup(selectedCommon);
                    }
                }
            }
    );

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarHelper.applyStandardNavBar(this);
        setContentView(R.layout.activity_bird_info);

        // Bind or inflate the UI pieces this method needs before it can update the screen.
        birdImageView = findViewById(R.id.birdImageView);
        referenceBirdImageView = findViewById(R.id.referenceBirdImageView);
        referenceImageStatusTextView = findViewById(R.id.referenceImageStatusTextView);
        commonNameTextView = findViewById(R.id.commonNameTextView);
        scientificNameTextView = findViewById(R.id.scientificNameTextView);
        speciesTextView = findViewById(R.id.speciesTextView);
        familyTextView = findViewById(R.id.familyTextView);
        btnStore = findViewById(R.id.btnStore);
        Button btnNotMyBird = findViewById(R.id.btnNotMyBird);
        Button btnDiscard = findViewById(R.id.btnDiscard);
        rgQuantity = findViewById(R.id.rgQuantity);

        currentImageUriStr = getIntent().getStringExtra("imageUri");
        currentBirdId = getIntent().getStringExtra("birdId");
        currentCommonName = getIntent().getStringExtra("commonName");
        currentScientificName = getIntent().getStringExtra("scientificName");
        currentSpecies = getIntent().getStringExtra("species");
        currentFamily = getIntent().getStringExtra("family");
        awardPoints = getIntent().getBooleanExtra("awardPoints", true);

        currentLatitude = getIntent().hasExtra("latitude") ? getIntent().getDoubleExtra("latitude", 0.0) : null;
        currentLongitude = getIntent().hasExtra("longitude") ? getIntent().getDoubleExtra("longitude", 0.0) : null;
        currentLocalityName = getIntent().getStringExtra("localityName");
        currentState = getIntent().getStringExtra("state");
        currentCountry = getIntent().getStringExtra("country");

        if (currentImageUriStr != null && birdImageView != null) {
            birdImageView.setImageURI(Uri.parse(currentImageUriStr));
        }

        updateBirdUi();

        rgQuantity.setOnCheckedChangeListener((group, checkedId) ->
                btnStore.setEnabled(checkedId != -1)
        );

        // Attach the user interaction that should run when this control is tapped.
        btnStore.setOnClickListener(v -> {
            if (!storeClicked.compareAndSet(false, true)) return;

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
            if (currentLatitude != null) i.putExtra(CardMakerActivity.EXTRA_LATITUDE, currentLatitude);
            if (currentLongitude != null) i.putExtra(CardMakerActivity.EXTRA_LONGITUDE, currentLongitude);
            i.putExtra(CardMakerActivity.EXTRA_COUNTRY, currentCountry);

            // Move into the next screen and pass the identifiers/data that screen needs.
            startActivity(i);
        });

        btnNotMyBird.setOnClickListener(v -> {
            Intent intent = new Intent(BirdInfoActivity.this, AiLoadingActivity.class);
            intent.putExtra("imageUri", currentImageUriStr);
            birdSelectionLauncher.launch(intent);
        });

        btnDiscard.setOnClickListener(v -> {
            Intent home = new Intent(BirdInfoActivity.this, HomeActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(home);
            finish();
        });
    }

    private void updateBirdUi() {
        if (commonNameTextView != null) commonNameTextView.setText("Common Name: " + (currentCommonName != null ? currentCommonName : "N/A"));
        if (scientificNameTextView != null) scientificNameTextView.setText("Scientific Name: " + (currentScientificName != null ? currentScientificName : "N/A"));
        if (speciesTextView != null) speciesTextView.setText("Species: " + (currentSpecies != null ? currentSpecies : "N/A"));
        if (familyTextView != null) familyTextView.setText("Family: " + (currentFamily != null ? currentFamily : "N/A"));
        loadReferenceBirdImage();
    }

    private void loadReferenceBirdImage() {
        if (referenceBirdImageView == null || referenceImageStatusTextView == null) return;

        String lookupBirdId = useReferenceNameLookupOnly ? null : currentBirdId;

        referenceBirdImageView.setImageDrawable(null);
        referenceBirdImageView.setVisibility(View.INVISIBLE);
        referenceImageStatusTextView.setText("Loading reference photo...");
        referenceImageStatusTextView.setVisibility(View.VISIBLE);

        BirdImageLoader.loadBirdImageInto(
                referenceBirdImageView,
                lookupBirdId,
                currentCommonName,
                currentScientificName,
                new BirdImageLoader.LoadCallback() {
                    @Override
                    public void onLoaded() {
                        if (isFinishing() || isDestroyed()) return;
                        referenceImageStatusTextView.setVisibility(View.GONE);
                    }

                    @Override
                    public void onNotFound() {
                        if (isFinishing() || isDestroyed()) return;
                        referenceBirdImageView.setImageDrawable(null);
                        referenceBirdImageView.setVisibility(View.GONE);
                        referenceImageStatusTextView.setText("Reference photo unavailable");
                        referenceImageStatusTextView.setVisibility(View.VISIBLE);
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

    /**
     * Runs when the screen returns to the foreground, so it often refreshes UI state or restarts
     * listeners.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    @Override
    protected void onResume() {
        super.onResume();
        // Reset guard if returning to this activity
        // Persist the new state so the action is saved outside the current screen.
        storeClicked.set(false);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     */
    private String getSelectedQuantity() {
        int checkedId = rgQuantity.getCheckedRadioButtonId();
        if (checkedId != -1) {
            // Bind or inflate the UI pieces this method needs before it can update the screen.
            RadioButton rb = findViewById(checkedId);
            return rb.getText().toString();
        }
        return "1-3";
    }
}
