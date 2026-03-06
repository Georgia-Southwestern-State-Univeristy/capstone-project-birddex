package com.birddex.app;

import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;
import com.google.firebase.functions.FirebaseFunctions;

import java.util.HashMap;
import java.util.Map;

public class BirdWikiActivity extends AppCompatActivity {

    private static final String TAG = "BirdWikiActivity";
    public static final String EXTRA_BIRD_ID = "birdId";

    private FirebaseFirestore db;
    private boolean isGeneratingFacts = false;

    private ImageView ivBirdHeaderImage;
    private TextView tvPageTitle;
    private TextView tvPageScientificName;

    private final Map<String, TextView> factViews = new HashMap<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bird_wiki);

        db = FirebaseFirestore.getInstance();

        bindViews();
        initializePlaceholders();

        String birdId = getIntent().getStringExtra(EXTRA_BIRD_ID);
        if (isBlank(birdId)) {
            Toast.makeText(this, "Missing birdId for info page.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        loadBirdBasics(birdId);
        loadGeneralFacts(birdId);
        loadHunterFacts(birdId);
    }

    private void bindViews() {
        ivBirdHeaderImage = findViewById(R.id.ivBirdHeaderImage);
        tvPageTitle = findViewById(R.id.tvPageTitle);
        tvPageScientificName = findViewById(R.id.tvPageScientificName);

        registerFactView("family", R.id.tvFamily);
        registerFactView("species", R.id.tvSpecies);
        registerFactView("conservationStatus", R.id.tvConservationStatus);
        registerFactView("seasonalPresence", R.id.tvSeasonalPresence);
        registerFactView("whereInGeorgiaFound", R.id.tvWhereInGeorgiaFound);

        registerFactView("sizeAppearance", R.id.tvSizeAppearance);
        registerFactView("distinctiveBehaviors", R.id.tvDistinctiveBehaviors);
        registerFactView("similarSpecies", R.id.tvSimilarSpecies);
        registerFactView("peakViewingTimes", R.id.tvPeakViewingTimes);
        registerFactView("diet", R.id.tvDiet);
        registerFactView("nestingHabits", R.id.tvNestingHabits);
        registerFactView("roleInEcosystem", R.id.tvRoleInEcosystem);
        registerFactView("uniqueBehaviorsSpecific", R.id.tvUniqueBehaviorsSpecific);
        registerFactView("recordSettingFacts", R.id.tvRecordSettingFacts);
        registerFactView("culturalHistoricalNotes", R.id.tvCulturalHistoricalNotes);
        registerFactView("howToHelp", R.id.tvHowToHelp);
        registerFactView("threatsInGeorgia", R.id.tvThreatsInGeorgia);
        registerFactView("bestAnglesBehaviors", R.id.tvBestAnglesBehaviors);
        registerFactView("timesBestLighting", R.id.tvTimesBestLighting);
        registerFactView("avoidDisturbing", R.id.tvAvoidDisturbing);

        registerFactView("hunterFacts", R.id.tvHunterFacts);
        registerFactView("relevantRegulations", R.id.tvRelevantRegulations);
        registerFactView("georgiaDNRHuntingLink", R.id.tvGeorgiaDNRHuntingLink);

        TextView linkView = findViewById(R.id.tvGeorgiaDNRHuntingLink);
        if (linkView != null) {
            linkView.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }

    private void registerFactView(String key, int viewId) {
        TextView tv = findViewById(viewId);
        if (tv != null) {
            factViews.put(key, tv);
        }
    }

    private void initializePlaceholders() {
        tvPageTitle.setText("Bird");
        tvPageScientificName.setText("Scientific name");
        ivBirdHeaderImage.setVisibility(View.GONE);

        for (TextView tv : factViews.values()) {
            tv.setText("Not available yet.");
        }
    }

    private void loadBirdBasics(String birdId) {
        // Try Cache first
        db.collection("birds").document(birdId).get(Source.CACHE)
                .addOnSuccessListener(this::processBasics)
                .addOnFailureListener(e -> fetchBasicsFromServer(birdId));
        
        // Always try to sync with server
        fetchBasicsFromServer(birdId);
    }

    private void fetchBasicsFromServer(String birdId) {
        db.collection("birds").document(birdId).get(Source.SERVER)
                .addOnSuccessListener(this::processBasics)
                .addOnFailureListener(e -> Log.e(TAG, "Failed to load basics from server", e));
    }

    private void processBasics(DocumentSnapshot doc) {
        if (isFinishing() || isDestroyed() || !doc.exists()) return;

        String commonName = doc.getString("commonName");
        String scientificName = doc.getString("scientificName");
        String family = doc.getString("family");
        String species = doc.getString("species");

        tvPageTitle.setText(firstNonBlank(commonName, "Unknown Bird"));
        tvPageScientificName.setText(firstNonBlank(scientificName, "Scientific name not available"));

        setFact("family", family);
        setFact("species", species);

        if (!isBlank(commonName)) {
            loadBirdImage(commonName);
        }
    }

    private void loadBirdImage(String commonName) {
        // Cache first for image URL
        db.collection("bird_images").document(commonName).get(Source.CACHE)
                .addOnSuccessListener(this::processImage)
                .addOnFailureListener(e -> fetchImageFromServer(commonName));
        
        fetchImageFromServer(commonName);
    }

    private void fetchImageFromServer(String commonName) {
        db.collection("bird_images").document(commonName).get(Source.SERVER)
                .addOnSuccessListener(this::processImage);
    }

    private void processImage(DocumentSnapshot imageDoc) {
        if (isFinishing() || isDestroyed() || !imageDoc.exists()) return;
        String imageUrl = imageDoc.getString("imageUrl");

        if (isBlank(imageUrl)) {
            ivBirdHeaderImage.setVisibility(View.GONE);
            return;
        }

        ivBirdHeaderImage.setVisibility(View.VISIBLE);
        Glide.with(this)
                .load(imageUrl)
                .into(ivBirdHeaderImage);
    }

    private void loadGeneralFacts(String birdId) {
        // Cache first
        db.collection("birdFacts").document(birdId).get(Source.CACHE)
                .addOnSuccessListener(this::processGeneralFacts)
                .addOnFailureListener(e -> fetchGeneralFactsFromServer(birdId));

        fetchGeneralFactsFromServer(birdId);
    }

    private void fetchGeneralFactsFromServer(String birdId) {
        db.collection("birdFacts").document(birdId).get(Source.SERVER)
                .addOnSuccessListener(this::processGeneralFacts);
    }

    private void processGeneralFacts(DocumentSnapshot doc) {
        if (isFinishing() || isDestroyed()) return;

        if (!doc.exists()) {
            fetchFactsFromCloudFunction(getIntent().getStringExtra(EXTRA_BIRD_ID));
            return;
        }

        updateGeneralFactsFromMap(doc.getData());
    }

    private void updateGeneralFactsFromMap(Map<String, Object> data) {
        if (data == null) return;

        setFact("conservationStatus", getString(data.get("conservationStatus")));
        setFact("seasonalPresence", getString(data.get("seasonalPresence")));
        setFact("whereInGeorgiaFound", getString(data.get("whereInGeorgiaFound")));

        setFact("sizeAppearance", getString(data.get("sizeAppearance")));
        setFact("distinctiveBehaviors", getString(data.get("distinctiveBehaviors")));
        setFact("similarSpecies", getString(data.get("similarSpecies")));
        setFact("peakViewingTimes", getString(data.get("peakViewingTimes")));
        setFact("diet", getString(data.get("diet")));
        setFact("nestingHabits", getString(data.get("nestingHabits")));
        setFact("roleInEcosystem", getString(data.get("roleInEcosystem")));
        setFact("uniqueBehaviorsSpecific", getString(data.get("uniqueBehaviorsSpecific")));
        setFact("recordSettingFacts", getString(data.get("recordSettingFacts")));
        setFact("culturalHistoricalNotes", getString(data.get("culturalHistoricalNotes")));
        setFact("howToHelp", getString(data.get("howToHelp")));
        setFact("threatsInGeorgia", getString(data.get("threatsInGeorgia")));
        setFact("bestAnglesBehaviors", getString(data.get("bestAnglesBehaviors")));
        setFact("timesBestLighting", getString(data.get("timesBestLighting")));
        setFact("avoidDisturbing", getString(data.get("avoidDisturbing")));
    }

    private void loadHunterFacts(String birdId) {
        db.collection("birdFacts").document(birdId).collection("hunterFacts").document(birdId).get(Source.CACHE)
                .addOnSuccessListener(this::processHunterFacts)
                .addOnFailureListener(e -> fetchHunterFactsFromServer(birdId));

        fetchHunterFactsFromServer(birdId);
    }

    private void fetchHunterFactsFromServer(String birdId) {
        db.collection("birdFacts").document(birdId).collection("hunterFacts").document(birdId).get(Source.SERVER)
                .addOnSuccessListener(this::processHunterFacts);
    }

    private void processHunterFacts(DocumentSnapshot doc) {
        if (isFinishing() || isDestroyed()) return;

        if (!doc.exists()) {
            fetchFactsFromCloudFunction(getIntent().getStringExtra(EXTRA_BIRD_ID));
            return;
        }

        updateHunterFactsFromMap(doc.getData());
    }

    private void updateHunterFactsFromMap(Map<String, Object> data) {
        if (data == null) return;

        String legalStatusGeorgia = getString(data.get("legalStatusGeorgia"));
        String season = getString(data.get("season"));
        String licenseRequirements = getString(data.get("licenseRequirements"));
        String federalProtections = getString(data.get("federalProtections"));
        String notHuntableStatement = getString(data.get("notHuntableStatement"));
        String relevantRegulations = getString(data.get("relevantRegulations"));
        String georgiaDNRHuntingLink = getString(data.get("georgiaDNRHuntingLink"));

        String isEndangered = getString(data.get("isEndangered"));

        String hunterFactsBlock = buildHunterBlock(
                legalStatusGeorgia,
                isEndangered,
                season,
                licenseRequirements,
                federalProtections,
                notHuntableStatement
        );

        setFact("hunterFacts", hunterFactsBlock);
        setFact("relevantRegulations", relevantRegulations);
        setFact("georgiaDNRHuntingLink", georgiaDNRHuntingLink);
    }

    private void fetchFactsFromCloudFunction(String birdId) {
        if (isGeneratingFacts || isBlank(birdId)) return;
        isGeneratingFacts = true;

        Log.d(TAG, "Fetching facts from Cloud Function for birdId: " + birdId);

        Map<String, Object> data = new HashMap<>();
        data.put("birdId", birdId);

        FirebaseFunctions.getInstance()
                .getHttpsCallable("getBirdDetailsAndFacts")
                .call(data)
                .addOnSuccessListener(result -> {
                    isGeneratingFacts = false;
                    if (isFinishing() || isDestroyed()) return;

                    Object resultData = result.getData();
                    if (resultData instanceof Map) {
                        Map<String, Object> resultMap = (Map<String, Object>) resultData;
                        
                        // Update general facts
                        Object genFactsObj = resultMap.get("generalFacts");
                        if (genFactsObj instanceof Map) {
                            updateGeneralFactsFromMap((Map<String, Object>) genFactsObj);
                        }

                        // Update hunter facts
                        Object hunterFactsObj = resultMap.get("hunterFacts");
                        if (hunterFactsObj instanceof Map) {
                            updateHunterFactsFromMap((Map<String, Object>) hunterFactsObj);
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    isGeneratingFacts = false;
                    Log.e(TAG, "Failed to fetch facts from Cloud Function", e);
                });
    }

    private String buildHunterBlock(String legalStatusGeorgia,
                                    String isEndangered,
                                    String season,
                                    String licenseRequirements,
                                    String federalProtections,
                                    String notHuntableStatement) {

        StringBuilder sb = new StringBuilder();

        if (!isBlank(legalStatusGeorgia)) {
            sb.append("Legal status in Georgia: ").append(legalStatusGeorgia).append("\n\n");
        }
        if (!isBlank(isEndangered)) {
            sb.append("Is endangered: ").append(isEndangered).append("\n\n");
        }
        if (!isBlank(season)) {
            sb.append("Season: ").append(season).append("\n\n");
        }
        if (!isBlank(licenseRequirements)) {
            sb.append("License requirements: ").append(licenseRequirements).append("\n\n");
        }
        if (!isBlank(federalProtections)) {
            sb.append("Federal protections: ").append(federalProtections).append("\n\n");
        }
        if (!isBlank(notHuntableStatement)) {
            sb.append("Not huntable statement: ").append(notHuntableStatement);
        }

        String result = sb.toString().trim();
        return isBlank(result) ? "Not available yet." : result;
    }

    private void setFact(String key, String value) {
        TextView tv = factViews.get(key);
        if (tv != null) {
            tv.setText(firstNonBlank(value, "Not available yet."));
        }
    }

    private String firstNonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String getString(Object val) {
        return val == null ? null : String.valueOf(val);
    }
}
