package com.birddex.app;

import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class BirdWikiActivity extends AppCompatActivity {

    public static final String EXTRA_BIRD_ID = "birdId";

    private FirebaseFirestore db;

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
        linkView.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private void registerFactView(String key, int viewId) {
        factViews.put(key, findViewById(viewId));
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
        db.collection("birds")
                .document(birdId)
                .get()
                .addOnSuccessListener(doc -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (!doc.exists()) {
                        tvPageTitle.setText("Unknown Bird");
                        return;
                    }

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
                })
                .addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, "Failed to load bird basics.", Toast.LENGTH_SHORT).show();
                });
    }

    private void loadBirdImage(String commonName) {
        db.collection("bird_images")
                .document(commonName)
                .get()
                .addOnSuccessListener(imageDoc -> {
                    if (isFinishing() || isDestroyed()) return;
                    String imageUrl = imageDoc.getString("imageUrl");

                    if (isBlank(imageUrl)) {
                        ivBirdHeaderImage.setVisibility(View.GONE);
                        return;
                    }

                    ivBirdHeaderImage.setVisibility(View.VISIBLE);
                    Glide.with(this)
                            .load(imageUrl)
                            .into(ivBirdHeaderImage);
                })
                .addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;
                    ivBirdHeaderImage.setVisibility(View.GONE);
                });
    }

    private void loadGeneralFacts(String birdId) {
        db.collection("birdFacts")
                .document(birdId)
                .get()
                .addOnSuccessListener(doc -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (!doc.exists()) return;

                    setFact("conservationStatus", doc.getString("conservationStatus"));
                    setFact("seasonalPresence", doc.getString("seasonalPresence"));
                    setFact("whereInGeorgiaFound", doc.getString("whereInGeorgiaFound"));

                    setFact("sizeAppearance", doc.getString("sizeAppearance"));
                    setFact("distinctiveBehaviors", doc.getString("distinctiveBehaviors"));
                    setFact("similarSpecies", doc.getString("similarSpecies"));
                    setFact("peakViewingTimes", doc.getString("peakViewingTimes"));
                    setFact("diet", doc.getString("diet"));
                    setFact("nestingHabits", doc.getString("nestingHabits"));
                    setFact("roleInEcosystem", doc.getString("roleInEcosystem"));
                    setFact("uniqueBehaviorsSpecific", doc.getString("uniqueBehaviorsSpecific"));
                    setFact("recordSettingFacts", doc.getString("recordSettingFacts"));
                    setFact("culturalHistoricalNotes", doc.getString("culturalHistoricalNotes"));
                    setFact("howToHelp", doc.getString("howToHelp"));
                    setFact("threatsInGeorgia", doc.getString("threatsInGeorgia"));
                    setFact("bestAnglesBehaviors", doc.getString("bestAnglesBehaviors"));
                    setFact("timesBestLighting", doc.getString("timesBestLighting"));
                    setFact("avoidDisturbing", doc.getString("avoidDisturbing"));
                })
                .addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, "Failed to load bird facts.", Toast.LENGTH_SHORT).show();
                });
    }

    private void loadHunterFacts(String birdId) {
        db.collection("birdFacts")
                .document(birdId)
                .collection("hunterFacts")
                .document(birdId)
                .get()
                .addOnSuccessListener(doc -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (!doc.exists()) return;

                    String legalStatusGeorgia = doc.getString("legalStatusGeorgia");
                    String season = doc.getString("season");
                    String licenseRequirements = doc.getString("licenseRequirements");
                    String federalProtections = doc.getString("federalProtections");
                    String notHuntableStatement = doc.getString("notHuntableStatement");
                    String relevantRegulations = doc.getString("relevantRegulations");
                    String georgiaDNRHuntingLink = doc.getString("georgiaDNRHuntingLink");

                    Object endangeredObj = doc.get("isEndangered");
                    String isEndangered = endangeredObj != null ? String.valueOf(endangeredObj) : null;

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
                })
                .addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, "Failed to load hunter facts.", Toast.LENGTH_SHORT).show();
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
}