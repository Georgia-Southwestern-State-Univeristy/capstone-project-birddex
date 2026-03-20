package com.birddex.app;

import android.os.Bundle;
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
    private LinearLayout upgradeOptionsHorizontalContainer;
    private ImageButton btnPrevRarity, btnNextRarity;
    private View loadingOverlay;

    private long userTotalPoints = 0;
    private List<String> availableRarities = CardRarityHelper.ORDER;

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
        
        Log.d(TAG, "parseIntent: slotId=" + slotId + ", birdId=" + birdId + ", rarity=" + currentRarity);
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
                .addSnapshotListener((snapshot, e) -> {
                    if (e != null) {
                        Log.w(TAG, "Listen failed.", e);
                        return;
                    }
                    if (snapshot != null && snapshot.exists()) {
                        userTotalPoints = snapshot.getLong("totalPoints") != null ? snapshot.getLong("totalPoints") : 0;
                        txtUserPoints.setText("Points: " + userTotalPoints);
                        setupUpgradeOptions(); // Refresh buttons based on points
                    }
                });
    }

    private void setupPreviews() {
        CardPreviewAdapter adapter = new CardPreviewAdapter(availableRarities);
        cardViewPager.setAdapter(adapter);
        
        // Start at current rarity
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
        if (dateTxt != null) dateTxt.setText(CardFormatUtils.formatCaughtDate(caughtTime > 0 ? new Date(caughtTime) : null));
        
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

        for (int i = currentIndex + 1; i < availableRarities.size(); i++) {
            String targetRarity = availableRarities.get(i);
            int cost = CardRarityHelper.getUpgradeCost(currentRarity, targetRarity);

            View optionView = inflater.inflate(R.layout.item_upgrade_option, upgradeOptionsHorizontalContainer, false);
            TextView txtTarget = optionView.findViewById(R.id.txtTargetRarity);
            TextView txtCost = optionView.findViewById(R.id.txtUpgradeCost);

            txtTarget.setText(targetRarity.toUpperCase());
            txtCost.setText(cost + " pts");

            boolean canAfford = userTotalPoints >= cost;
            optionView.setEnabled(canAfford);
            optionView.setAlpha(canAfford ? 1.0f : 0.5f);

            if (canAfford) {
                optionView.setOnClickListener(v -> performUpgrade(targetRarity, cost));
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    (int) (120 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, (int) (12 * density), 0);
            optionView.setLayoutParams(lp);

            upgradeOptionsHorizontalContainer.addView(optionView);
        }
        
        if (upgradeOptionsHorizontalContainer.getChildCount() == 0) {
            TextView tv = new TextView(this);
            tv.setText("Max Level");
            tv.setPadding(32, 16, 32, 16);
            upgradeOptionsHorizontalContainer.addView(tv);
        }
    }

    private void performUpgrade(String targetRarity, int cost) {
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
                    Toast.makeText(this, "Card upgraded to " + targetRarity, Toast.LENGTH_SHORT).show();
                    setupUpgradeOptions();
                })
                .addOnFailureListener(e -> {
                    loadingOverlay.setVisibility(View.GONE);
                    Log.e(TAG, "Upgrade error", e);
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
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
            // Fix: ViewPager2 children must have match_parent layout params to avoid IllegalStateException
            v.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 
                    ViewGroup.LayoutParams.MATCH_PARENT));
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