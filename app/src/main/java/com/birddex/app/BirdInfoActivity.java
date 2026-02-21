package com.birddex.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.DocumentSnapshot;

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
    private String currentImageUrl; // Firebase Storage URL
    private Double currentLatitude;
    private Double currentLongitude;
    private String currentLocalityName;
    private String currentState; // New field for state
    private String currentCountry; // New field for country

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bird_info);

        ImageView birdImageView = findViewById(R.id.birdImageView);
        TextView commonNameTextView = findViewById(R.id.commonNameTextView);
        TextView scientificNameTextView = findViewById(R.id.scientificNameTextView);
        TextView speciesTextView = findViewById(R.id.speciesTextView); // Corrected ID lookup
        TextView familyTextView = findViewById(R.id.familyTextView);
        Button btnStore = findViewById(R.id.btnStore);
        Button btnDiscard = findViewById(R.id.btnDiscard);

        firebaseManager = new FirebaseManager();

        currentImageUriStr = getIntent().getStringExtra("imageUri");
        currentBirdId = getIntent().getStringExtra("birdId");
        currentCommonName = getIntent().getStringExtra("commonName");
        currentScientificName = getIntent().getStringExtra("scientificName");
        currentSpecies = getIntent().getStringExtra("species");
        currentFamily = getIntent().getStringExtra("family");
        currentImageUrl = getIntent().getStringExtra("imageUrl");

        // Get location data from intent (all are nullable)
        currentLatitude = getIntent().hasExtra("latitude") ? getIntent().getDoubleExtra("latitude", 0.0) : null;
        currentLongitude = getIntent().hasExtra("longitude") ? getIntent().getDoubleExtra("longitude", 0.0) : null;
        currentLocalityName = getIntent().getStringExtra("localityName");
        currentState = getIntent().getStringExtra("state");
        currentCountry = getIntent().getStringExtra("country");

        if (currentImageUriStr != null) {
            birdImageView.setImageURI(Uri.parse(currentImageUriStr));
        }

        commonNameTextView.setText("Common Name: " + currentCommonName);
        scientificNameTextView.setText("Scientific Name: " + currentScientificName);
        speciesTextView.setText("Species: " + currentSpecies);
        familyTextView.setText("Family: " + currentFamily);

        btnStore.setOnClickListener(v -> storeBirdDiscovery());
        btnDiscard.setOnClickListener(v -> {
            startActivity(new Intent(BirdInfoActivity.this, HomeActivity.class));
            finish();
        });
    }

    private void storeBirdDiscovery() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.e(TAG, "Store failed: User is not authenticated.");
            Toast.makeText(this, "Error: No user logged in. Please log in again.", Toast.LENGTH_LONG).show();
            return;
        }

        String userId = user.getUid();
        String userBirdId = UUID.randomUUID().toString();
        String collectionSlotId = UUID.randomUUID().toString();
        Date now = new Date();

        // --- Handle Location (create and save) ---
        String newLocationId = UUID.randomUUID().toString();
        if (currentLatitude != null && currentLongitude != null) {
            Location location = new Location(
                    newLocationId,
                    new HashMap<>(), // metadata
                    currentLatitude,
                    currentLongitude,
                    currentCountry,
                    currentState,
                    currentLocalityName
            );

            firebaseManager.addLocation(location, locationTask -> {
                if (locationTask.isSuccessful()) {
                    Log.d(TAG, "SUCCESS: Saved Location entry: " + newLocationId);
                } else {
                    Log.e(TAG, "FAILURE: Could not save Location entry.", locationTask.getException());
                }
                // Proceed, passing the new locationId and the 'now' timestamp
                getAndSetSlotIndexAndCreateUserBirdAndCollectionSlot(userId, userBirdId, collectionSlotId, now, newLocationId);
            });
        } else {
            Log.w(TAG, "No valid location data. UserBird will be saved without a linked location.");
            // Proceed without a locationId, but still pass 'now'
            getAndSetSlotIndexAndCreateUserBirdAndCollectionSlot(userId, userBirdId, collectionSlotId, now, null);
        }
    }

    private void getAndSetSlotIndexAndCreateUserBirdAndCollectionSlot(String userId, String userBirdId, String collectionSlotId, Date now, @Nullable String locationId) {
        FirebaseFirestore.getInstance()
                .collection("users").document(userId).collection("collectionSlot")
                .orderBy("slotIndex", Query.Direction.DESCENDING)
                .limit(1)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        int nextSlotIndex = 0;
                        if (!task.getResult().isEmpty()) {
                            DocumentSnapshot document = task.getResult().getDocuments().get(0);
                            if (document.contains("slotIndex")) {
                                Long maxSlotIndex = document.getLong("slotIndex");
                                if (maxSlotIndex != null) {
                                    nextSlotIndex = maxSlotIndex.intValue() + 1;
                                } else {
                                    Log.e(TAG, "slotIndex in document is null, defaulting to 0");
                                }
                            } else {
                                Log.e(TAG, "Document does not contain slotIndex field, defaulting to 0");
                            }
                        }
                        createUserBirdAndCollectionSlot(userId, userBirdId, collectionSlotId, now, locationId, nextSlotIndex);
                    } else {
                        Log.e(TAG, "Error getting max slotIndex: " + task.getException().getMessage());
                        Toast.makeText(BirdInfoActivity.this, "Error assigning collection slot. Please try again.", Toast.LENGTH_LONG).show();
                        finish();
                    }
                });
    }

    private void createUserBirdAndCollectionSlot(String userId, String userBirdId, String collectionSlotId, Date now, @Nullable String locationId, int slotIndex) {
        UserBird userBird = new UserBird(
                userBirdId,
                userId,
                currentBirdId,
                currentImageUrl,
                locationId,
                now, // timeSpotted
                null, // birdFactsId
                null  // hunterFactsId
        );

        firebaseManager.addUserBird(userBird, task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "SUCCESS: Saved UserBird entry: " + userBirdId);

                CollectionSlot collectionSlot = new CollectionSlot();
                collectionSlot.setId(collectionSlotId);
                collectionSlot.setUserBirdId(userBirdId);
                collectionSlot.setTimestamp(now);
                collectionSlot.setImageUrl(currentImageUrl);
                collectionSlot.setRarity("R1"); // Set initial rarity to R1
                collectionSlot.setSlotIndex(slotIndex); // Set the calculated slot index

                firebaseManager.addCollectionSlot(userId, collectionSlotId, collectionSlot, slotTask -> {
                    if (slotTask.isSuccessful()) {
                        Log.d(TAG, "SUCCESS: Saved CollectionSlot: " + collectionSlotId + " with rarity R1 and slotIndex " + slotIndex);
                        saveUserBirdSighting(userId, userBirdId, now);
                    } else {
                        Log.e(TAG, "FAILURE: Could not save CollectionSlot.", slotTask.getException());
                        Toast.makeText(BirdInfoActivity.this, "Error saving to collection slot.", Toast.LENGTH_LONG).show();
                        finish();
                    }
                });
            } else {
                Log.e(TAG, "FAILURE: Could not save UserBird entry.", task.getException());
                Toast.makeText(BirdInfoActivity.this, "Failed to save bird data: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    private void saveUserBirdSighting(String userId, String userBirdId, Date timestamp) {
        // --- Create user_sighting map ---
        Map<String, Object> userSightingData = new HashMap<>();
        userSightingData.put("userBirdId", userBirdId);
        userSightingData.put("userId", userId);

        // --- Create Location object for denormalization ---
        Location sightingLocation = null;
        if (currentLatitude != null && currentLongitude != null) {
            sightingLocation = new Location(null, new HashMap<>(), currentLatitude, currentLongitude, currentCountry, currentState, currentLocalityName);
        }

        // --- Create and save UserBirdSighting document ---
        String userBirdSightId = UUID.randomUUID().toString();
        UserBirdSighting userBirdSighting = new UserBirdSighting(
                userBirdSightId,
                userSightingData,
                sightingLocation,
                currentBirdId,
                currentCommonName,
                currentImageUrl,
                timestamp
        );

        firebaseManager.addUserBirdSighting(userBirdSighting, userBirdSightingTask -> {
            if (userBirdSightingTask.isSuccessful()) {
                Log.d(TAG, "SUCCESS: Saved denormalized UserBirdSighting document: " + userBirdSightId);
            } else {
                Log.e(TAG, "FAILURE: Could not save UserBirdSighting document", userBirdSightingTask.getException());
            }
            Toast.makeText(BirdInfoActivity.this, "Saved to your collection!", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(BirdInfoActivity.this, HomeActivity.class));
            finish();
        });
    }
}
