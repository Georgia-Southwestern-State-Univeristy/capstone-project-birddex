package com.birddex.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.functions.FirebaseFunctions;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UpgradeActivity allows users to preview card rarities and spend or recover points
 * by upgrading or reverting their cards.
 */
public class UpgradeActivity extends AppCompatActivity {

    private static final String TAG = "UpgradeActivity";
    public static final String EXTRA_NEW_RARITY = "new_rarity";

    private String slotId, birdId, currentRarity, imageUrl;
    private String commonName, scientificName, state, locality;
    private long caughtTime;

    private TextView txtUserPoints;
    private ViewPager2 cardViewPager;
    private LinearLayout upgradeOptionsHorizontalContainer;
    private ImageButton btnPrevRarity, btnNextRarity;
    private View loadingOverlay;

    private long userTotalPoints = 0;
    private List<String> availableRarities = CardRarityHelper.ORDER;
    private boolean isUpgradeConfirmationShowing = false;
    private boolean isUpgradeInFlight = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_upgrade_card);

        parseIntent();
        initUI();
        loadUserPoints();
        setupPreviews();
        setupUpgradeOptions();
    }

    private void parseIntent() {
        slotId = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_SLOT_ID);
        birdId = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_BIRD_ID);
        currentRarity = CardRarityHelper.normalizeRarity(getIntent().getStringExtra(CollectionCardAdapter.EXTRA_RARITY));
        imageUrl = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_IMAGE_URL);
        commonName = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_COMMON_NAME);
        scientificName = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_SCI_NAME);
        state = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_STATE);
        locality = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_LOCALITY);
        caughtTime = getIntent().getLongExtra(CollectionCardAdapter.EXTRA_CAUGHT_TIME, -1L);
    }

    private void initUI() {
        txtUserPoints = findViewById(R.id.txtUserPoints);
        cardViewPager = findViewById(R.id.cardViewPager);
        upgradeOptionsHorizontalContainer = findViewById(R.id.upgradeOptionsHorizontalContainer);
        btnPrevRarity = findViewById(R.id.btnPrevRarity);
        btnNextRarity = findViewById(R.id.btnNextRarity);
        loadingOverlay = findViewById(R.id.loadingOverlay);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        btnPrevRarity.setOnClickListener(v -> {
            int current = cardViewPager.getCurrentItem();
            if (current > 0) cardViewPager.setCurrentItem(current - 1);
        });

        btnNextRarity.setOnClickListener(v -> {
            int current = cardViewPager.getCurrentItem();
            if (current < availableRarities.size() - 1) cardViewPager.setCurrentItem(current + 1);
        });
    }

    private void loadUserPoints() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return;

        FirebaseFirestore.getInstance().collection("users").document(uid)
                .addSnapshotListener(this, (snapshot, e) -> {
                    if (e != null) return;
                    if (snapshot != null && snapshot.exists()) {
                        userTotalPoints = snapshot.getLong("totalPoints") != null ? snapshot.getLong("totalPoints") : 0;
                        txtUserPoints.setText("Points: " + userTotalPoints);
                        setupUpgradeOptions();
                    }
                });
    }

    private void setupPreviews() {
        CardPreviewAdapter adapter = new CardPreviewAdapter(availableRarities);
        cardViewPager.setAdapter(adapter);
        int startIndex = CardRarityHelper.getRarityIndex(currentRarity);
        if (startIndex >= 0) {
            cardViewPager.setCurrentItem(startIndex, false);
        }
    }

    private void populateCardView(View v, String rarity) {
        TextView nameTxt = v.findViewById(R.id.txtBirdName);
        TextView sciTxt = v.findViewById(R.id.txtScientific);
        TextView locTxt = v.findViewById(R.id.txtLocation);
        TextView dateTxt = v.findViewById(R.id.txtDateCaught);
        ImageView img = v.findViewById(R.id.imgBird);

        if (nameTxt != null) nameTxt.setText(commonName != null ? commonName : "Unknown");
        if (sciTxt != null) sciTxt.setText(scientificName != null ? scientificName : "--");
        if (locTxt != null) locTxt.setText(CardFormatUtils.formatLocation(state, locality));
        if (dateTxt != null) dateTxt.setText(CardFormatUtils.formatCardViewerDate(caughtTime > 0 ? new Date(caughtTime) : null));

        if (img != null && imageUrl != null) {
            Glide.with(this).load(imageUrl).into(img);
        }

        TextView footer = v.findViewById(R.id.txtFooter);
        if (footer != null) {
            footer.setVisibility(View.VISIBLE);
            footer.setText(rarity.toUpperCase());
        }
    }

    private void setupUpgradeOptions() {
        upgradeOptionsHorizontalContainer.removeAllViews();
        int currentIndex = CardRarityHelper.getRarityIndex(currentRarity);
        LayoutInflater inflater = LayoutInflater.from(this);
        float density = getResources().getDisplayMetrics().density;

        for (int i = 0; i < availableRarities.size(); i++) {
            String targetRarity = availableRarities.get(i);

            View optionView = inflater.inflate(R.layout.item_upgrade_option, upgradeOptionsHorizontalContainer, false);
            TextView txtTarget = optionView.findViewById(R.id.txtTargetRarity);
            TextView txtCost = optionView.findViewById(R.id.txtUpgradeCost);

            txtTarget.setText(targetRarity.toUpperCase());

            if (i < currentIndex) {
                // REVERT Option
                int refund = CardRarityHelper.getDowngradeRefund(currentRarity, targetRarity);
                txtCost.setText("Revert (+" + refund + " pts)");
                txtCost.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark));
                optionView.setOnClickListener(v -> showConfirmRevertDialog(targetRarity, refund));
            } else if (i == currentIndex) {
                // CURRENT Rarity
                txtCost.setText("CURRENT");
                txtCost.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
                optionView.setEnabled(false);
                optionView.setAlpha(0.8f);
            } else {
                // UPGRADE Option
                int cost = CardRarityHelper.getUpgradeCost(currentRarity, targetRarity);
                txtCost.setText("Upgrade (" + cost + " pts)");
                txtCost.setTextColor(ContextCompat.getColor(this, R.color.uncommon_green));

                boolean canAfford = userTotalPoints >= cost;
                optionView.setEnabled(canAfford);
                optionView.setAlpha(canAfford ? 1.0f : 0.5f);
                if (canAfford) {
                    optionView.setOnClickListener(v -> showUpgradeConfirmation(targetRarity, cost));
                }
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    (int) (140 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, (int) (12 * density), 0);
            optionView.setLayoutParams(lp);
            upgradeOptionsHorizontalContainer.addView(optionView);
        }
    }

    private void showUpgradeConfirmation(String targetRarity, int cost) {
        if (isUpgradeConfirmationShowing || isUpgradeInFlight) return;
        isUpgradeConfirmationShowing = true;

        String fromRarityLabel = CardRarityHelper.normalizeRarity(currentRarity).toUpperCase();
        String toRarityLabel = CardRarityHelper.normalizeRarity(targetRarity).toUpperCase();

        new AlertDialog.Builder(this)
                .setTitle("Confirm Upgrade")
                .setMessage("Upgrade this card from " + fromRarityLabel + " to " + toRarityLabel
                        + " for " + cost + " points?")
                .setNegativeButton("Cancel", (dialog, which) -> isUpgradeConfirmationShowing = false)
                .setPositiveButton("Upgrade", (dialog, which) -> {
                    isUpgradeConfirmationShowing = false;
                    performUpgrade(targetRarity);
                })
                .setOnDismissListener(dialog -> isUpgradeConfirmationShowing = false)
                .show();
    }

    private void showConfirmRevertDialog(String targetRarity, int refund) {
        new AlertDialog.Builder(this)
                .setTitle("Revert Card Rarity?")
                .setMessage("Are you sure you want to revert this card to " + targetRarity.toUpperCase() + "? You will receive a refund of " + refund + " points (75% of spent points).")
                .setPositiveButton("Revert", (dialog, which) -> performRevert(targetRarity))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performUpgrade(String targetRarity) {
        callBackendUpgradeFunction("upgradeCollectionSlotRarity", targetRarity);
    }

    private void performRevert(String targetRarity) {
        callBackendUpgradeFunction("revertCollectionSlotRarity", targetRarity);
    }

    private void callBackendUpgradeFunction(String functionName, String targetRarity) {
        if (isUpgradeInFlight) return;
        isUpgradeInFlight = true;
        loadingOverlay.setVisibility(View.VISIBLE);
        Map<String, Object> data = new HashMap<>();
        data.put("slotId", slotId);
        data.put("targetRarity", targetRarity);

        FirebaseFunctions.getInstance()
                .getHttpsCallable(functionName)
                .call(data)
                .addOnSuccessListener(result -> {
                    isUpgradeInFlight = false;
                    loadingOverlay.setVisibility(View.GONE);
                    currentRarity = targetRarity;
                    MessagePopupHelper.show(this, "Success: Card rarity updated!");

                    Intent resultIntent = new Intent();
                    resultIntent.putExtra(EXTRA_NEW_RARITY, targetRarity);
                    setResult(Activity.RESULT_OK, resultIntent);

                    int newIndex = CardRarityHelper.getRarityIndex(targetRarity);
                    if (newIndex >= 0) cardViewPager.setCurrentItem(newIndex, true);
                    setupUpgradeOptions();
                })
                .addOnFailureListener(e -> {
                    isUpgradeInFlight = false;
                    loadingOverlay.setVisibility(View.GONE);
                    Log.e(TAG, "Backend call failed", e);
                    MessagePopupHelper.show(this, "Error: " + e.getMessage());
                });
    }

    private class CardPreviewAdapter extends RecyclerView.Adapter<CardPreviewAdapter.ViewHolder> {
        private final List<String> rarities;
        CardPreviewAdapter(List<String> rarities) { this.rarities = rarities; }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(viewType, parent, false);
            v.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            populateCardView(holder.itemView, rarities.get(position));
        }

        @Override
        public int getItemViewType(int position) {
            return CardRarityHelper.getLayoutResId(rarities.get(position));
        }

        @Override
        public int getItemCount() { return rarities.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            ViewHolder(@NonNull View itemView) { super(itemView); }
        }
    }
}
