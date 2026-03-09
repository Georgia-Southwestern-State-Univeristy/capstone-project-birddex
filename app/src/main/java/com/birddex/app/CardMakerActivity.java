package com.birddex.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CardMakerActivity handles saving the bird to the user's permanent collection.
 * It uploads a separate copy of the image to 'userCollectionImages'.
 *
 * Race condition fixes applied:
 *  - Fix D:   isSaveInProgress / isSaveFinished moved to CardMakerViewModel (survives rotation).
 *  - Fix 16 & 17: All core writes (location, userBird, userBirdImage, and collectionSlot)
 *    handled in a SINGLE atomic Firestore transaction.
 *  - Fix SIGHTING: Removed saveUserBirdSightingIfAllowed() (client-side cooldown).
 *    Replaced with recordSightingViaCloudFunction() which calls the `recordBirdSighting` CF.
 *    The CF enforces 1 sighting per user per species per 24 h inside a server-side Firestore
 *    transaction, closing two race conditions the old code had:
 *      (a) Two devices for the same user saving simultaneously both passed the client-side
 *          cooldown check before either wrote the cooldown timestamp.
 *      (b) A crash after writing the cooldown but before writing the sighting doc (or vice
 *          versa) left state inconsistent. The CF commits both in one atomic transaction.
 */
public class CardMakerActivity extends AppCompatActivity {

    private static final String TAG = "CardMakerActivity";

    public static final String EXTRA_IMAGE_URI       = "imageUri";
    public static final String EXTRA_BIRD_NAME       = "birdName";
    public static final String EXTRA_SCI_NAME        = "sciName";
    public static final String EXTRA_CONFIDENCE      = "confidence";
    public static final String EXTRA_RARITY          = "rarity";
    public static final String EXTRA_BIRD_ID         = "birdId";
    public static final String EXTRA_SPECIES         = "species";
    public static final String EXTRA_FAMILY          = "family";
    public static final String EXTRA_LOCALITY        = "localityName";
    public static final String EXTRA_STATE           = "state";
    public static final String EXTRA_CAUGHT_TIME     = "caughtTime";
    public static final String EXTRA_QUANTITY        = "quantity";
    public static final String EXTRA_RECORD_SIGHTING = "recordSighting";
    public static final String EXTRA_LATITUDE        = "latitude";
    public static final String EXTRA_LONGITUDE       = "longitude";
    public static final String EXTRA_COUNTRY         = "country";

    private CardMakerViewModel viewModel;
    private FirebaseManager firebaseManager;
    private FrameLayout loadingOverlay;
    private Button btnSave;
    private Button btnCancel;

    private Uri originalImageUri;
    private String currentBirdId;
    private String currentCommonName;
    private String currentRarity;
    private String currentScientificName;
    private String currentSpecies;
    private String currentFamily;
    private String currentConfidence;
    private String currentLocality;
    private String currentState;
    private long currentCaughtTime;
    private String currentQuantity;
    private boolean shouldRecordSighting;
    private Double currentLatitude;
    private Double currentLongitude;
    private String currentCountry;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_maker);

        viewModel       = new ViewModelProvider(this).get(CardMakerViewModel.class);
        firebaseManager = new FirebaseManager(this);
        loadingOverlay  = findViewById(R.id.loadingOverlay);
        btnSave         = findViewById(R.id.btnSaveCard);
        btnCancel       = findViewById(R.id.btnCancelCard);

        TextView txtBirdName   = findViewById(R.id.txtBirdName);
        TextView txtScientific = findViewById(R.id.txtScientific);
        TextView txtLocation   = findViewById(R.id.txtLocation);
        TextView txtDateCaught = findViewById(R.id.txtDateCaught);
        TextView txtFooter     = findViewById(R.id.txtFooter);
        ImageView imgBird      = findViewById(R.id.imgBird);

        String originalImageUriStr = getIntent().getStringExtra(EXTRA_IMAGE_URI);
        if (originalImageUriStr != null) originalImageUri = Uri.parse(originalImageUriStr);

        currentCommonName     = getIntent().getStringExtra(EXTRA_BIRD_NAME);
        currentScientificName = getIntent().getStringExtra(EXTRA_SCI_NAME);
        currentConfidence     = getIntent().getStringExtra(EXTRA_CONFIDENCE);
        currentRarity         = getIntent().getStringExtra(EXTRA_RARITY);
        currentBirdId         = getIntent().getStringExtra(EXTRA_BIRD_ID);
        currentSpecies        = getIntent().getStringExtra(EXTRA_SPECIES);
        currentFamily         = getIntent().getStringExtra(EXTRA_FAMILY);
        currentLocality       = getIntent().getStringExtra(EXTRA_LOCALITY);
        currentState          = getIntent().getStringExtra(EXTRA_STATE);
        currentCaughtTime     = getIntent().getLongExtra(EXTRA_CAUGHT_TIME, System.currentTimeMillis());
        currentQuantity       = getIntent().getStringExtra(EXTRA_QUANTITY);
        shouldRecordSighting  = getIntent().getBooleanExtra(EXTRA_RECORD_SIGHTING, false);
        currentLatitude       = getIntent().hasExtra(EXTRA_LATITUDE)
                ? getIntent().getDoubleExtra(EXTRA_LATITUDE, 0.0) : null;
        currentLongitude      = getIntent().hasExtra(EXTRA_LONGITUDE)
                ? getIntent().getDoubleExtra(EXTRA_LONGITUDE, 0.0) : null;
        currentCountry        = getIntent().getStringExtra(EXTRA_COUNTRY);

        if (originalImageUri == null) {
            Toast.makeText(this, "No image passed.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (currentCommonName != null && !currentCommonName.trim().isEmpty())
            txtBirdName.setText(currentCommonName);
        else if (currentScientificName != null && !currentScientificName.trim().isEmpty())
            txtBirdName.setText(currentScientificName);
        else
            txtBirdName.setText("Unknown Bird");

        txtScientific.setText((currentScientificName != null && !currentScientificName.trim().isEmpty())
                ? currentScientificName : "--");
        txtLocation.setText(CardFormatUtils.formatLocation(currentState, currentLocality));
        txtDateCaught.setText(CardFormatUtils.formatCaughtDate(new Date(currentCaughtTime)));
        txtFooter.setText("Preview only • Original photo will be saved");

        imgBird.setImageDrawable(null);
        Glide.with(this)
                .load(originalImageUri)
                .signature(new ObjectKey(originalImageUri.toString() + System.currentTimeMillis()))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .fitCenter()
                .into(imgBird);

        btnCancel.setOnClickListener(v -> { if (!viewModel.isSaveInProgress.get()) finish(); });

        if (viewModel.isSaveInProgress.get() && !viewModel.isSaveFinished.get()) setSavingUi(true);

        btnSave.setOnClickListener(v -> processAndSaveBirdDiscovery(originalImageUri));
    }

    // -------------------------------------------------------------------------
    // Step 1: upload image to Storage
    // -------------------------------------------------------------------------

    private void processAndSaveBirdDiscovery(Uri imageUriToSave) {
        if (viewModel.isSaveInProgress.get() || viewModel.isSaveFinished.get()) {
            Log.d(TAG, "Ignoring duplicate save tap.");
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Error: No user logged in. Please log in again.", Toast.LENGTH_LONG).show();
            return;
        }

        viewModel.isSaveInProgress.set(true);
        setSavingUi(true);

        String userId   = user.getUid();
        String fileName = "userCollectionImages/" + userId + "/" + UUID.randomUUID() + ".jpg";
        StorageReference storageRef = FirebaseStorage.getInstance().getReference().child(fileName);

        storageRef.putFile(imageUriToSave)
                .addOnSuccessListener(ts -> storageRef.getDownloadUrl()
                        .addOnSuccessListener(uri -> {
                            if (viewModel.isSaveFinished.get()) return;
                            storeBirdDiscoveryAtomic(uri.toString());
                        })
                        .addOnFailureListener(e -> handleSaveFailure("Failed to save collection image link.", e)))
                .addOnFailureListener(e -> handleSaveFailure("Failed to upload image to your collection.", e));
    }

    // -------------------------------------------------------------------------
    // Step 2: atomic Firestore transaction for all collection writes (Fix #16/#17)
    // -------------------------------------------------------------------------

    private void storeBirdDiscoveryAtomic(String originalImageUrl) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) { handleSaveFailure("Error: No user logged in.", null); return; }

        String userId              = user.getUid();
        FirebaseFirestore db       = FirebaseFirestore.getInstance();
        String userBirdId          = UUID.randomUUID().toString();
        String userBirdImageId     = UUID.randomUUID().toString();
        String locationId          = UUID.randomUUID().toString();
        String deterministicSlotId = userId + "_" + currentBirdId;
        Date now = currentCaughtTime > 0 ? new Date(currentCaughtTime) : new Date();

        DocumentReference locRef          = db.collection("locations").document(locationId);
        DocumentReference userBirdRef     = db.collection("userBirds").document(userBirdId);
        DocumentReference userBirdImageRef = db.collection("users").document(userId)
                .collection("userBirdImage").document(userBirdImageId);
        DocumentReference slotRef         = db.collection("users").document(userId)
                .collection("collectionSlot").document(deterministicSlotId);
        DocumentReference slotCounterRef  = db.collection("users").document(userId)
                .collection("collectionMeta").document("slotCounter");

        // Capture for lambda (userBirdId is already effectively final as a local)
        final String capturedUserBirdId = userBirdId;
        final Date capturedNow = now;

        db.runTransaction(transaction -> {
            DocumentSnapshot slotSnap    = transaction.get(slotRef);
            DocumentSnapshot counterSnap = transaction.get(slotCounterRef);

            // --- Location ---
            Map<String, Object> locationData = new HashMap<>();
            locationData.put("id", locationId);
            locationData.put("state", currentState);
            locationData.put("locality", currentLocality);
            if (currentLatitude  != null) locationData.put("latitude",  currentLatitude);
            if (currentLongitude != null) locationData.put("longitude", currentLongitude);
            if (currentCountry   != null) locationData.put("country",   currentCountry);
            transaction.set(locRef, locationData);

            // --- UserBird ---
            Map<String, Object> ubData = new HashMap<>();
            ubData.put("id", capturedUserBirdId);
            ubData.put("userId", userId);
            ubData.put("birdSpeciesId", currentBirdId);
            ubData.put("timeSpotted", capturedNow);
            ubData.put("locationId", locationId);
            ubData.put("imageUrl", originalImageUrl);
            ubData.put("imageCount", 1);
            transaction.set(userBirdRef, ubData);

            // --- UserBirdImage ---
            Map<String, Object> ubiData = new HashMap<>();
            ubiData.put("id", userBirdImageId);
            ubiData.put("userId", userId);
            ubiData.put("birdId", currentBirdId);
            ubiData.put("imageUrl", originalImageUrl);
            ubiData.put("timestamp", capturedNow);
            ubiData.put("userBirdRefId", capturedUserBirdId);
            transaction.set(userBirdImageRef, ubiData);

            // --- CollectionSlot (only if this species not already in collection) ---
            if (!slotSnap.exists()) {
                long nextIndex = (counterSnap.exists() && counterSnap.getLong("nextSlotIndex") != null)
                        ? counterSnap.getLong("nextSlotIndex") : 0L;
                transaction.set(slotCounterRef,
                        Collections.singletonMap("nextSlotIndex", nextIndex + 1), SetOptions.merge());

                Map<String, Object> slotData = new HashMap<>();
                slotData.put("id", deterministicSlotId);
                slotData.put("userBirdId", capturedUserBirdId);
                slotData.put("birdId", currentBirdId);
                slotData.put("timestamp", capturedNow);
                slotData.put("state", currentState);
                slotData.put("locality", currentLocality);
                slotData.put("imageUrl", originalImageUrl);
                slotData.put("rarity", (currentRarity != null && !currentRarity.trim().isEmpty())
                        ? currentRarity : "Unknown");
                slotData.put("slotIndex", (int) nextIndex);
                slotData.put("commonName", currentCommonName);
                slotData.put("scientificName", currentScientificName);
                transaction.set(slotRef, slotData);
            }

            return null;

        }).addOnSuccessListener(result -> {
            Log.d(TAG, "Discovery saved atomically.");
            if (shouldRecordSighting) {
                recordSightingViaCloudFunction(capturedUserBirdId, capturedNow);
            } else {
                handleSaveSuccess();
            }
        }).addOnFailureListener(e -> handleSaveFailure("Error saving discovery. Please try again.", e));
    }

    // -------------------------------------------------------------------------
    // Step 3 (optional): record heatmap sighting via server-side CF (Fix SIGHTING)
    //
    // Why this replaces the old saveUserBirdSightingIfAllowed():
    //   OLD: read cooldown doc → check locally → write cooldown + write sighting (two separate ops)
    //        Race: two concurrent saves pass the check before either writes the cooldown.
    //        Race: crash between the two writes leaves state inconsistent.
    //   NEW: single CF call → server reads + checks + writes cooldown + writes sighting
    //        inside ONE Firestore transaction. Concurrent calls for same user+species retry
    //        automatically; only the first one through commits.
    //
    // This call is intentionally non-fatal. The bird is already saved to the user's
    // collection. If the sighting is on cooldown or the CF errors, we log and proceed.
    // -------------------------------------------------------------------------

    private void recordSightingViaCloudFunction(String userBirdId, Date timestamp) {
        firebaseManager.recordBirdSighting(
                currentBirdId,
                currentCommonName,
                userBirdId,
                currentLatitude  != null ? currentLatitude  : 0.0,
                currentLongitude != null ? currentLongitude : 0.0,
                currentState,
                currentLocality,
                currentCountry,
                currentQuantity,
                timestamp.getTime(),
                new FirebaseManager.BirdSightingListener() {
                    @Override public void onRecorded() {
                        if (isFinishing() || isDestroyed()) return;
                        handleSaveSuccess();
                    }
                    @Override public void onCooldown() {
                        // Silent — bird still saved to collection, heatmap skipped
                        if (isFinishing() || isDestroyed()) return;
                        handleSaveSuccess();
                    }
                    @Override public void onFailure(String errorMessage) {
                        // Non-fatal: collection save already succeeded
                        Log.w(TAG, "recordBirdSighting failed (non-fatal): " + errorMessage);
                        if (isFinishing() || isDestroyed()) return;
                        handleSaveSuccess();
                    }
                }
        );
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private void handleSaveSuccess() {
        if (viewModel.isSaveFinished.get() || isFinishing() || isDestroyed()) return;
        viewModel.isSaveFinished.set(true);
        viewModel.isSaveInProgress.set(false);
        setSavingUi(false);
        Toast.makeText(this, "Saved to your collection!", Toast.LENGTH_SHORT).show();
        Intent home = new Intent(CardMakerActivity.this, HomeActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        home.putExtra("openTab", "collection");
        startActivity(home);
        finish();
    }

    private void handleSaveFailure(String userMessage, @Nullable Exception e) {
        if (viewModel.isSaveFinished.get() || isFinishing() || isDestroyed()) return;
        Log.e(TAG, userMessage, e);
        viewModel.isSaveInProgress.set(false);
        setSavingUi(false);
        Toast.makeText(CardMakerActivity.this, userMessage, Toast.LENGTH_LONG).show();
    }

    private void setSavingUi(boolean saving) {
        if (loadingOverlay != null) loadingOverlay.setVisibility(saving ? View.VISIBLE : View.GONE);
        if (btnSave   != null) { btnSave.setEnabled(!saving);   btnSave.setClickable(!saving); }
        if (btnCancel != null) { btnCancel.setEnabled(!saving); btnCancel.setClickable(!saving); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!viewModel.isSaveFinished.get() && loadingOverlay != null)
            loadingOverlay.setVisibility(View.GONE);
    }
}