package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.birddex.app.databinding.ActivityPostDetailBinding;
import com.bumptech.glide.Glide;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PostDetailActivity extends AppCompatActivity implements ForumCommentAdapter.OnCommentInteractionListener {

    private static final String TAG = "PostDetailActivity";
    public static final String EXTRA_POST_ID = "extra_post_id";
    private static final long EDIT_WINDOW_MS = 5 * 60 * 1000; // 5 minutes

    private ActivityPostDetailBinding binding;
    private FirebaseFirestore db;
    private FirebaseAuth mAuth;
    private FirebaseManager firebaseManager;
    private String postId;
    private ForumPost originalPost;
    private ForumCommentAdapter adapter;
    private String currentUsername;
    private String currentUserPfpUrl;
    
    private ForumComment replyingToComment = null;

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
                        if (isFinishing() || isDestroyed()) return;
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
        // addSnapshotListener with MetadataChanges.INCLUDE automatically handles caching
        db.collection("forumThreads").document(postId)
                .addSnapshotListener(MetadataChanges.INCLUDE, (doc, error) -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (error != null) return;
                    if (doc != null && doc.exists()) {
                        originalPost = doc.toObject(ForumPost.class);
                        if (originalPost != null) {
                            originalPost.setId(doc.getId());

                            FirebaseUser user = mAuth.getCurrentUser();
                            if (user != null) {
                                String userId = user.getUid();
                                
                                // Only increment views if this is fresh data from the server, not just a cache load
                                if (!doc.getMetadata().isFromCache()) {
                                    // Check for unique view
                                    if (originalPost.getViewedBy() == null || !originalPost.getViewedBy().containsKey(userId)) {
                                        db.collection("forumThreads").document(postId)
                                                .update("viewCount", FieldValue.increment(1),
                                                        "viewedBy." + userId, true);
                                    }

                                    // Reset notification flags if the author is viewing the post
                                    if (userId.equals(originalPost.getUserId())) {
                                        Map<String, Object> updates = new HashMap<>();
                                        if (originalPost.isNotificationSent()) {
                                            updates.put("notificationSent", false);
                                        }
                                        if (originalPost.isLikeNotificationSent()) {
                                            updates.put("likeNotificationSent", false);
                                        }
                                        updates.put("lastViewedAt", FieldValue.serverTimestamp());
                                        
                                        if (!updates.isEmpty()) {
                                            db.collection("forumThreads").document(postId).update(updates);
                                        }
                                    }
                                }
                            }

                            bindPostToLayout(originalPost);
                        }
                    }
                });
    }

    private void bindPostToLayout(ForumPost post) {
        if (isFinishing() || isDestroyed()) return;
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
        String messageText = post.getMessage();
        if (post.isEdited()) {
            messageText += " (edited)";
        }
        tvMessage.setText(messageText);
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
        
        ivPfp.setOnClickListener(v -> openUserProfile(post.getUserId()));
        tvUsername.setOnClickListener(v -> openUserProfile(post.getUserId()));
    }

    private void showPostOptions(ForumPost post, View view) {
        PopupMenu popup = new PopupMenu(this, view);
        FirebaseUser user = mAuth.getCurrentUser();
        
        if (user != null && post.getUserId().equals(user.getUid())) {
            if (post.getTimestamp() != null) {
                long postTime = post.getTimestamp().toDate().getTime();
                if (System.currentTimeMillis() - postTime <= EDIT_WINDOW_MS) {
                    popup.getMenu().add("Edit");
                }
            }
            popup.getMenu().add("Delete");
        }
        popup.getMenu().add("Report");

        popup.setOnMenuItemClickListener(item -> {
            if (item.getTitle().equals("Edit")) {
                showEditPostDialog(post);
            } else if (item.getTitle().equals("Delete")) {
                showDeleteConfirmation(post);
            } else if (item.getTitle().equals("Report")) {
                showReportDialog(post);
            }
            return true;
        });
        popup.show();
    }

    private void showEditPostDialog(ForumPost post) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit Post");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setText(post.getMessage());
        input.setHint("Message");
        layout.addView(input);

        final CheckBox cbSpotted = new CheckBox(this);
        cbSpotted.setText("Spotted");
        cbSpotted.setChecked(post.isSpotted());
        layout.addView(cbSpotted);

        final CheckBox cbHunted = new CheckBox(this);
        cbHunted.setText("Hunted");
        cbHunted.setChecked(post.isHunted());
        layout.addView(cbHunted);

        final SwitchMaterial swLocation = new SwitchMaterial(this);
        swLocation.setText("Show location on map");
        swLocation.setChecked(post.isShowLocation());
        layout.addView(swLocation);

        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newText = input.getText().toString().trim();
            updatePost(post, newText, cbSpotted.isChecked(), cbHunted.isChecked(), swLocation.isChecked());
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void updatePost(ForumPost post, String newText, boolean spotted, boolean hunted, boolean showLocation) {
        if (!ContentFilter.isSafe(this, newText, "Post message")) return;

        Map<String, Object> updates = new HashMap<>();
        updates.put("message", newText);
        updates.put("spotted", spotted);
        updates.put("hunted", hunted);
        updates.put("showLocation", showLocation);
        updates.put("edited", true);
        updates.put("lastEditedAt", FieldValue.serverTimestamp());

        db.collection("forumThreads").document(post.getId())
                .update(updates)
                .addOnSuccessListener(aVoid -> Toast.makeText(this, "Post updated", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(this, "Update failed", Toast.LENGTH_SHORT).show());
    }

    private void showDeleteConfirmation(ForumPost post) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Post")
                .setMessage("Are you sure you want to delete this post?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    archiveAndDeletePost(post);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void archiveAndDeletePost(ForumPost post) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        Map<String, Object> backlogData = new HashMap<>();
        backlogData.put("type", "post");
        backlogData.put("originalId", post.getId());
        backlogData.put("data", post);
        backlogData.put("deletedBy", user.getUid());
        backlogData.put("deletedAt", FieldValue.serverTimestamp());

        db.collection("deleted_backlog").add(backlogData)
                .addOnSuccessListener(docRef -> {
                    firebaseManager.deleteForumPost(post.getId(), task -> {
                        if (task.isSuccessful()) {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(this, "Post deleted", Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to backlog post before deletion", e);
                    Toast.makeText(this, "Failed to delete post. Try again.", Toast.LENGTH_SHORT).show();
                });
    }

    private void showReportDialog(ForumPost post) {
        String[] reasons = {"Inappropriate Language", "Inappropriate Image", "Spam", "Harassment", "Other"};
        new AlertDialog.Builder(this)
                .setTitle("Report Post")
                		.setItems(reasons, (dialog, which) -> {
                    String selectedReason = reasons[which];
                    if (selectedReason.equals("Other")) {
                        showOtherReportDialog(reason -> submitReport(post, reason));
                    } else {
                        submitReport(post, selectedReason);
                    }
                })
                .show();
    }

    private void submitReport(ForumPost post, String reason) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        Report report = new Report("post", post.getId(), user.getUid(), reason);
        firebaseManager.addReport(report, task -> {
            if (task.isSuccessful()) {
                if (isFinishing() || isDestroyed()) return;
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
                .addSnapshotListener(MetadataChanges.INCLUDE, (value, error) -> {
                    if (isFinishing() || isDestroyed()) return;
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

        if (!ContentFilter.isSafe(this, text, "Comment")) return;

        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        binding.btnSendComment.setEnabled(false);
        ForumComment comment = new ForumComment(postId, user.getUid(), currentUsername, currentUserPfpUrl, text);
        
        if (replyingToComment != null) {
            comment.setParentCommentId(replyingToComment.getId());
            comment.setParentUsername(replyingToComment.getUsername());
        }
        
        db.collection("forumThreads").document(postId).collection("comments").add(comment)
                .addOnSuccessListener(docRef -> {
                    if (isFinishing() || isDestroyed()) return;
                    binding.etComment.setText("");
                    binding.etComment.setHint("Write a comment...");
                    replyingToComment = null;
                    binding.btnSendComment.setEnabled(true);
                    db.collection("forumThreads").document(postId)
                            .update("commentCount", FieldValue.increment(1));
                })
                .addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;
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

    @Override
    public void onCommentReplyClick(ForumComment comment) {
        replyingToComment = comment;
        binding.etComment.setHint("Replying to " + comment.getUsername() + "...");
        binding.etComment.requestFocus();
    }

    @Override
    public void onCommentOptionsClick(ForumComment comment, View view) {
        showCommentOptions(comment, view);
    }

    @Override
    public void onUserClick(String userId) {
        openUserProfile(userId);
    }

    private void showCommentOptions(ForumComment comment, View view) {
        PopupMenu popup = new PopupMenu(this, view);
        FirebaseUser user = mAuth.getCurrentUser();
        
        boolean isCommentAuthor = user != null && comment.getUserId().equals(user.getUid());
        boolean isPostAuthor = user != null && originalPost != null && originalPost.getUserId().equals(user.getUid());

        if (isCommentAuthor) {
            if (comment.getTimestamp() != null) {
                long commentTime = comment.getTimestamp().toDate().getTime();
                if (System.currentTimeMillis() - commentTime <= EDIT_WINDOW_MS) {
                    popup.getMenu().add("Edit");
                }
            }
        }

        if (isCommentAuthor || isPostAuthor) {
            popup.getMenu().add("Delete");
        }
        popup.getMenu().add("Report");

        popup.setOnMenuItemClickListener(item -> {
            if (item.getTitle().equals("Edit")) {
                showEditCommentDialog(comment);
            } else if (item.getTitle().equals("Delete")) {
                showCommentDeleteConfirmation(comment);
            } else if (item.getTitle().equals("Report")) {
                showCommentReportDialog(comment);
            }
            return true;
        });
        popup.show();
    }

    private void showEditCommentDialog(ForumComment comment) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit Comment");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setText(comment.getText());
        
        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = params.rightMargin = 40;
        input.setLayoutParams(params);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newText = input.getText().toString().trim();
            if (!newText.isEmpty() && !newText.equals(comment.getText())) {
                updateComment(comment, newText);
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void updateComment(ForumComment comment, String newText) {
        if (!ContentFilter.isSafe(this, newText, "Comment")) return;

        db.collection("forumThreads").document(postId).collection("comments").document(comment.getId())
                .update("text", newText, 
                        "edited", true, 
                        "lastEditedAt", FieldValue.serverTimestamp())
                .addOnSuccessListener(aVoid -> Toast.makeText(this, "Comment updated", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(this, "Update failed", Toast.LENGTH_SHORT).show());
    }

    private void showCommentDeleteConfirmation(ForumComment comment) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Comment")
                .setMessage("Are you sure you want to delete this comment? " + (comment.getParentCommentId() == null ? "All replies will also be deleted." : ""))
                .setPositiveButton("Delete", (dialog, which) -> {
                    deleteCommentAndReplies(comment);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteCommentAndReplies(ForumComment comment) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        if (comment.getParentCommentId() == null) {
            db.collection("forumThreads").document(postId).collection("comments")
                    .whereEqualTo("parentCommentId", comment.getId())
                    .get()
                    .addOnSuccessListener(queryDocumentSnapshots -> {
                        WriteBatch batch = db.batch();
                        int totalToDelete = queryDocumentSnapshots.size() + 1;

                        for (DocumentSnapshot doc : queryDocumentSnapshots) {
                            Map<String, Object> backlogData = new HashMap<>();
                            backlogData.put("type", "comment_reply");
                            backlogData.put("originalId", doc.getId());
                            backlogData.put("data", doc.getData());
                            backlogByUserId(batch, user.getUid(), "comment_reply", doc.getId(), doc.getData());
                            
                            batch.delete(doc.getReference());
                        }
                        
                        backlogByUserId(batch, user.getUid(), "comment", comment.getId(), comment);

                        batch.delete(db.collection("forumThreads").document(postId)
                                .collection("comments").document(comment.getId()));
                        
                        batch.update(db.collection("forumThreads").document(postId), 
                                "commentCount", FieldValue.increment(-totalToDelete));
                        
                        batch.commit().addOnSuccessListener(aVoid -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(this, "Comment deleted", Toast.LENGTH_SHORT).show();
                        });
                    });
        } else {
            Map<String, Object> replyBacklog = new HashMap<>();
            replyBacklog.put("type", "reply");
            replyBacklog.put("originalId", comment.getId());
            replyBacklog.put("data", comment);
            replyBacklog.put("deletedBy", user.getUid());
            replyBacklog.put("deletedAt", FieldValue.serverTimestamp());

            db.collection("deleted_backlog").add(replyBacklog)
                    .addOnSuccessListener(docRef -> {
                        firebaseManager.deleteForumComment(postId, comment.getId(), task -> {
                            if (task.isSuccessful()) {
                                if (isFinishing() || isDestroyed()) return;
                                Toast.makeText(this, "Reply deleted", Toast.LENGTH_SHORT).show();
                                db.collection("forumThreads").document(postId)
                                        .update("commentCount", FieldValue.increment(-1));
                            }
                        });
                    });
        }
    }

    private void backlogByUserId(WriteBatch batch, String uid, String type, String originalId, Object data) {
        Map<String, Object> backlog = new HashMap<>();
        backlog.put("type", type);
        backlog.put("originalId", originalId);
        backlog.put("data", data);
        backlog.put("deletedBy", uid);
        backlog.put("deletedAt", FieldValue.serverTimestamp());
        batch.set(db.collection("deleted_backlog").document(), backlog);
    }

    private void showCommentReportDialog(ForumComment comment) {
        String[] reasons = {"Inappropriate Language", "Spam", "Harassment", "Other"};
        new AlertDialog.Builder(this)
                .setTitle("Report Comment")
                .setItems(reasons, (dialog, which) -> {
                    String selectedReason = reasons[which];
                    if (selectedReason.equals("Other")) {
                        showOtherReportDialog(reason -> submitCommentReport(comment, reason));
                    } else {
                        submitCommentReport(comment, selectedReason);
                    }
                })
                .show();
    }

    private void submitCommentReport(ForumComment comment, String reason) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        Report report = new Report("comment", comment.getId(), user.getUid(), reason);
        firebaseManager.addReport(report, task -> {
            if (task.isSuccessful()) {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, "Comment reported.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showOtherReportDialog(OnReasonEnteredListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Report Reason");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setHint("Please specify the reason (max 200 chars)...");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        input.setLines(5);
        
        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = params.rightMargin = 40;
        input.setLayoutParams(params);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("Submit", (dialog, which) -> {
            String reason = input.getText().toString().trim();
            if (reason.isEmpty()) {
                Toast.makeText(this, "Please enter a reason", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!ContentFilter.isSafe(this, reason, "Report reason")) return;
            listener.onReasonEntered(reason);
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private interface OnReasonEnteredListener {
        void onReasonEntered(String reason);
    }

    private void openUserProfile(String userId) {
        Intent intent = new Intent(this, HomeActivity.class);
        intent.putExtra("target_user_id", userId);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }
}