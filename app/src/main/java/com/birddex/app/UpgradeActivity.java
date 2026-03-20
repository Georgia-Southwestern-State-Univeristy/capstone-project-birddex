package com.birddex.app;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
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
 * UpgradeActivity allows users to preview card rarities and spend points to upgrade their cards.
 */
public class UpgradeActivity extends AppCompatActivity {

    private static final String TAG = "UpgradeActivity";

    private String slotId, birdId, currentRarity, imageUrl;
    private String commonName, scientificName, state, locality;
    private long caughtTime;

    private TextView txtUserPoints;
    private ViewPager2 cardViewPager;
    private LinearLayout upgradeOptionsTopRow;
    private LinearLayout upgradeOptionsBottomRow;
    private ImageButton btnPrevRarity, btnNextRarity;
    private View loadingOverlay;

    private long userTotalPoints = 0L;
    private final List<String> availableRarities = CardRarityHelper.ORDER;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarHelper.applyStandardNavBar(this);
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
        currentRarity = CardRarityHelper.normalizeRarity(
                getIntent().getStringExtra(CollectionCardAdapter.EXTRA_RARITY)
        );
        imageUrl = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_IMAGE_URL);
        commonName = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_COMMON_NAME);
        scientificName = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_SCI_NAME);
        state = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_STATE);
        locality = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_LOCALITY);
        caughtTime = getIntent().getLongExtra(CollectionCardAdapter.EXTRA_CAUGHT_TIME, -1L);

        Log.d(TAG, "parseIntent: slotId=" + slotId + ", birdId=" + birdId + ", rarity=" + currentRarity);
    }

    private void initUI() {
        txtUserPoints = findViewById(R.id.txtUserPoints);
        cardViewPager = findViewById(R.id.cardViewPager);
        upgradeOptionsTopRow = findViewById(R.id.upgradeOptionsTopRow);
        upgradeOptionsBottomRow = findViewById(R.id.upgradeOptionsBottomRow);
        btnPrevRarity = findViewById(R.id.btnPrevRarity);
        btnNextRarity = findViewById(R.id.btnNextRarity);
        loadingOverlay = findViewById(R.id.loadingOverlay);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        btnPrevRarity.setOnClickListener(v -> {
            int current = cardViewPager.getCurrentItem();
            if (current > 0) {
                cardViewPager.setCurrentItem(current - 1, true);
            }
        });

        btnNextRarity.setOnClickListener(v -> {
            int current = cardViewPager.getCurrentItem();
            if (current < availableRarities.size() - 1) {
                cardViewPager.setCurrentItem(current + 1, true);
            }
        });

        cardViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updatePagerArrows(position);
            }
        });
    }

    private void loadUserPoints() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return;

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .addSnapshotListener((snapshot, e) -> {
                    if (e != null) {
                        Log.w(TAG, "Listen failed.", e);
                        return;
                    }

                    if (snapshot != null && snapshot.exists()) {
                        Long total = snapshot.getLong("totalPoints");
                        userTotalPoints = total != null ? total : 0L;
                        txtUserPoints.setText("Points: " + userTotalPoints);
                        setupUpgradeOptions();
                    }
                });
    }

    private void setupPreviews() {
        CardPreviewAdapter adapter = new CardPreviewAdapter(availableRarities);
        cardViewPager.setAdapter(adapter);
        cardViewPager.setOffscreenPageLimit(availableRarities.size());

        int startIndex = CardRarityHelper.getRarityIndex(currentRarity);
        if (startIndex < 0) {
            startIndex = 0;
        }

        cardViewPager.setCurrentItem(startIndex, false);
        updatePagerArrows(startIndex);
    }

    private void populateCardView(View rootView, String rarity) {
        TextView nameTxt = rootView.findViewById(R.id.txtBirdName);
        TextView sciTxt = rootView.findViewById(R.id.txtScientific);
        TextView locTxt = rootView.findViewById(R.id.txtLocation);
        TextView dateTxt = rootView.findViewById(R.id.txtDateCaught);
        ImageView img = rootView.findViewById(R.id.imgBird);
        TextView footer = rootView.findViewById(R.id.txtFooter);

        if (nameTxt != null) {
            nameTxt.setText(commonName != null ? commonName : "Unknown");
        }

        if (sciTxt != null) {
            sciTxt.setText(scientificName != null ? scientificName : "--");
            sciTxt.setVisibility(View.VISIBLE);
            sciTxt.setAlpha(1f);
        }

        if (locTxt != null) {
            locTxt.setText(CardFormatUtils.formatLocation(state, locality));
            locTxt.setVisibility(View.VISIBLE);
            locTxt.setAlpha(1f);
        }

        if (dateTxt != null) {
            dateTxt.setText(CardFormatUtils.formatCaughtDate(caughtTime > 0 ? new Date(caughtTime) : null));
            dateTxt.setVisibility(View.VISIBLE);
            dateTxt.setAlpha(1f);
        }

        if (img != null) {
            if (!TextUtils.isEmpty(imageUrl)) {
                Glide.with(this)
                        .load(imageUrl)
                        .placeholder(R.drawable.bg_image_placeholder)
                        .error(R.drawable.bg_image_placeholder)
                        .fitCenter()
                        .into(img);
            } else {
                img.setImageResource(R.drawable.bg_image_placeholder);
            }
        }

        if (footer != null) {
            footer.setVisibility(View.GONE);
        }

        fitPreviewCard(rootView);
    }

    /**
     * The text is already present on the card layouts.
     * The real issue is that the upgrade preview is too tall, so the lower stats area gets pushed
     * out of view. This scales the existing card just enough to show the scientific name, location,
     * and date without changing your XML layout.
     */
    private void fitPreviewCard(View previewRoot) {
        if (previewRoot == null) return;

        previewRoot.post(() -> {
            View card = previewRoot.findViewById(R.id.cardContainer);
            if (card == null) return;

            int availableWidth = previewRoot.getWidth() - dp(24);
            int availableHeight = previewRoot.getHeight() - dp(12);
            int cardWidth = card.getWidth();
            int cardHeight = card.getHeight();

            if (availableWidth <= 0 || availableHeight <= 0 || cardWidth <= 0 || cardHeight <= 0) {
                return;
            }

            float widthScale = (float) availableWidth / (float) cardWidth;
            float heightScale = (float) availableHeight / (float) cardHeight;
            float scale = Math.min(1f, Math.min(widthScale, heightScale));

            card.setPivotX(cardWidth / 2f);
            card.setPivotY(0f);
            card.setScaleX(scale);
            card.setScaleY(scale);
            card.setTranslationX(0f);

            float usedHeight = cardHeight * scale;
            float offsetY = Math.max(0f, (availableHeight - usedHeight) / 2f);
            card.setTranslationY(offsetY);
        });
    }

    private void setupUpgradeOptions() {
        upgradeOptionsTopRow.removeAllViews();
        upgradeOptionsBottomRow.removeAllViews();
        upgradeOptionsBottomRow.setVisibility(View.GONE);

        int currentIndex = CardRarityHelper.getRarityIndex(currentRarity);
        if (currentIndex < 0) currentIndex = 0;

        List<String> upgradeTargets = new ArrayList<>();
        for (int i = currentIndex + 1; i < availableRarities.size(); i++) {
            upgradeTargets.add(availableRarities.get(i));
        }

        if (upgradeTargets.isEmpty()) {
            View optionView = LayoutInflater.from(this)
                    .inflate(R.layout.item_upgrade_option, upgradeOptionsTopRow, false);

            TextView txtTarget = optionView.findViewById(R.id.txtTargetRarity);
            TextView txtCost = optionView.findViewById(R.id.txtUpgradeCost);

            txtTarget.setText("MAX LEVEL");
            txtCost.setText("Fully upgraded");
            optionView.setEnabled(false);
            optionView.setAlpha(0.75f);

            upgradeOptionsTopRow.addView(optionView, buildOptionLayoutParams());
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < upgradeTargets.size(); i++) {
            String targetRarity = upgradeTargets.get(i);
            int cost = CardRarityHelper.getUpgradeCost(currentRarity, targetRarity);

            View optionView = inflater.inflate(R.layout.item_upgrade_option, upgradeOptionsTopRow, false);
            TextView txtTarget = optionView.findViewById(R.id.txtTargetRarity);
            TextView txtCost = optionView.findViewById(R.id.txtUpgradeCost);

            txtTarget.setText(targetRarity.toUpperCase());
            txtCost.setText(cost + " pts");

            boolean canAfford = userTotalPoints >= cost;
            optionView.setEnabled(canAfford);
            optionView.setAlpha(canAfford ? 1f : 0.5f);

            if (canAfford) {
                optionView.setOnClickListener(v -> performUpgrade(targetRarity));
            }

            if (i < 3) {
                upgradeOptionsTopRow.addView(optionView, buildOptionLayoutParams());
            } else {
                upgradeOptionsBottomRow.setVisibility(View.VISIBLE);
                upgradeOptionsBottomRow.addView(optionView, buildOptionLayoutParams());
            }
        }
    }

    private LinearLayout.LayoutParams buildOptionLayoutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(106), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(5), dp(4), dp(5), dp(4));
        return lp;
    }

    private void performUpgrade(String targetRarity) {
        loadingOverlay.setVisibility(View.VISIBLE);

        Map<String, Object> data = new HashMap<>();
        data.put("slotId", slotId);
        data.put("targetRarity", targetRarity);

        FirebaseFunctions.getInstance()
                .getHttpsCallable("upgradeCollectionSlotRarity")
                .call(data)
                .addOnSuccessListener(result -> {
                    loadingOverlay.setVisibility(View.GONE);

                    currentRarity = targetRarity;

                    int newIndex = CardRarityHelper.getRarityIndex(targetRarity);
                    if (newIndex < 0) {
                        newIndex = 0;
                    }

                    cardViewPager.setCurrentItem(newIndex, true);
                    updatePagerArrows(newIndex);
                    setupUpgradeOptions();

                    Toast.makeText(this, "Card upgraded to " + targetRarity, Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    loadingOverlay.setVisibility(View.GONE);
                    Log.e(TAG, "Upgrade error", e);
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void updatePagerArrows(int position) {
        boolean canGoPrev = position > 0;
        boolean canGoNext = position < availableRarities.size() - 1;

        btnPrevRarity.setEnabled(canGoPrev);
        btnPrevRarity.setAlpha(canGoPrev ? 0.95f : 0.28f);

        btnNextRarity.setEnabled(canGoNext);
        btnNextRarity.setAlpha(canGoNext ? 0.95f : 0.28f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class CardPreviewAdapter extends RecyclerView.Adapter<CardPreviewAdapter.ViewHolder> {
        private final List<String> rarities;

        CardPreviewAdapter(List<String> rarities) {
            this.rarities = rarities;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(viewType, parent, false);
            v.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
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
        public int getItemCount() {
            return rarities.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ViewHolder(@NonNull View itemView) {
                super(itemView);
            }
        }
    }
}