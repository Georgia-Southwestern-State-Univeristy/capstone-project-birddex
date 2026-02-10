package com.example.birddex;

import android.content.Intent;
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

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.bumptech.glide.Glide;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BirdLookupActivity extends AppCompatActivity {

    // private static final String TAG = "BirdLookupActivity";
    // private EditText commonNameEditText;
    // private EditText scientificNameEditText;
    // private Button searchButton;
    // private Button uploadImageButton;
    // private ListView resultsListView;
    // private ConstraintLayout finalDisplayLayout;
    // private ImageView birdImageView;
    // private TextView speciesInfoTextView;

    // private RequestQueue requestQueue;
    // private FirebaseFirestore db;
    // private ArrayAdapter<String> resultsAdapter;
    // private List<JSONObject> birdResults;
    // private List<String> georgiaBirdSpeciesCodes;

    // private NuthatchApi nuthatchApi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //setContentView(R.layout.activity_bird_lookup);

        // --- UI Initialization ---
        // commonNameEditText = findViewById(R.id.commonNameEditText);
        // scientificNameEditText = findViewById(R.id.scientificNameEditText);
        // searchButton = findViewById(R.id.searchButton);
        // uploadImageButton = findViewById(R.id.uploadImageButton);
        // resultsListView = findViewById(R.id.resultsListView);
        // finalDisplayLayout = findViewById(R.id.finalDisplayLayout);
        // birdImageView = findViewById(R.id.birdImageView);
        // speciesInfoTextView = findViewById(R.id.speciesInfoTextView);

        // --- Backend and Data Initialization ---
        // requestQueue = Volley.newRequestQueue(this);
        // db = FirebaseFirestore.getInstance();
        // birdResults = new ArrayList<>();
        // georgiaBirdSpeciesCodes = new ArrayList<>();
        // resultsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        // resultsListView.setAdapter(resultsAdapter);

        // nuthatchApi = new NuthatchApi(requestQueue);

        // fetchGeorgiaBirdList();

        // --- Click Listeners ---
        // searchButton.setOnClickListener(v -> {
        //     String commonNameQuery = commonNameEditText.getText().toString().trim();
        //     String scientificNameQuery = scientificNameEditText.getText().toString().trim();

        //     if (commonNameQuery.isEmpty()) {
        //         Toast.makeText(this, "Common name is required.", Toast.LENGTH_SHORT).show();
        //         return;
        //     }
        //     if (georgiaBirdSpeciesCodes.isEmpty()) {
        //         Toast.makeText(this, "Bird list for Georgia is still loading...", Toast.LENGTH_SHORT).show();
        //         return;
        //     }
        //     resetToSearchState();
        //     searchLocalBirdList(commonNameQuery, scientificNameQuery);
        // });

        // uploadImageButton.setOnClickListener(v -> {
        //     startActivity(new Intent(BirdLookupActivity.this, ImageUploadActivity.class));
        // });

        // resultsListView.setOnItemClickListener((parent, view, position, id) -> {
        //     try {
        //         JSONObject selectedBird = birdResults.get(position);
        //         String commonName = selectedBird.getString("comName");
        //         String sciName = selectedBird.getString("sciName");
        //         String family = selectedBird.getString("familyComName");

        //         resetToFinalDisplayState();
        //         findImageAndDisplay(commonName, sciName, family);
        //     } catch (JSONException e) {
        //         Log.e(TAG, "Error getting selected bird data", e);
        //     }
        // });
    }

    // private void fetchGeorgiaBirdList() {
    //     String url = "https://api.ebird.org/v2/product/spplist/US-GA";
    //     StringRequest stringRequest = new StringRequest(Request.Method.GET, url,
    //             response -> {
    //                 try {
    //                     JSONArray speciesCodes = new JSONArray(response);
    //                     for (int i = 0; i < speciesCodes.length(); i++) {
    //                         georgiaBirdSpeciesCodes.add(speciesCodes.getString(i));
    //                     }
    //                 } catch (JSONException e) {
    //                     Log.e(TAG, "Failed to parse Georgia species list.", e);
    //                 }
    //             }, error -> {
    //         Log.e(TAG, "Failed to fetch Georgia species list.", error);
    //     }) {
    //         @Override
    //         public Map<String, String> getHeaders() {
    //             Map<String, String> headers = new HashMap<>();
    //             headers.put("X-eBirdApiToken", BuildConfig.EBIRD_API_KEY);
    //             return headers;
    //         }
    //     };
    //     requestQueue.add(stringRequest);
    // }

    // private void searchLocalBirdList(String commonNameQuery, String sciNameQuery) {
    //     Toast.makeText(this, "Searching BirdDex...", Toast.LENGTH_SHORT).show();
    //     String url = "https://api.ebird.org/v2/ref/taxonomy/ebird?fmt=json";

    //     StringRequest stringRequest = new StringRequest(Request.Method.GET, url,
    //             response -> {
    //                 try {
    //                     JSONArray taxonomy = new JSONArray(response);
    //                     birdResults.clear();
    //                     resultsAdapter.clear();

    //                     for (int i = 0; i < taxonomy.length(); i++) {
    //                         JSONObject bird = taxonomy.getJSONObject(i);
    //                         if (georgiaBirdSpeciesCodes.contains(bird.getString("speciesCode"))) {
    //                             String comName = bird.getString("comName").toLowerCase();
    //                             String sciName = bird.getString("sciName").toLowerCase();
    //                             if (comName.contains(commonNameQuery.toLowerCase()) ||
    //                                     (!sciNameQuery.isEmpty() && sciName.contains(sciNameQuery.toLowerCase()))) {
    //                                 birdResults.add(bird);
    //                             }
    //                         }
    //                     }

    //                     if (birdResults.isEmpty()) {
    //                         Toast.makeText(this, "No Georgia birds found matching your search.", Toast.LENGTH_SHORT).show();
    //                     } else {
    //                         for (JSONObject bird : birdResults) {
    //                             resultsAdapter.add(bird.getString("comName") + "\n(" + bird.getString("sciName") + ")");
    //                         }
    //                         resultsAdapter.notifyDataSetChanged();
    //                     }

    //                 } catch (JSONException e) {
    //                     Log.e(TAG, "eBird JSON parsing error", e);
    //                     Toast.makeText(this, "An error occurred while parsing bird data.", Toast.LENGTH_SHORT).show();
    //                 }
    //             }, error -> {
    //         Log.e(TAG, "eBird network error", error);
    //         Toast.makeText(this, "Failed to fetch bird data. Check network.", Toast.LENGTH_SHORT).show();
    //     }) {
    //         @Override
    //         public Map<String, String> getHeaders() {
    //             Map<String, String> headers = new HashMap<>();
    //             headers.put("X-eBirdApiToken", BuildConfig.EBIRD_API_KEY);
    //             return headers;
    //         }
    //     };
    //     requestQueue.add(stringRequest);
    // }


    // private void findImageAndDisplay(final String commonName, final String scientificName, final String family) {
    //     db.collection("bird_images").document(commonName).get()
    //             .addOnSuccessListener(documentSnapshot -> {
    //                 if (documentSnapshot.exists() && documentSnapshot.contains("imageUrl")) {
    //                     String imageUrl = documentSnapshot.getString("imageUrl");
    //                     displayFinalResults(imageUrl, family, scientificName);
    //                 } else {
    //                     searchNuthatchForImage(commonName, scientificName, family);
    //                 }
    //             }).addOnFailureListener(e -> searchNuthatchForImage(commonName, scientificName, family));
    // }

    // private void searchNuthatchForImage(final String commonName, final String scientificName, final String family) {
    //     nuthatchApi.searchNuthatchByName(commonName, new NuthatchApi.SearchResultHandler() {
    //         @Override
    //         public void onImageFound(String imageUrl) {
    //             displayFinalResults(imageUrl, family, scientificName);
    //             saveBirdToFirestore(commonName, scientificName, imageUrl, family);
    //         }

    //         @Override
    //         public void onImageNotFound() {
    //             Toast.makeText(BirdLookupActivity.this, "Could not find an image for this bird.", Toast.LENGTH_SHORT).show();
    //         }
    //     });
    // }

    // private void displayFinalResults(String imageUrl, String family, String scientificName) {
    //     Glide.with(this).load(imageUrl).into(birdImageView);
    //     String infoText = "Family: " + family + "\nScientific Name: (" + scientificName + ")";
    //     speciesInfoTextView.setText(infoText);
    // }

    // private void saveBirdToFirestore(String commonName, String scientificName, String imageUrl, String family) {
    //     Map<String, Object> birdData = new HashMap<>();
    //     birdData.put("commonName", commonName);
    //     birdData.put("scientificName", scientificName);
    //     birdData.put("imageUrl", imageUrl);
    //     birdData.put("family", family);

    //     db.collection("bird_images").document(commonName).set(birdData)
    //             .addOnSuccessListener(aVoid -> Log.d(TAG, "Saved to Firestore: " + commonName))
    //             .addOnFailureListener(e -> Log.w(TAG, "Error saving to Firestore", e));
    // }

    // private void resetToSearchState() {
    //     finalDisplayLayout.setVisibility(View.GONE);
    //     resultsListView.setVisibility(View.VISIBLE);
    //     resultsAdapter.clear();
    //     birdResults.clear();
    // }

    // private void resetToFinalDisplayState() {
    //     resultsListView.setVisibility(View.GONE);
    //     finalDisplayLayout.setVisibility(View.VISIBLE);
    // }
}
