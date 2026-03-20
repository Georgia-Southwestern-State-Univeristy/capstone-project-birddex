package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
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

/**
 * ViewBirdCardActivity: Viewer for a finished bird card and its metadata/actions.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class ViewBirdCardActivity extends AppCompatActivity {

    private static final String TAG = "ViewBirdCard";
    public static final String EXTRA_ALLOW_IMAGE_CHANGE = "com.birddex.app.extra.ALLOW_IMAGE_CHANGE";

    private FrameLayout cardPlaceholder;
    private View currentCardView;

    private ImageView imgBird;
    private Button btnChangeCardImage, btnBirdInfo, btnUpgradeCard;
    private TextView txtLocation, txtDateCaught;

    private String currentImageUrl, currentBirdId, currentState, currentLocality;
    private String currentSlotId, currentRarity;
    private Date currentCaughtDate;

    private ActivityResultLauncher<Intent> changeCardImageLauncher;

    // FIX: Generation counter to ignore stale resolution callbacks
    private int resolutionGeneration = 0;
    private boolean isResolving = false;

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
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

            // Give the user immediate feedback about the result of this action.
            if (isBlank(url)) { Toast.makeText(this, "Invalid selection.", Toast.LENGTH_SHORT).show(); return; }
            if (url.equals(currentImageUrl)) { Toast.makeText(this, "Already in use.", Toast.LENGTH_SHORT).show(); return; }

            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;

            final int myGen = ++resolutionGeneration;
            isResolving = true;
            updateButtonsEnabled(false);

            resolveSelectionAndApply(user.getUid(), new ImageChoice(url, ts > 0 ? new Date(ts) : null, normalizeBlankToNull(refId)), myGen);
        });

        initUI();
    }

    /**
     * Initializes helpers, adapters, listeners, or default values used by the rest of this file.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    private void initUI() {
        currentImageUrl = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_IMAGE_URL);
        currentBirdId = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_BIRD_ID);
        currentSlotId = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_SLOT_ID);
        currentRarity = CardRarityHelper.normalizeRarity(getIntent().getStringExtra(CollectionCardAdapter.EXTRA_RARITY));
        currentState = normalizeBlankToNull(getIntent().getStringExtra(CollectionCardAdapter.EXTRA_STATE));
        currentLocality = normalizeBlankToNull(getIntent().getStringExtra(CollectionCardAdapter.EXTRA_LOCALITY));
        long time = getIntent().getLongExtra(CollectionCardAdapter.EXTRA_CAUGHT_TIME, -1L);
        currentCaughtDate = time > 0 ? new Date(time) : null;

        // Bind or inflate the UI pieces this method needs before it can update the screen.
        cardPlaceholder = findViewById(R.id.cardPlaceholder);
        inflateCardLayoutForRarity(currentRarity);

        btnChangeCardImage = findViewById(R.id.btnChangeCardImage);
        btnBirdInfo = findViewById(R.id.btnBirdInfo);
        btnUpgradeCard = findViewById(R.id.btnUpgradeCard);

        String name = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_COMMON_NAME);
        String sci = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_SCI_NAME);
        ((TextView) currentCardView.findViewById(R.id.txtBirdName)).setText(!isBlank(name) ? name : (!isBlank(sci) ? sci : "Unknown Bird"));
        ((TextView) currentCardView.findViewById(R.id.txtScientific)).setText(!isBlank(sci) ? sci : "--");

        txtLocation.setText(CardFormatUtils.formatLocation(currentState, currentLocality));
        txtDateCaught.setText(CardFormatUtils.formatCaughtDate(currentCaughtDate));
        hideCardFooter();
        loadBirdImage(currentImageUrl);
        scheduleCardFit();

        boolean allowChange = getIntent().getBooleanExtra(EXTRA_ALLOW_IMAGE_CHANGE, true);
        if (!allowChange) btnChangeCardImage.setVisibility(View.GONE);

        if (isBlank(currentBirdId)) {
            if (allowChange) { btnChangeCardImage.setEnabled(false); btnChangeCardImage.setText("No ID"); }
            btnBirdInfo.setEnabled(false);
            btnUpgradeCard.setEnabled(false);
        } else {
            // Attach the user interaction that should run when this control is tapped.
            if (allowChange) btnChangeCardImage.setOnClickListener(v -> openImagePicker());
            // Move into the next screen and pass the identifiers/data that screen needs.
            btnBirdInfo.setOnClickListener(v -> startActivity(new Intent(this, BirdWikiActivity.class).putExtra(BirdWikiActivity.EXTRA_BIRD_ID, currentBirdId)));

            if (isBlank(currentSlotId)) {
                btnUpgradeCard.setEnabled(false);
            } else {
                btnUpgradeCard.setOnClickListener(v -> openUpgradeScreen());
            }
        }
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }

    /**
     * Moves the user to the upgrade screen and passes along the required extras.
     */
    private void openUpgradeScreen() {
        Intent i = new Intent(this, UpgradeActivity.class);
        i.putExtra(CollectionCardAdapter.EXTRA_SLOT_ID, currentSlotId);
        i.putExtra(CollectionCardAdapter.EXTRA_BIRD_ID, currentBirdId);
        i.putExtra(CollectionCardAdapter.EXTRA_RARITY, currentRarity);
        i.putExtra(CollectionCardAdapter.EXTRA_IMAGE_URL, currentImageUrl);
        i.putExtra(CollectionCardAdapter.EXTRA_COMMON_NAME, ((TextView) findViewById(R.id.txtBirdName)).getText().toString());
        i.putExtra(CollectionCardAdapter.EXTRA_SCI_NAME, ((TextView) findViewById(R.id.txtScientific)).getText().toString());
        i.putExtra(CollectionCardAdapter.EXTRA_STATE, currentState);
        i.putExtra(CollectionCardAdapter.EXTRA_LOCALITY, currentLocality);
        if (currentCaughtDate != null) i.putExtra(CollectionCardAdapter.EXTRA_CAUGHT_TIME, currentCaughtDate.getTime());
        startActivity(i);
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * Image loading happens here, which is why placeholder/error behavior for profile
     * photos/cards/posts usually traces back to this code path.
     */
    private void loadBirdImage(String url) {
        if (isFinishing() || isDestroyed()) return;
        // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
        Glide.with(this).load(url).override(800, 800).fitCenter().transition(DrawableTransitionOptions.withCrossFade()).placeholder(R.drawable.bg_image_placeholder).into(imgBird);
    }

    /**
     * Inflates one standalone viewer layout, then applies the current rarity styling in code.
     * This keeps the view card separate from the rarity XML files while still changing color
     * when the card is upgraded.
     */
    private void inflateCardLayoutForRarity(String rarity) {
        if (cardPlaceholder == null) return;

        cardPlaceholder.removeAllViews();
        currentCardView = getLayoutInflater().inflate(R.layout.view_bird_card, cardPlaceholder, false);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        currentCardView.setLayoutParams(params);
        cardPlaceholder.addView(currentCardView);

        imgBird = currentCardView.findViewById(R.id.imgBird);
        txtLocation = currentCardView.findViewById(R.id.txtLocation);
        txtDateCaught = currentCardView.findViewById(R.id.txtDateCaught);

        applyRarityStyling(rarity);
    }

    /**
     * Applies the correct card shell colors/background based on the current rarity while keeping
     * the viewer on a single shared XML layout.
     */
    private void applyRarityStyling(String rarity) {
        if (currentCardView == null) return;

        CardView cardContainer = currentCardView.findViewById(R.id.cardContainer);
        FrameLayout cardSurface = currentCardView.findViewById(R.id.cardSurface);
        TextView footer = currentCardView.findViewById(R.id.txtFooter);

        if (cardContainer == null || cardSurface == null) return;

        String normalized = CardRarityHelper.normalizeRarity(rarity);

        switch (normalized) {
            case CardRarityHelper.UNCOMMON:
                cardContainer.setCardBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent));
                cardSurface.setBackgroundResource(R.drawable.bg_uncommon_card);
                if (footer != null) {
                    footer.setBackgroundColor(ContextCompat.getColor(this, R.color.uncommon_green_dark));
                }
                break;

            case CardRarityHelper.RARE:
                cardContainer.setCardBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent));
                cardSurface.setBackgroundResource(R.drawable.bg_rare_card);
                if (footer != null) {
                    footer.setBackgroundColor(ContextCompat.getColor(this, R.color.rare_blue_dark));
                }
                break;

            case CardRarityHelper.EPIC:
                cardContainer.setCardBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent));
                cardSurface.setBackgroundResource(R.drawable.bg_epic_card);
                if (footer != null) {
                    footer.setBackgroundColor(ContextCompat.getColor(this, R.color.epic_purple_dark));
                }
                break;

            case CardRarityHelper.LEGENDARY:
                cardContainer.setCardBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent));
                cardSurface.setBackgroundResource(R.drawable.bg_legendary_card);
                if (footer != null) {
                    footer.setBackgroundColor(ContextCompat.getColor(this, R.color.legendary_gold_dark));
                }
                break;

            case CardRarityHelper.MYTHIC:
                cardContainer.setCardBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent));
                cardSurface.setBackgroundResource(R.drawable.bg_mythic_card);
                if (footer != null) {
                    footer.setBackgroundColor(ContextCompat.getColor(this, R.color.legendary_red_dark));
                }
                break;

            case CardRarityHelper.COMMON:
            default:
                cardContainer.setCardBackgroundColor(ContextCompat.getColor(this, android.R.color.white));
                cardSurface.setBackgroundColor(ContextCompat.getColor(this, android.R.color.white));
                if (footer != null) {
                    footer.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray));
                }
                break;
        }
    }

    /**
     * Hides the footer strip so the card keeps its rarity styling without wasting vertical space.
     */
    private void hideCardFooter() {
        if (currentCardView == null) return;
        TextView footer = currentCardView.findViewById(R.id.txtFooter);
        if (footer != null) footer.setVisibility(View.GONE);
    }

    /**
     * Scales the whole card uniformly so it stays on screen without changing its proportions.
     */
    private void scheduleCardFit() {
        if (cardPlaceholder == null || currentCardView == null) return;
        cardPlaceholder.post(this::fitCardToAvailableSpace);
    }

    /**
     * Applies a uniform scale so the card fits the available area while keeping the original shape.
     */
    private void fitCardToAvailableSpace() {
        if (cardPlaceholder == null || currentCardView == null) return;

        int availableWidth = cardPlaceholder.getWidth();
        int availableHeight = cardPlaceholder.getHeight();
        int measuredWidth = currentCardView.getMeasuredWidth();
        int measuredHeight = currentCardView.getMeasuredHeight();

        if (availableWidth <= 0 || availableHeight <= 0 || measuredWidth <= 0 || measuredHeight <= 0) {
            return;
        }

        float widthScale = (float) availableWidth / (float) measuredWidth;
        float heightScale = (float) availableHeight / (float) measuredHeight;
        float scale = Math.min(1f, Math.min(widthScale, heightScale));

        currentCardView.setPivotX(measuredWidth / 2f);
        currentCardView.setPivotY(0f);
        currentCardView.setScaleX(scale);
        currentCardView.setScaleY(scale);

        float usedHeight = measuredHeight * scale;
        float usedWidth = measuredWidth * scale;
        currentCardView.setTranslationY(Math.max(0f, (availableHeight - usedHeight) / 2f));
        currentCardView.setTranslationX((availableWidth - usedWidth) / 2f);
    }

    /**
     * Moves the user to another screen or flow and passes along the required extras.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    private void openImagePicker() {
        if (isResolving) return;
        changeCardImageLauncher.launch(new Intent(this, ChangeCardImageActivity.class)
                .putExtra(ChangeCardImageActivity.EXTRA_BIRD_ID, currentBirdId)
                .putExtra(ChangeCardImageActivity.EXTRA_CURRENT_IMAGE_URL, currentImageUrl)
                // Bind or inflate the UI pieces this method needs before it can update the screen.
                .putExtra(ChangeCardImageActivity.EXTRA_COMMON_NAME, ((TextView) findViewById(R.id.txtBirdName)).getText().toString()));
    }

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    private void resolveSelectionAndApply(String uid, ImageChoice choice, int gen) {
        if (choice.userBirdRefId != null) { resolveFromUserBirdDocument(uid, choice, choice.userBirdRefId, gen); return; }
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("userBirdImage").whereEqualTo("imageUrl", choice.imageUrl).limit(1).get()
                .addOnSuccessListener(snap -> {
                    if (gen != resolutionGeneration || isFinishing()) return;
                    if (!snap.isEmpty()) {
                        DocumentSnapshot d = snap.getDocuments().get(0);
                        resolveFromUserBirdDocument(uid, choice, d.getString("userBirdRefId"), gen);
                    } else resolveFromUserBirdByImage(uid, choice, gen);
                }).addOnFailureListener(e -> resolveFromUserBirdByImage(uid, choice, gen));
    }

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private void resolveFromUserBirdByImage(String uid, ImageChoice choice, int gen) {
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        FirebaseFirestore.getInstance().collection("userBirds").whereEqualTo("imageUrl", choice.imageUrl).limit(5).get()
                .addOnSuccessListener(snap -> {
                    if (gen != resolutionGeneration || isFinishing()) return;
                    DocumentSnapshot found = null;
                    for (DocumentSnapshot d : snap.getDocuments()) { if (uid.equals(d.getString("userId")) && currentBirdId.equals(d.getString("birdSpeciesId"))) { found = d; break; } }
                    if (found == null) applyResolvedSelection(uid, new ResolvedSelection(choice.imageUrl, choice.timestamp, null, null, null), gen);
                    else resolveFromLocation(uid, choice.imageUrl, found.getDate("timeSpotted"), found.getId(), found.getString("locationId"), gen);
                }).addOnFailureListener(e -> applyResolvedSelection(uid, new ResolvedSelection(choice.imageUrl, choice.timestamp, null, null, null), gen));
    }

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private void resolveFromUserBirdDocument(String uid, ImageChoice choice, String refId, int gen) {
        if (isBlank(refId)) { resolveFromUserBirdByImage(uid, choice, gen); return; }
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        FirebaseFirestore.getInstance().collection("userBirds").document(refId).get().addOnSuccessListener(snap -> {
            if (gen != resolutionGeneration || isFinishing()) return;
            if (!snap.exists()) resolveFromUserBirdByImage(uid, choice, gen);
            else resolveFromLocation(uid, choice.imageUrl, snap.getDate("timeSpotted"), refId, snap.getString("locationId"), gen);
        }).addOnFailureListener(e -> resolveFromUserBirdByImage(uid, choice, gen));
    }

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private void resolveFromLocation(String uid, String url, Date ts, String refId, String locId, int gen) {
        if (isBlank(locId)) { applyResolvedSelection(uid, new ResolvedSelection(url, ts, refId, null, null), gen); return; }
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        FirebaseFirestore.getInstance().collection("locations").document(locId).get().addOnSuccessListener(snap -> {
            if (gen != resolutionGeneration || isFinishing()) return;
            applyResolvedSelection(uid, new ResolvedSelection(url, ts, refId, snap.getString("state"), snap.getString("locality")), gen);
        }).addOnFailureListener(e -> applyResolvedSelection(uid, new ResolvedSelection(url, ts, refId, null, null), gen));
    }

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void applyResolvedSelection(String uid, ResolvedSelection resolved, int gen) {
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("collectionSlot").whereEqualTo("birdId", currentBirdId).limit(1).get()
                .addOnSuccessListener(snap -> {
                    if (gen != resolutionGeneration || isFinishing()) return;
                    if (snap.isEmpty()) { finalizeResolution(false, null); return; }
                    WriteBatch b = FirebaseFirestore.getInstance().batch();
                    Map<String, Object> u = new HashMap<>();
                    u.put("imageUrl", resolved.imageUrl); u.put("state", normalizeBlankToNull(resolved.state)); u.put("locality", normalizeBlankToNull(resolved.locality));
                    if (resolved.timestamp != null) u.put("timestamp", resolved.timestamp);
                    u.put("userBirdId", normalizeBlankToNull(resolved.userBirdRefId));
                    // Persist the new state so the action is saved outside the current screen.
                    b.update(snap.getDocuments().get(0).getReference(), u);
                    b.commit().addOnSuccessListener(v -> { if (gen == resolutionGeneration) finalizeResolution(true, resolved); })
                            .addOnFailureListener(e -> finalizeResolution(false, null));
                }).addOnFailureListener(e -> finalizeResolution(false, null));
    }

    /**
     * Main logic block for this part of the feature.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private void finalizeResolution(boolean success, @Nullable ResolvedSelection resolved) {
        isResolving = false;
        updateButtonsEnabled(true);
        if (success && resolved != null) {
            currentImageUrl = resolved.imageUrl; if (resolved.timestamp != null) currentCaughtDate = resolved.timestamp;
            currentState = normalizeBlankToNull(resolved.state); currentLocality = normalizeBlankToNull(resolved.locality);
            loadBirdImage(currentImageUrl);
            txtLocation.setText(CardFormatUtils.formatLocation(currentState, currentLocality));
            txtDateCaught.setText(CardFormatUtils.formatCaughtDate(currentCaughtDate));
            scheduleCardFit();
            // Give the user immediate feedback about the result of this action.
            Toast.makeText(this, "Updated.", Toast.LENGTH_SHORT).show();
        } else Toast.makeText(this, "Failed to update card.", Toast.LENGTH_SHORT).show();
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     */
    private void updateButtonsEnabled(boolean enabled) {
        if (btnChangeCardImage != null) btnChangeCardImage.setEnabled(enabled);
        if (btnBirdInfo != null) btnBirdInfo.setEnabled(enabled);
        if (btnUpgradeCard != null) btnUpgradeCard.setEnabled(enabled);
    }

    private boolean isBlank(String v) { return v == null || v.trim().isEmpty(); }
    private String normalizeBlankToNull(String v) { return isBlank(v) ? null : v.trim(); }

    private static class ImageChoice {
        final String imageUrl, userBirdRefId; final Date timestamp;
        ImageChoice(String u, Date t, String r) { this.imageUrl = u; this.timestamp = t; this.userBirdRefId = r; }
    }
    private static class ResolvedSelection {
        final String imageUrl, userBirdRefId, state, locality; final Date timestamp;
        ResolvedSelection(String u, Date t, String r, String s, String l) { this.imageUrl = u; this.timestamp = t; this.userBirdRefId = r; this.state = s; this.locality = l; }
    }
}