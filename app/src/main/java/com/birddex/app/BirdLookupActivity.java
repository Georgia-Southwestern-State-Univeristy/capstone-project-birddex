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
 * BirdLookupActivity allows users to search for birds within the Georgia regional list.
 * It uses Cloud Functions to securely fetch data and find images.
 */
public class BirdLookupActivity extends AppCompatActivity {

    private static final String TAG = "BirdLookupActivity";
    private EditText commonNameEditText;
    private EditText scientificNameEditText;
    private Button searchButton;
    private ListView resultsListView;
    private ConstraintLayout finalDisplayLayout;
    private ImageView birdImageView;
    private TextView speciesInfoTextView;

    private FirebaseFirestore db;
    private ArrayAdapter<String> resultsAdapter;
    private List<JSONObject> allGeorgiaBirds;
    private List<JSONObject> filteredResults;

    private NuthatchApi nuthatchApi;
    private EbirdApi ebirdApi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //setContentView(R.layout.activity_bird_lookup);

        // --- UI Initialization ---
        //commonNameEditText = findViewById(R.id.commonNameEditText);
        //scientificNameEditText = findViewById(R.id.scientificNameEditText);
        //searchButton = findViewById(R.id.searchButton);
        //resultsListView = findViewById(R.id.resultsListView);
        //finalDisplayLayout = findViewById(R.id.finalDisplayLayout);
        //birdImageView = findViewById(R.id.birdImageView);
        //speciesInfoTextView = findViewById(R.id.speciesInfoTextView);

        // --- Backend Initialization ---
        db = FirebaseFirestore.getInstance();
        allGeorgiaBirds = new ArrayList<>();
        filteredResults = new ArrayList<>();
        resultsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        resultsListView.setAdapter(resultsAdapter);

        // API Helpers (Now using Cloud Functions)
        nuthatchApi = new NuthatchApi();
        ebirdApi = new EbirdApi();

        // Pre-load the Georgia bird list from the secure Cloud Function cache
        fetchGeorgiaBirdList();

        // --- Click Listeners ---
        searchButton.setOnClickListener(v -> {
            String commonNameQuery = commonNameEditText.getText().toString().trim();
            String scientificNameQuery = scientificNameEditText.getText().toString().trim();

            if (commonNameQuery.isEmpty() && scientificNameQuery.isEmpty()) {
                Toast.makeText(this, "Please enter a search term.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (allGeorgiaBirds.isEmpty()) {
                Toast.makeText(this, "Bird list is still loading...", Toast.LENGTH_SHORT).show();
                return;
            }
            resetToSearchState();
            searchLocalBirdList(commonNameQuery, scientificNameQuery);
        });

        resultsListView.setOnItemClickListener((parent, view, position, id) -> {
            try {
                JSONObject selectedBird = filteredResults.get(position);
                String commonName = selectedBird.getString("commonName");
                String sciName = selectedBird.getString("scientificName");
                String family = selectedBird.getString("family");

                resetToFinalDisplayState();
                findImageAndDisplay(commonName, sciName, family);
            } catch (JSONException e) {
                Log.e(TAG, "Error getting selected bird data", e);
            }
        });
    }

    private void fetchGeorgiaBirdList() {
        ebirdApi.fetchGeorgiaBirdList(new EbirdApi.EbirdCallback() {
            @Override
            public void onSuccess(List<JSONObject> birds) {
                allGeorgiaBirds.clear();
                allGeorgiaBirds.addAll(birds);
                Log.d(TAG, "Loaded " + allGeorgiaBirds.size() + " Georgia birds from Cloud cache.");
            }

            @Override
            public void onFailure(Exception e) {
                Log.e(TAG, "Failed to fetch bird list.", e);
                Toast.makeText(BirdLookupActivity.this, "Failed to load regional bird data.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void searchLocalBirdList(String commonNameQuery, String sciNameQuery) {
        filteredResults.clear();
        resultsAdapter.clear();

        for (JSONObject bird : allGeorgiaBirds) {
            try {
                String comName = bird.getString("commonName").toLowerCase();
                String sciName = bird.getString("scientificName").toLowerCase();
                
                boolean matchCommon = !commonNameQuery.isEmpty() && comName.contains(commonNameQuery.toLowerCase());
                boolean matchSci = !sciNameQuery.isEmpty() && sciName.contains(sciNameQuery.toLowerCase());

                if (matchCommon || matchSci) {
                    filteredResults.add(bird);
                    resultsAdapter.add(bird.getString("commonName") + "\n(" + bird.getString("scientificName") + ")");
                }
            } catch (JSONException ignored) {}
        }

        if (filteredResults.isEmpty()) {
            Toast.makeText(this, "No matching birds found in Georgia.", Toast.LENGTH_SHORT).show();
        } else {
            resultsAdapter.notifyDataSetChanged();
        }
    }

    private void findImageAndDisplay(final String commonName, final String scientificName, final String family) {
        // First check if we already have a high-quality image URL in Firestore
        db.collection("bird_images").document(commonName).get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists() && documentSnapshot.contains("imageUrl")) {
                        displayFinalResults(documentSnapshot.getString("imageUrl"), family, scientificName);
                    } else {
                        searchNuthatchForImage(commonName, scientificName, family);
                    }
                }).addOnFailureListener(e -> searchNuthatchForImage(commonName, scientificName, family));
    }

    private void searchNuthatchForImage(final String commonName, final String scientificName, final String family) {
        nuthatchApi.searchNuthatchByName(commonName, new NuthatchApi.SearchResultHandler() {
            @Override
            public void onImageFound(String imageUrl) {
                displayFinalResults(imageUrl, family, scientificName);
                saveBirdImageMetadata(commonName, scientificName, imageUrl, family);
            }

            @Override
            public void onImageNotFound() {
                Toast.makeText(BirdLookupActivity.this, "No reference image found.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void displayFinalResults(String imageUrl, String family, String scientificName) {
        Glide.with(this).load(imageUrl).into(birdImageView);
        String infoText = "Family: " + family + "\nScientific Name: (" + scientificName + ")";
        speciesInfoTextView.setText(infoText);
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
        finalDisplayLayout.setVisibility(View.GONE);
        resultsListView.setVisibility(View.VISIBLE);
    }

    private void resetToFinalDisplayState() {
        resultsListView.setVisibility(View.GONE);
        finalDisplayLayout.setVisibility(View.VISIBLE);
    }
}