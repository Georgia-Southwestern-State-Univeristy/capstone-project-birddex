package com.birddex.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
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
    private boolean awardPoints = true;
    
    // FIX: Guard against double-tap launching multiple activities
    private final AtomicBoolean storeClicked = new AtomicBoolean(false);

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
        setContentView(R.layout.activity_bird_info);

        // Bind or inflate the UI pieces this method needs before it can update the screen.
        ImageView birdImageView = findViewById(R.id.birdImageView);
        TextView commonNameTextView = findViewById(R.id.commonNameTextView);
        TextView scientificNameTextView = findViewById(R.id.scientificNameTextView);
        TextView speciesTextView = findViewById(R.id.speciesTextView);
        TextView familyTextView = findViewById(R.id.familyTextView);
        btnStore = findViewById(R.id.btnStore);
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

        if (currentImageUriStr != null) {
            birdImageView.setImageURI(Uri.parse(currentImageUriStr));
        }

        commonNameTextView.setText("Common Name: " + (currentCommonName != null ? currentCommonName : "N/A"));
        scientificNameTextView.setText("Scientific Name: " + (currentScientificName != null ? currentScientificName : "N/A"));
        speciesTextView.setText("Species: " + (currentSpecies != null ? currentSpecies : "N/A"));
        familyTextView.setText("Family: " + (currentFamily != null ? currentFamily : "N/A"));

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

        btnDiscard.setOnClickListener(v -> {
            Intent home = new Intent(BirdInfoActivity.this, HomeActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(home);
            finish();
        });
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
        return "N/A";
    }
}
