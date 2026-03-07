package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class ViewBirdCardActivity extends AppCompatActivity {

    public static final String EXTRA_ALLOW_IMAGE_CHANGE = "com.birddex.app.extra.ALLOW_IMAGE_CHANGE";

    private ImageView imgBird;
    private Button btnChangeCardImage;
    private Button btnBirdInfo;
    private TextView txtLocation;
    private TextView txtDateCaught;

    private String currentImageUrl;
    private String currentBirdId;
    private String currentState;
    private String currentLocality;
    private Date currentCaughtDate;

    private ActivityResultLauncher<Intent> changeCardImageLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_view_bird_card);

        changeCardImageLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                        return;
                    }

                    Intent data = result.getData();
                    String selectedImageUrl = data.getStringExtra(ChangeCardImageActivity.RESULT_IMAGE_URL);
                    long timestampMillis = data.getLongExtra(ChangeCardImageActivity.RESULT_TIMESTAMP, -1L);
                    String selectedUserBirdRefId = data.getStringExtra(ChangeCardImageActivity.RESULT_USER_BIRD_REF_ID);

                    if (isBlank(selectedImageUrl)) {
                        Toast.makeText(this, "Invalid image selection.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (selectedImageUrl.equals(currentImageUrl)) {
                        Toast.makeText(this, "That image is already being used.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
                    if (currentUser == null) {
                        Toast.makeText(this, "No user logged in.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    ImageChoice selectedChoice = new ImageChoice(
                            selectedImageUrl,
                            timestampMillis > 0 ? new Date(timestampMillis) : null,
                            normalizeBlankToNull(selectedUserBirdRefId)
                    );

                    resolveSelectionAndApply(currentUser.getUid(), selectedChoice);
                }
        );

        ImageButton btnBack = findViewById(R.id.btnBack);
        TextView txtBirdName = findViewById(R.id.txtBirdName);
        TextView txtScientific = findViewById(R.id.txtScientific);
        txtLocation = findViewById(R.id.txtLocation);
        txtDateCaught = findViewById(R.id.txtDateCaught);
        TextView txtFooter = findViewById(R.id.txtFooter);
        imgBird = findViewById(R.id.imgBird);
        btnChangeCardImage = findViewById(R.id.btnChangeCardImage);
        btnBirdInfo = findViewById(R.id.btnBirdInfo);

        currentImageUrl = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_IMAGE_URL);
        String commonName = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_COMMON_NAME);
        String sciName = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_SCI_NAME);
        currentState = normalizeBlankToNull(getIntent().getStringExtra(CollectionCardAdapter.EXTRA_STATE));
        currentLocality = normalizeBlankToNull(getIntent().getStringExtra(CollectionCardAdapter.EXTRA_LOCALITY));
        currentBirdId = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_BIRD_ID);

        long caughtTime = getIntent().getLongExtra(CollectionCardAdapter.EXTRA_CAUGHT_TIME, -1L);
        currentCaughtDate = caughtTime > 0 ? new Date(caughtTime) : null;

        if (commonName != null && !commonName.trim().isEmpty()) {
            txtBirdName.setText(commonName);
        } else if (sciName != null && !sciName.trim().isEmpty()) {
            txtBirdName.setText(sciName);
        } else {
            txtBirdName.setText("Unknown Bird");
        }

        if (sciName != null && !sciName.trim().isEmpty()) {
            txtScientific.setText(sciName);
        } else {
            txtScientific.setText("--");
        }

        txtLocation.setText(CardFormatUtils.formatLocation(currentState, currentLocality));
        txtDateCaught.setText(CardFormatUtils.formatCaughtDate(currentCaughtDate));
        txtFooter.setVisibility(View.GONE);

        loadBirdImage(currentImageUrl);

        boolean allowImageChange = getIntent().getBooleanExtra(EXTRA_ALLOW_IMAGE_CHANGE, true);

        if (!allowImageChange) {
            btnChangeCardImage.setVisibility(View.GONE);

            View parent = (View) btnBirdInfo.getParent();
            if (parent instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) parent;
                row.setWeightSum(1f);
            }

            LinearLayout.LayoutParams birdInfoParams =
                    (LinearLayout.LayoutParams) btnBirdInfo.getLayoutParams();
            birdInfoParams.width = 0;
            birdInfoParams.weight = 1f;
            birdInfoParams.setMargins(
                    0,
                    birdInfoParams.topMargin,
                    0,
                    birdInfoParams.bottomMargin
            );
            btnBirdInfo.setLayoutParams(birdInfoParams);
        }

        if (currentBirdId == null || currentBirdId.trim().isEmpty()) {
            if (allowImageChange) {
                btnChangeCardImage.setEnabled(false);
                btnChangeCardImage.setText("No Saved Bird ID");
            }

            btnBirdInfo.setEnabled(false);
            btnBirdInfo.setText("No Bird Info");
        } else {
            if (allowImageChange) {
                btnChangeCardImage.setOnClickListener(v -> openImagePickerForThisBird());
            }
            btnBirdInfo.setOnClickListener(v -> openBirdInfoPage());
        }

        btnBack.setOnClickListener(v -> finish());
    }

    private void openBirdInfoPage() {
        if (currentBirdId == null || currentBirdId.trim().isEmpty()) {
            Toast.makeText(this, "No bird info available.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, BirdWikiActivity.class);
        intent.putExtra(BirdWikiActivity.EXTRA_BIRD_ID, currentBirdId);
        startActivity(intent);
    }

    private void loadBirdImage(String imageUrl) {
        if (isFinishing() || isDestroyed()) return;

        Glide.with(this)
                .load(imageUrl)
                .override(800, 800)
                .fitCenter()
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.bg_image_placeholder)
                .into(imgBird);
    }

    private void openImagePickerForThisBird() {
        if (isBlank(currentBirdId)) {
            Toast.makeText(this, "No saved bird ID.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, ChangeCardImageActivity.class);
        intent.putExtra(ChangeCardImageActivity.EXTRA_BIRD_ID, currentBirdId);
        intent.putExtra(ChangeCardImageActivity.EXTRA_CURRENT_IMAGE_URL, currentImageUrl);
        intent.putExtra(ChangeCardImageActivity.EXTRA_COMMON_NAME, ((TextView) findViewById(R.id.txtBirdName)).getText().toString());
        intent.putExtra(ChangeCardImageActivity.EXTRA_SCI_NAME, ((TextView) findViewById(R.id.txtScientific)).getText().toString());
        changeCardImageLauncher.launch(intent);
    }

    private void resolveSelectionAndApply(String userId, ImageChoice selectedChoice) {
        if (selectedChoice == null) {
            Toast.makeText(this, "Invalid image selection.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isBlank(selectedChoice.userBirdRefId)) {
            resolveFromUserBirdDocument(userId, selectedChoice, selectedChoice.userBirdRefId);
            return;
        }

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(userId)
                .collection("userBirdImage")
                .whereEqualTo("imageUrl", selectedChoice.imageUrl)
                .limit(1)
                .get()
                .addOnSuccessListener(imageQuery -> {
                    if (isFinishing() || isDestroyed()) return;

                    if (!imageQuery.isEmpty()) {
                        DocumentSnapshot imageDoc = imageQuery.getDocuments().get(0);
                        String resolvedUserBirdRefId = normalizeBlankToNull(imageDoc.getString("userBirdRefId"));
                        Date resolvedTimestamp = imageDoc.getDate("timestamp");

                        ImageChoice enrichedChoice = new ImageChoice(
                                selectedChoice.imageUrl,
                                resolvedTimestamp != null ? resolvedTimestamp : selectedChoice.timestamp,
                                resolvedUserBirdRefId
                        );

                        if (!isBlank(enrichedChoice.userBirdRefId)) {
                            resolveFromUserBirdDocument(userId, enrichedChoice, enrichedChoice.userBirdRefId);
                            return;
                        }
                    }

                    resolveFromUserBirdByImage(userId, selectedChoice);
                })
                .addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;
                    resolveFromUserBirdByImage(userId, selectedChoice);
                });
    }

    private void resolveFromUserBirdByImage(String userId, ImageChoice selectedChoice) {
        FirebaseFirestore.getInstance()
                .collection("userBirds")
                .whereEqualTo("imageUrl", selectedChoice.imageUrl)
                .limit(5)
                .get()
                .addOnSuccessListener(userBirdQuery -> {
                    if (isFinishing() || isDestroyed()) return;

                    if (userBirdQuery.isEmpty()) {
                        ResolvedSelection resolved = new ResolvedSelection(
                                selectedChoice.imageUrl,
                                selectedChoice.timestamp,
                                null,
                                null,
                                null
                        );
                        applyResolvedSelection(userId, resolved);
                        return;
                    }

                    DocumentSnapshot userBirdDoc = null;
                    for (DocumentSnapshot candidate : userBirdQuery.getDocuments()) {
                        String candidateUserId = candidate.getString("userId");
                        String candidateBirdId = candidate.getString("birdSpeciesId");
                        if (userId.equals(candidateUserId) && currentBirdId.equals(candidateBirdId)) {
                            userBirdDoc = candidate;
                            break;
                        }
                    }

                    if (userBirdDoc == null) {
                        ResolvedSelection resolved = new ResolvedSelection(
                                selectedChoice.imageUrl,
                                selectedChoice.timestamp,
                                null,
                                null,
                                null
                        );
                        applyResolvedSelection(userId, resolved);
                        return;
                    }

                    String resolvedUserBirdRefId = userBirdDoc.getId();
                    Date timeSpotted = userBirdDoc.getDate("timeSpotted");
                    String locationId = normalizeBlankToNull(userBirdDoc.getString("locationId"));

                    resolveFromLocation(
                            userId,
                            selectedChoice.imageUrl,
                            timeSpotted != null ? timeSpotted : selectedChoice.timestamp,
                            resolvedUserBirdRefId,
                            locationId
                    );
                })
                .addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;

                    ResolvedSelection resolved = new ResolvedSelection(
                            selectedChoice.imageUrl,
                            selectedChoice.timestamp,
                            null,
                            null,
                            null
                    );
                    applyResolvedSelection(userId, resolved);
                });
    }

    private void resolveFromUserBirdDocument(String userId, ImageChoice selectedChoice, String userBirdRefId) {
        FirebaseFirestore.getInstance()
                .collection("userBirds")
                .document(userBirdRefId)
                .get()
                .addOnSuccessListener(userBirdSnap -> {
                    if (isFinishing() || isDestroyed()) return;

                    if (!userBirdSnap.exists()) {
                        resolveFromUserBirdByImage(userId, selectedChoice);
                        return;
                    }

                    Date timeSpotted = userBirdSnap.getDate("timeSpotted");
                    String locationId = normalizeBlankToNull(userBirdSnap.getString("locationId"));

                    resolveFromLocation(
                            userId,
                            selectedChoice.imageUrl,
                            timeSpotted != null ? timeSpotted : selectedChoice.timestamp,
                            userBirdRefId,
                            locationId
                    );
                })
                .addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;
                    resolveFromUserBirdByImage(userId, selectedChoice);
                });
    }

    private void resolveFromLocation(String userId,
                                     String imageUrl,
                                     Date timestamp,
                                     String userBirdRefId,
                                     String locationId) {
        if (isBlank(locationId)) {
            ResolvedSelection resolved = new ResolvedSelection(
                    imageUrl,
                    timestamp,
                    userBirdRefId,
                    null,
                    null
            );
            applyResolvedSelection(userId, resolved);
            return;
        }

        FirebaseFirestore.getInstance()
                .collection("locations")
                .document(locationId)
                .get()
                .addOnSuccessListener(locationSnap -> {
                    if (isFinishing() || isDestroyed()) return;

                    String resolvedState = null;
                    String resolvedLocality = null;

                    if (locationSnap.exists()) {
                        resolvedState = normalizeBlankToNull(locationSnap.getString("state"));
                        resolvedLocality = normalizeBlankToNull(locationSnap.getString("locality"));
                    }

                    ResolvedSelection resolved = new ResolvedSelection(
                            imageUrl,
                            timestamp,
                            userBirdRefId,
                            resolvedState,
                            resolvedLocality
                    );
                    applyResolvedSelection(userId, resolved);
                })
                .addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;

                    ResolvedSelection resolved = new ResolvedSelection(
                            imageUrl,
                            timestamp,
                            userBirdRefId,
                            null,
                            null
                    );
                    applyResolvedSelection(userId, resolved);
                });
    }

    private void applyResolvedSelection(String userId, ResolvedSelection resolved) {
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(userId)
                .collection("collectionSlot")
                .whereEqualTo("birdId", currentBirdId)
                .limit(1)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (isFinishing() || isDestroyed()) return;

                    if (queryDocumentSnapshots.isEmpty()) {
                        Toast.makeText(this, "No collection card found for this bird.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    DocumentSnapshot doc = queryDocumentSnapshots.getDocuments().get(0);
                    WriteBatch batch = FirebaseFirestore.getInstance().batch();

                    Map<String, Object> updates = new HashMap<>();
                    updates.put("imageUrl", resolved.imageUrl);
                    updates.put("state", normalizeBlankToNull(resolved.state));
                    updates.put("locality", normalizeBlankToNull(resolved.locality));

                    if (resolved.timestamp != null) {
                        updates.put("timestamp", resolved.timestamp);
                    }

                    updates.put("userBirdId", normalizeBlankToNull(resolved.userBirdRefId));

                    batch.update(doc.getReference(), updates);

                    batch.commit()
                            .addOnSuccessListener(unused -> {
                                if (isFinishing() || isDestroyed()) return;

                                currentImageUrl = resolved.imageUrl;

                                if (resolved.timestamp != null) {
                                    currentCaughtDate = resolved.timestamp;
                                }

                                currentState = normalizeBlankToNull(resolved.state);
                                currentLocality = normalizeBlankToNull(resolved.locality);

                                loadBirdImage(currentImageUrl);
                                txtLocation.setText(CardFormatUtils.formatLocation(currentState, currentLocality));
                                txtDateCaught.setText(CardFormatUtils.formatCaughtDate(currentCaughtDate));

                                Toast.makeText(this, "Card image updated.", Toast.LENGTH_SHORT).show();
                            })
                            .addOnFailureListener(e -> {
                                if (isFinishing() || isDestroyed()) return;
                                Toast.makeText(this, "Failed to update card image.", Toast.LENGTH_SHORT).show();
                            });
                })
                .addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, "Failed to update collection.", Toast.LENGTH_SHORT).show();
                });
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String normalizeBlankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static class ImageChoice {
        final String imageUrl;
        final Date timestamp;
        final String userBirdRefId;

        ImageChoice(String imageUrl, Date timestamp, String userBirdRefId) {
            this.imageUrl = imageUrl;
            this.timestamp = timestamp;
            this.userBirdRefId = userBirdRefId;
        }
    }

    private static class ResolvedSelection {
        final String imageUrl;
        final Date timestamp;
        final String userBirdRefId;
        final String state;
        final String locality;

        ResolvedSelection(String imageUrl, Date timestamp, String userBirdRefId, String state, String locality) {
            this.imageUrl = imageUrl;
            this.timestamp = timestamp;
            this.userBirdRefId = userBirdRefId;
            this.state = state;
            this.locality = locality;
        }
    }
}