package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.List;

public class UserSocialProfileActivity extends AppCompatActivity implements ForumPostAdapter.OnPostClickListener {

    private static final String TAG = "UserSocialProfile";
    public static final String EXTRA_USER_ID = "extra_user_id";

    private ShapeableImageView ivPfp;
    private TextView tvUsername, tvBio;
    private TextView tvPostCount, tvFollowerCount, tvFollowingCount;
    private MaterialButton btnFollow;
    private RecyclerView rvPosts;
    private TabLayout tabLayout;

    private FirebaseFirestore db;
    private FirebaseManager firebaseManager;
    private ForumPostAdapter adapter;
    
    private String targetUserId;
    private boolean isFollowing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_social_profile);

        targetUserId = getIntent().getStringExtra(EXTRA_USER_ID);
        if (targetUserId == null) {
            finish();
            return;
        }

        db = FirebaseFirestore.getInstance();
        firebaseManager = new FirebaseManager(this);

        initUI();
        loadUserDetails();
        checkFollowingStatus();
        setupRecyclerView();
        loadUserPosts();
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
        btnFollow = findViewById(R.id.btnUserFollow);
        rvPosts = findViewById(R.id.rvUserProfilePosts);
        tabLayout = findViewById(R.id.profileTabLayout);

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null && currentUser.getUid().equals(targetUserId)) {
            btnFollow.setVisibility(View.GONE);
        }

        btnFollow.setOnClickListener(v -> toggleFollow());
    }

    private void loadUserDetails() {
        db.collection("users").document(targetUserId).get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        User user = doc.toObject(User.class);
                        if (user != null) {
                            tvUsername.setText(user.getUsername());
                            tvBio.setText(user.getBio() != null ? user.getBio() : "No bio yet.");
                            tvFollowerCount.setText(String.valueOf(user.getFollowerCount()));
                            tvFollowingCount.setText(String.valueOf(user.getFollowingCount()));
                            
                            Glide.with(this)
                                    .load(user.getProfilePictureUrl())
                                    .placeholder(R.drawable.ic_profile)
                                    .into(ivPfp);
                        }
                    }
                });
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
            btnFollow.setIconResource(R.drawable.ic_bolt_24);
        } else {
            btnFollow.setText("Follow");
            btnFollow.setIcon(null);
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
                    loadUserDetails(); // Refresh counts
                }
            });
        } else {
            firebaseManager.followUser(targetUserId, task -> {
                btnFollow.setEnabled(true);
                if (task.isSuccessful()) {
                    isFollowing = true;
                    updateFollowButtonUI();
                    loadUserDetails(); // Refresh counts
                }
            });
        }
    }

    private void setupRecyclerView() {
        adapter = new ForumPostAdapter(this);
        rvPosts.setLayoutManager(new LinearLayoutManager(this));
        rvPosts.setAdapter(adapter);
    }

    private void loadUserPosts() {
        db.collection("forumThreads")
                .whereEqualTo("userId", targetUserId)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    List<ForumPost> posts = new ArrayList<>();
                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        ForumPost post = doc.toObject(ForumPost.class);
                        if (post != null) {
                            post.setId(doc.getId());
                            posts.add(post);
                        }
                    }
                    tvPostCount.setText(String.valueOf(posts.size()));
                    adapter.setPosts(posts);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error loading posts", e);
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
                    .update("likeCount", com.google.firebase.firestore.FieldValue.increment(-1),
                            "likedBy." + userId, com.google.firebase.firestore.FieldValue.delete());
        } else {
            db.collection("forumThreads").document(post.getId())
                    .update("likeCount", com.google.firebase.firestore.FieldValue.increment(1),
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
    public void onOptionsClick(ForumPost post, View view) {
        // Implement options if needed
    }

    @Override
    public void onUserClick(String userId) {
        if (!userId.equals(targetUserId)) {
            Intent intent = new Intent(this, UserSocialProfileActivity.class);
            intent.putExtra(UserSocialProfileActivity.EXTRA_USER_ID, userId);
            startActivity(intent);
        }
    }
}
