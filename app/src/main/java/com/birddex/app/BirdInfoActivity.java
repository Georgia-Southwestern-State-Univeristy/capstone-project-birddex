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

    private String currentImageUrl; // Firebase Storage URL (nullable)
    private Double currentLatitude; // nullable
    private Double currentLongitude; // nullable
    private String currentLocalityName;
    private String currentState;
    private String currentCountry;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bird_info);

        ImageView birdImageView = findViewById(R.id.birdImageView);
        TextView commonNameTextView = findViewById(R.id.commonNameTextView);
        TextView scientificNameTextView = findViewById(R.id.scientificNameTextView);
        TextView speciesTextView = findViewById(R.id.speciesTextView);
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

        // From the more advanced version (may be null depending on caller)
        currentImageUrl = getIntent().getStringExtra("imageUrl");
        currentLatitude = getIntent().hasExtra("latitude") ? getIntent().getDoubleExtra("latitude", 0.0) : null;
        currentLongitude = getIntent().hasExtra("longitude") ? getIntent().getDoubleExtra("longitude", 0.0) : null;
        currentLocalityName = getIntent().getStringExtra("localityName");
        currentState = getIntent().getStringExtra("state");
        currentCountry = getIntent().getStringExtra("country");

        if (currentImageUriStr != null) {
            birdImageView.setImageURI(Uri.parse(currentImageUriStr));
        }

        // Keep the safer null-handling text from the simpler version
        commonNameTextView.setText("Common Name: " + (currentCommonName != null ? currentCommonName : "N/A"));
        scientificNameTextView.setText("Scientific Name: " + (currentScientificName != null ? currentScientificName : "N/A"));
        speciesTextView.setText("Species: " + (currentSpecies != null ? currentSpecies : "N/A"));
        familyTextView.setText("Family: " + (currentFamily != null ? currentFamily : "N/A"));

        // Store: do BOTH behaviors (only added code; none removed):
        // 1) Save to Firebase collection (advanced version)
        // 2) Open CardMakerActivity (simple version)
        btnStore.setOnClickListener(v -> {
            // If you don't have imageUrl yet, you can still go to CardMakerActivity.
            // Firebase save will still attempt (and may store null imageUrl depending on your models).
            storeBirdDiscovery();

            Intent i = new Intent(BirdInfoActivity.this, CardMakerActivity.class);
            i.putExtra(CardMakerActivity.EXTRA_IMAGE_URI, currentImageUriStr);
            i.putExtra(CardMakerActivity.EXTRA_BIRD_NAME, currentCommonName);
            i.putExtra(CardMakerActivity.EXTRA_SCI_NAME, currentScientificName);
            i.putExtra(CardMakerActivity.EXTRA_CONFIDENCE, "--"); // Not provided here, default
            i.putExtra(CardMakerActivity.EXTRA_RARITY, "Unknown"); // Not provided here, default
            i.putExtra(CardMakerActivity.EXTRA_BIRD_ID, currentBirdId);
            i.putExtra(CardMakerActivity.EXTRA_SPECIES, currentSpecies);
            i.putExtra(CardMakerActivity.EXTRA_FAMILY, currentFamily);
            startActivity(i);
        });

        // Discard: keep the "clear task stack" behavior (more robust),
        // but also works like the advanced version (go home + finish)
        btnDiscard.setOnClickListener(v -> {
            Intent home = new Intent(BirdInfoActivity.this, HomeActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(home);
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
                getAndSetSlotIndexAndCreateUserBirdAndCollectionSlot(
                        userId, userBirdId, collectionSlotId, now, newLocationId
                );
            });
        } else {
            Log.w(TAG, "No valid location data. UserBird will be saved without a linked location.");
            getAndSetSlotIndexAndCreateUserBirdAndCollectionSlot(
                    userId, userBirdId, collectionSlotId, now, null
            );
        }
    }

    private void getAndSetSlotIndexAndCreateUserBirdAndCollectionSlot(String userId,
                                                                      String userBirdId,
                                                                      String collectionSlotId,
                                                                      Date now,
                                                                      @Nullable String locationId) {
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
                        Toast.makeText(BirdInfoActivity.this,
                                "Error assigning collection slot. Please try again.",
                                Toast.LENGTH_LONG).show();
                        // Do NOT finish() here because the user may still want CardMakerActivity to work
                    }
                });
    }

    private void createUserBirdAndCollectionSlot(String userId,
                                                 String userBirdId,
                                                 String collectionSlotId,
                                                 Date now,
                                                 @Nullable String locationId,
                                                 int slotIndex) {

        UserBird userBird = new UserBird(
                userBirdId,
                userId,
                currentBirdId,
                currentImageUrl,
                locationId,
                now,   // timeSpotted
                null,  // birdFactsId
                null   // hunterFactsId
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
                collectionSlot.setSlotIndex(slotIndex);

                firebaseManager.addCollectionSlot(userId, collectionSlotId, collectionSlot, slotTask -> {
                    if (slotTask.isSuccessful()) {
                        Log.d(TAG, "SUCCESS: Saved CollectionSlot: " + collectionSlotId
                                + " with rarity R1 and slotIndex " + slotIndex);
                        saveUserBirdSighting(userId, userBirdId, now);
                    } else {
                        Log.e(TAG, "FAILURE: Could not save CollectionSlot.", slotTask.getException());
                        Toast.makeText(BirdInfoActivity.this,
                                "Error saving to collection slot.",
                                Toast.LENGTH_LONG).show();
                    }
                });

            } else {
                Log.e(TAG, "FAILURE: Could not save UserBird entry.", task.getException());
                Toast.makeText(BirdInfoActivity.this,
                        "Failed to save bird data: " + (task.getException() != null ? task.getException().getMessage() : "Unknown error"),
                        Toast.LENGTH_LONG).show();
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
            sightingLocation = new Location(
                    null,
                    new HashMap<>(),
                    currentLatitude,
                    currentLongitude,
                    currentCountry,
                    currentState,
                    currentLocalityName
            );
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
            // Don't force navigation here because CardMakerActivity may already be open.
        });
    }
}