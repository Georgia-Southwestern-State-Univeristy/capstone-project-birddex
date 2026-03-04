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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.birddex.app.databinding.FragmentForumBinding;
import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.List;

/**
 * ForumFragment serves as the community hub for users to interact,
 * share sightings, and discuss birds.
 */
public class ForumFragment extends Fragment implements ForumPostAdapter.OnPostClickListener {

    private static final String TAG = "ForumFragment";
    private static final int PAGE_SIZE = 20;

    private static final String PREFS_NAME = "ForumPrefs";
    private static final String KEY_FILTER = "current_filter";
    
    private FragmentForumBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseManager firebaseManager;
    private ForumPostAdapter adapter;
    
    private List<ForumPost> postList = new ArrayList<>();
    private DocumentSnapshot lastVisible;
    private boolean isFetching = false;
    private boolean isLastPage = false;
    private ListenerRegistration postsListener;
    private String currentFilter = "Recent";

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
        
        refreshPosts();
        applyFilter(); // Load initial data based on saved filter

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
                
                applyFilter();
            }
            return true;
        });
        popup.show();
    }

    private void applyFilter() {
        if (postsListener != null) {
            postsListener.remove();
        }
        
        if ("Following".equals(currentFilter)) {
            listenForFollowingPosts();
        } else {
            listenForPosts();
        }
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
        lastVisible = null;
        isLastPage = false;
        postList.clear();
        fetchPosts();
    }

    private void fetchPosts() {
        if (isFetching || isLastPage) {
            if (binding != null) binding.swipeRefreshLayout.setRefreshing(false);
            return;
        }
        isFetching = true;

        Query query = db.collection("forumThreads")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(PAGE_SIZE);

        if (lastVisible != null) {
            query = query.startAfter(lastVisible);
        }

        query.get().addOnSuccessListener(value -> {
            if (!isAdded() || binding == null) return;
            binding.swipeRefreshLayout.setRefreshing(false);
            
            if (value != null && !value.isEmpty()) {
                lastVisible = value.getDocuments().get(value.size() - 1);
                
                for (DocumentSnapshot doc : value.getDocuments()) {
                    ForumPost post = doc.toObject(ForumPost.class);
                    if (post != null) {
                        post.setId(doc.getId());
                        postList.add(post);
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

    private void listenForFollowingPosts() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) return;

        db.collection("users").document(currentUser.getUid()).collection("following")
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (!isAdded() || binding == null) return;
                    
                    List<String> followedIds = new ArrayList<>();
                    for (DocumentSnapshot doc : queryDocumentSnapshots.getDocuments()) {
                        followedIds.add(doc.getId());
                    }

                    if (followedIds.isEmpty()) {
                        adapter.setPosts(new ArrayList<>());
                        Toast.makeText(getContext(), "You are not following anyone yet.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // Firestore whereIn limit is 30. For simplicity, we use the first 30.
                    if (followedIds.size() > 30) {
                        followedIds = followedIds.subList(0, 30);
                    }

                    postsListener = db.collection("forumThreads")
                            .whereIn("userId", followedIds)
                            .orderBy("timestamp", Query.Direction.DESCENDING)
                            .addSnapshotListener(MetadataChanges.INCLUDE, (value, error) -> {
                                if (!isAdded() || binding == null) return;
                                if (error != null) {
                                    Log.e(TAG, "Following listen failed.", error);
                                    return;
                                }

                                if (value != null) {
                                    processSnapshot(value.getDocuments(), value.getMetadata().isFromCache());
                                }
                            });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error fetching following list", e);
                });
    }

    private void processSnapshot(List<DocumentSnapshot> documents, boolean isFromCache) {
        List<ForumPost> posts = new ArrayList<>();
        for (DocumentSnapshot doc : documents) {
            ForumPost post = doc.toObject(ForumPost.class);
            if (post != null) {
                post.setId(doc.getId());
                posts.add(post);
            }
        }
        adapter.setPosts(posts);

        if (isFromCache) {
            Log.d(TAG, "Forum data loaded from local cache");
        } else {
            Log.d(TAG, "Forum data synchronized with server");
        }
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
                            refreshPosts(); // Refresh after deletion
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
        if (postsListener != null) {
            postsListener.remove();
        }
        binding = null;
    }
}
