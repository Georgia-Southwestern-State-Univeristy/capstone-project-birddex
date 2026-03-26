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

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class BirdInfoActivity extends AppCompatActivity {

    private String currentImageUriStr;
    private String currentImageUrl;
    private String currentBirdId;
    private String currentCommonName;
    private String currentScientificName;
    private String currentSpecies;
    private String currentFamily;
    private String identificationLogId;
    private String identificationId;
    private String currentSelectionSource;

    private Double currentLatitude;
    private Double currentLongitude;
    private String currentLocalityName;
    private String currentState;
    private String currentCountry;

    private ArrayList<Bundle> modelAlternatives = new ArrayList<>();

    private RadioGroup rgQuantity;
    private Button btnStore;
    private TextView commonNameTextView, scientificNameTextView, speciesTextView, familyTextView;
    private TextView referenceImageStatusTextView;
    private ImageView birdImageView;
    private ImageView referenceBirdImageView;
    private boolean awardPoints = true;
    private boolean useReferenceNameLookupOnly = false;
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
        SystemBarHelper.applyStandardNavBar(this);
        setContentView(R.layout.activity_bird_info);

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
        currentImageUrl = getIntent().getStringExtra("imageUrl");
        currentBirdId = getIntent().getStringExtra("birdId");
        currentCommonName = getIntent().getStringExtra("commonName");
        currentScientificName = getIntent().getStringExtra("scientificName");
        currentSpecies = getIntent().getStringExtra("species");
        currentFamily = getIntent().getStringExtra("family");
        identificationLogId = getIntent().getStringExtra("identificationLogId");
        identificationId = getIntent().getStringExtra("identificationId");
        currentSelectionSource = getIntent().getStringExtra("selectionSource");
        awardPoints = getIntent().getBooleanExtra("awardPoints", true);

        currentLatitude = getIntent().hasExtra("latitude") ? getIntent().getDoubleExtra("latitude", 0.0) : null;
        currentLongitude = getIntent().hasExtra("longitude") ? getIntent().getDoubleExtra("longitude", 0.0) : null;
        currentLocalityName = getIntent().getStringExtra("localityName");
        currentState = getIntent().getStringExtra("state");
        currentCountry = getIntent().getStringExtra("country");

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

        rgQuantity.setOnCheckedChangeListener((group, checkedId) -> btnStore.setEnabled(checkedId != -1));

        btnStore.setOnClickListener(v -> {
            if (!storeClicked.compareAndSet(false, true)) return;

            openAiApi.syncIdentificationFeedback(
                    identificationLogId,
                    identificationId,
                    "confirm_final_choice",
                    currentBirdId,
                    currentSelectionSource,
                    null
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
            if (currentLatitude != null) i.putExtra(CardMakerActivity.EXTRA_LATITUDE, currentLatitude);
            if (currentLongitude != null) i.putExtra(CardMakerActivity.EXTRA_LONGITUDE, currentLongitude);
            i.putExtra(CardMakerActivity.EXTRA_COUNTRY, currentCountry);
            startActivity(i);
        });

        btnNotMyBird.setOnClickListener(v -> {
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

    @Override
    protected void onResume() {
        super.onResume();
        storeClicked.set(false);
    }

    private String getSelectedQuantity() {
        int checkedId = rgQuantity.getCheckedRadioButtonId();
        if (checkedId != -1) {
            RadioButton rb = findViewById(checkedId);
            return rb.getText().toString();
        }
        return "1-3";
    }
}
