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

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.UUID;

/**
 * CardMakerActivity shows a preview of the generated Bird Card and lets the user save it.
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

    private View cardView;
    private Bitmap cardBitmap; // generated after layout
    private FirebaseManager firebaseManager;

    // Bird data fields
    private String currentImageUriStr;
    private String currentBirdId;
    private String currentCommonName;
    private String currentScientificName;
    private String currentSpecies;
    private String currentFamily;
    private String currentImageUrl; // Firebase Storage URL

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_maker);

        firebaseManager = new FirebaseManager();

        FrameLayout cardHost = findViewById(R.id.cardHost);
        Button btnSave = findViewById(R.id.btnSaveCard);
        Button btnCancel = findViewById(R.id.btnCancelCard);

        currentImageUriStr = getIntent().getStringExtra(EXTRA_IMAGE_URI);
        currentCommonName = getIntent().getStringExtra(EXTRA_BIRD_NAME);
        currentScientificName = getIntent().getStringExtra(EXTRA_SCI_NAME);
        String confidence = getIntent().getStringExtra(EXTRA_CONFIDENCE);
        String rarity = getIntent().getStringExtra(EXTRA_RARITY);
        currentBirdId = getIntent().getStringExtra(EXTRA_BIRD_ID);
        currentSpecies = getIntent().getStringExtra(EXTRA_SPECIES);
        currentFamily = getIntent().getStringExtra(EXTRA_FAMILY);

        if (currentImageUriStr == null) {
            Toast.makeText(this, "No image passed.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Uri imageUri = Uri.parse(currentImageUriStr);

        Bitmap birdBitmap;
        try {
            birdBitmap = loadBitmapSafe(imageUri);
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
                        rarity != null ? rarity : "Unknown",
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

        btnCancel.setOnClickListener(v -> finish());

        btnSave.setOnClickListener(v -> {
            try {
                if (cardBitmap == null) {
                    cardBitmap = BirdCardGenerator.renderViewToBitmap(cardView);
                }

                File cardFile = BirdCardGenerator.savePngToAppFiles(
                        this,
                        cardBitmap,
                        "birddex_card_" + System.currentTimeMillis()
                );

                saveCardToCollection(cardFile.getAbsolutePath());

            } catch (IOException e) {
                e.printStackTrace();
                Toast.makeText(this, "Failed to save card.", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "Card render failed.", Toast.LENGTH_LONG).show();
            }
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

    private void saveCardToCollection(String cardPath) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Error: No user logged in.", Toast.LENGTH_LONG).show();
            return;
        }

        Uri cardUri = Uri.fromFile(new File(cardPath));
        String userId = user.getUid();

        // IMPORTANT: upload to the SAME folder as the working image upload rules
        String fileName = "images/" + userId + "/" + UUID.randomUUID().toString() + ".png";
        StorageReference storageRef = FirebaseStorage.getInstance().getReference().child(fileName);

        storageRef.putFile(cardUri)
                .addOnSuccessListener(taskSnapshot ->
                        storageRef.getDownloadUrl().addOnSuccessListener(downloadUri -> {
                            currentImageUrl = downloadUri.toString();
                            storeBirdDiscovery();
                        })
                )
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Card upload failed", e);
                    Toast.makeText(this, "Failed to upload card image.", Toast.LENGTH_LONG).show();
                });
    }

    private void storeBirdDiscovery() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.e(TAG, "Store failed: User is not authenticated.");
            Toast.makeText(this, "Error: No user logged in. Please log in again.", Toast.LENGTH_LONG).show();
            return;
        }

        String userId = user.getUid();
        String userBirdId = UUID.randomUUID().toString();
        String collectionSlotId = UUID.randomUUID().toString();
        Date now = new Date();

        UserBird userBird = new UserBird();
        userBird.setId(userBirdId);
        userBird.setUserId(userId);
        userBird.setBirdSpeciesId(currentBirdId);
        userBird.setTimeSpotted(now);

        CollectionSlot collectionSlot = new CollectionSlot();
        collectionSlot.setId(collectionSlotId);
        collectionSlot.setUserBirdId(userBirdId);
        collectionSlot.setTimestamp(now);
        collectionSlot.setImageUrl(currentImageUrl);

        Log.d(TAG, "--- Preparing to write to Firestore ---");
        Log.d(TAG, "User UID: " + userId);
        Log.d(TAG, "UserBird to save (userBirds/" + userBird.getId() + "): userId field = " + userBird.getUserId() + ", birdSpeciesId field = " + userBird.getBirdSpeciesId());

        firebaseManager.addUserBird(userBird, task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "SUCCESS: Saved UserBird entry: " + userBirdId);
                firebaseManager.addCollectionSlot(userId, collectionSlotId, collectionSlot, slotTask -> {
                    if (slotTask.isSuccessful()) {
                        Log.d(TAG, "SUCCESS: Saved CollectionSlot: " + collectionSlotId);
                        Toast.makeText(CardMakerActivity.this, "Saved to your collection!", Toast.LENGTH_SHORT).show();

                        // ✅ IMPORTANT: clear the back stack so user can't go back to Identify/BirdInfo/CardMaker
                        Intent home = new Intent(CardMakerActivity.this, HomeActivity.class);
                        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

                        // Optional: if anyone wants HomeActivity to open the collection tab
                        home.putExtra("openTab", "collection");

                        startActivity(home);
                        finish();
                    } else {
                        Log.e(TAG, "FAILURE: Could not save CollectionSlot.", slotTask.getException());
                        Toast.makeText(CardMakerActivity.this, "Error saving to collection slot. Please try again.", Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                Log.e(TAG, "FAILURE: Could not save UserBird entry.", task.getException());
                Toast.makeText(CardMakerActivity.this, "Failed to save bird data: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }
}
