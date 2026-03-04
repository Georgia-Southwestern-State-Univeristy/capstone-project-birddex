package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class UserSocialProfileActivity extends AppCompatActivity implements 
        ForumPostAdapter.OnPostClickListener,
        FavoritesAdapter.OnFavoriteInteractionListener {

    private static final String TAG = "UserSocialProfile";
    public static final String EXTRA_USER_ID = "extra_user_id";
    private static final int FAVORITE_SLOT_COUNT = 3;

    private ShapeableImageView ivPfp;
    private TextView tvUsername, tvBio;
    private TextView tvPostCount, tvFollowerCount, tvFollowingCount;
    private LinearLayout btnUserFollowers, btnUserFollowing;
    private MaterialButton btnFollow;
    private RecyclerView rvPosts, rvFavoriteCards;
    private TabLayout tabLayout;
    private TextView tvProfileTabEmpty;
    private AppBarLayout appBarLayout;
    private View profileHeader;

    private FirebaseFirestore db;
    private FirebaseManager firebaseManager;
    private ForumPostAdapter adapter;
    private FavoritesAdapter favoritesAdapter;
    
    private String targetUserId;
    private boolean isFollowing = false;
    private ListenerRegistration postsListener;

    private final List<String> favoriteCardKeys = new ArrayList<>();
    private final List<CollectionSlot> allCollectionSlots = new ArrayList<>();
    private final List<ForumPost> currentPosts = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_social_profile);

        targetUserId = getIntent().getStringExtra(EXTRA_USER_ID);
        if (targetUserId == null) {
            Toast.makeText(this, "User ID not found.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        db = FirebaseFirestore.getInstance();
        firebaseManager = new FirebaseManager(this);

        initUI();
        loadUserDetails();
        checkFollowingStatus();
        setupRecyclerViews();
        listenForUserPosts();
    }

    private void initUI() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        ivPfp = findViewById(R.id.ivUserProfilePfp);
        tvUsername = findViewById(R.id.tvUserProfileUsername);
        tvBio = findViewById(R.id.tvUserProfileBio);
        tvPostCount = findViewById(R.id.tvUserPostCount);
        tvFollowerCount = findViewById(R.id.tvUserFollowerCount);
        tvFollowingCount = findViewById(R.id.tvUserFollowingCount);
        btnUserFollowers = findViewById(R.id.btnUserFollowers);
        btnUserFollowing = findViewById(R.id.btnUserFollowing);
        btnFollow = findViewById(R.id.btnUserFollow);
        rvPosts = findViewById(R.id.rvUserProfilePosts);
        rvFavoriteCards = findViewById(R.id.rvFavoriteCards);
        tabLayout = findViewById(R.id.profileTabLayout);
        tvProfileTabEmpty = findViewById(R.id.tvProfileTabEmpty);
        appBarLayout = findViewById(R.id.appBarLayout);
        profileHeader = findViewById(R.id.profileHeader);

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null && currentUser.getUid().equals(targetUserId)) {
            btnFollow.setVisibility(View.GONE);
        }

        btnFollow.setOnClickListener(v -> toggleFollow());

        // Click listeners for counts
        btnUserFollowers.setOnClickListener(v -> {
            Intent intent = new Intent(this, SocialActivity.class);
            intent.putExtra(SocialActivity.EXTRA_USER_ID, targetUserId);
            intent.putExtra(SocialActivity.EXTRA_SHOW_FOLLOWING, false);
            startActivity(intent);
        });

        btnUserFollowing.setOnClickListener(v -> {
            Intent intent = new Intent(this, SocialActivity.class);
            intent.putExtra(SocialActivity.EXTRA_USER_ID, targetUserId);
            intent.putExtra(SocialActivity.EXTRA_SHOW_FOLLOWING, true);
            startActivity(intent);
        });

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                applyTabState(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
        
        applyTabState(0);
    }

    private void applyTabState(int position) {
        Toolbar toolbar = findViewById(R.id.toolbar);
        AppBarLayout.LayoutParams toolbarParams = (AppBarLayout.LayoutParams) toolbar.getLayoutParams();
        AppBarLayout.LayoutParams headerParams = (AppBarLayout.LayoutParams) profileHeader.getLayoutParams();

        if (position == 0) {
            rvFavoriteCards.setVisibility(View.VISIBLE);
            rvPosts.setVisibility(View.GONE);
            tvProfileTabEmpty.setVisibility(View.GONE);

            toolbarParams.setScrollFlags(0);
            headerParams.setScrollFlags(0);
            appBarLayout.setExpanded(true, true);
        } else {
            rvFavoriteCards.setVisibility(View.GONE);
            if (currentPosts.isEmpty()) {
                tvProfileTabEmpty.setVisibility(View.VISIBLE);
                rvPosts.setVisibility(View.GONE);
            } else {
                tvProfileTabEmpty.setVisibility(View.GONE);
                rvPosts.setVisibility(View.VISIBLE);
            }

            toolbarParams.setScrollFlags(AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL);
            headerParams.setScrollFlags(AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL);
        }
        
        toolbar.setLayoutParams(toolbarParams);
        profileHeader.setLayoutParams(headerParams);
    }

    private void loadUserDetails() {
        db.collection("users").document(targetUserId).get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        User user = doc.toObject(User.class);
                        if (user != null) {
                            tvUsername.setText(user.getUsername());
                            tvBio.setText(user.getBio() != null && !user.getBio().isEmpty() ? user.getBio() : "No bio yet.");
                            tvFollowerCount.setText(String.valueOf(user.getFollowerCount()));
                            tvFollowingCount.setText(String.valueOf(user.getFollowingCount()));
                            
                            Glide.with(this)
                                    .load(user.getProfilePictureUrl())
                                    .placeholder(R.drawable.ic_profile)
                                    .into(ivPfp);

                            favoriteCardKeys.clear();
                            List<String> savedKeys = (List<String>) doc.get("favoriteCardKeys");
                            if (savedKeys != null) {
                                favoriteCardKeys.addAll(savedKeys);
                            }
                            loadFavoriteCards();
                        }
                    }
                });
    }

    private void loadFavoriteCards() {
        db.collection("users")
                .document(targetUserId)
                .collection("collectionSlot")
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    allCollectionSlots.clear();
                    for (DocumentSnapshot doc : queryDocumentSnapshots.getDocuments()) {
                        CollectionSlot slot = doc.toObject(CollectionSlot.class);
                        if (slot == null) continue;
                        slot.setId(doc.getId());
                        allCollectionSlots.add(slot);
                    }
                    refreshFavoritesDisplay();
                });
    }

    private void refreshFavoritesDisplay() {
        while (favoriteCardKeys.size() < FAVORITE_SLOT_COUNT) {
            favoriteCardKeys.add("");
        }

        List<CollectionSlot> favoriteSlots = new ArrayList<>();
        for (int i = 0; i < FAVORITE_SLOT_COUNT; i++) {
            favoriteSlots.add(findSlotById(favoriteCardKeys.get(i)));
        }
        favoritesAdapter.submitList(favoriteSlots);
    }

    @Nullable
    private CollectionSlot findSlotById(@Nullable String id) {
        if (id == null || id.trim().isEmpty()) return null;
        for (CollectionSlot slot : allCollectionSlots) {
            if (slot != null && id.equals(slot.getId())) {
                return slot;
            }
        }
        return null;
    }

    private void checkFollowingStatus() {
        firebaseManager.isFollowing(targetUserId, task -> {
            if (task.isSuccessful()) {
                isFollowing = task.getResult();
                updateFollowButtonUI();
            }
        });
    }

    private void updateFollowButtonUI() {
        if (isFollowing) {
            btnFollow.setText("Following");
        } else {
            btnFollow.setText("Follow");
        }
    }

    private void toggleFollow() {
        btnFollow.setEnabled(false);
        if (isFollowing) {
            firebaseManager.unfollowUser(targetUserId, task -> {
                btnFollow.setEnabled(true);
                if (task.isSuccessful()) {
                    isFollowing = false;
                    updateFollowButtonUI();
                    loadUserDetails();
                }
            });
        } else {
            firebaseManager.followUser(targetUserId, task -> {
                btnFollow.setEnabled(true);
                if (task.isSuccessful()) {
                    isFollowing = true;
                    updateFollowButtonUI();
                    loadUserDetails();
                }
            });
        }
    }

    private void setupRecyclerViews() {
        adapter = new ForumPostAdapter(this);
        rvPosts.setLayoutManager(new LinearLayoutManager(this));
        rvPosts.setAdapter(adapter);

        favoritesAdapter = new FavoritesAdapter(false, this);
        rvFavoriteCards.setLayoutManager(new GridLayoutManager(this, 3));
        rvFavoriteCards.setAdapter(favoritesAdapter);
    }

    private void listenForUserPosts() {
        postsListener = db.collection("forumThreads")
                .whereEqualTo("userId", targetUserId)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .addSnapshotListener((value, error) -> {
                    if (error != null) {
                        return;
                    }

                    if (value != null) {
                        List<ForumPost> posts = new ArrayList<>();
                        for (DocumentSnapshot doc : value.getDocuments()) {
                            ForumPost post = doc.toObject(ForumPost.class);
                            if (post != null) {
                                post.setId(doc.getId());
                                posts.add(post);
                            }
                        }
                        tvPostCount.setText(String.valueOf(posts.size()));
                        currentPosts.clear();
                        currentPosts.addAll(posts);
                        adapter.setPosts(posts);
                        applyTabState(tabLayout.getSelectedTabPosition());
                    }
                });
    }

    @Override
    public void onLikeClick(ForumPost post) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        String userId = user.getUid();
        boolean currentlyLiked = post.getLikedBy() != null && post.getLikedBy().containsKey(userId);

        if (currentlyLiked) {
            db.collection("forumThreads").document(post.getId())
                    .update("likeCount", FieldValue.increment(-1),
                            "likedBy." + userId, FieldValue.delete());
        } else {
            db.collection("forumThreads").document(post.getId())
                    .update("likeCount", FieldValue.increment(1),
                            "likedBy." + userId, true);
        }
    }

    @Override
    public void onCommentClick(ForumPost post) {
        onPostClick(post);
    }

    @Override
    public void onPostClick(ForumPost post) {
        Intent intent = new Intent(this, PostDetailActivity.class);
        intent.putExtra(PostDetailActivity.EXTRA_POST_ID, post.getId());
        startActivity(intent);
    }

    @Override
    public void onOptionsClick(ForumPost post, View view) {}

    @Override
    public void onUserClick(String userId) {
        if (!userId.equals(targetUserId)) {
            Intent intent = new Intent(this, UserSocialProfileActivity.class);
            intent.putExtra(UserSocialProfileActivity.EXTRA_USER_ID, userId);
            startActivity(intent);
        }
    }

    @Override
    public void onFavoriteClicked(int position, @Nullable CollectionSlot slot) {
        if (slot != null) {
            Intent intent = new Intent(this, ViewBirdCardActivity.class);
            intent.putExtra(CollectionCardAdapter.EXTRA_IMAGE_URL, slot.getImageUrl());
            intent.putExtra(CollectionCardAdapter.EXTRA_COMMON_NAME, slot.getCommonName());
            intent.putExtra(CollectionCardAdapter.EXTRA_SCI_NAME, slot.getScientificName());
            intent.putExtra(CollectionCardAdapter.EXTRA_STATE, slot.getState());
            intent.putExtra(CollectionCardAdapter.EXTRA_LOCALITY, slot.getLocality());
            intent.putExtra(CollectionCardAdapter.EXTRA_BIRD_ID, slot.getBirdId());
            if (slot.getTimestamp() != null) {
                intent.putExtra(CollectionCardAdapter.EXTRA_CAUGHT_TIME, slot.getTimestamp().getTime());
            }
            startActivity(intent);
        }
    }

    @Override
    public boolean onFavoriteLongPressed(int position, @Nullable CollectionSlot slot) {
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (postsListener != null) {
            postsListener.remove();
        }
    }
}
