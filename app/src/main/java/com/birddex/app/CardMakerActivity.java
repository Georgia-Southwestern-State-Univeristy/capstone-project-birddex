package com.birddex.app;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;

import androidx.annotation.NonNull;
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
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
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
    private LoadingDialog loadingDialog;

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

        firebaseManager = new FirebaseManager(this);
        loadingDialog = new LoadingDialog(this);

        Button btnSave = findViewById(R.id.btnSaveCard);
        Button btnCancel = findViewById(R.id.btnCancelCard);

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
        currentLatitude = getIntent().hasExtra(EXTRA_LATITUDE) ? getIntent().getDoubleExtra(EXTRA_LATITUDE, 0.0) : null;
        currentLongitude = getIntent().hasExtra(EXTRA_LONGITUDE) ? getIntent().getDoubleExtra(EXTRA_LONGITUDE, 0.0) : null;
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

        btnCancel.setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> processAndSaveBirdDiscovery(originalImageUri));
    }

    private void processAndSaveBirdDiscovery(Uri originalImageUri) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.e(TAG, "Process failed: User is not authenticated.");
            Toast.makeText(this, "Error: No user logged in. Please log in again.", Toast.LENGTH_LONG).show();
            return;
        }

        loadingDialog.show();

        String userId = user.getUid();
        String originalImageFileName = "userCollectionImages/" + userId + "/" + UUID.randomUUID() + ".jpg";
        StorageReference storageRef = FirebaseStorage.getInstance().getReference().child(originalImageFileName);

        Log.d(TAG, "processAndSaveBirdDiscovery: Uploading to " + originalImageFileName);
        
        storageRef.putFile(originalImageUri)
                .addOnSuccessListener(taskSnapshot -> {
                    storageRef.getDownloadUrl().addOnSuccessListener(originalDownloadUri -> {
                        Log.d(TAG, "Original image uploaded to collection: " + originalDownloadUri);
                        storeBirdDiscovery(originalDownloadUri.toString());
                    }).addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to get download URL", e);
                        loadingDialog.dismiss();
                        Toast.makeText(CardMakerActivity.this, "Failed to save collection image link.", Toast.LENGTH_LONG).show();
                    });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Original image upload failed: " + e.getMessage(), e);
                    loadingDialog.dismiss();
                    Toast.makeText(CardMakerActivity.this, "Failed to upload image to your collection.", Toast.LENGTH_LONG).show();
                });
    }

    private void storeBirdDiscovery(String originalImageUrl) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            loadingDialog.dismiss();
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
                .addOnSuccessListener(unused -> addLocationTcs.setResult(null))
                .addOnFailureListener(addLocationTcs::setException);

        final TaskCompletionSource<Void> addUserBirdTcs = new TaskCompletionSource<>();
        firebaseManager.addUserBird(userBird, task -> {
            if (task.isSuccessful()) {
                addUserBirdTcs.setResult(null);
            } else {
                addUserBirdTcs.setException(task.getException());
            }
        });

        final TaskCompletionSource<Void> addUserBirdImageTcs = new TaskCompletionSource<>();
        firebaseManager.addUserBirdImage(userId, userBirdImageId, userBirdImage, task -> {
            if (task.isSuccessful()) {
                addUserBirdImageTcs.setResult(null);
            } else {
                addUserBirdImageTcs.setException(task.getException());
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
                        addCollectionSlotMaybeTcs.setResult(null);
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
                                        addCollectionSlotMaybeTcs.setResult(null);
                                    } else {
                                        addCollectionSlotMaybeTcs.setException(task.getException());
                                    }
                                });
                            })
                            .addOnFailureListener(addCollectionSlotMaybeTcs::setException);
                })
                .addOnFailureListener(addCollectionSlotMaybeTcs::setException);

        final TaskCompletionSource<Void> addSightingTcs = new TaskCompletionSource<>();
        if (shouldRecordSighting) {
            saveUserBirdSighting(userId, userBirdId, now, currentQuantity, addSightingTcs);
        } else {
            addSightingTcs.setResult(null);
        }

        Tasks.whenAll(
                        addLocationTcs.getTask(),
                        addUserBirdTcs.getTask(),
                        addUserBirdImageTcs.getTask(),
                        addCollectionSlotMaybeTcs.getTask(),
                        addSightingTcs.getTask()
                )
                .addOnSuccessListener(unused -> {
                    Log.d(TAG, "SUCCESS: All Firestore writes succeeded.");
                    loadingDialog.dismiss();
                    Toast.makeText(CardMakerActivity.this, "Saved to your collection!", Toast.LENGTH_SHORT).show();

                    Intent home = new Intent(CardMakerActivity.this, HomeActivity.class);
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    home.putExtra("openTab", "collection");
                    startActivity(home);
                    finish();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "FAILURE: One or more Firestore writes failed.", e);
                    loadingDialog.dismiss();
                    Toast.makeText(CardMakerActivity.this, "Error saving to collection. Please try again.", Toast.LENGTH_LONG).show();
                });
    }

    private void saveUserBirdSighting(String userId, String userBirdId, Date timestamp, String quantity, TaskCompletionSource<Void> tcs) {
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
                tcs.setResult(null);
            } else {
                Log.e(TAG, "FAILURE: Could not save UserBirdSighting", task.getException());
                tcs.setException(task.getException());
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
        }
    }
}
