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
    private static final int PAGE_SIZE = 20;
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
    
    private List<ForumPost> postList = new ArrayList<>();
    private DocumentSnapshot lastVisible;
    private boolean isFetching = false;
    private boolean isLastPage = false;

    private final List<String> favoriteCardKeys = new ArrayList<>();
    private final List<CollectionSlot> allCollectionSlots = new ArrayList<>();

    private ListenerRegistration userDetailsListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_social_profile);

        targetUserId = getIntent().getStringExtra(EXTRA_USER_ID);
        if (targetUserId == null) {
            Log.e(TAG, "onCreate: Missing targetUserId in intent.");
            Toast.makeText(this, "User ID not found.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Log.d(TAG, "onCreate: Loading profile for UID: " + targetUserId);
        db = FirebaseFirestore.getInstance();
        firebaseManager = new FirebaseManager(this);

        initUI();
        loadUserDetails();
        checkFollowingStatus();
        setupRecyclerViews();
        
        fetchUserPosts();
    }

    private void initUI() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> {
            Log.d(TAG, "Navigation back clicked.");
            finish();
        });

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
            Log.d(TAG, "User is viewing their own profile via SocialProfileActivity. Hiding follow button.");
            btnFollow.setVisibility(View.GONE);
        }

        btnFollow.setOnClickListener(v -> toggleFollow());

        btnUserFollowers.setOnClickListener(v -> {
            Log.d(TAG, "Followers count clicked.");
            Intent intent = new Intent(this, SocialActivity.class);
            intent.putExtra(SocialActivity.EXTRA_USER_ID, targetUserId);
            intent.putExtra(SocialActivity.EXTRA_SHOW_FOLLOWING, false);
            startActivity(intent);
        });

        btnUserFollowing.setOnClickListener(v -> {
            Log.d(TAG, "Following count clicked.");
            Intent intent = new Intent(this, SocialActivity.class);
            intent.putExtra(SocialActivity.EXTRA_USER_ID, targetUserId);
            intent.putExtra(SocialActivity.EXTRA_SHOW_FOLLOWING, true);
            startActivity(intent);
        });

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                Log.d(TAG, "Tab selected: " + tab.getPosition());
                applyTabState(tab.getPosition());
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
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
            rvPosts.setVisibility(postList.isEmpty() ? View.GONE : View.VISIBLE);
            tvProfileTabEmpty.setVisibility(postList.isEmpty() ? View.VISIBLE : View.GONE);
            toolbarParams.setScrollFlags(AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL | AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS);
            headerParams.setScrollFlags(AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL | AppBarLayout.LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED);
        }
        
        toolbar.setLayoutParams(toolbarParams);
        profileHeader.setLayoutParams(headerParams);
    }

    private void loadUserDetails() {
        if (userDetailsListener != null) userDetailsListener.remove();

        Log.d(TAG, "Setting up SnapshotListener for user details: " + targetUserId);
        userDetailsListener = db.collection("users").document(targetUserId)
                .addSnapshotListener((doc, e) -> {
                    if (e != null) {
                        Log.e(TAG, "loadUserDetails: Listen failed.", e);
                        return;
                    }

                    if (doc != null && doc.exists()) {
                        Log.d(TAG, "loadUserDetails: Document updated.");
                        User user = doc.toObject(User.class);
                        if (user != null) {
                            if (Boolean.TRUE.equals(doc.getBoolean("isDeleted"))) {
                                Log.w(TAG, "Viewing deleted user.");
                                tvUsername.setText("Deleted User");
                                tvBio.setText("");
                                tvFollowerCount.setText("0");
                                tvFollowingCount.setText("0");
                                ivPfp.setImageResource(R.drawable.ic_profile);
                                btnFollow.setVisibility(View.GONE);
                                return;
                            }

                            tvUsername.setText(user.getUsername());
                            tvBio.setText(user.getBio() != null && !user.getBio().isEmpty() ? user.getBio() : "No bio yet.");
                            tvFollowerCount.setText(String.valueOf(user.getFollowerCount()));
                            tvFollowingCount.setText(String.valueOf(user.getFollowingCount()));
                            
                            Glide.with(this).load(user.getProfilePictureUrl()).placeholder(R.drawable.ic_profile).into(ivPfp);

                            favoriteCardKeys.clear();
                            List<String> savedKeys = (List<String>) doc.get("favoriteCardKeys");
                            if (savedKeys != null) favoriteCardKeys.addAll(savedKeys);
                            loadFavoriteCards();
                        }
                    } else {
                        Log.w(TAG, "loadUserDetails: User document does not exist.");
                        tvUsername.setText("User Not Found");
                        btnFollow.setVisibility(View.GONE);
                    }
                });
    }

    private void loadFavoriteCards() {
        Log.d(TAG, "loadFavoriteCards: Fetching collectionSlot subcollection.");
        db.collection("users").document(targetUserId).collection("collectionSlot").get()
                .addOnSuccessListener(querySnapshot -> {
                    allCollectionSlots.clear();
                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        CollectionSlot slot = doc.toObject(CollectionSlot.class);
                        if (slot != null) {
                            slot.setId(doc.getId());
                            allCollectionSlots.add(slot);
                        }
                    }
                    Log.d(TAG, "loadFavoriteCards: " + allCollectionSlots.size() + " slots loaded.");
                    refreshFavoritesDisplay();
                })
                .addOnFailureListener(e -> Log.e(TAG, "loadFavoriteCards: Failed.", e));
    }

    private void refreshFavoritesDisplay() {
        while (favoriteCardKeys.size() < FAVORITE_SLOT_COUNT) favoriteCardKeys.add("");
        List<CollectionSlot> favoriteSlots = new ArrayList<>();
        for (int i = 0; i < FAVORITE_SLOT_COUNT; i++) {
            favoriteSlots.add(findSlotById(favoriteCardKeys.get(i)));
        }
        favoritesAdapter.submitList(favoriteSlots);
    }

    private CollectionSlot findSlotById(String id) {
        if (id == null || id.trim().isEmpty()) return null;
        for (CollectionSlot slot : allCollectionSlots) {
            if (id.equals(slot.getId())) return slot;
        }
        return null;
    }

    private void checkFollowingStatus() {
        Log.d(TAG, "checkFollowingStatus: Calling FirebaseManager.");
        firebaseManager.isFollowing(targetUserId, task -> {
            if (task.isSuccessful()) {
                isFollowing = task.getResult();
                Log.d(TAG, "checkFollowingStatus result: " + isFollowing);
                updateFollowButtonUI();
            } else {
                Log.e(TAG, "checkFollowingStatus failed.", task.getException());
            }
        });
    }

    private void updateFollowButtonUI() {
        btnFollow.setText(isFollowing ? "Following" : "Follow");
    }

    private void toggleFollow() {
        btnFollow.setEnabled(false);
        Log.d(TAG, "toggleFollow: Current state: " + isFollowing);
        if (isFollowing) {
            firebaseManager.unfollowUser(targetUserId, task -> {
                btnFollow.setEnabled(true);
                if (task.isSuccessful()) {
                    Log.d(TAG, "unfollowUser success via CF.");
                    isFollowing = false;
                    updateFollowButtonUI();
                } else {
                    Log.e(TAG, "unfollowUser failed.", task.getException());
                    Toast.makeText(this, "Unfollow failed.", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            firebaseManager.followUser(targetUserId, task -> {
                btnFollow.setEnabled(true);
                if (task.isSuccessful()) {
                    Log.d(TAG, "followUser success via CF.");
                    isFollowing = true;
                    updateFollowButtonUI();
                } else {
                    Log.e(TAG, "followUser failed.", task.getException());
                    Toast.makeText(this, "Follow failed.", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void setupRecyclerViews() {
        Log.d(TAG, "setupRecyclerViews: Initializing adapters.");
        adapter = new ForumPostAdapter(this);
        rvPosts.setLayoutManager(new LinearLayoutManager(this));
        rvPosts.setAdapter(adapter);

        rvPosts.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0) {
                    LinearLayoutManager lm = (LinearLayoutManager) rvPosts.getLayoutManager();
                    if (lm != null && !isFetching && !isLastPage) {
                        if (lm.getChildCount() + lm.findFirstVisibleItemPosition() >= lm.getItemCount()) {
                            Log.d(TAG, "End of list reached. Fetching next page.");
                            fetchUserPosts();
                        }
                    }
                }
            }
        });

        favoritesAdapter = new FavoritesAdapter(false, this);
        rvFavoriteCards.setLayoutManager(new GridLayoutManager(this, 3));
        rvFavoriteCards.setAdapter(favoritesAdapter);
    }

    private void fetchUserPosts() {
        if (isFetching || isLastPage) return;
        isFetching = true;
        Log.d(TAG, "fetchUserPosts: Fetching posts for UID: " + targetUserId);

        Query query = db.collection("forumThreads")
                .whereEqualTo("userId", targetUserId)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(PAGE_SIZE);

        if (lastVisible != null) query = query.startAfter(lastVisible);

        query.get().addOnSuccessListener(value -> {
            if (value != null && !value.isEmpty()) {
                lastVisible = value.getDocuments().get(value.size() - 1);
                for (DocumentSnapshot doc : value.getDocuments()) {
                    ForumPost post = doc.toObject(ForumPost.class);
                    if (post != null) {
                        post.setId(doc.getId());
                        postList.add(post);
                    }
                }
                Log.d(TAG, "fetchUserPosts: " + value.size() + " posts loaded. Total: " + postList.size());
                tvPostCount.setText(String.valueOf(postList.size()));
                adapter.setPosts(new ArrayList<>(postList));
                if (value.size() < PAGE_SIZE) isLastPage = true;
            } else {
                Log.d(TAG, "fetchUserPosts: No more posts.");
                isLastPage = true;
            }
            isFetching = false;
        }).addOnFailureListener(e -> {
            isFetching = false;
            Log.e(TAG, "fetchUserPosts: Error.", e);
        });
    }

    @Override public void onLikeClick(ForumPost post) {
        Log.d(TAG, "Post Liked: " + post.getId());
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        String userId = user.getUid();
        boolean currentlyLiked = post.getLikedBy() != null && post.getLikedBy().containsKey(userId);
        if (currentlyLiked) {
            db.collection("forumThreads").document(post.getId()).update("likeCount", FieldValue.increment(-1), "likedBy." + userId, FieldValue.delete());
        } else {
            db.collection("forumThreads").document(post.getId()).update("likeCount", FieldValue.increment(1), "likedBy." + userId, true);
        }
    }

    @Override public void onCommentClick(ForumPost post) { onPostClick(post); }
    @Override public void onPostClick(ForumPost post) {
        Log.d(TAG, "Post clicked: " + post.getId());
        Intent intent = new Intent(this, PostDetailActivity.class);
        intent.putExtra(PostDetailActivity.EXTRA_POST_ID, post.getId());
        startActivity(intent);
    }
    @Override public void onOptionsClick(ForumPost post, View view) { Log.d(TAG, "Post options clicked."); }
    @Override public void onUserClick(String userId) {
        if (!userId.equals(targetUserId)) {
            Log.d(TAG, "User in list clicked: " + userId);
            Intent intent = new Intent(this, UserSocialProfileActivity.class);
            intent.putExtra(UserSocialProfileActivity.EXTRA_USER_ID, userId);
            startActivity(intent);
        }
    }
    @Override public void onMapClick(ForumPost post) {
        if (post.getLatitude() != null && post.getLongitude() != null) {
            Log.d(TAG, "Map clicked for post: " + post.getId());
            Intent intent = new Intent(this, NearbyHeatmapActivity.class);
            intent.putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LAT, post.getLatitude());
            intent.putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LNG, post.getLongitude());
            intent.putExtra("extra_post_id", post.getId());
            startActivity(intent);
        }
    }

    @Override public void onFavoriteClicked(int position, @Nullable CollectionSlot slot) {
        if (slot != null) {
            Log.d(TAG, "Favorite card clicked: " + slot.getCommonName());
            Intent intent = new Intent(this, ViewBirdCardActivity.class);
            intent.putExtra(CollectionCardAdapter.EXTRA_IMAGE_URL, slot.getImageUrl());
            intent.putExtra(CollectionCardAdapter.EXTRA_COMMON_NAME, slot.getCommonName());
            intent.putExtra(CollectionCardAdapter.EXTRA_SCI_NAME, slot.getScientificName());
            intent.putExtra(CollectionCardAdapter.EXTRA_STATE, slot.getState());
            intent.putExtra(CollectionCardAdapter.EXTRA_LOCALITY, slot.getLocality());
            intent.putExtra(CollectionCardAdapter.EXTRA_BIRD_ID, slot.getBirdId());
            if (slot.getTimestamp() != null) intent.putExtra(CollectionCardAdapter.EXTRA_CAUGHT_TIME, slot.getTimestamp().getTime());
            startActivity(intent);
        }
    }

    @Override public boolean onFavoriteLongPressed(int position, @Nullable CollectionSlot slot) { return false; }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (userDetailsListener != null) userDetailsListener.remove();
    }
}
