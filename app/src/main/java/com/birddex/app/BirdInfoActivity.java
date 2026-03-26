package com.birddex.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BirdInfoActivity: Confirmation screen for an identified bird.
 */
public class BirdInfoActivity extends AppCompatActivity {

    private String currentImageUriStr;
    private String currentBirdId;
    private String currentCommonName;
    private String currentScientificName;
    private String currentSpecies;
    private String currentFamily;
    private String currentSelectionSource;
    private String identificationLogId;
    private String identificationId;
    private String identificationImageUrl;

    private Double currentLatitude;
    private Double currentLongitude;
    private String currentLocalityName;
    private String currentState;
    private String currentCountry;

    private ArrayList<Bundle> modelAlternatives = new ArrayList<>();

    private RadioGroup rgQuantity;
    private Button btnStore;
    private TextView commonNameTextView;
    private TextView scientificNameTextView;
    private TextView speciesTextView;
    private TextView familyTextView;
    private boolean awardPoints = true;

    private final AtomicBoolean storeClicked = new AtomicBoolean(false);
    private final OpenAiApi openAiApi = new OpenAiApi();

    private final ActivityResultLauncher<Intent> birdSelectionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String selectedBirdId = result.getData().getStringExtra("selectedBirdId");
                    String selectedCommon = result.getData().getStringExtra("selectedCommonName");
                    String selectedSci = result.getData().getStringExtra("selectedScientificName");
                    String selectedSpec = result.getData().getStringExtra("selectedSpecies");
                    String selectedFam = result.getData().getStringExtra("selectedFamily");
                    String selectedSource = result.getData().getStringExtra("selectedSource");

                    if (selectedBirdId != null && selectedCommon != null) {
                        currentBirdId = selectedBirdId;
                        currentCommonName = selectedCommon;
                        currentScientificName = selectedSci;
                        currentSpecies = selectedSpec;
                        currentFamily = selectedFam;
                        currentSelectionSource = selectedSource != null ? selectedSource : currentSelectionSource;

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

        ImageView birdImageView = findViewById(R.id.birdImageView);
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
        currentSelectionSource = getIntent().getStringExtra("selectionSource");
        if (currentSelectionSource == null || currentSelectionSource.trim().isEmpty()) {
            currentSelectionSource = "initial_result";
        }
        identificationLogId = getIntent().getStringExtra("identificationLogId");
        identificationId = getIntent().getStringExtra("identificationId");
        identificationImageUrl = getIntent().getStringExtra("imageUrl");
        awardPoints = getIntent().getBooleanExtra("awardPoints", true);

        ArrayList<Bundle> incomingAlternatives = getIntent().getParcelableArrayListExtra("modelAlternatives");
        if (incomingAlternatives != null) {
            modelAlternatives = incomingAlternatives;
        }

        currentLatitude = getIntent().hasExtra("latitude") ? getIntent().getDoubleExtra("latitude", 0.0) : null;
        currentLongitude = getIntent().hasExtra("longitude") ? getIntent().getDoubleExtra("longitude", 0.0) : null;
        currentLocalityName = getIntent().getStringExtra("localityName");
        currentState = getIntent().getStringExtra("state");
        currentCountry = getIntent().getStringExtra("country");

        if (currentImageUriStr != null) {
            birdImageView.setImageURI(Uri.parse(currentImageUriStr));
        }

        updateBirdUi();

        rgQuantity.setOnCheckedChangeListener((group, checkedId) -> btnStore.setEnabled(checkedId != -1));

        btnStore.setOnClickListener(v -> {
            if (!storeClicked.compareAndSet(false, true)) return;
            confirmSelectionAndContinue();
        });

        btnNotMyBird.setOnClickListener(v -> {
            if ("initial_result".equals(currentSelectionSource)) {
                openAiApi.syncIdentificationFeedback(
                        identificationLogId,
                        identificationId,
                        "reject_initial_result",
                        null,
                        null,
                        null
                );
            }

            Intent intent = new Intent(BirdInfoActivity.this, NotMyBirdActivity.class);
            intent.putExtra("imageUri", currentImageUriStr);
            intent.putExtra("imageUrl", identificationImageUrl);
            intent.putExtra("identificationLogId", identificationLogId);
            intent.putExtra("identificationId", identificationId);
            intent.putParcelableArrayListExtra("modelAlternatives", modelAlternatives);
            birdSelectionLauncher.launch(intent);
        });

        btnDiscard.setOnClickListener(v -> {
            openAiApi.syncIdentificationFeedback(
                    identificationLogId,
                    identificationId,
                    "discarded",
                    null,
                    currentSelectionSource,
                    null
            );
            Intent home = new Intent(BirdInfoActivity.this, HomeActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(home);
            finish();
        });
    }

    private void confirmSelectionAndContinue() {
        String quantity = getSelectedQuantity();
        long caughtTime = System.currentTimeMillis();

        openAiApi.syncIdentificationFeedback(
                identificationLogId,
                identificationId,
                "confirm_final_choice",
                currentBirdId,
                currentSelectionSource,
                new OpenAiApi.StatusCallback() {
                    @Override
                    public void onSuccess() {
                        launchCardMaker(quantity, caughtTime);
                    }

                    @Override
                    public void onFailure(Exception e, String message) {
                        Toast.makeText(BirdInfoActivity.this, "Saved, but identification feedback could not be synced.", Toast.LENGTH_SHORT).show();
                        launchCardMaker(quantity, caughtTime);
                    }
                }
        );
    }

    private void launchCardMaker(String quantity, long caughtTime) {
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
    }

    private void updateBirdUi() {
        if (commonNameTextView != null) commonNameTextView.setText("Common Name: " + (currentCommonName != null ? currentCommonName : "N/A"));
        if (scientificNameTextView != null) scientificNameTextView.setText("Scientific Name: " + (currentScientificName != null ? currentScientificName : "N/A"));
        if (speciesTextView != null) speciesTextView.setText("Species: " + (currentSpecies != null ? currentSpecies : "N/A"));
        if (familyTextView != null) familyTextView.setText("Family: " + (currentFamily != null ? currentFamily : "N/A"));
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
