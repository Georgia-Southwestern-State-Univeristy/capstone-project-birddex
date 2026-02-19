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

import java.util.Date;
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
        currentImageUrl = getIntent().getStringExtra("imageUrl");

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

        // Create and populate the UserBird object
        UserBird userBird = new UserBird();
        userBird.setId(userBirdId);
        userBird.setUserId(userId); 
        userBird.setBirdId(currentBirdId); // Use the bird's unique ID
        userBird.setCaptureDate(now);

        // Create and populate the CollectionSlot object
        CollectionSlot collectionSlot = new CollectionSlot();
        collectionSlot.setId(collectionSlotId);
        collectionSlot.setUserBirdId(userBirdId);
        collectionSlot.setTimestamp(now);  // Set the timestamp
        collectionSlot.setImageUrl(currentImageUrl); // Set the image URL
        
        // Log the data being sent to Firestore for debugging
        Log.d(TAG, "--- Preparing to write to Firestore ---");
        Log.d(TAG, "User UID: " + userId);
        Log.d(TAG, "UserBird to save (userBirds/" + userBird.getId() + "): userId field = " + userBird.getUserId() + ", birdId field = " + userBird.getBirdId());
        Log.d(TAG, "CollectionSlot to save (users/" + userId + "/collectionSlot/" + collectionSlot.getId() + "): userBirdId field = " + collectionSlot.getUserBirdId());

        // Save both objects to Firestore using FirebaseManager
        firebaseManager.addUserBird(userBird, task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "SUCCESS: Saved UserBird entry: " + userBirdId);
                firebaseManager.addCollectionSlot(userId, collectionSlotId, collectionSlot, slotTask -> {
                    if (slotTask.isSuccessful()) {
                        Log.d(TAG, "SUCCESS: Saved CollectionSlot: " + collectionSlotId);
                        Toast.makeText(BirdInfoActivity.this, "Saved to your collection!", Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(BirdInfoActivity.this, HomeActivity.class));
                        finish();
                    } else {
                        Log.e(TAG, "FAILURE: Could not save CollectionSlot. User may see this bird in general list but not in their personal collection.", slotTask.getException());
                        Toast.makeText(BirdInfoActivity.this, "Error saving to collection slot. Please try again.", Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                Log.e(TAG, "FAILURE: Could not save UserBird entry. This is the root of the PERMISSION_DENIED error.", task.getException());
                Toast.makeText(BirdInfoActivity.this, "Failed to save bird data: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }
}