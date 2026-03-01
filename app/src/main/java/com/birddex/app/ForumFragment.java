package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.birddex.app.databinding.FragmentForumBinding;
import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.List;

/**
 * ForumFragment serves as the community hub for users to interact,
 * share sightings, and discuss birds.
 */
public class ForumFragment extends Fragment implements ForumPostAdapter.OnPostClickListener {

    private static final String TAG = "ForumFragment";
    private FragmentForumBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseManager firebaseManager;
    private ForumPostAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentForumBinding.inflate(inflater, container, false);
        View view = binding.getRoot();

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        firebaseManager = new FirebaseManager(requireContext());

        setupRecyclerView();
        setupClickListeners();
        loadUserProfilePicture();
        listenForPosts();

        return view;
    }

    private void setupRecyclerView() {
        adapter = new ForumPostAdapter(this);
        binding.rvForumPosts.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.rvForumPosts.setAdapter(adapter);
    }

    private void setupClickListeners() {
        binding.createPostCardView.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), CreatePostActivity.class);
            startActivity(intent);
        });

        binding.btnSocial.setOnClickListener(v -> {
            startActivity(new Intent(getActivity(), SocialActivity.class));
        });
    }

    private void loadUserProfilePicture() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) return;

        db.collection("users").document(currentUser.getUid()).get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (!isAdded() || getActivity() == null || binding == null) return;
                    if (documentSnapshot.exists()) {
                        String profilePictureUrl = documentSnapshot.getString("profilePictureUrl");
                        if (getContext() != null) {
                            Glide.with(this)
                                    .load(profilePictureUrl)
                                    .placeholder(R.drawable.ic_profile)
                                    .into(binding.ivUserProfilePicture);
                        }
                    }
                });
    }

    private void listenForPosts() {
        db.collection("forumThreads")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .addSnapshotListener((value, error) -> {
                    if (!isAdded() || binding == null) return;
                    if (error != null) {
                        Log.e(TAG, "Listen failed.", error);
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
                        adapter.setPosts(posts);
                    }
                });
    }

    @Override
    public void onLikeClick(ForumPost post) {
        FirebaseUser user = mAuth.getCurrentUser();
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
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) {
            String userId = user.getUid();
            // Check if user has already viewed this post
            if (post.getViewedBy() == null || !post.getViewedBy().containsKey(userId)) {
                db.collection("forumThreads").document(post.getId())
                        .update("viewCount", FieldValue.increment(1),
                                "viewedBy." + userId, true);
            }
        }
        openPostDetail(post);
    }

    @Override
    public void onOptionsClick(ForumPost post, View view) {
        PopupMenu popup = new PopupMenu(getContext(), view);
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

    @Override
    public void onUserClick(String userId) {
        openUserProfile(userId);
    }

    private void showDeleteConfirmation(ForumPost post) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Post")
                .setMessage("Are you sure you want to delete this post?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    firebaseManager.deleteForumPost(post.getId(), task -> {
                        if (!isAdded()) return;
                        if (task.isSuccessful()) {
                            Toast.makeText(getContext(), "Post deleted", Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showReportDialog(ForumPost post) {
        String[] reasons = {"Inappropriate Language", "Inappropriate Image", "Spam", "Harassment", "Other"};
        new AlertDialog.Builder(requireContext())
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

    private void showOtherReportDialog(OnReasonEnteredListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Report Reason");

        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setHint("Please specify the reason (max 200 chars)...");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        input.setSingleLine(false);
        input.setHorizontallyScrolling(false);
        input.setLines(5);

        FrameLayout container = new FrameLayout(requireContext());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = params.rightMargin = 40;
        input.setLayoutParams(params);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("Submit", (dialog, which) -> {
            String reason = input.getText().toString().trim();
            if (reason.isEmpty()) {
                Toast.makeText(getContext(), "Please enter a reason", Toast.LENGTH_SHORT).show();
                return;
            }
            if (ContentFilter.containsInappropriateContent(reason)) {
                Toast.makeText(getContext(), "Inappropriate language detected in your report.", Toast.LENGTH_SHORT).show();
                return;
            }
            listener.onReasonEntered(reason);
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private interface OnReasonEnteredListener {
        void onReasonEntered(String reason);
    }

    private void submitReport(ForumPost post, String reason) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        Report report = new Report("post", post.getId(), user.getUid(), reason);
        firebaseManager.addReport(report, task -> {
            if (!isAdded()) return;
            if (task.isSuccessful()) {
                Toast.makeText(getContext(), "Thank you for reporting. We will review this post.", Toast.LENGTH_LONG).show();
            } else {
                String error = "Failed to submit report.";
                if (task.getException() != null && task.getException().getMessage() != null) {
                    error = task.getException().getMessage();
                }
                Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openPostDetail(ForumPost post) {
        Intent intent = new Intent(getActivity(), PostDetailActivity.class);
        intent.putExtra(PostDetailActivity.EXTRA_POST_ID, post.getId());
        startActivity(intent);
    }

    private void openUserProfile(String userId) {
        Intent intent = new Intent(getActivity(), UserSocialProfileActivity.class);
        intent.putExtra(UserSocialProfileActivity.EXTRA_USER_ID, userId);
        startActivity(intent);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
