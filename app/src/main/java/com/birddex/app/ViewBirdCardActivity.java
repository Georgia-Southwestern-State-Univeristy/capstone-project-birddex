package com.birddex.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public class ViewBirdCardActivity extends AppCompatActivity {

    private ImageView imgBird;
    private Button btnChangeCardImage;
    private Button btnBirdInfo;
    private TextView txtLocation;
    private TextView txtDateCaught;

    private String currentImageUrl;
    private String currentBirdId;
    private String currentState;
    private String currentLocality;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_view_bird_card);

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
        currentState = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_STATE);
        currentLocality = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_LOCALITY);
        currentBirdId = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_BIRD_ID);
        long caughtTime = getIntent().getLongExtra(CollectionCardAdapter.EXTRA_CAUGHT_TIME, -1L);

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
        txtDateCaught.setText(CardFormatUtils.formatCaughtDate(caughtTime > 0 ? new Date(caughtTime) : null));
        txtFooter.setVisibility(View.GONE);

        loadBirdImage(currentImageUrl);

        if (currentBirdId == null || currentBirdId.trim().isEmpty()) {
            btnChangeCardImage.setEnabled(false);
            btnChangeCardImage.setText("No Saved Bird ID");

            btnBirdInfo.setEnabled(false);
            btnBirdInfo.setText("No Bird Info");
        } else {
            btnChangeCardImage.setOnClickListener(v -> openImagePickerForThisBird());
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
                .fitCenter()
                .into(imgBird);
    }

    private void openImagePickerForThisBird() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "No user logged in.", Toast.LENGTH_SHORT).show();
            return;
        }

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .collection("userBirdImage")
                .whereEqualTo("birdId", currentBirdId)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (queryDocumentSnapshots.isEmpty()) {
                        Toast.makeText(this, "No stored images found for this bird.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    List<ImageChoice> choices = new ArrayList<>();
                    LinkedHashSet<String> seenUrls = new LinkedHashSet<>();

                    for (DocumentSnapshot doc : queryDocumentSnapshots.getDocuments()) {
                        String imageUrl = doc.getString("imageUrl");
                        Date timestamp = doc.getDate("timestamp");
                        String userBirdRefId = doc.getString("userBirdRefId");
                        Boolean hiddenFromUser = doc.getBoolean("hiddenFromUser");

                        if (Boolean.TRUE.equals(hiddenFromUser)) {
                            continue;
                        }

                        if (imageUrl != null && !imageUrl.trim().isEmpty() && !seenUrls.contains(imageUrl)) {
                            seenUrls.add(imageUrl);
                            choices.add(new ImageChoice(imageUrl, timestamp, userBirdRefId));
                        }
                    }

                    if (choices.isEmpty()) {
                        Toast.makeText(this, "No usable stored images found for this bird.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Collections.sort(choices, (a, b) -> {
                        long ta = a.timestamp != null ? a.timestamp.getTime() : 0L;
                        long tb = b.timestamp != null ? b.timestamp.getTime() : 0L;
                        return Long.compare(tb, ta);
                    });

                    CharSequence[] labels = new CharSequence[choices.size()];
                    for (int i = 0; i < choices.size(); i++) {
                        String dateText = formatShortDate(choices.get(i).timestamp);
                        boolean isCurrent = currentImageUrl != null && currentImageUrl.equals(choices.get(i).imageUrl);
                        labels[i] = isCurrent
                                ? "Image " + (i + 1) + " • " + dateText + " • Current"
                                : "Image " + (i + 1) + " • " + dateText;
                    }

                    new AlertDialog.Builder(this)
                            .setTitle("Choose card image")
                            .setItems(labels, (dialog, which) -> {
                                ImageChoice selected = choices.get(which);

                                if (selected.imageUrl != null && selected.imageUrl.equals(currentImageUrl)) {
                                    Toast.makeText(this, "That image is already being used.", Toast.LENGTH_SHORT).show();
                                    return;
                                }

                                resolveSelectionAndApply(user.getUid(), selected);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                })
                .addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, "Failed to load stored images.", Toast.LENGTH_SHORT).show();
                });
    }

    private void resolveSelectionAndApply(String userId, ImageChoice selectedChoice) {
        if (selectedChoice == null) {
            Toast.makeText(this, "Invalid image selection.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isBlank(selectedChoice.userBirdRefId)) {
            ResolvedSelection resolved = new ResolvedSelection(
                    selectedChoice.imageUrl,
                    selectedChoice.timestamp,
                    null,
                    currentState,
                    currentLocality
            );
            applyResolvedSelection(userId, resolved);
            return;
        }

        FirebaseFirestore.getInstance()
                .collection("userBirds")
                .document(selectedChoice.userBirdRefId)
                .get()
                .addOnSuccessListener(userBirdSnap -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (!userBirdSnap.exists()) {
                        ResolvedSelection resolved = new ResolvedSelection(
                                selectedChoice.imageUrl,
                                selectedChoice.timestamp,
                                selectedChoice.userBirdRefId,
                                currentState,
                                currentLocality
                        );
                        applyResolvedSelection(userId, resolved);
                        return;
                    }

                    Date timeSpotted = userBirdSnap.getDate("timeSpotted");
                    String locationId = userBirdSnap.getString("locationId");

                    if (isBlank(locationId)) {
                        ResolvedSelection resolved = new ResolvedSelection(
                                selectedChoice.imageUrl,
                                timeSpotted != null ? timeSpotted : selectedChoice.timestamp,
                                selectedChoice.userBirdRefId,
                                currentState,
                                currentLocality
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
                                String resolvedState = currentState;
                                String resolvedLocality = currentLocality;

                                if (locationSnap.exists()) {
                                    String dbState = locationSnap.getString("state");
                                    String dbLocality = locationSnap.getString("locality");

                                    if (!isBlank(dbState)) resolvedState = dbState;
                                    if (!isBlank(dbLocality)) resolvedLocality = dbLocality;
                                }

                                ResolvedSelection resolved = new ResolvedSelection(
                                        selectedChoice.imageUrl,
                                        timeSpotted != null ? timeSpotted : selectedChoice.timestamp,
                                        selectedChoice.userBirdRefId,
                                        resolvedState,
                                        resolvedLocality
                                );
                                applyResolvedSelection(userId, resolved);
                            })
                            .addOnFailureListener(e -> {
                                if (isFinishing() || isDestroyed()) return;
                                ResolvedSelection resolved = new ResolvedSelection(
                                        selectedChoice.imageUrl,
                                        timeSpotted != null ? timeSpotted : selectedChoice.timestamp,
                                        selectedChoice.userBirdRefId,
                                        currentState,
                                        currentLocality
                                );
                                applyResolvedSelection(userId, resolved);
                            });
                })
                .addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;
                    ResolvedSelection resolved = new ResolvedSelection(
                            selectedChoice.imageUrl,
                            selectedChoice.timestamp,
                            selectedChoice.userBirdRefId,
                            currentState,
                            currentLocality
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

                    batch.update(doc.getReference(), "imageUrl", resolved.imageUrl);

                    if (resolved.timestamp != null) {
                        batch.update(doc.getReference(), "timestamp", resolved.timestamp);
                    }

                    if (!isBlank(resolved.userBirdRefId)) {
                        batch.update(doc.getReference(), "userBirdId", resolved.userBirdRefId);
                    }

                    batch.update(doc.getReference(), "state", resolved.state);
                    batch.update(doc.getReference(), "locality", resolved.locality);

                    batch.commit()
                            .addOnSuccessListener(unused -> {
                                if (isFinishing() || isDestroyed()) return;
                                currentImageUrl = resolved.imageUrl;
                                currentState = resolved.state;
                                currentLocality = resolved.locality;

                                loadBirdImage(currentImageUrl);
                                txtLocation.setText(CardFormatUtils.formatLocation(currentState, currentLocality));
                                txtDateCaught.setText(CardFormatUtils.formatCaughtDate(resolved.timestamp));

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

    private String formatShortDate(Date date) {
        if (date == null) return "Unknown date";
        return new SimpleDateFormat("M/d/yy", Locale.US).format(date);
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