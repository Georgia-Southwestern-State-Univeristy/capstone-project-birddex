package com.birddex.app;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BirdWikiActivity: Activity class for one BirdDex screen. It owns screen setup, user actions, and navigation for this part of the app.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class BirdWikiActivity extends AppCompatActivity {

    private static final String TAG = "BirdWikiActivity";
    public static final String EXTRA_BIRD_ID = "birdId";
    private static final long LOADING_TIMEOUT_MS = 10000L;

    private FirebaseFirestore db;
    private FirebaseManager firebaseManager;
    private boolean isGeneratingFacts = false;

    private ImageView ivBirdHeaderImage;
    private TextView tvPageTitle;
    private TextView tvPageScientificName;
    private View contentScrollView;
    private View loadingOverlay;

    private boolean basicsLoaded = false;
    private boolean factsLoaded = false;
    private boolean imageLoaded = false;
    private boolean contentShown = false;

    private String currentBirdId;
    private String currentCommonName;
    private String currentScientificName;

    private final Map<String, TextView> factViews = new HashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable loadingTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (contentShown || isFinishing() || isDestroyed()) return;

            Log.w(TAG, "BirdWiki timeout reached. Showing content without waiting any longer.");
            contentShown = true;
            showLoadingOverlay(false);
        }
    };

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bird_wiki);

        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db = FirebaseFirestore.getInstance();
        firebaseManager = new FirebaseManager(this);

        bindViews();
        initializePlaceholders();
        showLoadingOverlay(true);
        mainHandler.postDelayed(loadingTimeoutRunnable, LOADING_TIMEOUT_MS);

        currentBirdId = getIntent().getStringExtra(EXTRA_BIRD_ID);
        if (isBlank(currentBirdId)) {
            // Give the user immediate feedback about the result of this action.
            Toast.makeText(this, "Missing birdId for info page.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        loadAllData(currentBirdId);
    }

    /**
     * Android calls this when the Activity is being destroyed so cleanup can happen before the
     * screen is removed.
     */
    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(loadingTimeoutRunnable);
        super.onDestroy();
    }

    /**
     * Connects already-fetched data to views so the user can see the current state.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     */
    private void bindViews() {
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        contentScrollView = findViewById(R.id.contentScrollView);
        loadingOverlay = findViewById(R.id.loadingOverlay);
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

    /**
     * Main logic block for this part of the feature.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     */
    private void registerFactView(String key, int viewId) {
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        TextView tv = findViewById(viewId);
        if (tv != null) {
            factViews.put(key, tv);
        }
    }

    /**
     * Initializes helpers, adapters, listeners, or default values used by the rest of this file.
     */
    private void initializePlaceholders() {
        tvPageTitle.setText("Loading...");
        tvPageScientificName.setText("Scientific name");
        ivBirdHeaderImage.setVisibility(View.GONE);

        for (TextView tv : factViews.values()) {
            tv.setText("Not available yet.");
        }
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    private void loadAllData(String birdId) {
        // 1. Load Basics (Cache -> Server)
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("birds").document(birdId).get(Source.CACHE)
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) processBasics(doc);
                    else fetchBasicsFromServer(birdId);
                })
                .addOnFailureListener(e -> fetchBasicsFromServer(birdId));

        // 2. Coordinate Facts Loading (Check cache first for both)
        db.collection("birdFacts").document(birdId).get(Source.CACHE)
                .addOnSuccessListener(genDoc -> {
                    db.collection("birdFacts").document(birdId).collection("hunterFacts").document(birdId).get(Source.CACHE)
                            .addOnSuccessListener(hunterDoc -> {
                                if (genDoc.exists() && hunterDoc.exists()) {
                                    // Both found in cache!
                                    updateGeneralFactsFromMap(genDoc.getData());
                                    updateHunterFactsFromMap(hunterDoc.getData());
                                    markFactsLoaded();
                                } else {
                                    // Something is missing, call Cloud Function once
                                    fetchFactsFromCloudFunction(birdId);
                                }
                            })
                            .addOnFailureListener(e -> fetchFactsFromCloudFunction(birdId));
                })
                .addOnFailureListener(e -> fetchFactsFromCloudFunction(birdId));
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    private void fetchBasicsFromServer(String birdId) {
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("birds").document(birdId).get(Source.SERVER)
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) processBasics(doc);
                    else finishBasicsWithoutImage();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to load basics from server", e);
                    finishBasicsWithoutImage();
                });
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void processBasics(DocumentSnapshot doc) {
        if (isFinishing() || isDestroyed()) return;
        if (!doc.exists()) {
            finishBasicsWithoutImage();
            return;
        }

        String commonName = doc.getString("commonName");
        String scientificName = doc.getString("scientificName");
        String family = doc.getString("family");
        String species = doc.getString("species");

        currentBirdId = firstNonBlank(doc.getId(), currentBirdId);
        currentCommonName = commonName;
        currentScientificName = scientificName;

        tvPageTitle.setText(firstNonBlank(commonName, "Unknown Bird"));
        tvPageScientificScientificName(firstNonBlank(scientificName, "Scientific name not available"));

        setFact("family", family);
        setFact("species", species);

        markBasicsLoaded();

        loadBirdImage(currentBirdId, currentCommonName, currentScientificName);
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void tvPageScientificScientificName(String name) {
        if (tvPageScientificName != null) tvPageScientificName.setText(name);
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    private void loadBirdImage(String birdId, String commonName, String scientificName) {
        if (isBlank(birdId) && isBlank(commonName) && isBlank(scientificName)) {
            ivBirdHeaderImage.setImageDrawable(null);
            ivBirdHeaderImage.setVisibility(View.GONE);
            markImageLoaded();
            return;
        }

        // Try Nuthatch first, then fall back to iNaturalist.
        tryBirdImageFromCollection("nuthatch_images", birdId, commonName, scientificName, () ->
                tryBirdImageFromCollection("inaturalist_images", birdId, commonName, scientificName, () -> {
                    if (isFinishing() || isDestroyed()) return;
                    ivBirdHeaderImage.setImageDrawable(null);
                    ivBirdHeaderImage.setVisibility(View.GONE);
                    markImageLoaded();
                })
        );
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    private void tryBirdImageFromCollection(String collectionName,
                                            String birdId,
                                            String commonName,
                                            String scientificName,
                                            Runnable onNotFound) {
        if (isFinishing() || isDestroyed()) return;

        if (!isBlank(birdId)) {
            // First try the document ID directly because your image collections are keyed by species code.
            db.collection(collectionName).document(birdId).get(Source.SERVER)
                    .addOnSuccessListener(doc -> {
                        if (doc.exists()) {
                            processImage(doc);
                        } else {
                            queryBirdImageFallbacks(collectionName, birdId, commonName, scientificName, onNotFound);
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Failed direct image lookup in " + collectionName + " for birdId=" + birdId, e);
                        queryBirdImageFallbacks(collectionName, birdId, commonName, scientificName, onNotFound);
                    });
        } else {
            queryBirdImageFallbacks(collectionName, birdId, commonName, scientificName, onNotFound);
        }
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    private void queryBirdImageFallbacks(String collectionName,
                                         String birdId,
                                         String commonName,
                                         String scientificName,
                                         Runnable onNotFound) {
        if (isFinishing() || isDestroyed()) return;

        queryBirdImageByField(collectionName, "speciesCode", birdId, () ->
                queryBirdImageByField(collectionName, "commonName", commonName, () ->
                        queryBirdImageByField(collectionName, "scientificName", scientificName, onNotFound)
                )
        );
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    private void queryBirdImageByField(String collectionName,
                                       String fieldName,
                                       String value,
                                       Runnable onNotFound) {
        if (isFinishing() || isDestroyed()) return;

        if (isBlank(value)) {
            onNotFound.run();
            return;
        }

        db.collection(collectionName)
                .whereEqualTo(fieldName, value)
                .limit(1)
                .get(Source.SERVER)
                .addOnSuccessListener(querySnapshot -> processImageQueryResult(querySnapshot, onNotFound))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed image query in " + collectionName + " where " + fieldName + "=" + value, e);
                    onNotFound.run();
                });
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void processImageQueryResult(QuerySnapshot querySnapshot, Runnable onNotFound) {
        if (isFinishing() || isDestroyed()) return;

        if (querySnapshot != null && !querySnapshot.isEmpty()) {
            processImage(querySnapshot.getDocuments().get(0));
        } else {
            onNotFound.run();
        }
    }

    /**
     * Main logic block for this part of the feature.
     * Image loading happens here, which is why placeholder/error behavior for profile
     * photos/cards/posts usually traces back to this code path.
     */
    private void processImage(DocumentSnapshot imageDoc) {
        if (isFinishing() || isDestroyed()) return;
        if (!imageDoc.exists()) {
            ivBirdHeaderImage.setImageDrawable(null);
            ivBirdHeaderImage.setVisibility(View.GONE);
            markImageLoaded();
            return;
        }

        String imageUrl = extractImageUrl(imageDoc);

        if (isBlank(imageUrl)) {
            ivBirdHeaderImage.setImageDrawable(null);
            ivBirdHeaderImage.setVisibility(View.GONE);
            markImageLoaded();
            return;
        }

        ivBirdHeaderImage.setVisibility(View.VISIBLE);

        // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
        Glide.with(this)
                .load(imageUrl)
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                        if (e != null) {
                            Log.e(TAG, "Bird header image failed to load for url=" + model, e);
                        }
                        ivBirdHeaderImage.setImageDrawable(null);
                        ivBirdHeaderImage.setVisibility(View.GONE);
                        markImageLoaded();
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                        ivBirdHeaderImage.setVisibility(View.VISIBLE);
                        markImageLoaded();
                        return false;
                    }
                })
                .into(ivBirdHeaderImage);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    private String extractImageUrl(DocumentSnapshot imageDoc) {
        String singleUrl = imageDoc.getString("imageUrl");
        if (!isBlank(singleUrl)) {
            return singleUrl;
        }

        Object imageUrlsObj = imageDoc.get("imageUrls");
        if (imageUrlsObj instanceof List) {
            for (Object item : (List<?>) imageUrlsObj) {
                String candidate = getString(item);
                if (!isBlank(candidate)) {
                    return candidate;
                }
            }
        }

        return null;
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     */
    private void updateGeneralFactsFromMap(Map<String, Object> data) {
        if (data == null || isFinishing() || isDestroyed()) return;

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

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     */
    private void updateHunterFactsFromMap(Map<String, Object> data) {
        if (data == null || isFinishing() || isDestroyed()) return;

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

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     */
    private void fetchFactsFromCloudFunction(String birdId) {
        if (isBlank(birdId)) {
            markFactsLoaded();
            return;
        }
        if (isGeneratingFacts) return;
        isGeneratingFacts = true;

        Log.d(TAG, "Fetching facts from Cloud Function for birdId: " + birdId);

        firebaseManager.getBirdDetailsAndFacts(birdId, task -> {
            isGeneratingFacts = false;
            if (isFinishing() || isDestroyed()) return;

            if (task.isSuccessful() && task.getResult() != null) {
                Object resultData = task.getResult().getData();
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
            } else {
                Log.e(TAG, "Failed to fetch facts from Cloud Function", task.getException());
            }

            markFactsLoaded();
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

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     */
    private void setFact(String key, String value) {
        TextView tv = factViews.get(key);
        if (tv != null) {
            tv.setText(firstNonBlank(value, "Not available yet."));
        }
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    private void showLoadingOverlay(boolean show) {
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (contentScrollView != null) {
            contentScrollView.setVisibility(show ? View.INVISIBLE : View.VISIBLE);
        }
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    private void finishBasicsWithoutImage() {
        markBasicsLoaded();
        ivBirdHeaderImage.setImageDrawable(null);
        ivBirdHeaderImage.setVisibility(View.GONE);
        markImageLoaded();
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    private void markBasicsLoaded() {
        basicsLoaded = true;
        maybeShowContent();
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    private void markFactsLoaded() {
        factsLoaded = true;
        maybeShowContent();
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    private void markImageLoaded() {
        imageLoaded = true;
        maybeShowContent();
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void maybeShowContent() {
        if (contentShown || isFinishing() || isDestroyed()) return;

        // Do not block the whole page on the image request. As soon as the bird basics and facts
        // are ready, show the page and let the image continue loading in the background.
        if (basicsLoaded && factsLoaded) {
            contentShown = true;
            mainHandler.removeCallbacks(loadingTimeoutRunnable);
            showLoadingOverlay(false);
        }
    }

    /**
     * Main logic block for this part of the feature.
     */
    private String firstNonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    private String getString(Object val) {
        return val == null ? null : String.valueOf(val);
    }
}