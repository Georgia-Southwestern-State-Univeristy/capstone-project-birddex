package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
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
import com.google.firebase.firestore.Source;
import com.google.firebase.firestore.WriteBatch;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * ViewBirdCardActivity: Viewer for a finished bird card and its metadata/actions.
 */
public class ViewBirdCardActivity extends AppCompatActivity {

    private static final String TAG = "ViewBirdCard";
    public static final String EXTRA_ALLOW_IMAGE_CHANGE = "com.birddex.app.extra.ALLOW_IMAGE_CHANGE";

    private ImageView imgBird;
    private ImageButton btnFavoriteToggle;
    private Button btnChangeCardImage, btnBirdInfo, btnUpgradeCard;
    private TextView txtLocation, txtDateCaught;

    private String currentImageUrl, currentBirdId, currentState, currentLocality;
    private String currentSlotId, currentRarity;
    private Date currentCaughtDate;
    private boolean currentIsFavorite = false;
    private boolean isSavingFavorite = false;

    private ActivityResultLauncher<Intent> changeCardImageLauncher;
    private ActivityResultLauncher<Intent> upgradeCardLauncher;

    // FIX: Generation counter to ignore stale resolution callbacks
    private int resolutionGeneration = 0;
    private boolean isResolving = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarHelper.applyStandardNavBar(this);
        setContentView(R.layout.activity_view_bird_card);

        changeCardImageLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
            Intent data = result.getData();
            String url = data.getStringExtra(ChangeCardImageActivity.RESULT_IMAGE_URL);
            long ts = data.getLongExtra(ChangeCardImageActivity.RESULT_TIMESTAMP, -1L);
            String refId = data.getStringExtra(ChangeCardImageActivity.RESULT_USER_BIRD_REF_ID);

            if (isBlank(url)) {
                Toast.makeText(this, "Invalid selection.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (url.equals(currentImageUrl)) {
                Toast.makeText(this, "Already in use.", Toast.LENGTH_SHORT).show();
                return;
            }

            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;

            final int myGen = ++resolutionGeneration;
            isResolving = true;
            updateButtonsEnabled(false);

            resolveSelectionAndApply(
                    user.getUid(),
                    new ImageChoice(url, ts > 0 ? new Date(ts) : null, normalizeBlankToNull(refId)),
                    myGen
            );
        });

        // Setup launcher for UpgradeActivity to handle auto-update of UI
        upgradeCardLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                String newRarity = result.getData().getStringExtra(UpgradeActivity.EXTRA_NEW_RARITY);
                if (newRarity != null && !newRarity.equals(currentRarity)) {
                    currentRarity = newRarity;
                    refreshCardUI(); // Re-inflate layout and re-bind views
                }
            }
        });

        initUI();
    }

    private void initUI() {
        currentImageUrl = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_IMAGE_URL);
        currentBirdId = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_BIRD_ID);
        currentSlotId = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_SLOT_ID);
        currentRarity = CardRarityHelper.normalizeRarity(getIntent().getStringExtra(CollectionCardAdapter.EXTRA_RARITY));
        currentState = normalizeBlankToNull(getIntent().getStringExtra(CollectionCardAdapter.EXTRA_STATE));
        currentLocality = normalizeBlankToNull(getIntent().getStringExtra(CollectionCardAdapter.EXTRA_LOCALITY));
        currentIsFavorite = getIntent().getBooleanExtra(CollectionCardAdapter.EXTRA_IS_FAVORITE, false);
        long time = getIntent().getLongExtra(CollectionCardAdapter.EXTRA_CAUGHT_TIME, -1L);
        currentCaughtDate = time > 0 ? new Date(time) : null;

        refreshCardUI();

        btnChangeCardImage = findViewById(R.id.btnChangeCardImage);
        btnBirdInfo = findViewById(R.id.btnBirdInfo);
        btnUpgradeCard = findViewById(R.id.btnUpgradeCard);
        btnFavoriteToggle = findViewById(R.id.btnFavoriteToggle);

        boolean allowChange = getIntent().getBooleanExtra(EXTRA_ALLOW_IMAGE_CHANGE, true);
        if (!allowChange) btnChangeCardImage.setVisibility(View.GONE);

        if (isBlank(currentSlotId)) {
            btnFavoriteToggle.setVisibility(View.GONE);
        } else {
            btnFavoriteToggle.setOnClickListener(v -> toggleFavorite());
            refreshFavoriteState();
        }

        if (isBlank(currentBirdId)) {
            if (allowChange) {
                btnChangeCardImage.setEnabled(false);
                btnChangeCardImage.setText("No ID");
            }
            btnBirdInfo.setEnabled(false);
            btnUpgradeCard.setEnabled(false);
        } else {
            if (allowChange) btnChangeCardImage.setOnClickListener(v -> openImagePicker());
            btnBirdInfo.setOnClickListener(v ->
                    startActivity(new Intent(this, BirdWikiActivity.class)
                            .putExtra(BirdWikiActivity.EXTRA_BIRD_ID, currentBirdId)));

            if (isBlank(currentSlotId)) {
                btnUpgradeCard.setEnabled(false);
            } else {
                btnUpgradeCard.setOnClickListener(v -> openUpgradeScreen());
            }
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }

    /**
     * Completely refreshes the card UI by swapping the rarity layout and re-binding views.
     */
    private void refreshCardUI() {
        FrameLayout cardPlaceholder = findViewById(R.id.cardPlaceholder);
        if (cardPlaceholder != null) {
            cardPlaceholder.removeAllViews();
            getLayoutInflater().inflate(CardRarityHelper.getLayoutResId(currentRarity), cardPlaceholder, true);
        }

        imgBird = findViewById(R.id.imgBird);
        txtLocation = findViewById(R.id.txtLocation);
        txtDateCaught = findViewById(R.id.txtDateCaught);

        String name = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_COMMON_NAME);
        String sci = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_SCI_NAME);
        TextView txtBirdName = findViewById(R.id.txtBirdName);
        TextView txtScientific = findViewById(R.id.txtScientific);
        
        if (txtBirdName != null) txtBirdName.setText(!isBlank(name) ? name : (!isBlank(sci) ? sci : "Unknown Bird"));
        if (txtScientific != null) txtScientific.setText(!isBlank(sci) ? sci : "--");

        if (txtLocation != null) txtLocation.setText(CardFormatUtils.formatLocation(currentState, currentLocality));
        if (txtDateCaught != null) txtDateCaught.setText(CardFormatUtils.formatCaughtDate(currentCaughtDate));
        
        loadBirdImage(currentImageUrl);
        updateFavoriteUi(currentIsFavorite);
    }

    private void openUpgradeScreen() {
        Intent i = new Intent(this, UpgradeActivity.class);
        i.putExtra(CollectionCardAdapter.EXTRA_SLOT_ID, currentSlotId);
        i.putExtra(CollectionCardAdapter.EXTRA_BIRD_ID, currentBirdId);
        i.putExtra(CollectionCardAdapter.EXTRA_RARITY, currentRarity);
        i.putExtra(CollectionCardAdapter.EXTRA_IMAGE_URL, currentImageUrl);
        TextView txtBirdName = findViewById(R.id.txtBirdName);
        TextView txtScientific = findViewById(R.id.txtScientific);
        i.putExtra(CollectionCardAdapter.EXTRA_COMMON_NAME, txtBirdName != null ? txtBirdName.getText().toString() : "");
        i.putExtra(CollectionCardAdapter.EXTRA_SCI_NAME, txtScientific != null ? txtScientific.getText().toString() : "");
        i.putExtra(CollectionCardAdapter.EXTRA_STATE, currentState);
        i.putExtra(CollectionCardAdapter.EXTRA_LOCALITY, currentLocality);
        if (currentCaughtDate != null) i.putExtra(CollectionCardAdapter.EXTRA_CAUGHT_TIME, currentCaughtDate.getTime());
        
        upgradeCardLauncher.launch(i);
    }

    private void loadBirdImage(String url) {
        if (isFinishing() || isDestroyed() || imgBird == null) return;
        Glide.with(this)
                .load(url)
                .override(800, 800)
                .fitCenter()
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.bg_image_placeholder)
                .into(imgBird);
    }

    private void refreshFavoriteState() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || isBlank(currentSlotId)) return;

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .collection("collectionSlot")
                .document(currentSlotId)
                .get(Source.CACHE)
                .addOnSuccessListener(this::applyFavoriteSnapshot);

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .collection("collectionSlot")
                .document(currentSlotId)
                .get(Source.SERVER)
                .addOnSuccessListener(this::applyFavoriteSnapshot)
                .addOnFailureListener(e -> Log.w(TAG, "Failed to refresh favorite state.", e));
    }

    private void applyFavoriteSnapshot(@Nullable DocumentSnapshot snapshot) {
        if (snapshot == null || !snapshot.exists()) return;
        currentIsFavorite = Boolean.TRUE.equals(snapshot.getBoolean("isFavorite"));
        updateFavoriteUi(currentIsFavorite);
    }

    private void toggleFavorite() {
        if (isSavingFavorite || isBlank(currentSlotId)) return;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        final boolean targetValue = !currentIsFavorite;
        isSavingFavorite = true;
        if (btnFavoriteToggle != null) btnFavoriteToggle.setEnabled(false);

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .collection("collectionSlot")
                .document(currentSlotId)
                .update("isFavorite", targetValue)
                .addOnSuccessListener(unused -> {
                    currentIsFavorite = targetValue;
                    updateFavoriteUi(currentIsFavorite);
                    isSavingFavorite = false;
                    if (btnFavoriteToggle != null) btnFavoriteToggle.setEnabled(true);
                    Toast.makeText(this, currentIsFavorite ? "Added to favorites." : "Removed from favorites.", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    isSavingFavorite = false;
                    if (btnFavoriteToggle != null) btnFavoriteToggle.setEnabled(true);
                    Toast.makeText(this, "Failed to update favorite.", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Favorite toggle failed.", e);
                });
    }

    private void updateFavoriteUi(boolean isFavorite) {
        if (btnFavoriteToggle != null) {
            btnFavoriteToggle.setImageResource(isFavorite ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        }
    }

    private void openImagePicker() {
        if (isResolving) return;
        TextView txtBirdName = findViewById(R.id.txtBirdName);
        changeCardImageLauncher.launch(new Intent(this, ChangeCardImageActivity.class)
                .putExtra(ChangeCardImageActivity.EXTRA_BIRD_ID, currentBirdId)
                .putExtra(ChangeCardImageActivity.EXTRA_CURRENT_IMAGE_URL, currentImageUrl)
                .putExtra(ChangeCardImageActivity.EXTRA_COMMON_NAME, txtBirdName != null ? txtBirdName.getText().toString() : ""));
    }

    private void resolveSelectionAndApply(String uid, ImageChoice choice, int gen) {
        if (choice.userBirdRefId != null) {
            resolveFromUserBirdDocument(uid, choice, choice.userBirdRefId, gen);
            return;
        }

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("userBirdImage")
                .whereEqualTo("imageUrl", choice.imageUrl)
                .limit(1)
                .get()
                .addOnSuccessListener(snap -> {
                    if (gen != resolutionGeneration || isFinishing()) return;
                    if (!snap.isEmpty()) {
                        DocumentSnapshot d = snap.getDocuments().get(0);
                        resolveFromUserBirdDocument(uid, choice, d.getString("userBirdRefId"), gen);
                    } else {
                        resolveFromUserBirdByImage(uid, choice, gen);
                    }
                })
                .addOnFailureListener(e -> resolveFromUserBirdByImage(uid, choice, gen));
    }

    private void resolveFromUserBirdByImage(String uid, ImageChoice choice, int gen) {
        FirebaseFirestore.getInstance()
                .collection("userBirds")
                .whereEqualTo("imageUrl", choice.imageUrl)
                .limit(5)
                .get()
                .addOnSuccessListener(snap -> {
                    if (gen != resolutionGeneration || isFinishing()) return;
                    DocumentSnapshot found = null;
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        if (uid.equals(d.getString("userId")) && currentBirdId.equals(d.getString("birdSpeciesId"))) {
                            found = d;
                            break;
                        }
                    }
                    if (found == null) {
                        applyResolvedSelection(uid, new ResolvedSelection(choice.imageUrl, choice.timestamp, null, null, null), gen);
                    } else {
                        resolveFromLocation(uid, choice.imageUrl, found.getDate("timeSpotted"), found.getId(), found.getString("locationId"), gen);
                    }
                })
                .addOnFailureListener(e -> applyResolvedSelection(uid, new ResolvedSelection(choice.imageUrl, choice.timestamp, null, null, null), gen));
    }

    private void resolveFromUserBirdDocument(String uid, ImageChoice choice, String refId, int gen) {
        if (isBlank(refId)) {
            resolveFromUserBirdByImage(uid, choice, gen);
            return;
        }

        FirebaseFirestore.getInstance()
                .collection("userBirds")
                .document(refId)
                .get()
                .addOnSuccessListener(snap -> {
                    if (gen != resolutionGeneration || isFinishing()) return;
                    if (!snap.exists()) {
                        resolveFromUserBirdByImage(uid, choice, gen);
                    } else {
                        resolveFromLocation(uid, choice.imageUrl, snap.getDate("timeSpotted"), refId, snap.getString("locationId"), gen);
                    }
                })
                .addOnFailureListener(e -> resolveFromUserBirdByImage(uid, choice, gen));
    }

    private void resolveFromLocation(String uid, String url, Date ts, String refId, String locId, int gen) {
        if (isBlank(locId)) {
            applyResolvedSelection(uid, new ResolvedSelection(url, ts, refId, null, null), gen);
            return;
        }

        FirebaseFirestore.getInstance()
                .collection("locations")
                .document(locId)
                .get()
                .addOnSuccessListener(snap -> {
                    if (gen != resolutionGeneration || isFinishing()) return;
                    applyResolvedSelection(uid, new ResolvedSelection(url, ts, refId, snap.getString("state"), snap.getString("locality")), gen);
                })
                .addOnFailureListener(e -> applyResolvedSelection(uid, new ResolvedSelection(url, ts, refId, null, null), gen));
    }

    private void applyResolvedSelection(String uid, ResolvedSelection resolved, int gen) {
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("collectionSlot")
                .whereEqualTo("birdId", currentBirdId)
                .limit(1)
                .get()
                .addOnSuccessListener(snap -> {
                    if (gen != resolutionGeneration || isFinishing()) return;
                    if (snap.isEmpty()) {
                        finalizeResolution(false, null);
                        return;
                    }

                    WriteBatch b = FirebaseFirestore.getInstance().batch();
                    Map<String, Object> u = new HashMap<>();
                    u.put("imageUrl", resolved.imageUrl);
                    u.put("state", normalizeBlankToNull(resolved.state));
                    u.put("locality", normalizeBlankToNull(resolved.locality));
                    if (resolved.timestamp != null) u.put("timestamp", resolved.timestamp);
                    u.put("userBirdId", normalizeBlankToNull(resolved.userBirdRefId));

                    b.update(snap.getDocuments().get(0).getReference(), u);
                    b.commit()
                            .addOnSuccessListener(v -> {
                                if (gen == resolutionGeneration) finalizeResolution(true, resolved);
                            })
                            .addOnFailureListener(e -> finalizeResolution(false, null));
                })
                .addOnFailureListener(e -> finalizeResolution(false, null));
    }

    private void finalizeResolution(boolean success, @Nullable ResolvedSelection resolved) {
        isResolving = false;
        updateButtonsEnabled(true);

        if (success && resolved != null) {
            currentImageUrl = resolved.imageUrl;
            if (resolved.timestamp != null) currentCaughtDate = resolved.timestamp;
            currentState = normalizeBlankToNull(resolved.state);
            currentLocality = normalizeBlankToNull(resolved.locality);

            refreshCardUI();

            Toast.makeText(this, "Updated.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Failed to update card.", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateButtonsEnabled(boolean enabled) {
        if (btnChangeCardImage != null) btnChangeCardImage.setEnabled(enabled);
        if (btnBirdInfo != null) btnBirdInfo.setEnabled(enabled);
        if (btnUpgradeCard != null) btnUpgradeCard.setEnabled(enabled);
        if (btnFavoriteToggle != null && !isBlank(currentSlotId) && !isSavingFavorite) btnFavoriteToggle.setEnabled(enabled);
    }

    private boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    private String normalizeBlankToNull(String v) {
        return isBlank(v) ? null : v.trim();
    }

    private static class ImageChoice {
        final String imageUrl;
        final String userBirdRefId;
        final Date timestamp;

        ImageChoice(String u, Date t, String r) {
            this.imageUrl = u;
            this.timestamp = t;
            this.userBirdRefId = r;
        }
    }

    private static class ResolvedSelection {
        final String imageUrl;
        final String userBirdRefId;
        final String state;
        final String locality;
        final Date timestamp;

        ResolvedSelection(String u, Date t, String r, String s, String l) {
            this.imageUrl = u;
            this.timestamp = t;
            this.userBirdRefId = r;
            this.state = s;
            this.locality = l;
        }
    }
}
