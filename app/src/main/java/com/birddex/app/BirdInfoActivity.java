package com.birddex.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BirdInfoActivity extends AppCompatActivity {

    private static final String TAG = "BirdInfoActivity";
    private FirebaseManager firebaseManager;

    private String currentImageUriStr;
    private String currentBirdId;
    private String currentCommonName;
    private String currentScientificName;
    private String currentSpecies;
    private String currentFamily;

    private String currentImageUrl; // Firebase Storage URL (identificationImages)
    private Double currentLatitude; // nullable
    private Double currentLongitude; // nullable
    private String currentLocalityName;
    private String currentState;
    private String currentCountry;

    private CheckBox cbYes, cbNo;
    private LinearLayout layoutQuantity;
    private RadioGroup rgQuantity;
    private Button btnStore;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bird_info);

        ImageView birdImageView = findViewById(R.id.birdImageView);
        TextView commonNameTextView = findViewById(R.id.commonNameTextView);
        TextView scientificNameTextView = findViewById(R.id.scientificNameTextView);
        TextView speciesTextView = findViewById(R.id.speciesTextView);
        TextView familyTextView = findViewById(R.id.familyTextView);
        btnStore = findViewById(R.id.btnStore);
        Button btnDiscard = findViewById(R.id.btnDiscard);

        cbYes = findViewById(R.id.cbYes);
        cbNo = findViewById(R.id.cbNo);
        layoutQuantity = findViewById(R.id.layoutQuantity);
        rgQuantity = findViewById(R.id.rgQuantity);

        firebaseManager = new FirebaseManager(this);

        currentImageUriStr = getIntent().getStringExtra("imageUri");
        currentBirdId = getIntent().getStringExtra("birdId");
        currentCommonName = getIntent().getStringExtra("commonName");
        currentScientificName = getIntent().getStringExtra("scientificName");
        currentSpecies = getIntent().getStringExtra("species");
        currentFamily = getIntent().getStringExtra("family");

        // From the identification phase
        currentImageUrl = getIntent().getStringExtra("imageUrl");
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

        // Logic for CheckBoxes
        cbYes.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                cbNo.setChecked(false);
                layoutQuantity.setVisibility(View.VISIBLE);
            } else {
                layoutQuantity.setVisibility(View.GONE);
                rgQuantity.clearCheck();
            }
            updateStoreButtonState();
        });

        cbNo.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                cbYes.setChecked(false);
                layoutQuantity.setVisibility(View.GONE);
                rgQuantity.clearCheck();
            }
            updateStoreButtonState();
        });

        rgQuantity.setOnCheckedChangeListener((group, checkedId) -> updateStoreButtonState());

        // Store: Pass data to CardMakerActivity for permanent storage
        btnStore.setOnClickListener(v -> {
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
            i.putExtra(CardMakerActivity.EXTRA_RARITY, "Unknown"); 
            i.putExtra(CardMakerActivity.EXTRA_BIRD_ID, currentBirdId);
            i.putExtra(CardMakerActivity.EXTRA_SPECIES, currentSpecies);
            i.putExtra(CardMakerActivity.EXTRA_FAMILY, currentFamily);
            
            // Pass the crucial extras for Sighting and Collection
            i.putExtra("quantity", quantity);
            i.putExtra("recordSighting", cbYes.isChecked());
            if (currentLatitude != null) i.putExtra("latitude", currentLatitude);
            if (currentLongitude != null) i.putExtra("longitude", currentLongitude);
            i.putExtra("country", currentCountry);

            startActivity(i);
        });

        btnDiscard.setOnClickListener(v -> {
            Intent home = new Intent(BirdInfoActivity.this, HomeActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(home);
            finish();
        });
    }

    private void updateStoreButtonState() {
        boolean isYesChecked = cbYes.isChecked();
        boolean isNoChecked = cbNo.isChecked();
        boolean isQuantitySelected = rgQuantity.getCheckedRadioButtonId() != -1;

        if (isNoChecked) {
            btnStore.setEnabled(true);
        } else if (isYesChecked && isQuantitySelected) {
            btnStore.setEnabled(true);
        } else {
            btnStore.setEnabled(false);
        }
    }

    private String getSelectedQuantity() {
        int checkedId = rgQuantity.getCheckedRadioButtonId();
        if (checkedId != -1) {
            RadioButton rb = findViewById(checkedId);
            return rb.getText().toString();
        }
        return "N/A"; 
    }

    // REDUNDANT METHODS (logic moved to CardMakerActivity)
    private void storeBirdDiscovery(String quantity) {}
    private void getAndSetSlotIndexAndCreateUserBirdAndCollectionSlot(String userId, String userBirdId, String collectionSlotId, Date now, @Nullable String locationId, String quantity) {}
    private void createUserBirdAndCollectionSlot(String userId, String userBirdId, String collectionSlotId, Date now, @Nullable String locationId, int slotIndex, String quantity) {}
    private void saveUserBirdSighting(String userId, String userBirdId, Date timestamp, String quantity) {}
}
