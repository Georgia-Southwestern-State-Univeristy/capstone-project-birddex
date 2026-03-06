package com.birddex.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import androidx.recyclerview.widget.RecyclerView;

import com.birddex.app.databinding.FragmentForumBinding;
import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.WriteBatch;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ForumFragment serves as the community hub for users to interact,
 * share sightings, and discuss birds.
 */
public class ForumFragment extends Fragment implements ForumPostAdapter.OnPostClickListener {

    private static final String TAG = "ForumFragment";
    private static final int PAGE_SIZE = 20;

    private static final String PREFS_NAME = "BirdDexPrefs";
    private static final String KEY_FILTER = "current_filter";
    private static final String KEY_GRAPHIC_CONTENT = "show_graphic_content";
    
    private FragmentForumBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseManager firebaseManager;
    private ForumPostAdapter adapter;
    
    private List<ForumPost> postList = new ArrayList<>();
    private DocumentSnapshot lastVisible;
    private boolean isFetching = false;
    private boolean isLastPage = false;
    private String currentFilter = "Recent";
    private List<String> followedIds = new ArrayList<>();

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

        // Load saved filter preference
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        currentFilter = prefs.getString(KEY_FILTER, "Recent");

        setupRecyclerView();
        setupClickListeners();
        loadUserProfilePicture();
        setupSwipeRefresh();
        
        return view;
    }

    private void setupRecyclerView() {
        adapter = new ForumPostAdapter(this);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        binding.rvForumPosts.setLayoutManager(layoutManager);
        binding.rvForumPosts.setAdapter(adapter);

        binding.rvForumPosts.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (dy > 0) { // Scrolling down
                    int visibleItemCount = layoutManager.getChildCount();
                    int totalItemCount = layoutManager.getItemCount();
                    int pastVisibleItems = layoutManager.findFirstVisibleItemPosition();

                    if (!isFetching && !isLastPage) {
                        if ((visibleItemCount + pastVisibleItems) >= totalItemCount) {
                            fetchPosts();
                        }
                    }
                }
            }
        });
    }

    private void setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener(() -> {
            refreshPosts();
        });
    }

    private void setupClickListeners() {
        binding.createPostCardView.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), CreatePostActivity.class);
            startActivity(intent);
        });

        binding.btnSocial.setOnClickListener(v -> {
            startActivity(new Intent(getActivity(), SocialActivity.class));
        });

        binding.btnFilter.setOnClickListener(this::showFilterMenu);
    }

    private void showFilterMenu(View v) {
        PopupMenu popup = new PopupMenu(getContext(), v);
        popup.getMenu().add("Recent");
        popup.getMenu().add("Following");

        popup.setOnMenuItemClickListener(item -> {
            String selectedFilter = item.getTitle().toString();
            if (!selectedFilter.equals(currentFilter)) {
                currentFilter = selectedFilter;
                
                // Save filter preference
                SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString(KEY_FILTER, currentFilter).apply();
                
                refreshPosts();
            }
            return true;
        });
        popup.show();
    }

    private void loadUserProfilePicture() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) return;

        db.collection("users").document(currentUser.getUid()).get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (!isAdded() || binding == null) return;
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

    private void refreshPosts() {
        isFetching = false;
        lastVisible = null;
        isLastPage = false;
        postList.clear();
        adapter.setPosts(new ArrayList<>());
        
        if ("Following".equals(currentFilter)) {
            fetchFollowedIdsAndLoad();
        } else {
            fetchPosts();
        }
    }

    private void fetchFollowedIdsAndLoad() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        isFetching = true;
        db.collection("users").document(user.getUid()).collection("following")
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    followedIds.clear();
                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        followedIds.add(doc.getId());
                    }
                    isFetching = false;
                    fetchPosts();
                })
                .addOnFailureListener(e -> {
                    isFetching = false;
                    if (binding != null) binding.swipeRefreshLayout.setRefreshing(false);
                });
    }

    private void fetchPosts() {
        if (isFetching || isLastPage) {
            if (binding != null) binding.swipeRefreshLayout.setRefreshing(false);
            return;
        }
        
        if ("Following".equals(currentFilter) && followedIds.isEmpty()) {
            if (binding != null) binding.swipeRefreshLayout.setRefreshing(false);
            Toast.makeText(getContext(), "You aren't following anyone yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        isFetching = true;

        Query query = db.collection("forumThreads")
                .orderBy("timestamp", Query.Direction.DESCENDING);

        if ("Following".equals(currentFilter)) {
            // Firestore whereIn limit is 30.
            List<String> limitedIds = followedIds.size() > 30 ? followedIds.subList(0, 30) : followedIds;
            query = query.whereIn("userId", limitedIds);
        }

        query = query.limit(PAGE_SIZE);

        if (lastVisible != null) {
            query = query.startAfter(lastVisible);
        }

        SharedPreferences sharedPreferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean showGraphicContent = sharedPreferences.getBoolean(KEY_GRAPHIC_CONTENT, false);

        query.get().addOnSuccessListener(value -> {
            if (!isAdded() || binding == null) return;
            binding.swipeRefreshLayout.setRefreshing(false);
            
            if (value != null && !value.isEmpty()) {
                lastVisible = value.getDocuments().get(value.size() - 1);
                
                for (DocumentSnapshot doc : value.getDocuments()) {
                    ForumPost post = doc.toObject(ForumPost.class);
                    if (post != null) {
                        post.setId(doc.getId());
                        if (showGraphicContent || !post.isHunted()) {
                            postList.add(post);
                        }
                    }
                }
                
                adapter.setPosts(new ArrayList<>(postList));
                
                if (value.size() < PAGE_SIZE) {
                    isLastPage = true;
                }
            } else {
                isLastPage = true;
            }
            isFetching = false;
        }).addOnFailureListener(e -> {
            isFetching = false;
            if (binding != null) binding.swipeRefreshLayout.setRefreshing(false);
            Log.e(TAG, "Error fetching posts", e);
        });
    }

    @Override
    public void onLikeClick(ForumPost post) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null || post.getId() == null) return;

        String userId = user.getUid();
        boolean currentlyLiked = post.getLikedBy() != null && post.getLikedBy().containsKey(userId);

        // Optimistic UI Update: change the count locally before Firestore finishes
        int currentCount = post.getLikeCount();
        if (currentlyLiked) {
            post.setLikeCount(Math.max(0, currentCount - 1));
            post.getLikedBy().remove(userId);
        } else {
            post.setLikeCount(currentCount + 1);
            post.getLikedBy().put(userId, true);
        }
        adapter.notifyDataSetChanged();

        // Perform actual Firestore update
        if (currentlyLiked) {
            db.collection("forumThreads").document(post.getId())
                    .update("likeCount", FieldValue.increment(-1),
                            "likedBy." + userId, FieldValue.delete())
                    .addOnFailureListener(e -> {
                        // Revert local state on failure
                        post.setLikeCount(currentCount);
                        post.getLikedBy().put(userId, true);
                        adapter.notifyDataSetChanged();
                        Toast.makeText(getContext(), "Failed to update like", Toast.LENGTH_SHORT).show();
                    });
        } else {
            db.collection("forumThreads").document(post.getId())
                    .update("likeCount", FieldValue.increment(1),
                            "likedBy." + userId, true)
                    .addOnFailureListener(e -> {
                        // Revert local state on failure
                        post.setLikeCount(currentCount);
                        post.getLikedBy().remove(userId);
                        adapter.notifyDataSetChanged();
                        Toast.makeText(getContext(), "Failed to update like", Toast.LENGTH_SHORT).show();
                    });
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

    @Override
    public void onMapClick(ForumPost post) {
        if (post.getLatitude() != null && post.getLongitude() != null) {
            Intent intent = new Intent(getActivity(), NearbyHeatmapActivity.class);
            intent.putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LAT, post.getLatitude());
            intent.putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LNG, post.getLongitude());
            intent.putExtra("extra_post_id", post.getId()); // Passing ID to focus on it
            startActivity(intent);
        }
    }

    private void showDeleteConfirmation(ForumPost post) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Post")
                .setMessage("Are you sure you want to delete this post? All comments and images will be archived.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    archiveAndDeletePost(post);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void archiveAndDeletePost(ForumPost post) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        String imageUrl = post.getBirdImageUrl();
        if (imageUrl != null && !imageUrl.isEmpty()) {
            moveImageToArchive(user.getUid(), post.getId(), imageUrl, new OnImageArchivedListener() {
                @Override
                public void onSuccess(String archivedUrl) {
                    post.setBirdImageUrl(archivedUrl);
                    handleCommentsArchiveAndDeletion(user.getUid(), post);
                }

                @Override
                public void onFailure(Exception e) {
                    Log.e(TAG, "Failed to archive image, proceeding with original URL", e);
                    handleCommentsArchiveAndDeletion(user.getUid(), post);
                }
            });
        } else {
            handleCommentsArchiveAndDeletion(user.getUid(), post);
        }
    }

    private void handleCommentsArchiveAndDeletion(String userId, ForumPost post) {
        // Fetch all comments to archive them before deleting the post
        db.collection("forumThreads").document(post.getId()).collection("comments")
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    WriteBatch batch = db.batch();
                    
                    // Archive and delete each comment
                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        Map<String, Object> commentBacklog = new HashMap<>();
                        commentBacklog.put("type", "comment_archived_with_post");
                        commentBacklog.put("originalId", doc.getId());
                        commentBacklog.put("postId", post.getId());
                        commentBacklog.put("data", doc.getData());
                        commentBacklog.put("deletedBy", userId);
                        commentBacklog.put("deletedAt", FieldValue.serverTimestamp());
                        
                        batch.set(db.collection("deletedforum_backlog").document(), commentBacklog);
                        batch.delete(doc.getReference());
                    }
                    
                    // Final post archive
                    Map<String, Object> postBacklog = new HashMap<>();
                    postBacklog.put("type", "post");
                    postBacklog.put("originalId", post.getId());
                    postBacklog.put("data", post);
                    postBacklog.put("deletedBy", userId);
                    postBacklog.put("deletedAt", FieldValue.serverTimestamp());
                    
                    batch.set(db.collection("deletedforum_backlog").document(), postBacklog);
                    
                    // Delete the post document itself
                    batch.delete(db.collection("forumThreads").document(post.getId()));
                    
                    batch.commit().addOnSuccessListener(aVoid -> {
                        if (!isAdded()) return;
                        Toast.makeText(getContext(), "Post and comments deleted", Toast.LENGTH_SHORT).show();
                        refreshPosts();
                    }).addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to execute deletion batch", e);
                        Toast.makeText(getContext(), "Error deleting post content", Toast.LENGTH_SHORT).show();
                    });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to fetch comments for deletion", e);
                    // Fallback: just delete the post
                    savePostToBacklogAndFirestore(userId, post);
                });
    }

    private interface OnImageArchivedListener {
        void onSuccess(String archivedUrl);
        void onFailure(Exception e);
    }

    private void moveImageToArchive(String userId, String postId, String originalUrl, OnImageArchivedListener listener) {
        FirebaseStorage storage = FirebaseStorage.getInstance();
        try {
            StorageReference oldRef = storage.getReferenceFromUrl(originalUrl);
            String fileName = oldRef.getName();
            StorageReference newRef = storage.getReference().child("archive/forum_post_images/" + userId + "/" + postId + "_" + fileName);

            oldRef.getBytes(10 * 1024 * 1024).addOnSuccessListener(bytes -> {
                newRef.putBytes(bytes).addOnSuccessListener(taskSnapshot -> {
                    newRef.getDownloadUrl().addOnSuccessListener(uri -> {
                        oldRef.delete().addOnCompleteListener(task -> {
                            listener.onSuccess(uri.toString());
                        });
                    }).addOnFailureListener(listener::onFailure);
                }).addOnFailureListener(listener::onFailure);
            }).addOnFailureListener(listener::onFailure);
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    private void savePostToBacklogAndFirestore(String userId, ForumPost post) {
        Map<String, Object> backlogData = new HashMap<>();
        backlogData.put("type", "post");
        backlogData.put("originalId", post.getId());
        backlogData.put("data", post);
        backlogData.put("deletedBy", userId);
        backlogData.put("deletedAt", FieldValue.serverTimestamp());

        db.collection("deletedforum_backlog").add(backlogData)
                .addOnSuccessListener(docRef -> {
                    firebaseManager.deleteForumPost(post.getId(), task -> {
                        if (!isAdded()) return;
                        if (task.isSuccessful()) {
                            Toast.makeText(getContext(), "Post deleted", Toast.LENGTH_SHORT).show();
                            refreshPosts();
                        }
                    });
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    Log.e(TAG, "Failed to backlog post", e);
                    Toast.makeText(getContext(), "Failed to delete post. Try again.", Toast.LENGTH_SHORT).show();
                });
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
    public void onResume() {
        super.onResume();
        if (binding != null) {
            binding.getRoot().postDelayed(this::refreshPosts, 1500);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
