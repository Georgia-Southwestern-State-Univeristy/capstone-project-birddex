package com.birddex.app;

import android.app.Activity;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.birddex.app.databinding.FragmentForumBinding;
import com.bumptech.glide.Glide;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.Source;
import com.google.firebase.firestore.WriteBatch;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.ArrayList;
import java.util.Date;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ForumFragment: Main forum feed screen that loads posts, supports refresh/paging, and routes into post details.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
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
    private Runnable pendingRefreshRunnable = null;
    private int fetchGeneration = 0;

    private final Set<String> postLikeInFlight = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // FIX: Navigation guard
    private boolean isNavigating = false;

    private ActivityResultLauncher<Intent> createPostLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createPostLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != Activity.RESULT_OK) {
                        return;
                    }

                    ForumPost createdPost = extractCreatedPostFromResult(result.getData());
                    if (shouldOptimisticallyInsert(createdPost)) {
                        insertCreatedPostAtTop(createdPost);
                        fetchSinglePostAndReplace(createdPost.getId());
                    } else {
                        refreshPosts();
                    }
                }
        );
    }

    /**
     * Android calls this to inflate the Fragment's XML and return the root view that will be shown
     * on screen.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        binding = FragmentForumBinding.inflate(inflater, container, false);
        mAuth = FirebaseAuth.getInstance();
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db = FirebaseFirestore.getInstance();
        firebaseManager = new FirebaseManager(requireContext());
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        currentFilter = prefs.getString(KEY_FILTER, "Recent");
        setupRecyclerView();
        setupClickListeners();
        loadUserProfilePicture();
        setupSwipeRefresh();

        // Load the forum immediately the first time this view is created.
        if (postList.isEmpty() && !isFetching) {
            refreshPosts();
        }

        return binding.getRoot();
    }

    /**
     * Runs when the screen returns to the foreground, so it often refreshes UI state or restarts
     * listeners.
     */
    @Override
    public void onResume() {
        super.onResume();
        isNavigating = false; // Reset on return
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    private void setupRecyclerView() {
        // Hook the data source to the list/grid adapter so model objects can render as UI rows/cards.
        adapter = new ForumPostAdapter(this);
        LinearLayoutManager lm = new LinearLayoutManager(getContext());
        binding.rvForumPosts.setLayoutManager(lm);
        binding.rvForumPosts.setAdapter(adapter);
        binding.rvForumPosts.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 0 && !isFetching && !isLastPage) {
                    if ((lm.getChildCount() + lm.findFirstVisibleItemPosition()) >= lm.getItemCount()) fetchPosts();
                }
            }
        });
    }

    private void setupSwipeRefresh() { binding.swipeRefreshLayout.setOnRefreshListener(this::refreshPosts); }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    private void setupClickListeners() {
        // Attach the user interaction that should run when this control is tapped.
        binding.createPostCardView.setOnClickListener(v -> {
            if (isNavigating) return;
            isNavigating = true;
            // Move into the next screen and pass the identifiers/data that screen needs.
            createPostLauncher.launch(new Intent(getActivity(), CreatePostActivity.class));
        });

        binding.btnSocial.setOnClickListener(v -> {
            if (isNavigating) return;
            isNavigating = true;
            startActivity(new Intent(getActivity(), SocialActivity.class));
        });

        binding.btnFilter.setOnClickListener(this::showFilterMenu);
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     * This method also reads or writes local device preferences so some state survives app
     * restarts.
     */
    private void showFilterMenu(View v) {
        PopupMenu popup = new PopupMenu(getContext(), v);
        popup.getMenu().add("Recent");
        popup.getMenu().add("Following");
        popup.setOnMenuItemClickListener(item -> {
            String selected = item.getTitle().toString();
            if (!selected.equals(currentFilter)) {
                currentFilter = selected;
                requireContext()
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_FILTER, currentFilter)
                        .apply();

                // Switch the visible feed immediately so the old filter's posts
                // are not still shown while the new filter is loading.
                postList.clear();
                lastVisible = null;
                isLastPage = false;
                adapter.setPosts(new ArrayList<>());

                refreshPosts();
            }
            return true;
        });
        popup.show();
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     * Image loading happens here, which is why placeholder/error behavior for profile
     * photos/cards/posts usually traces back to this code path.
     */
    private void loadUserProfilePicture() {
        FirebaseUser user = mAuth.getCurrentUser();
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        if (user != null) db.collection("users").document(user.getUid()).get().addOnSuccessListener(doc -> {
            if (!isAdded() || binding == null) return;
            String url = doc.getString("profilePictureUrl");
            // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
            if (getContext() != null) Glide.with(this).load(url).placeholder(R.drawable.ic_profile).into(binding.ivUserProfilePicture);
        });
    }

    private ForumPost extractCreatedPostFromResult(@Nullable Intent data) {
        if (data == null) return null;

        String postId = data.getStringExtra(CreatePostActivity.EXTRA_CREATED_POST_ID);
        String userId = data.getStringExtra(CreatePostActivity.EXTRA_CREATED_POST_USER_ID);
        String username = data.getStringExtra(CreatePostActivity.EXTRA_CREATED_POST_USERNAME);
        String profilePicUrl = data.getStringExtra(CreatePostActivity.EXTRA_CREATED_POST_USER_PROFILE_PIC_URL);
        String message = data.getStringExtra(CreatePostActivity.EXTRA_CREATED_POST_MESSAGE);
        String imageUrl = data.getStringExtra(CreatePostActivity.EXTRA_CREATED_POST_IMAGE_URL);

        if (postId == null || userId == null) {
            return null;
        }

        ForumPost post = new ForumPost(userId, username, profilePicUrl, message, imageUrl);
        post.setId(postId);
        post.setTimestamp(new Timestamp(new Date()));
        post.setSpotted(data.getBooleanExtra(CreatePostActivity.EXTRA_CREATED_POST_SPOTTED, false));
        post.setHunted(data.getBooleanExtra(CreatePostActivity.EXTRA_CREATED_POST_HUNTED, false));
        post.setShowLocation(data.getBooleanExtra(CreatePostActivity.EXTRA_CREATED_POST_SHOW_LOCATION, false));

        if (data.hasExtra(CreatePostActivity.EXTRA_CREATED_POST_LATITUDE)) {
            post.setLatitude(data.getDoubleExtra(CreatePostActivity.EXTRA_CREATED_POST_LATITUDE, 0d));
        }
        if (data.hasExtra(CreatePostActivity.EXTRA_CREATED_POST_LONGITUDE)) {
            post.setLongitude(data.getDoubleExtra(CreatePostActivity.EXTRA_CREATED_POST_LONGITUDE, 0d));
        }

        return post;
    }

    private boolean shouldOptimisticallyInsert(@Nullable ForumPost post) {
        if (post == null || post.getId() == null || !isAdded() || binding == null) {
            return false;
        }

        if ("Following".equals(currentFilter)) {
            return false;
        }

        boolean showGraphic = requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_GRAPHIC_CONTENT, false);

        return isForumPostVisible(post) && (showGraphic || !post.isHunted());
    }

    private void insertCreatedPostAtTop(@NonNull ForumPost post) {
        removePostFromLocalList(post.getId());
        postList.add(0, post);
        adapter.insertPostAtTop(post);
        binding.rvForumPosts.scrollToPosition(0);
    }

    private void fetchSinglePostAndReplace(@Nullable String postId) {
        if (postId == null || !isAdded() || binding == null) return;

        boolean showGraphic = requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_GRAPHIC_CONTENT, false);

        db.collection("forumThreads")
                .document(postId)
                .get(Source.SERVER)
                .addOnSuccessListener(doc -> {
                    if (!isAdded() || binding == null || !doc.exists()) return;

                    ForumPost serverPost = doc.toObject(ForumPost.class);
                    if (serverPost == null) return;

                    serverPost.setId(doc.getId());

                    if (!isForumPostVisible(serverPost) || (!showGraphic && serverPost.isHunted())) {
                        removePostFromLocalList(postId);
                        adapter.removePostById(postId);
                        return;
                    }

                    int index = findLocalPostIndex(postId);
                    if (index >= 0) {
                        postList.set(index, serverPost);
                        adapter.replacePostById(serverPost);
                    }
                });
    }

    private int findLocalPostIndex(@Nullable String postId) {
        if (postId == null) return -1;

        for (int i = 0; i < postList.size(); i++) {
            ForumPost post = postList.get(i);
            if (post != null && postId.equals(post.getId())) {
                return i;
            }
        }
        return -1;
    }

    private void removePostFromLocalList(@Nullable String postId) {
        int index = findLocalPostIndex(postId);
        if (index >= 0) {
            postList.remove(index);
        }
    }

    /**
     * FIX: Resetting fetch state with generation increment to handle overlapping requests.
     */
    /**
     * Main logic block for this part of the feature.
     */
    private void refreshPosts() {
        fetchGeneration++;
        isFetching = false;
        lastVisible = null;
        isLastPage = false;
        // Don't clear postList here to avoid flickering if a new fetch is already starting.
        // It will be cleared inside fetchPosts when the SUCCESSFUL generation returns.
        if ("Following".equals(currentFilter)) fetchFollowedIdsAndLoad(fetchGeneration);
        else fetchPosts();
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    private void fetchFollowedIdsAndLoad(int generation) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            if (binding != null) binding.swipeRefreshLayout.setRefreshing(false);
            return;
        }

        isFetching = true;

        db.collection("users")
                .document(user.getUid())
                .collection("following")
                .get()
                .addOnSuccessListener(snap -> {
                    if (!isAdded() || fetchGeneration != generation) return;

                    followedIds.clear();
                    for (DocumentSnapshot doc : snap.getDocuments()) {
                        followedIds.add(doc.getId());
                    }

                    if (followedIds.isEmpty()) {
                        postList.clear();
                        lastVisible = null;
                        isLastPage = true;
                        adapter.setPosts(new ArrayList<>());
                        isFetching = false;
                        if (binding != null) binding.swipeRefreshLayout.setRefreshing(false);

                        MessagePopupHelper.showBrief(requireContext(), "You are not following anyone yet.");
                        return;
                    }

                    isFetching = false;
                    fetchPosts();
                })
                .addOnFailureListener(e -> {
                    if (fetchGeneration == generation) {
                        isFetching = false;
                        if (binding != null) binding.swipeRefreshLayout.setRefreshing(false);
                    }
                });
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     * This method also reads or writes local device preferences so some state survives app
     * restarts.
     */
    private void fetchPosts() {
        if (!isAdded() || getContext() == null || isFetching || isLastPage) {
            if (binding != null) binding.swipeRefreshLayout.setRefreshing(false);
            return;
        }
        if ("Following".equals(currentFilter) && followedIds.isEmpty()) {
            if (binding != null) binding.swipeRefreshLayout.setRefreshing(false);
            return;
        }

        isFetching = true;
        final int myGen = fetchGeneration;
        final boolean showGraphic = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_GRAPHIC_CONTENT, false);

        if (lastVisible == null) {
            Query firstPageQuery = buildForumBaseQuery();
            firstPageQuery.get(Source.CACHE).addOnSuccessListener(val -> {
                if (!isAdded() || binding == null || fetchGeneration != myGen) return;
                if (val != null && !val.isEmpty()) {
                    applyForumFirstPageSnapshot(val, showGraphic, false, myGen);
                }
                fetchForumFirstPageFromServer(firstPageQuery, showGraphic, myGen);
            }).addOnFailureListener(e -> fetchForumFirstPageFromServer(firstPageQuery, showGraphic, myGen));
            return;
        }

        buildForumBaseQuery()
                .startAfter(lastVisible)
                .get(Source.SERVER)
                .addOnSuccessListener(val -> {
                    if (!isAdded() || binding == null || fetchGeneration != myGen) return;
                    appendForumPageSnapshot(val, showGraphic, myGen);
                    finishForumFetch(myGen);
                })
                .addOnFailureListener(e -> finishForumFetch(myGen));
    }

    private Query buildForumBaseQuery() {
        Query q = db.collection("forumThreads").orderBy("timestamp", Query.Direction.DESCENDING);
        if ("Following".equals(currentFilter)) {
            q = q.whereIn("userId", followedIds.size() > 30 ? followedIds.subList(0, 30) : followedIds);
        }
        return q.limit(PAGE_SIZE);
    }

    private boolean isForumPostVisible(ForumPost post) {
        if (post == null) return false;
        String status = post.getModerationStatus();
        return status == null
                || status.isEmpty()
                || "visible".equalsIgnoreCase(status)
                || "under_review".equalsIgnoreCase(status);
    }

    private void fetchForumFirstPageFromServer(Query firstPageQuery, boolean showGraphic, int generation) {
        firstPageQuery.get(Source.SERVER).addOnSuccessListener(val -> {
            if (!isAdded() || binding == null || fetchGeneration != generation) return;
            applyForumFirstPageSnapshot(val, showGraphic, true, generation);
            finishForumFetch(generation);
        }).addOnFailureListener(e -> finishForumFetch(generation));
    }

    private void applyForumFirstPageSnapshot(com.google.firebase.firestore.QuerySnapshot value, boolean showGraphic, boolean fromServer, int generation) {
        if (!isAdded() || binding == null || fetchGeneration != generation) return;
        if (fromServer || (value != null && !value.isEmpty())) {
            postList.clear();
            lastVisible = null;
            isLastPage = false;
        }

        if (value != null && !value.isEmpty()) {
            lastVisible = value.getDocuments().get(value.size() - 1);
            for (DocumentSnapshot doc : value.getDocuments()) {
                ForumPost p = doc.toObject(ForumPost.class);
                if (p != null) {
                    p.setId(doc.getId());
                    if (isForumPostVisible(p) && (showGraphic || !p.isHunted())) postList.add(p);
                }
            }
            if (value.size() < PAGE_SIZE) isLastPage = true;
        } else if (fromServer) {
            isLastPage = true;
        }

        adapter.setPosts(new ArrayList<>(postList));
    }

    private void appendForumPageSnapshot(com.google.firebase.firestore.QuerySnapshot value, boolean showGraphic, int generation) {
        if (!isAdded() || binding == null || fetchGeneration != generation) return;
        if (value != null && !value.isEmpty()) {
            lastVisible = value.getDocuments().get(value.size() - 1);
            for (DocumentSnapshot doc : value.getDocuments()) {
                ForumPost p = doc.toObject(ForumPost.class);
                if (p != null) {
                    p.setId(doc.getId());
                    if (isForumPostVisible(p) && (showGraphic || !p.isHunted())) postList.add(p);
                }
            }
            adapter.setPosts(new ArrayList<>(postList));
            if (value.size() < PAGE_SIZE) isLastPage = true;
        } else {
            isLastPage = true;
        }
    }

    private void finishForumFetch(int generation) {
        if (fetchGeneration == generation) {
            isFetching = false;
            if (binding != null) binding.swipeRefreshLayout.setRefreshing(false);
        }
    }

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    @Override
    public void onLikeClick(ForumPost p) {
        FirebaseUser u = mAuth.getCurrentUser();
        if (u == null || p.getId() == null || postLikeInFlight.contains(p.getId())) return;
        postLikeInFlight.add(p.getId());

        String uid = u.getUid(); boolean liked = p.getLikedBy() != null && p.getLikedBy().containsKey(uid);
        int count = p.getLikeCount();

        // Optimistic UI update
        if (liked) { p.setLikeCount(Math.max(0, count - 1)); p.getLikedBy().remove(uid); }
        else { p.setLikeCount(count + 1); if (p.getLikedBy() == null) p.setLikedBy(new HashMap<>()); p.getLikedBy().put(uid, true); }
        adapter.notifyDataSetChanged();

        firebaseManager.toggleForumPostLike(p.getId(), !liked, new FirebaseManager.ActionListener() {
            @Override
            public void onSuccess() {
                postLikeInFlight.remove(p.getId());
            }

            @Override
            public void onFailure(String errorMessage) {
                postLikeInFlight.remove(p.getId());
                p.setLikeCount(count); if (liked) p.getLikedBy().put(uid, true); else p.getLikedBy().remove(uid);
                adapter.notifyDataSetChanged();
                if (isAdded()) {
                    MessagePopupHelper.showBrief(getContext(), (errorMessage != null && !errorMessage.trim().isEmpty()) ? errorMessage : "Failed to update like status.");
                }
            }
        });
    }

    @Override public void onCommentClick(ForumPost p) { onPostClick(p); }
    @Override public void onPostClick(ForumPost p) {
        if (isNavigating) return;
        isNavigating = true;
        startActivity(new Intent(getActivity(), PostDetailActivity.class).putExtra(PostDetailActivity.EXTRA_POST_ID, p.getId()));
    }

    @Override public void onOptionsClick(ForumPost p, View v) {
        FirebaseUser u = mAuth.getCurrentUser();
        if (u == null) {
            showResolvedPostOptions(p, v, false);
            return;
        }

        firebaseManager.isForumPostSaved(p.getId(), task -> {
            boolean isSaved = task.isSuccessful() && task.getResult() != null && task.getResult();
            if (!isAdded()) return;
            showResolvedPostOptions(p, v, isSaved);
        });
    }

    private void showResolvedPostOptions(ForumPost p, View v, boolean isSaved) {
        PopupMenu popup = new PopupMenu(getContext(), v);
        FirebaseUser u = mAuth.getCurrentUser();
        if (u != null && p.getUserId().equals(u.getUid())) popup.getMenu().add("Delete");
        popup.getMenu().add(isSaved ? "Unsave Post" : "Save Post");
        if (u != null && !p.getUserId().equals(u.getUid())) popup.getMenu().add("Report");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getTitle().equals("Delete")) showDeleteConfirmation(p);
            else if (item.getTitle().equals("Save Post")) savePostForLater(p);
            else if (item.getTitle().equals("Unsave Post")) unsavePost(p);
            else if (item.getTitle().equals("Report")) showReportDialog(p);
            return true;
        });
        popup.show();
    }

    private void savePostForLater(ForumPost p) {
        firebaseManager.saveForumPost(p.getId(), new FirebaseManager.ForumWriteListener() {
            @Override public void onSuccess() {
                if (isAdded()) MessagePopupHelper.showBrief(requireContext(), "Post saved");
            }

            @Override public void onFailure(String errorMessage) {
                if (isAdded()) MessagePopupHelper.showBrief(requireContext(), errorMessage != null ? errorMessage : "Failed to save post.");
            }
        });
    }

    private void unsavePost(ForumPost p) {
        firebaseManager.unsaveForumPost(p.getId(), new FirebaseManager.ForumWriteListener() {
            @Override public void onSuccess() {
                if (isAdded()) MessagePopupHelper.showBrief(requireContext(), "Post unsaved");
            }

            @Override public void onFailure(String errorMessage) {
                if (isAdded()) MessagePopupHelper.showBrief(requireContext(), errorMessage != null ? errorMessage : "Failed to unsave post.");
            }
        });
    }

    @Override public void onUserClick(String uid) {
        if (isNavigating) return;
        isNavigating = true;
        startActivity(new Intent(getActivity(), UserSocialProfileActivity.class).putExtra(UserSocialProfileActivity.EXTRA_USER_ID, uid));
    }

    @Override public void onMapClick(ForumPost p) {
        if (isNavigating) return;
        if (p.getLatitude() != null && p.getLongitude() != null) {
            isNavigating = true;
            Intent i = new Intent(getActivity(), NearbyHeatmapActivity.class);
            i.putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LAT, p.getLatitude());
            i.putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LNG, p.getLongitude());
            i.putExtra("extra_post_id", p.getId());
            startActivity(i);
        }
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void showDeleteConfirmation(ForumPost p) {
        new AlertDialog.Builder(requireContext()).setTitle("Delete Post").setMessage("Are you sure?").setPositiveButton("Delete", (d, w) -> archiveAndDeletePost(p)).setNegativeButton("Cancel", null).show();
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void archiveAndDeletePost(ForumPost p) {
        firebaseManager.deleteForumPost(p.getId(), task -> {
            if (!isAdded()) return;
            if (task.isSuccessful()) {
                refreshPosts();
            } else {
                String error = task.getException() != null && task.getException().getMessage() != null
                        ? task.getException().getMessage()
                        : "Failed to delete post.";
                MessagePopupHelper.showBrief(requireContext(), error);
            }
        });
    }

    /**
     * Central handler that reacts to an event/input and decides what the next app action should
     * be.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void handleCommentsArchiveAndDeletion(String uid, ForumPost p) {
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("forumThreads").document(p.getId()).collection("comments").get().addOnSuccessListener(snap -> {
            WriteBatch b = db.batch();
            for (DocumentSnapshot doc : snap) {
                Map<String, Object> m = new HashMap<>(); m.put("type", "comment_archived_with_post"); m.put("originalId", doc.getId()); m.put("postId", p.getId()); m.put("data", doc.getData()); m.put("deletedBy", uid); m.put("deletedAt", FieldValue.serverTimestamp());
                // Persist the new state so the action is saved outside the current screen.
                b.set(db.collection("deletedforum_backlog").document(), m); b.delete(doc.getReference());
            }
            Map<String, Object> pb = new HashMap<>(); pb.put("type", "post"); pb.put("originalId", p.getId()); pb.put("data", p); pb.put("deletedBy", uid); pb.put("deletedAt", FieldValue.serverTimestamp());
            b.set(db.collection("deletedforum_backlog").document(), pb); b.delete(db.collection("forumThreads").document(p.getId()));
            b.commit().addOnSuccessListener(v -> { if (isAdded()) refreshPosts(); });
        }).addOnFailureListener(e -> savePostToBacklogAndFirestore(uid, p));
    }

    private interface OnImageArchivedListener { void onSuccess(String url); void onFailure(Exception e); }

    /**
     * Main logic block for this part of the feature.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void moveImageToArchive(String uid, String pid, String url, OnImageArchivedListener l) {
        FirebaseStorage s = FirebaseStorage.getInstance();
        try {
            StorageReference old = s.getReferenceFromUrl(url);
            StorageReference next = s.getReference().child("archive/forum_post_images/" + uid + "/" + pid + "_" + old.getName());
            // Persist the new state so the action is saved outside the current screen.
            old.getBytes(10 * 1024 * 1024).addOnSuccessListener(bytes -> next.putBytes(bytes).addOnSuccessListener(ts -> next.getDownloadUrl().addOnSuccessListener(uri -> old.delete().addOnCompleteListener(t -> l.onSuccess(uri.toString()))).addOnFailureListener(l::onFailure)).addOnFailureListener(l::onFailure)).addOnFailureListener(l::onFailure);
        } catch (Exception e) { l.onFailure(e); }
    }

    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void savePostToBacklogAndFirestore(String uid, ForumPost p) {
        WriteBatch b = db.batch(); Map<String, Object> m = new HashMap<>();
        m.put("type", "post"); m.put("originalId", p.getId()); m.put("data", p); m.put("deletedBy", uid); m.put("deletedAt", FieldValue.serverTimestamp());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        b.set(db.collection("deletedforum_backlog").document(), m); b.delete(db.collection("forumThreads").document(p.getId()));
        b.commit().addOnSuccessListener(v -> { if (isAdded()) refreshPosts(); });
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void showReportDialog(ForumPost p) {
        String[] rs = {"Inappropriate Language", "Inappropriate Image", "Spam", "Harassment", "Other"};
        new AlertDialog.Builder(requireContext()).setTitle("Report Post").setItems(rs, (d, w) -> {
            if (rs[w].equals("Other")) showOtherReportDialog(reason -> submitReport(p, reason));
            else submitReport(p, rs[w]);
        }).show();
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void showOtherReportDialog(OnReasonEnteredListener l) {
        AlertDialog.Builder b = new AlertDialog.Builder(requireContext()); b.setTitle("Report Reason");
        final EditText i = new EditText(requireContext()); i.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); i.setHint("Reason..."); i.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        FrameLayout c = new FrameLayout(requireContext()); FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); p.leftMargin = p.rightMargin = 40; i.setLayoutParams(p); c.addView(i); b.setView(c);
        b.setPositiveButton("Submit", (d, w) -> { String r = i.getText().toString().trim(); if (!r.isEmpty()) l.onReasonEntered(r); });
        b.setNegativeButton("Cancel", (d, w) -> d.cancel()); b.show();
    }

    private interface OnReasonEnteredListener { void onReasonEntered(String r); }

    /**
     * Main logic block for this part of the feature.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void submitReport(ForumPost p, String r) {
        FirebaseUser u = mAuth.getCurrentUser(); if (u == null) return;
        // Give the user immediate feedback about the result of this action.
        firebaseManager.addReport(new Report("post", p.getId(), u.getUid(), r), t -> { if (isAdded() && t.isSuccessful()) MessagePopupHelper.showBrief(getContext(), "Reported"); });
    }

    /**
     * Fragment cleanup point for clearing view references and listeners tied to the Fragment's
     * view.
     */
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (binding != null && pendingRefreshRunnable != null) binding.getRoot().removeCallbacks(pendingRefreshRunnable);
        pendingRefreshRunnable = null;
        binding = null;
    }
}