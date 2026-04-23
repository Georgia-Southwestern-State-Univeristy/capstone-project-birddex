package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LeaderboardActivity extends AppCompatActivity {

    private FirebaseManager firebaseManager;
    private LeaderboardAdapter adapter;

    private ProgressBar progressBar;
    private TextView tvEmpty;

    private NestedScrollView leaderboardScroll;
    private View podiumSection;
    private TextView tvListHeader;
    private RecyclerView rvLeaderboard;

    private View cardFirst;
    private View cardSecond;
    private View cardThird;

    private ShapeableImageView ivFirst;
    private ShapeableImageView ivSecond;
    private ShapeableImageView ivThird;

    private TextView tvFirstName;
    private TextView tvFirstPoints;
    private TextView tvSecondName;
    private TextView tvSecondPoints;
    private TextView tvThirdName;
    private TextView tvThirdPoints;

    private View firstPedestal;
    private View secondPedestal;
    private View thirdPedestal;

    private int firstPedestalBaseHeight;
    private int secondPedestalBaseHeight;
    private int thirdPedestalBaseHeight;

    private final NumberFormat numberFormat = NumberFormat.getInstance(Locale.US);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_leaderboard);

        firebaseManager = new FirebaseManager(this);

        Toolbar toolbar = findViewById(R.id.toolbarLeaderboard);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        progressBar = findViewById(R.id.progressLeaderboard);
        tvEmpty = findViewById(R.id.tvLeaderboardEmpty);

        leaderboardScroll = findViewById(R.id.leaderboardScroll);
        podiumSection = findViewById(R.id.podiumSection);
        tvListHeader = findViewById(R.id.tvListHeader);
        rvLeaderboard = findViewById(R.id.rvLeaderboard);

        cardFirst = findViewById(R.id.cardFirstPlace);
        cardSecond = findViewById(R.id.cardSecondPlace);
        cardThird = findViewById(R.id.cardThirdPlace);

        ivFirst = findViewById(R.id.ivFirstPlace);
        ivSecond = findViewById(R.id.ivSecondPlace);
        ivThird = findViewById(R.id.ivThirdPlace);

        tvFirstName = findViewById(R.id.tvFirstName);
        tvFirstPoints = findViewById(R.id.tvFirstPoints);
        tvSecondName = findViewById(R.id.tvSecondName);
        tvSecondPoints = findViewById(R.id.tvSecondPoints);
        tvThirdName = findViewById(R.id.tvThirdName);
        tvThirdPoints = findViewById(R.id.tvThirdPoints);

        firstPedestal = findViewById(R.id.viewFirstPedestal);
        secondPedestal = findViewById(R.id.viewSecondPedestal);
        thirdPedestal = findViewById(R.id.viewThirdPedestal);

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        String currentUserId = currentUser != null ? currentUser.getUid() : null;

        adapter = new LeaderboardAdapter(currentUserId, this::openUserProfile);
        rvLeaderboard.setLayoutManager(new LinearLayoutManager(this));
        rvLeaderboard.setAdapter(adapter);
        rvLeaderboard.setNestedScrollingEnabled(false);

        podiumSection.post(() -> {
            firstPedestalBaseHeight = firstPedestal.getHeight();
            secondPedestalBaseHeight = secondPedestal.getHeight();
            thirdPedestalBaseHeight = thirdPedestal.getHeight();
            setupPodiumCollapseOnScroll();
        });

        loadLeaderboard();
    }

    private void setupPodiumCollapseOnScroll() {
        if (leaderboardScroll == null || podiumSection == null) return;

        leaderboardScroll.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener)
                (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {

                    int collapseDistance = dpToPx(240);
                    float raw = Math.min(1f, Math.max(0f, scrollY / (float) collapseDistance));

                    // Ease-out so the movement feels natural
                    float progress = 1f - (float) Math.pow(1f - raw, 2f);

                    // Only move upward + slight fade. No X/Y scaling on whole podium.
                    float translateY = -dpToPx(72) * progress;
                    float alpha = 1f - (0.28f * progress);

                    podiumSection.setTranslationY(translateY);
                    podiumSection.setAlpha(alpha);

                    // Keep width stable so it doesn't feel like it slides sideways
                    podiumSection.setScaleX(1f);
                    podiumSection.setScaleY(1f);

                    // Shrink the pedestal blocks vertically
                    resizePedestal(firstPedestal, firstPedestalBaseHeight, progress, 0.42f);
                    resizePedestal(secondPedestal, secondPedestalBaseHeight, progress, 0.48f);
                    resizePedestal(thirdPedestal, thirdPedestalBaseHeight, progress, 0.54f);
                });
    }

    private void resizePedestal(View pedestal, int baseHeight, float progress, float collapseFraction) {
        if (pedestal == null || baseHeight <= 0) return;

        int minHeight = Math.round(baseHeight * 0.45f);
        int targetHeight = Math.max(
                minHeight,
                Math.round(baseHeight * (1f - (collapseFraction * progress)))
        );

        ViewGroup.LayoutParams lp = pedestal.getLayoutParams();
        if (lp != null && lp.height != targetHeight) {
            lp.height = targetHeight;
            pedestal.setLayoutParams(lp);
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void loadLeaderboard() {
        progressBar.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        podiumSection.setVisibility(View.GONE);
        tvListHeader.setVisibility(View.GONE);
        rvLeaderboard.setVisibility(View.GONE);

        firebaseManager.getLeaderboard(new FirebaseManager.LeaderboardListener() {
            @Override
            public void onDataLoaded(List<Map<String, Object>> leaderboard) {
                progressBar.setVisibility(View.GONE);

                List<LeaderboardEntry> entries = mapEntries(leaderboard);

                if (entries.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    podiumSection.setVisibility(View.GONE);
                    tvListHeader.setVisibility(View.GONE);
                    rvLeaderboard.setVisibility(View.GONE);
                    return;
                }

                bindPodium(entries);

                boolean hasRemainingEntries = entries.size() > 3;
                if (hasRemainingEntries) {
                    adapter.setEntries(entries.subList(3, entries.size()));
                } else {
                    adapter.setEntries(new ArrayList<>());
                }

                podiumSection.setVisibility(View.VISIBLE);
                tvListHeader.setVisibility(hasRemainingEntries ? View.VISIBLE : View.GONE);
                rvLeaderboard.setVisibility(hasRemainingEntries ? View.VISIBLE : View.GONE);
                tvEmpty.setVisibility(View.GONE);
            }

            @Override
            public void onError(String error) {
                progressBar.setVisibility(View.GONE);
                tvEmpty.setVisibility(View.VISIBLE);
                tvEmpty.setText(error != null && !error.trim().isEmpty()
                        ? error
                        : "Unable to load leaderboard right now.");

                podiumSection.setVisibility(View.GONE);
                tvListHeader.setVisibility(View.GONE);
                rvLeaderboard.setVisibility(View.GONE);
            }
        });
    }

    private List<LeaderboardEntry> mapEntries(List<Map<String, Object>> rawLeaderboard) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        if (rawLeaderboard == null) return entries;

        for (int i = 0; i < rawLeaderboard.size(); i++) {
            Map<String, Object> map = rawLeaderboard.get(i);
            if (map == null) continue;

            String userId = asString(map.get("id"));
            String username = asString(map.get("username"));
            String profilePictureUrl = asString(map.get("profilePictureUrl"));
            long totalPoints = asLong(map.get("totalPoints"));

            entries.add(new LeaderboardEntry(
                    userId,
                    (username == null || username.trim().isEmpty()) ? "BirdDex User" : username.trim(),
                    totalPoints,
                    profilePictureUrl,
                    i + 1
            ));
        }

        return entries;
    }

    private void bindPodium(List<LeaderboardEntry> entries) {
        bindPodiumCard(entries.size() > 0 ? entries.get(0) : null, cardFirst, ivFirst, tvFirstName, tvFirstPoints);
        bindPodiumCard(entries.size() > 1 ? entries.get(1) : null, cardSecond, ivSecond, tvSecondName, tvSecondPoints);
        bindPodiumCard(entries.size() > 2 ? entries.get(2) : null, cardThird, ivThird, tvThirdName, tvThirdPoints);
    }

    private void bindPodiumCard(@Nullable LeaderboardEntry entry,
                                View card,
                                ShapeableImageView imageView,
                                TextView tvName,
                                TextView tvPoints) {
        if (entry == null) {
            card.setVisibility(View.INVISIBLE);
            return;
        }

        card.setVisibility(View.VISIBLE);
        tvName.setText(entry.getUsername());
        tvPoints.setText(numberFormat.format(entry.getTotalPoints()) + " pts");

        Glide.with(this)
                .load(entry.getProfilePictureUrl())
                .placeholder(R.drawable.ic_profile)
                .error(R.drawable.ic_profile)
                .into(imageView);

        card.setOnClickListener(v -> openUserProfile(entry));
    }

    private void openUserProfile(LeaderboardEntry entry) {
        if (entry == null || entry.getUserId() == null || entry.getUserId().trim().isEmpty()) return;

        Intent intent = new Intent(this, UserSocialProfileActivity.class);
        intent.putExtra(UserSocialProfileActivity.EXTRA_USER_ID, entry.getUserId());
        startActivity(intent);
    }

    @Nullable
    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) return 0L;

        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0L;
        }
    }
}