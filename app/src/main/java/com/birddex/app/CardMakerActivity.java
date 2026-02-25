package com.birddex.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.UUID;

/**
 * CardMakerActivity shows a preview of the generated Bird Card and lets the user save it.
 */
public class CardMakerActivity extends AppCompatActivity {

    private static final String TAG = "CardMakerActivity";

    public static final String EXTRA_IMAGE_URI = "imageUri"; // Original image URI
    public static final String EXTRA_BIRD_NAME = "birdName";
    public static final String EXTRA_SCI_NAME = "sciName";
    public static final String EXTRA_CONFIDENCE = "confidence";
    public static final String EXTRA_RARITY = "rarity";
    public static final String EXTRA_BIRD_ID = "birdId";
    public static final String EXTRA_SPECIES = "species";
    public static final String EXTRA_FAMILY = "family";

    private View cardView;
    private Bitmap cardBitmap; // generated after layout
    private FirebaseManager firebaseManager;

    // Bird data fields
    private Uri originalImageUri;
    private String currentBirdId;
    private String currentCommonName;
    private String currentRarity;
    private String currentScientificName;
    private String currentSpecies;
    private String currentFamily;
    private String currentRarity;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_maker);

        firebaseManager = new FirebaseManager(this);

        FrameLayout cardHost = findViewById(R.id.cardHost);
        Button btnSave = findViewById(R.id.btnSaveCard);
        Button btnCancel = findViewById(R.id.btnCancelCard);

        String originalImageUriStr = getIntent().getStringExtra(EXTRA_IMAGE_URI);
        if (originalImageUriStr != null) {
            originalImageUri = Uri.parse(originalImageUriStr);
        }

        currentCommonName = getIntent().getStringExtra(EXTRA_BIRD_NAME);
        currentScientificName = getIntent().getStringExtra(EXTRA_SCI_NAME);
        String confidence = getIntent().getStringExtra(EXTRA_CONFIDENCE);
        currentRarity = getIntent().getStringExtra(EXTRA_RARITY);
        currentBirdId = getIntent().getStringExtra(EXTRA_BIRD_ID);
        currentSpecies = getIntent().getStringExtra(EXTRA_SPECIES);
        currentFamily = getIntent().getStringExtra(EXTRA_FAMILY);

        if (originalImageUri == null) {
            Toast.makeText(this, "No image passed.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Bitmap birdBitmap;
        try {
            birdBitmap = loadBitmapSafe(originalImageUri);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Could not load image for card.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Build card view using generator (now bird_card.xml uses fitCenter + square area)
        BirdCardGenerator.BirdCardData data =
                new BirdCardGenerator.BirdCardData(
                        currentCommonName != null ? currentCommonName : "Unknown Bird",
                        currentScientificName != null ? currentScientificName : "",
                        currentRarity != null ? currentRarity : "Unknown",
                        confidence != null ? confidence : "--",
                        "BirdDex • Captured by you"
                );

        cardView = BirdCardGenerator.buildCardView(this, birdBitmap, data);

        // Make sure the card host renders correctly
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        cardView.setLayoutParams(lp);

        cardHost.removeAllViews();
        cardHost.addView(cardView);

        // Render AFTER layout
        cardHost.post(() -> {
            try {
                cardBitmap = BirdCardGenerator.renderViewToBitmap(cardView);
            } catch (Exception e) {
                e.printStackTrace();
                cardBitmap = null;
            }
        });

        btnCancel.setOnClickListener(v -> finish()); // "cancel" on the birdCard UI page

        btnSave.setOnClickListener(v -> {
            if (cardBitmap == null) {
                Toast.makeText(this, "Card image not ready yet, please wait.", Toast.LENGTH_SHORT).show();
                return;
            }
            processAndSaveBirdDiscovery(originalImageUri, cardBitmap);
        });
    }

    /**
     * Handles BOTH file:// and content:// safely
     */
    private Bitmap loadBitmapSafe(Uri uri) throws IOException {
        String scheme = uri.getScheme();

        if ("file".equalsIgnoreCase(scheme)) {
            return BitmapFactory.decodeFile(uri.getPath());
        }

        if (Build.VERSION.SDK_INT >= 28) {
            android.graphics.ImageDecoder.Source src =
                    android.graphics.ImageDecoder.createSource(getContentResolver(), uri);
            return android.graphics.ImageDecoder.decodeBitmap(src);
        } else {
            return MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
        }
    }

    /**
     * Orchestrates the process of saving a bird discovery:
     * 1. Converts original image and card bitmap to byte arrays.
     * 2. Uploads both images to Firebase Storage.
     * 3. Stores the discovery details (UserBird, CollectionSlot, UserBirdImage) in Firestore.
     *
     * @param originalImageUri The URI of the original user-taken image.
     * @param cardBitmap The generated bird card bitmap.
     */
    private void processAndSaveBirdDiscovery(Uri originalImageUri, Bitmap cardBitmap) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.e(TAG, "Process failed: User is not authenticated.");
            Toast.makeText(this, "Error: No user logged in. Please log in again.", Toast.LENGTH_LONG).show();
            return;
        }

        String userId = user.getUid();
        String originalImageFileName = "user_images/" + userId + "/" + UUID.randomUUID().toString() + ".jpg";
        String cardImageFileName = "bird_cards/" + userId + "/" + UUID.randomUUID().toString() + ".jpg";

        // Convert original image URI to byte array
        byte[] originalImageData;
        try {
            Bitmap originalBitmap = loadBitmapSafe(originalImageUri);
            ByteArrayOutputStream originalBaos = new ByteArrayOutputStream();
            originalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, originalBaos);
            originalImageData = originalBaos.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, "Error converting original image to byte array", e);
            Toast.makeText(this, "Error processing original image.", Toast.LENGTH_LONG).show();
            return;
        }

        // Convert card Bitmap to byte array
        ByteArrayOutputStream cardBaos = new ByteArrayOutputStream();
        cardBitmap.compress(Bitmap.CompressFormat.JPEG, 90, cardBaos);
        byte[] cardImageData = cardBaos.toByteArray();

        // Upload both images
        Task<Uri> originalUploadTask = uploadImageAndGetUrl(originalImageData, userId, originalImageFileName);
        Task<Uri> cardUploadTask = uploadImageAndGetUrl(cardImageData, userId, cardImageFileName);

        Tasks.whenAllSuccess(originalUploadTask, cardUploadTask)
                .addOnSuccessListener(results -> {
                    Uri originalDownloadUri = (Uri) results.get(0);
                    Uri cardDownloadUri = (Uri) results.get(1);
                    Log.d(TAG, "Original image uploaded: " + originalDownloadUri.toString());
                    Log.d(TAG, "Card image uploaded: " + cardDownloadUri.toString());
                    storeBirdDiscovery(originalDownloadUri.toString(), cardDownloadUri.toString());
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Image upload failed: " + e.getMessage(), e);
                    Toast.makeText(CardMakerActivity.this, "Failed to upload images. Please try again.", Toast.LENGTH_LONG).show();
                });
    }

    /**
     * Uploads an image (either original photo or generated card) to Firebase Storage
     * and returns a Task that resolves with the download URL.
     */
    private Task<Uri> uploadImageAndGetUrl(byte[] imageData, String userId, String storagePath) {
        StorageReference storageRef = FirebaseStorage.getInstance().getReference().child(storagePath);
        UploadTask uploadTask = storageRef.putBytes(imageData);

        return uploadTask.continueWithTask(new Continuation<UploadTask.TaskSnapshot, Task<Uri>>() {
            @Override
            public Task<Uri> then(@NonNull Task<UploadTask.TaskSnapshot> task) throws Exception {
                if (!task.isSuccessful()) {
                    throw task.getException();
                }
                return storageRef.getDownloadUrl();
            }
        });
    }

    private void storeBirdDiscovery(String originalImageUrl, String cardImageUrl) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.e(TAG, "Store failed: User is not authenticated.");
            Toast.makeText(this, "Error: No user logged in. Please log in again.", Toast.LENGTH_LONG).show();
            return;
        }

        String userId = user.getUid();
        String userBirdId = UUID.randomUUID().toString();
        String collectionSlotId = UUID.randomUUID().toString();
        String userBirdImageId = UUID.randomUUID().toString(); // New ID for UserBirdImage
        Date now = new Date();

        // 1. Create and save UserBird
        UserBird userBird = new UserBird();
        userBird.setId(userBirdId);
        userBird.setUserId(userId);
        userBird.setBirdSpeciesId(currentBirdId);
        userBird.setTimeSpotted(now);

        // 3. Create and save UserBirdImage (using the originalImageUrl)
        UserBirdImage userBirdImage = new UserBirdImage();
        userBirdImage.setId(userBirdImageId);
        userBirdImage.setUserId(userId);
        userBirdImage.setBirdId(currentBirdId);
        userBirdImage.setImageUrl(originalImageUrl);
        userBirdImage.setTimestamp(now);


        Log.d(TAG, "--- Preparing to write to Firestore ---");
        Log.d(TAG, "User UID: " + userId);
        Log.d(TAG, "UserBird to save (userBirds/" + userBird.getId() + "): userId field = " + userBird.getUserId() + ", birdSpeciesId field = " + userBird.getBirdSpeciesId());
        Log.d(TAG, "UserBirdImage to save (users/" + userId + "/userBirdImage/" + userBirdImage.getId() + "): imageUrl = " + userBirdImage.getImageUrl());

        // Use TaskCompletionSource to create Tasks from OnCompleteListener callbacks
        final TaskCompletionSource<Void> addUserBirdTcs = new TaskCompletionSource<>();
        firebaseManager.addUserBird(userBird, task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "UserBird write SUCCESS for ID: " + userBirdId);
                addUserBirdTcs.setResult(null);
            } else {
                Log.e(TAG, "UserBird write FAILURE for ID: " + userBirdId + ": " + task.getException().getMessage(), task.getException());
                addUserBirdTcs.setException(task.getException());
            }
        });

        // Task to determine the next slotIndex for CollectionSlot
        final TaskCompletionSource<Integer> nextSlotIndexTcs = new TaskCompletionSource<>();
        FirebaseFirestore.getInstance().collection("users").document(userId).collection("collectionSlot")
                .orderBy("slotIndex", Query.Direction.DESCENDING)
                .limit(1)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        int nextSlotIndex = 0;
                        if (task.getResult() != null && !task.getResult().isEmpty()) {
                            // If there are existing slots, get the max slotIndex and increment
                            Long maxSlotIndexLong = task.getResult().getDocuments().get(0).getLong("slotIndex");
                            if (maxSlotIndexLong != null) {
                                nextSlotIndex = maxSlotIndexLong.intValue() + 1;
                            } else {
                                Log.w(TAG, "Existing collectionSlot document found but slotIndex field is null. Starting from 0.");
                            }
                        }
                        Log.d(TAG, "Determined next slotIndex: " + nextSlotIndex);
                        nextSlotIndexTcs.setResult(nextSlotIndex);
                    } else {
                        Log.e(TAG, "Failed to get next slotIndex: " + task.getException().getMessage(), task.getException());
                        nextSlotIndexTcs.setException(task.getException());
                    }
                });

        final TaskCompletionSource<Void> addCollectionSlotTcs = new TaskCompletionSource<>();
        // This task now depends on nextSlotIndexTcs
        nextSlotIndexTcs.getTask().addOnSuccessListener(nextSlotIndex -> {
            CollectionSlot collectionSlot = new CollectionSlot();
            collectionSlot.setId(collectionSlotId);
            collectionSlot.setUserBirdId(userBirdId);
            collectionSlot.setTimestamp(now);
            collectionSlot.setImageUrl(cardImageUrl); // Use the URL of the generated card
            collectionSlot.setRarity(currentRarity != null ? currentRarity : "Unknown"); // Set the rarity
            collectionSlot.setSlotIndex(nextSlotIndex); // Set the determined slot index

            Log.d(TAG, "CollectionSlot to save (users/" + userId + "/collectionSlot/" + collectionSlot.getId() + "): imageUrl = " + collectionSlot.getImageUrl() + ", slotIndex = " + collectionSlot.getSlotIndex());

            firebaseManager.addCollectionSlot(userId, collectionSlotId, collectionSlot, task -> {
                if (task.isSuccessful()) {
                    Log.d(TAG, "CollectionSlot write SUCCESS for ID: " + collectionSlotId + " with slotIndex: " + nextSlotIndex);
                    addCollectionSlotTcs.setResult(null);
                } else {
                    Log.e(TAG, "CollectionSlot write FAILURE for ID: " + collectionSlotId + ": " + task.getException().getMessage(), task.getException());
                    addCollectionSlotTcs.setException(task.getException());
                }
            });
        }).addOnFailureListener(e -> {
            // If fetching nextSlotIndex fails, then addCollectionSlotTcs must also fail
            addCollectionSlotTcs.setException(e);
        });

        final TaskCompletionSource<Void> addUserBirdImageTcs = new TaskCompletionSource<>();
        firebaseManager.addUserBirdImage(userId, userBirdImageId, userBirdImage, task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "UserBirdImage write SUCCESS for ID: " + userBirdImageId);
                addUserBirdImageTcs.setResult(null);
            } else {
                Log.e(TAG, "UserBirdImage write FAILURE for ID: " + userBirdImageId + ": " + task.getException().getMessage(), task.getException());
                addUserBirdImageTcs.setException(task.getException());
            }
        });

        Task<Void> addUserBirdTask = addUserBirdTcs.getTask();
        Task<Void> addCollectionSlotTask = addCollectionSlotTcs.getTask();
        Task<Void> addUserBirdImageTask = addUserBirdImageTcs.getTask();

        Tasks.whenAllComplete(addUserBirdTask, addCollectionSlotTask, addUserBirdImageTask)
                .addOnCompleteListener(allTasks -> {
                    if (allTasks.isSuccessful()) {
                        Log.d(TAG, "SUCCESS: All Firestore writes completed.");
                        Toast.makeText(CardMakerActivity.this, "Saved to your collection!", Toast.LENGTH_SHORT).show();

                        Intent home = new Intent(CardMakerActivity.this, HomeActivity.class);
                        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        home.putExtra("openTab", "collection");
                        startActivity(home);
                        finish();
                    } else {
                        Log.e(TAG, "FAILURE: One or more Firestore writes failed.", allTasks.getException());
                        Toast.makeText(CardMakerActivity.this, "Error saving to collection. Please try again.", Toast.LENGTH_LONG).show();
                    }
                });
    }
}