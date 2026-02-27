package com.birddex.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
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
import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.UUID;

/**
 * CardMakerActivity now acts as a PREVIEW screen only.
 * It shows the styled bird card preview using view_bird_card.xml,
 * but saves ONLY the original bird photo to Firebase / collection.
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

    private FirebaseManager firebaseManager;

    private Uri originalImageUri;
    private String currentBirdId;
    private String currentCommonName;
    private String currentRarity;
    private String currentScientificName;
    private String currentSpecies;
    private String currentFamily;
    private String currentConfidence;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_maker);

        firebaseManager = new FirebaseManager(this);

        Button btnSave = findViewById(R.id.btnSaveCard);
        Button btnCancel = findViewById(R.id.btnCancelCard);

        TextView txtBirdName = findViewById(R.id.txtBirdName);
        TextView txtScientific = findViewById(R.id.txtScientific);
        TextView txtRarity = findViewById(R.id.txtRarity);
        TextView txtConfidence = findViewById(R.id.txtConfidence);
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

        if (originalImageUri == null) {
            Toast.makeText(this, "No image passed.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Populate preview card text
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

        if (currentRarity != null && !currentRarity.trim().isEmpty()) {
            txtRarity.setText("Rarity: " + currentRarity);
        } else {
            txtRarity.setText("Rarity: Unknown");
        }

        if (currentConfidence != null && !currentConfidence.trim().isEmpty()) {
            txtConfidence.setText("Confidence: " + currentConfidence);
        } else {
            txtConfidence.setText("Confidence: --");
        }

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
     * Upload ONLY the original image.
     * Do NOT render or upload a full card bitmap anymore.
     */
    private void processAndSaveBirdDiscovery(Uri originalImageUri) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.e(TAG, "Process failed: User is not authenticated.");
            Toast.makeText(this, "Error: No user logged in. Please log in again.", Toast.LENGTH_LONG).show();
            return;
        }

        String userId = user.getUid();
        String originalImageFileName = "user_images/" + userId + "/" + UUID.randomUUID() + ".jpg";

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

        uploadImageAndGetUrl(originalImageData, originalImageFileName)
                .addOnSuccessListener(originalDownloadUri -> {
                    Log.d(TAG, "Original image uploaded: " + originalDownloadUri);
                    storeBirdDiscovery(originalDownloadUri.toString());
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Original image upload failed: " + e.getMessage(), e);
                    Toast.makeText(CardMakerActivity.this, "Failed to upload image. Please try again.", Toast.LENGTH_LONG).show();
                });
    }

    private Task<Uri> uploadImageAndGetUrl(byte[] imageData, String storagePath) {
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

    private void storeBirdDiscovery(String originalImageUrl) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.e(TAG, "Store failed: User is not authenticated.");
            Toast.makeText(this, "Error: No user logged in. Please log in again.", Toast.LENGTH_LONG).show();
            return;
        }

        String userId = user.getUid();
        String userBirdId = UUID.randomUUID().toString();
        String collectionSlotId = UUID.randomUUID().toString();
        String userBirdImageId = UUID.randomUUID().toString();
        Date now = new Date();

        UserBird userBird = new UserBird();
        userBird.setId(userBirdId);
        userBird.setUserId(userId);
        userBird.setBirdSpeciesId(currentBirdId);
        userBird.setTimeSpotted(now);

        UserBirdImage userBirdImage = new UserBirdImage();
        userBirdImage.setId(userBirdImageId);
        userBirdImage.setUserId(userId);
        userBirdImage.setBirdId(currentBirdId);
        userBirdImage.setImageUrl(originalImageUrl);
        userBirdImage.setTimestamp(now);
        userBirdImage.setUserBirdRefId(userBirdId); // This line is present and correct.

        final TaskCompletionSource<Void> addUserBirdTcs = new TaskCompletionSource<>();
        firebaseManager.addUserBird(userBird, task -> {
            if (task.isSuccessful()) {
                addUserBirdTcs.setResult(null);
            } else {
                addUserBirdTcs.setException(task.getException());
            }
        });

        final TaskCompletionSource<Integer> nextSlotIndexTcs = new TaskCompletionSource<>();
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(userId)
                .collection("collectionSlot")
                .orderBy("slotIndex", Query.Direction.DESCENDING)
                .limit(1)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        int nextSlotIndex = 0;
                        if (task.getResult() != null && !task.getResult().isEmpty()) {
                            Long maxSlotIndexLong = task.getResult().getDocuments().get(0).getLong("slotIndex");
                            if (maxSlotIndexLong != null) {
                                nextSlotIndex = maxSlotIndexLong.intValue() + 1;
                            }
                        }
                        nextSlotIndexTcs.setResult(nextSlotIndex);
                    } else {
                        nextSlotIndexTcs.setException(task.getException());
                    }
                });

        final TaskCompletionSource<Void> addCollectionSlotTcs = new TaskCompletionSource<>();
        nextSlotIndexTcs.getTask()
                .addOnSuccessListener(nextSlotIndex -> {
                    CollectionSlot collectionSlot = new CollectionSlot();
                    collectionSlot.setId(collectionSlotId);
                    collectionSlot.setUserBirdId(userBirdId); // *** ADDED THIS LINE ***
                    collectionSlot.setTimestamp(now);

                    // collectionSlot.setImageUrl(originalImageUrl); // Removed this line

                    collectionSlot.setRarity("common"); // Set initial rarity to common
                    collectionSlot.setSlotIndex(nextSlotIndex);

                    // Save names too so collection loads cleaner
                    collectionSlot.setCommonName(currentCommonName);
                    collectionSlot.setScientificName(currentScientificName);

                    firebaseManager.addCollectionSlot(userId, collectionSlotId, collectionSlot, task -> {
                        if (task.isSuccessful()) {
                            addCollectionSlotTcs.setResult(null);
                        } else {
                            addCollectionSlotTcs.setException(task.getException());
                        }
                    });
                })
                .addOnFailureListener(addCollectionSlotTcs::setException);

        final TaskCompletionSource<Void> addUserBirdImageTcs = new TaskCompletionSource<>();
        firebaseManager.addUserBirdImage(userId, userBirdImageId, userBirdImage, task -> {
            if (task.isSuccessful()) {
                addUserBirdImageTcs.setResult(null);
            } else {
                addUserBirdImageTcs.setException(task.getException());
            }
        });

        Tasks.whenAll(
                        addUserBirdTcs.getTask(),
                        addCollectionSlotTcs.getTask(),
                        addUserBirdImageTcs.getTask()
                )
                .addOnSuccessListener(unused -> {
                    Toast.makeText(CardMakerActivity.this, "Saved to your collection!", Toast.LENGTH_SHORT).show();

                    Intent home = new Intent(CardMakerActivity.this, HomeActivity.class);
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    home.putExtra("openTab", "collection");
                    startActivity(home);
                    finish();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "FAILURE: One or more Firestore writes failed.", e);
                    Toast.makeText(CardMakerActivity.this, "Error saving to collection. Please try again.", Toast.LENGTH_LONG).show();
                });
    }
}