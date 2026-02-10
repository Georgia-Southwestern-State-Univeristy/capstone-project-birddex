package com.example.birddex;

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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BirdInfoActivity displays the details of a bird after it has been identified.
 * It shows the captured image along with the common name, scientific name, species, and family.
 * Users can choose to store the discovery in their collection or discard it.
 */
public class BirdInfoActivity extends AppCompatActivity {

    private static final String TAG = "BirdInfoActivity";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bird_info);

        // Initialize UI components.
        ImageView birdImageView = findViewById(R.id.birdImageView);
        TextView commonNameTextView = findViewById(R.id.commonNameTextView);
        TextView scientificNameTextView = findViewById(R.id.scientificNameTextView);
        TextView speciesTextView = findViewById(R.id.speciesTextView);
        TextView familyTextView = findViewById(R.id.familyTextView);
        Button btnStore = findViewById(R.id.btnStore);
        Button btnDiscard = findViewById(R.id.btnDiscard);

        // Retrieve data from Intent.
        String uriStr = getIntent().getStringExtra("imageUri");
        String commonName = getIntent().getStringExtra("commonName");
        String scientificName = getIntent().getStringExtra("scientificName");
        String species = getIntent().getStringExtra("species");
        String family = getIntent().getStringExtra("family");
        String imageUrl = getIntent().getStringExtra("imageUrl"); // The Firebase Storage URL

        Log.d(TAG, "Intent data: commonName=" + commonName + ", scientificName=" + scientificName + ", imageUrl=" + imageUrl);

        if (uriStr != null) {
            birdImageView.setImageURI(Uri.parse(uriStr));
        }

        commonNameTextView.setText("Common Name: " + commonName);
        scientificNameTextView.setText("Scientific Name: " + scientificName);
        speciesTextView.setText("Species: " + species);
        familyTextView.setText("Family: " + family);

        // Handle the "Store image in collection" action.
        btnStore.setOnClickListener(v -> {
            storeInCollection(commonName, scientificName, species, family, imageUrl);
        });

        // Handle the "Discard image" action.
        btnDiscard.setOnClickListener(v -> {
            // Simply return to the Home screen without saving further.
            startActivity(new Intent(BirdInfoActivity.this, HomeActivity.class));
            finish();
        });
    }

    /**
     * Stores the bird discovery in the user's dedicated collection in Firestore.
     * Generates a unique collection slot ID for the entry.
     */
    private void storeInCollection(String commonName, String scientificName, String species, String family, String imageUrl) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.e(TAG, "No user logged in. Cannot store in collection.");
            Toast.makeText(this, "Error: No user logged in", Toast.LENGTH_SHORT).show();
            return;
        }

        String userId = user.getUid();
        Log.d(TAG, "Storing in collection for user: " + userId);

        // Generate a unique ID for this collection slot.
        String collectionSlotId = UUID.randomUUID().toString();

        Map<String, Object> birdData = new HashMap<>();
        birdData.put("collectionSlotId", collectionSlotId);
        birdData.put("commonName", commonName);
        birdData.put("scientificName", scientificName);
        birdData.put("species", species);
        birdData.put("family", family);
        birdData.put("imageUrl", imageUrl);
        birdData.put("timestamp", System.currentTimeMillis());

        Log.d(TAG, "Bird data map: " + birdData.toString());

        // Save the bird data to a specific "collection" sub-collection for the user.
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(userId)
                .collection("collection")
                .document(collectionSlotId)
                .set(birdData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Successfully saved to collection: " + collectionSlotId);
                    Toast.makeText(BirdInfoActivity.this, "Saved to your collection!", Toast.LENGTH_SHORT).show();
                    // After storing, go back to Home.
                    startActivity(new Intent(BirdInfoActivity.this, HomeActivity.class));
                    finish();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "CRITICAL ERROR: Failed to save to collection", e);
                    Log.e(TAG, "Error message: " + e.getMessage());
                    Log.e(TAG, "Error cause: " + e.getCause());
                    Toast.makeText(BirdInfoActivity.this, "Failed to save: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }
}
