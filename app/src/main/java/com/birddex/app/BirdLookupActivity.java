package com.birddex.app;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.bumptech.glide.Glide;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BirdLookupActivity is currently configured to demonstrate direct bird detail and image fetching by name.
 * It no longer pre-loads the full Georgia bird list or provides local search functionality.
 */
public class BirdLookupActivity extends AppCompatActivity implements NetworkMonitor.NetworkStatusListener {

    private static final String TAG = "BirdLookupActivity";
    private EditText commonNameEditText;
    private EditText scientificNameEditText; // This field is now unused for direct search
    private Button searchButton;
    private ConstraintLayout finalDisplayLayout;
    private ImageView birdImageView;
    private TextView speciesInfoTextView;
    private TextView generalFactsTextView;
    private TextView hunterFactsTextView;

    private FirebaseFirestore db;

    private NuthatchApi nuthatchApi;
    private EbirdApi ebirdApi;
    private NetworkMonitor networkMonitor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //setContentView(R.layout.activity_bird_lookup);

        // --- UI Initialization ---
        //**commonNameEditText = findViewById(R.id.commonNameEditText);
        // scientificNameEditText is present in layout but not actively used for this simplified flow.
        //scientificNameEditText = findViewById(R.id.scientificNameEditText);
        //searchButton = findViewById(R.id.searchButton);
        // resultsListView removed - no longer displaying a list of search results
        //finalDisplayLayout = findViewById(R.id.finalDisplayLayout);
        //birdImageView = findViewById(R.id.birdImageView);
        //speciesInfoTextView = findViewById(R.id.speciesInfoTextView);
        //generalFactsTextView = findViewById(R.id.generalFactsTextView);
        //hunterFactsTextView = findViewById(R.id.hunterFactsTextView);

        // --- Backend Initialization ---
        db = FirebaseFirestore.getInstance();

        // API Helpers (Now using Cloud Functions)
        nuthatchApi = new NuthatchApi();
        ebirdApi = new EbirdApi();
        networkMonitor = new NetworkMonitor(this, this);

        // Search button now triggers direct fetch and display for a single bird
        searchButton.setOnClickListener(v -> {
            if (!networkMonitor.isConnected()) {
                Toast.makeText(this, "No internet connection. Cannot perform search.", Toast.LENGTH_SHORT).show();
                return;
            }

            String commonNameQuery = commonNameEditText.getText().toString().trim();
            if (commonNameQuery.isEmpty()) {
                Toast.makeText(this, "Please enter a bird common name.", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Clear previous results and show loading state if desired, then fetch
            resetToFinalDisplayState(); // Show final display area
            // You might want to add a loading spinner here

            // Attempt to fetch details directly based on common name
            // NOTE: This assumes 'getBirdDetailsAndFacts' can handle common name search,
            // but the Cloud Function currently expects birdId. This will need adjustment
            // if you want to search by common name via Cloud Function.
            // For now, it will attempt to find an image and then display a placeholder for facts.
            findImageAndDisplay(commonNameQuery, "Unknown Scientific Name", "Unknown Family", null);
            Toast.makeText(this, "Attempting to fetch details for: " + commonNameQuery, Toast.LENGTH_SHORT).show();
        });

        // resultsListView.setOnItemClickListener removed as there is no resultsListView
    }

    @Override
    protected void onResume() {
        super.onResume();
        networkMonitor.register();
        // Re-enable search button if network is available
        if (networkMonitor.isConnected()) {
            searchButton.setEnabled(true);
        } else {
            searchButton.setEnabled(false);
            Toast.makeText(this, "Internet disconnected. Search functionality limited.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        networkMonitor.unregister();
    }

    // --- NetworkMonitor Callbacks ---
    @Override
    public void onNetworkAvailable() {
        Log.d(TAG, "Network became available in BirdLookupActivity.");
        Toast.makeText(this, "Internet connection restored.", Toast.LENGTH_SHORT).show();
        searchButton.setEnabled(true);
    }

    @Override
    public void onNetworkLost() {
        Log.e(TAG, "Network lost in BirdLookupActivity.");
        Toast.makeText(this, "Internet connection lost. Search functionality disabled.", Toast.LENGTH_LONG).show();
        searchButton.setEnabled(false);
    }

    /**
     * Fetches and displays full details and facts for a specific bird.
     * This method is intended to be called with a valid birdId.
     * Currently, the search button does not provide a birdId directly.
     * @param birdId The ID of the bird to fetch.
     */
    private void fetchAndDisplayBirdDetails(String birdId) {
        ebirdApi.fetchBirdDetailsAndFacts(birdId, new EbirdApi.BirdDetailsCallback() {
            @Override
            public void onSuccess(JSONObject birdDetails) {
                try {
                    String commonName = birdDetails.getString("commonName");
                    String scientificName = birdDetails.getString("scientificName");
                    String family = birdDetails.getString("family");
                    String imageUrl = null;

                    if (birdDetails.has("imageUrl")) {
                        imageUrl = birdDetails.getString("imageUrl");
                        displayFinalResults(imageUrl, commonName, scientificName, family, birdDetails);
                    } else {
                        searchNuthatchForImage(commonName, scientificName, family, birdDetails);
                    }

                } catch (JSONException e) {
                    Log.e(TAG, "Error parsing bird details JSON: " + e.getMessage(), e);
                    Toast.makeText(BirdLookupActivity.this, "Error displaying bird details.", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Exception e) {
                Log.e(TAG, "Failed to fetch bird details and facts: " + e.getMessage(), e);
                Toast.makeText(BirdLookupActivity.this, "Failed to load bird details.", Toast.LENGTH_LONG).show();
                // Display placeholders for facts if fetching fails
                displayFinalResults(null, "Unknown", "Unknown", "Unknown", null);
            }
        });
    }

    // Modified to accept commonName and the full birdDetails JSONObject
    private void findImageAndDisplay(final String commonName, final String scientificName, final String family, final JSONObject birdDetails) {
        if (!networkMonitor.isConnected()) {
            Toast.makeText(this, "No internet connection. Cannot fetch image.", Toast.LENGTH_SHORT).show();
            return;
        }

        // First check if we already have a high-quality image URL in Firestore
        // Note: It's more efficient if imageUrl is part of the birdDetails from getBirdDetailsAndFacts CF already
        db.collection("bird_images").document(commonName).get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists() && documentSnapshot.contains("imageUrl")) {
                        displayFinalResults(documentSnapshot.getString("imageUrl"), commonName, scientificName, family, birdDetails);
                    } else {
                        searchNuthatchForImage(commonName, scientificName, family, birdDetails);
                    }
                }).addOnFailureListener(e -> {
                    Log.e(TAG, "Firestore image lookup failed or timed out: ", e);
                    if (networkMonitor.isConnected()) {
                        searchNuthatchForImage(commonName, scientificName, family, birdDetails);
                    } else {
                        Toast.makeText(BirdLookupActivity.this, "Failed to get bird image due to network or server issue.", Toast.LENGTH_LONG).show();
                        displayFinalResults(null, commonName, scientificName, family, birdDetails);
                    }
                });
    }

    // Modified to accept commonName and the full birdDetails JSONObject
    private void searchNuthatchForImage(final String commonName, final String scientificName, final String family, final JSONObject birdDetails) {
        if (!networkMonitor.isConnected()) {
            Toast.makeText(this, "No internet connection. Cannot search for images.", Toast.LENGTH_SHORT).show();
            return;
        }

        nuthatchApi.searchNuthatchByName(commonName, new NuthatchApi.SearchResultHandler() {
            @Override
            public void onImageFound(String imageUrl) {
                displayFinalResults(imageUrl, commonName, scientificName, family, birdDetails);
                saveBirdImageMetadata(commonName, scientificName, imageUrl, family);
            }

            @Override
            public void onImageNotFound() {
                Toast.makeText(BirdLookupActivity.this, "No reference image found for " + commonName + ".", Toast.LENGTH_LONG).show();
                displayFinalResults(null, commonName, scientificName, family, birdDetails);
            }
        });
    }

    // Modified to accept commonName and the full birdDetails JSONObject for displaying facts
    private void displayFinalResults(String imageUrl, String commonName, String scientificName, String family, JSONObject birdDetails) {
        if (imageUrl != null && !imageUrl.isEmpty()) {
            Glide.with(this).load(imageUrl).into(birdImageView);
        } else {
           // birdImageView.setImageResource(R.drawable.ic_bird_placeholder); // Use a placeholder if no image
        }

        // Display core info
        StringBuilder coreInfo = new StringBuilder();
        coreInfo.append("Common Name: ").append(commonName).append("\n");
        coreInfo.append("Scientific Name: ").append(scientificName).append("\n");
        coreInfo.append("Family: ").append(family).append("\n");
        speciesInfoTextView.setText(coreInfo.toString());

        // Display General Facts
        StringBuilder generalFacts = new StringBuilder("\n--- General Facts ---\n");
        try {
            if (birdDetails != null) {
                JSONObject generalFactsJson = birdDetails.getJSONObject("generalFacts");
                generalFacts.append("Size/Appearance: ").append(generalFactsJson.optString("sizeAppearance", "N/A")).append("\n");
                generalFacts.append("Distinctive Behaviors: ").append(generalFactsJson.optString("distinctiveBehaviors", "N/A")).append("\n");
                generalFacts.append("Where in Georgia Found: ").append(generalFactsJson.optString("whereInGeorgiaFound", "N/A")).append("\n");
                generalFacts.append("Seasonal Presence: ").append(generalFactsJson.optString("seasonalPresence", "N/A")).append("\n");
                generalFacts.append("Diet: ").append(generalFactsJson.optString("diet", "N/A")).append("\n");
                // Add more general facts as needed
            } else {
                generalFacts.append("Bird details not available.");
            }
        } catch (JSONException e) {
            generalFacts.append("No general facts available.");
            Log.e(TAG, "Error parsing general facts", e);
        }
        generalFactsTextView.setText(generalFacts.toString());

        // Display Hunter Facts
        StringBuilder hunterFacts = new StringBuilder("\n--- Hunter Facts ---\n");
        try {
            if (birdDetails != null) {
                JSONObject hunterFactsJson = birdDetails.getJSONObject("hunterFacts");
                hunterFacts.append("Legal Status (GA): ").append(hunterFactsJson.optString("legalStatusGeorgia", "N/A")).append("\n");
                hunterFacts.append("Season: ").append(hunterFactsJson.optString("season", "N/A")).append("\n");
                hunterFacts.append("License Requirements: ").append(hunterFactsJson.optString("licenseRequirements", "N/A")).append("\n");
                hunterFacts.append("Georgia DNR Link: ").append(hunterFactsJson.optString("georgiaDNRHuntingLink", "N/A")).append("\n");
                // Add more hunter facts as needed
            } else {
                hunterFacts.append("Bird details not available.");
            }
        } catch (JSONException e) {
            hunterFacts.append("No hunter facts available.");
            Log.e(TAG, "Error parsing hunter facts", e);
        }
        hunterFactsTextView.setText(hunterFacts.toString());
    }

    private void saveBirdImageMetadata(String commonName, String scientificName, String imageUrl, String family) {
        Map<String, Object> birdData = new HashMap<>();
        birdData.put("commonName", commonName);
        birdData.put("scientificName", scientificName);
        birdData.put("imageUrl", imageUrl);
        birdData.put("family", family);

        db.collection("bird_images").document(commonName).set(birdData);
    }

    private void resetToSearchState() {
        if (finalDisplayLayout != null) finalDisplayLayout.setVisibility(View.GONE);
        // resultsListView removed
        if (generalFactsTextView != null) generalFactsTextView.setText("");
        if (hunterFactsTextView != null) hunterFactsTextView.setText("");
        if (speciesInfoTextView != null) speciesInfoTextView.setText("");
        if (birdImageView != null) birdImageView.setImageDrawable(null);
    }

    private void resetToFinalDisplayState() {
        // resultsListView removed
        if (finalDisplayLayout != null) finalDisplayLayout.setVisibility(View.VISIBLE);
    }
}
