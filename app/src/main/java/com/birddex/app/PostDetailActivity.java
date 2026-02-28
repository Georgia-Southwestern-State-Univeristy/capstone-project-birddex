package com.birddex.app;

import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.birddex.app.databinding.ActivityPostDetailBinding;
import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.List;

public class PostDetailActivity extends AppCompatActivity implements ForumCommentAdapter.OnCommentLikeClickListener {

    private static final String TAG = "PostDetailActivity";
    public static final String EXTRA_POST_ID = "extra_post_id";

    private ActivityPostDetailBinding binding;
    private FirebaseFirestore db;
    private FirebaseAuth mAuth;
    private FirebaseManager firebaseManager;
    private String postId;
    private ForumPost originalPost;
    private ForumCommentAdapter adapter;
    private String currentUsername;
    private String currentUserPfpUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPostDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = FirebaseFirestore.getInstance();
        mAuth = FirebaseAuth.getInstance();
        firebaseManager = new FirebaseManager(this);
        postId = getIntent().getStringExtra(EXTRA_POST_ID);

        if (postId == null) {
            finish();
            return;
        }

        setupUI();
        loadCurrentUser();
        loadPostDetails();
        setupCommentsRecyclerView();
        listenForComments();
    }

    private void setupUI() {
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.btnSendComment.setOnClickListener(v -> postComment());
    }

    private void loadCurrentUser() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) {
            db.collection("users").document(user.getUid()).get()
                    .addOnSuccessListener(doc -> {
                        if (doc.exists()) {
                            currentUsername = doc.getString("username");
                            currentUserPfpUrl = doc.getString("profilePictureUrl");
                            Glide.with(this)
                                    .load(currentUserPfpUrl)
                                    .placeholder(R.drawable.ic_profile)
                                    .into(binding.ivCurrentUserPfp);
                        }
                    });
        }
    }

    private void loadPostDetails() {
        db.collection("forumThreads").document(postId)
                .addSnapshotListener((doc, error) -> {
                    if (error != null) return;
                    if (doc != null && doc.exists()) {
                        originalPost = doc.toObject(ForumPost.class);
                        if (originalPost != null) {
                            originalPost.setId(doc.getId());
                            bindPostToLayout(originalPost);
                        }
                    }
                });
    }

    private void bindPostToLayout(ForumPost post) {
        View postView = binding.postContent.getRoot();
        
        TextView tvUsername = postView.findViewById(R.id.tvPostUsername);
        TextView tvTimestamp = postView.findViewById(R.id.tvPostTimestamp);
        TextView tvMessage = postView.findViewById(R.id.tvPostMessage);
        ImageView ivPfp = postView.findViewById(R.id.ivPostUserProfilePicture);
        ImageView ivBird = postView.findViewById(R.id.ivPostBirdImage);
        View cvImage = postView.findViewById(R.id.cvPostImage);
        TextView tvLikes = postView.findViewById(R.id.tvLikeCount);
        TextView tvComments = postView.findViewById(R.id.tvCommentCount);
        TextView tvViews = postView.findViewById(R.id.tvViewCount);
        View btnLike = postView.findViewById(R.id.btnLike);
        View btnOptions = postView.findViewById(R.id.btnPostOptions);

        tvUsername.setText(post.getUsername());
        tvMessage.setText(post.getMessage());
        tvLikes.setText(String.valueOf(post.getLikeCount()));
        tvComments.setText(String.valueOf(post.getCommentCount()));
        tvViews.setText(post.getViewCount() + " views");

        if (post.getTimestamp() != null) {
            tvTimestamp.setText(DateUtils.getRelativeTimeSpanString(post.getTimestamp().toDate().getTime()));
        }

        Glide.with(this).load(post.getUserProfilePictureUrl()).placeholder(R.drawable.ic_profile).into(ivPfp);
        
        if (post.getBirdImageUrl() != null && !post.getBirdImageUrl().isEmpty()) {
            cvImage.setVisibility(View.VISIBLE);
            Glide.with(this).load(post.getBirdImageUrl()).into(ivBird);
        } else {
            cvImage.setVisibility(View.GONE);
        }

        btnLike.setOnClickListener(v -> toggleLike());
        btnOptions.setOnClickListener(v -> showPostOptions(post, v));
    }

    private void showPostOptions(ForumPost post, View view) {
        PopupMenu popup = new PopupMenu(this, view);
        FirebaseUser user = mAuth.getCurrentUser();
        
        if (user != null && post.getUserId().equals(user.getUid())) {
            popup.getMenu().add("Delete");
        }
        popup.getMenu().add("Report");

        popup.setOnMenuItemClickListener(item -> {
            if (item.getTitle().equals("Delete")) {
                showDeleteConfirmation(post);
            } else if (item.getTitle().equals("Report")) {
                showReportDialog(post);
            }
            return true;
        });
        popup.show();
    }

    private void showDeleteConfirmation(ForumPost post) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Post")
                .setMessage("Are you sure you want to delete this post?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    firebaseManager.deleteForumPost(post.getId(), task -> {
                        if (task.isSuccessful()) {
                            Toast.makeText(this, "Post deleted", Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showReportDialog(ForumPost post) {
        String[] reasons = {"Inappropriate Language", "Inappropriate Image", "Spam", "Harassment", "Other"};
        new AlertDialog.Builder(this)
                .setTitle("Report Post")
                .setItems(reasons, (dialog, which) -> {
                    String selectedReason = reasons[which];
                    submitReport(post, selectedReason);
                })
                .show();
    }

    private void submitReport(ForumPost post, String reason) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        Report report = new Report("post", post.getId(), user.getUid(), reason);
        firebaseManager.addReport(report, task -> {
            if (task.isSuccessful()) {
                Toast.makeText(this, "Report submitted.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void toggleLike() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null || originalPost == null) return;

        String userId = user.getUid();
        boolean liked = originalPost.getLikedBy() != null && originalPost.getLikedBy().containsKey(userId);

        if (liked) {
            db.collection("forumThreads").document(postId)
                    .update("likeCount", FieldValue.increment(-1),
                            "likedBy." + userId, FieldValue.delete());
        } else {
            db.collection("forumThreads").document(postId)
                    .update("likeCount", FieldValue.increment(1),
                            "likedBy." + userId, true);
        }
    }

    private void setupCommentsRecyclerView() {
        adapter = new ForumCommentAdapter(this);
        binding.rvComments.setLayoutManager(new LinearLayoutManager(this));
        binding.rvComments.setAdapter(adapter);
    }

    private void listenForComments() {
        db.collection("forumThreads").document(postId).collection("comments")
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .addSnapshotListener((value, error) -> {
                    if (error != null) return;
                    if (value != null) {
                        List<ForumComment> comments = new ArrayList<>();
                        for (DocumentSnapshot doc : value.getDocuments()) {
                            ForumComment comment = doc.toObject(ForumComment.class);
                            if (comment != null) {
                                comment.setId(doc.getId());
                                comments.add(comment);
                            }
                        }
                        adapter.setComments(comments);
                    }
                });
    }

    private void postComment() {
        String text = binding.etComment.getText().toString().trim();
        if (text.isEmpty()) return;

        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        binding.btnSendComment.setEnabled(false);
        ForumComment comment = new ForumComment(postId, user.getUid(), currentUsername, currentUserPfpUrl, text);
        
        db.collection("forumThreads").document(postId).collection("comments").add(comment)
                .addOnSuccessListener(docRef -> {
                    binding.etComment.setText("");
                    binding.btnSendComment.setEnabled(true);
                    db.collection("forumThreads").document(postId)
                            .update("commentCount", FieldValue.increment(1));
                })
                .addOnFailureListener(e -> {
                    binding.btnSendComment.setEnabled(true);
                    Toast.makeText(this, "Failed to post comment", Toast.LENGTH_SHORT).show();
                });
    }

    @Override
    public void onCommentLikeClick(ForumComment comment) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        String userId = user.getUid();
        boolean liked = comment.getLikedBy() != null && comment.getLikedBy().containsKey(userId);

        if (liked) {
            db.collection("forumThreads").document(postId).collection("comments").document(comment.getId())
                    .update("likeCount", FieldValue.increment(-1),
                            "likedBy." + userId, FieldValue.delete());
        } else {
            db.collection("forumThreads").document(postId).collection("comments").document(comment.getId())
                    .update("likeCount", FieldValue.increment(1),
                            "likedBy." + userId, true);
        }
    }
}
