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

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.Transaction;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CardMakerActivity handles saving the bird to the user's permanent collection.
 * It uploads a separate copy of the image to 'userCollectionImages'.
 */
public class CardMakerActivity extends AppCompatActivity {

    private static final String TAG = "CardMakerActivity";
    private static final long HEATMAP_COOLDOWN_MS = 24L * 60L * 60L * 1000L;

    public static final String EXTRA_IMAGE_URI = "imageUri";
    public static final String EXTRA_BIRD_NAME = "birdName";
    public static final String EXTRA_SCI_NAME = "sciName";
    public static final String EXTRA_CONFIDENCE = "confidence";
    public static final String EXTRA_RARITY = "rarity";
    public static final String EXTRA_BIRD_ID = "birdId";
    public static final String EXTRA_SPECIES = "species";
    public static final String EXTRA_FAMILY = "family";
    public static final String EXTRA_LOCALITY = "localityName";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_CAUGHT_TIME = "caughtTime";
    public static final String EXTRA_QUANTITY = "quantity";
    public static final String EXTRA_RECORD_SIGHTING = "recordSighting";
    public static final String EXTRA_LATITUDE = "latitude";
    public static final String EXTRA_LONGITUDE = "longitude";
    public static final String EXTRA_COUNTRY = "country";

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

    private boolean isSaveInProgress = false;
    private boolean isSaveFinished = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_maker);

        firebaseManager = new FirebaseManager(this);
        loadingOverlay = findViewById(R.id.loadingOverlay);

        btnSave = findViewById(R.id.btnSaveCard);
        btnCancel = findViewById(R.id.btnCancelCard);

        TextView txtBirdName = findViewById(R.id.txtBirdName);
        TextView txtScientific = findViewById(R.id.txtScientific);
        TextView txtLocation = findViewById(R.id.txtLocation);
        TextView txtDateCaught = findViewById(R.id.txtDateCaught);
        TextView txtFooter = findViewById(R.id.txtFooter);
        ImageView imgBird = findViewById(R.id.imgBird);

        String originalImageUriStr = getIntent().getStringExtra(EXTRA_IMAGE_URI);
        if (originalImageUriStr != null) {
            originalImageUri = Uri.parse(originalImageUriStr);
        }

        currentCommonName = getIntent().getStringExtra(EXTRA_BIRD_NAME);
        currentScientificName = getIntent().getStringExtra(EXTRA_SCI_NAME);
        currentConfidence = getIntent().getStringExtra(EXTRA_CONFIDENCE);
        currentRarity = getIntent().getStringExtra(EXTRA_RARITY);
        currentBirdId = getIntent().getStringExtra(EXTRA_BIRD_ID);
        currentSpecies = getIntent().getStringExtra(EXTRA_SPECIES);
        currentFamily = getIntent().getStringExtra(EXTRA_FAMILY);
        currentLocality = getIntent().getStringExtra(EXTRA_LOCALITY);
        currentState = getIntent().getStringExtra(EXTRA_STATE);
        currentCaughtTime = getIntent().getLongExtra(EXTRA_CAUGHT_TIME, System.currentTimeMillis());

        currentQuantity = getIntent().getStringExtra(EXTRA_QUANTITY);
        shouldRecordSighting = getIntent().getBooleanExtra(EXTRA_RECORD_SIGHTING, false);
        currentLatitude = getIntent().hasExtra(EXTRA_LATITUDE)
                ? getIntent().getDoubleExtra(EXTRA_LATITUDE, 0.0)
                : null;
        currentLongitude = getIntent().hasExtra(EXTRA_LONGITUDE)
                ? getIntent().getDoubleExtra(EXTRA_LONGITUDE, 0.0)
                : null;
        currentCountry = getIntent().getStringExtra(EXTRA_COUNTRY);

        if (originalImageUri == null) {
            Toast.makeText(this, "No image passed.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (currentCommonName != null && !currentCommonName.trim().isEmpty()) {
            txtBirdName.setText(currentCommonName);
        } else if (currentScientificName != null && !currentScientificName.trim().isEmpty()) {
            txtBirdName.setText(currentScientificName);
        } else {
            txtBirdName.setText("Unknown Bird");
        }

        if (currentScientificName != null && !currentScientificName.trim().isEmpty()) {
            txtScientific.setText(currentScientificName);
        } else {
            txtScientific.setText("--");
        }

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

        btnCancel.setOnClickListener(v -> {
            if (!isSaveInProgress) {
                finish();
            }
        });

        btnSave.setOnClickListener(v -> processAndSaveBirdDiscovery(originalImageUri));
    }

    private void processAndSaveBirdDiscovery(Uri imageUriToSave) {
        if (isSaveInProgress || isSaveFinished) {
            Log.d(TAG, "Ignoring duplicate save tap.");
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.e(TAG, "Process failed: User is not authenticated.");
            Toast.makeText(this, "Error: No user logged in. Please log in again.", Toast.LENGTH_LONG).show();
            return;
        }

        isSaveInProgress = true;
        setSavingUi(true);

        String userId = user.getUid();
        String originalImageFileName = "userCollectionImages/" + userId + "/" + UUID.randomUUID() + ".jpg";
        StorageReference storageRef = FirebaseStorage.getInstance().getReference().child(originalImageFileName);

        Log.d(TAG, "processAndSaveBirdDiscovery: Uploading to " + originalImageFileName);

        storageRef.putFile(imageUriToSave)
                .addOnSuccessListener(taskSnapshot ->
                        storageRef.getDownloadUrl()
                                .addOnSuccessListener(originalDownloadUri -> {
                                    if (isSaveFinished) {
                                        return;
                                    }
                                    Log.d(TAG, "Original image uploaded to collection: " + originalDownloadUri);
                                    storeBirdDiscovery(originalDownloadUri.toString());
                                })
                                .addOnFailureListener(e ->
                                        handleSaveFailure("Failed to save collection image link.", e)
                                )
                )
                .addOnFailureListener(e ->
                        handleSaveFailure("Failed to upload image to your collection.", e)
                );
    }

    private void storeBirdDiscovery(String originalImageUrl) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            handleSaveFailure("Error: No user logged in. Please log in again.", null);
            return;
        }

        String userId = user.getUid();
        String userBirdId = UUID.randomUUID().toString();
        String collectionSlotId = UUID.randomUUID().toString();
        String userBirdImageId = UUID.randomUUID().toString();
        String locationId = UUID.randomUUID().toString();
        Date now = currentCaughtTime > 0 ? new Date(currentCaughtTime) : new Date();

        Map<String, Object> locationData = new HashMap<>();
        locationData.put("id", locationId);
        locationData.put("state", currentState);
        locationData.put("locality", currentLocality);
        if (currentLatitude != null) locationData.put("latitude", currentLatitude);
        if (currentLongitude != null) locationData.put("longitude", currentLongitude);
        if (currentCountry != null) locationData.put("country", currentCountry);

        UserBird userBird = new UserBird();
        userBird.setId(userBirdId);
        userBird.setUserId(userId);
        userBird.setBirdSpeciesId(currentBirdId);
        userBird.setTimeSpotted(now);
        userBird.setLocationId(locationId);
        userBird.setImageUrl(originalImageUrl);
        userBird.setImageCount(1); // Track image count for atomic deletion

        UserBirdImage userBirdImage = new UserBirdImage();
        userBirdImage.setId(userBirdImageId);
        userBirdImage.setUserId(userId);
        userBirdImage.setBirdId(currentBirdId);
        userBirdImage.setImageUrl(originalImageUrl);
        userBirdImage.setTimestamp(now);
        userBirdImage.setUserBirdRefId(userBirdId);

        final TaskCompletionSource<Void> addLocationTcs = new TaskCompletionSource<>();
        FirebaseFirestore.getInstance()
                .collection("locations")
                .document(locationId)
                .set(locationData)
                .addOnSuccessListener(unused -> safeSetResult(addLocationTcs))
                .addOnFailureListener(e -> safeSetException(addLocationTcs, e));

        final TaskCompletionSource<Void> addUserBirdTcs = new TaskCompletionSource<>();
        firebaseManager.addUserBird(userBird, task -> {
            if (task.isSuccessful()) {
                safeSetResult(addUserBirdTcs);
            } else {
                safeSetException(addUserBirdTcs, task.getException() != null
                        ? task.getException()
                        : new IllegalStateException("Failed to save userBird."));
            }
        });

        final TaskCompletionSource<Void> addUserBirdImageTcs = new TaskCompletionSource<>();
        firebaseManager.addUserBirdImage(userId, userBirdImageId, userBirdImage, task -> {
            if (task.isSuccessful()) {
                safeSetResult(addUserBirdImageTcs);
            } else {
                safeSetException(addUserBirdImageTcs, task.getException() != null
                        ? task.getException()
                        : new IllegalStateException("Failed to save userBirdImage."));
            }
        });

        final TaskCompletionSource<Void> addCollectionSlotMaybeTcs = new TaskCompletionSource<>();

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(userId)
                .collection("collectionSlot")
                .whereEqualTo("birdId", currentBirdId)
                .limit(1)
                .get()
                .addOnSuccessListener(existingSlotQuery -> {
                    if (!existingSlotQuery.isEmpty()) {
                        safeSetResult(addCollectionSlotMaybeTcs);
                        return;
                    }

                    FirebaseFirestore.getInstance()
                            .collection("users")
                            .document(userId)
                            .collection("collectionSlot")
                            .orderBy("slotIndex", Query.Direction.DESCENDING)
                            .limit(1)
                            .get()
                            .addOnSuccessListener(slotIndexQuery -> {
                                int nextSlotIndex = 0;
                                if (!slotIndexQuery.isEmpty()) {
                                    Long maxSlotIndexLong = slotIndexQuery.getDocuments().get(0).getLong("slotIndex");
                                    if (maxSlotIndexLong != null) {
                                        nextSlotIndex = maxSlotIndexLong.intValue() + 1;
                                    }
                                }

                                CollectionSlot collectionSlot = new CollectionSlot();
                                collectionSlot.setId(collectionSlotId);
                                collectionSlot.setUserBirdId(userBirdId);
                                collectionSlot.setBirdId(currentBirdId);
                                collectionSlot.setTimestamp(now);
                                collectionSlot.setState(currentState);
                                collectionSlot.setLocality(currentLocality);
                                collectionSlot.setImageUrl(originalImageUrl);
                                collectionSlot.setRarity(currentRarity != null && !currentRarity.trim().isEmpty()
                                        ? currentRarity
                                        : "Unknown");
                                collectionSlot.setSlotIndex(nextSlotIndex);
                                collectionSlot.setCommonName(currentCommonName);
                                collectionSlot.setScientificName(currentScientificName);

                                firebaseManager.addCollectionSlot(userId, collectionSlotId, collectionSlot, task -> {
                                    if (task.isSuccessful()) {
                                        safeSetResult(addCollectionSlotMaybeTcs);
                                    } else {
                                        safeSetException(addCollectionSlotMaybeTcs, task.getException() != null
                                                ? task.getException()
                                                : new IllegalStateException("Failed to save collectionSlot."));
                                    }
                                });
                            })
                            .addOnFailureListener(e -> safeSetException(addCollectionSlotMaybeTcs, e));
                })
                .addOnFailureListener(e -> safeSetException(addCollectionSlotMaybeTcs, e));

        final TaskCompletionSource<Void> addSightingTcs = new TaskCompletionSource<>();
        if (shouldRecordSighting) {
            saveUserBirdSightingIfAllowed(userId, userBirdId, now, currentQuantity, addSightingTcs);
        } else {
            safeSetResult(addSightingTcs);
        }

        Task<Void> requiredSaveTask = Tasks.whenAll(
                addLocationTcs.getTask(),
                addUserBirdTcs.getTask(),
                addUserBirdImageTcs.getTask(),
                addCollectionSlotMaybeTcs.getTask()
        );

        requiredSaveTask
                .addOnSuccessListener(unused -> {
                    Log.d(TAG, "SUCCESS: Required collection writes succeeded.");

                    addSightingTcs.getTask()
                            .addOnSuccessListener(sightingUnused ->
                                    Log.d(TAG, "SUCCESS: Optional sighting flow completed."))
                            .addOnFailureListener(e ->
                                    Log.w(TAG, "Sighting write failed, but collection save already succeeded.", e));

                    handleSaveSuccess();
                })
                .addOnFailureListener(e ->
                        handleSaveFailure("Error saving to collection. Please try again.", e)
                );
    }

    private void handleSaveSuccess() {
        if (isSaveFinished || isFinishing() || isDestroyed()) {
            return;
        }

        isSaveFinished = true;
        isSaveInProgress = false;
        setSavingUi(false);

        Toast.makeText(this, "Saved to your collection!", Toast.LENGTH_SHORT).show();

        Intent home = new Intent(CardMakerActivity.this, HomeActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        home.putExtra("openTab", "collection");
        startActivity(home);
        finish();
    }

    private void handleSaveFailure(String userMessage, @Nullable Exception e) {
        if (isSaveFinished || isFinishing() || isDestroyed()) {
            Log.w(TAG, "Ignoring late failure after save completion.", e);
            return;
        }

        Log.e(TAG, userMessage, e);
        isSaveInProgress = false;
        setSavingUi(false);
        Toast.makeText(CardMakerActivity.this, userMessage, Toast.LENGTH_LONG).show();
    }

    private void setSavingUi(boolean saving) {
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(saving ? View.VISIBLE : View.GONE);
        }
        if (btnSave != null) {
            btnSave.setEnabled(!saving);
            btnSave.setClickable(!saving);
        }
        if (btnCancel != null) {
            btnCancel.setEnabled(!saving);
            btnCancel.setClickable(!saving);
        }
    }

    private void saveUserBirdSightingIfAllowed(String userId,
                                               String userBirdId,
                                               Date timestamp,
                                               String quantity,
                                               TaskCompletionSource<Void> tcs) {
        if (currentBirdId == null || currentBirdId.trim().isEmpty()) {
            Log.w(TAG, "Skipping heatmap upload: currentBirdId is missing.");
            safeSetResult(tcs);
            return;
        }

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        DocumentReference cooldownRef = db.collection("users")
                .document(userId)
                .collection("settings")
                .document("heatmapCooldowns");

        long nowMs = timestamp.getTime();

        db.runTransaction((Transaction.Function<Boolean>) transaction -> {
            com.google.firebase.firestore.DocumentSnapshot snapshot = transaction.get(cooldownRef);

            Map<String, Object> speciesCooldowns = new HashMap<>();
            Object rawCooldowns = snapshot.get("speciesCooldowns");
            if (rawCooldowns instanceof Map) {
                Map<?, ?> existing = (Map<?, ?>) rawCooldowns;
                for (Map.Entry<?, ?> entry : existing.entrySet()) {
                    if (entry.getKey() != null) {
                        speciesCooldowns.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
            }

            long lastUploadMs = 0L;
            Object rawLastUpload = speciesCooldowns.get(currentBirdId);
            if (rawLastUpload instanceof Number) {
                lastUploadMs = ((Number) rawLastUpload).longValue();
            }

            boolean cooldownExpired = (nowMs - lastUploadMs) >= HEATMAP_COOLDOWN_MS;
            if (cooldownExpired) {
                speciesCooldowns.put(currentBirdId, nowMs);

                Map<String, Object> cooldownDoc = new HashMap<>();
                cooldownDoc.put("speciesCooldowns", speciesCooldowns);
                cooldownDoc.put("updatedAt", nowMs);
                transaction.set(cooldownRef, cooldownDoc);
                return true;
            }

            return false;
        }).addOnSuccessListener(canUpload -> {
            if (Boolean.TRUE.equals(canUpload)) {
                saveUserBirdSighting(userId, userBirdId, timestamp, quantity, tcs);
            } else {
                Log.d(TAG, "Heatmap cooldown active for birdId=" + currentBirdId + ". Skipping userBirdSighting write.");
                Toast.makeText(
                        CardMakerActivity.this,
                        "Saved to collection. Heatmap upload skipped because this species was already uploaded in the last 24 hours.",
                        Toast.LENGTH_LONG
                ).show();
                safeSetResult(tcs);
            }
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed checking heatmap cooldown", e);
            safeSetException(tcs, e);
        });
    }

    private void saveUserBirdSighting(String userId,
                                      String userBirdId,
                                      Date timestamp,
                                      String quantity,
                                      TaskCompletionSource<Void> tcs) {
        Map<String, Object> userSightingData = new HashMap<>();
        userSightingData.put("userBirdId", userBirdId);
        userSightingData.put("userId", userId);
        userSightingData.put("quantity", quantity);

        Location sightingLocation = new Location(
                UUID.randomUUID().toString(),
                new HashMap<>(),
                currentLatitude != null ? currentLatitude : 0.0,
                currentLongitude != null ? currentLongitude : 0.0,
                currentCountry != null ? currentCountry : "US",
                currentState != null ? currentState : "Georgia",
                currentLocality != null ? currentLocality : "Unknown"
        );

        String userBirdSightId = UUID.randomUUID().toString();
        UserBirdSighting userBirdSighting = new UserBirdSighting(
                userBirdSightId,
                userSightingData,
                sightingLocation,
                currentBirdId,
                currentCommonName,
                quantity,
                timestamp
        );

        firebaseManager.addUserBirdSighting(userBirdSighting, task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "SUCCESS: Saved UserBirdSighting: " + userBirdSightId);
                safeSetResult(tcs);
            } else {
                Exception error = task.getException() != null
                        ? task.getException()
                        : new IllegalStateException("Failed to save UserBirdSighting.");
                Log.e(TAG, "FAILURE: Could not save UserBirdSighting", error);
                safeSetException(tcs, error);
            }
        });
    }

    private void safeSetResult(TaskCompletionSource<Void> tcs) {
        if (!tcs.getTask().isComplete()) {
            tcs.setResult(null);
        }
    }

    private void safeSetException(TaskCompletionSource<Void> tcs, Exception e) {
        Exception safeException = e != null ? e : new IllegalStateException("Unknown task failure.");
        if (!tcs.getTask().isComplete()) {
            tcs.setException(safeException);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isSaveFinished) {
            return;
        }
        if (loadingOverlay != null && loadingOverlay.getVisibility() == View.VISIBLE) {
            loadingOverlay.setVisibility(View.GONE);
        }
    }
}