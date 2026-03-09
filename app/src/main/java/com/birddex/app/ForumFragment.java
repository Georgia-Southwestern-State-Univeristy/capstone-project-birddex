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
        if (binding != null) {
            pendingRefreshRunnable = () -> { if (binding != null && !isFetching) refreshPosts(); };
            binding.getRoot().postDelayed(pendingRefreshRunnable, 1500);
        }
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
            startActivity(new Intent(getActivity(), CreatePostActivity.class));
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
                requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_FILTER, currentFilter).apply();
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
        if (user == null) return;
        isFetching = true;
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("users").document(user.getUid()).collection("following").get().addOnSuccessListener(snap -> {
            if (!isAdded() || fetchGeneration != generation) return;
            followedIds.clear();
            for (DocumentSnapshot doc : snap.getDocuments()) followedIds.add(doc.getId());
            isFetching = false;
            fetchPosts();
        }).addOnFailureListener(e -> {
            if (fetchGeneration == generation) { isFetching = false; if (binding != null) binding.swipeRefreshLayout.setRefreshing(false); }
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
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        Query q = db.collection("forumThreads").orderBy("timestamp", Query.Direction.DESCENDING);
        if ("Following".equals(currentFilter)) q = q.whereIn("userId", followedIds.size() > 30 ? followedIds.subList(0, 30) : followedIds);
        q = q.limit(PAGE_SIZE);
        if (lastVisible != null) q = q.startAfter(lastVisible);

        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean showGraphic = prefs.getBoolean(KEY_GRAPHIC_CONTENT, false);

        q.get().addOnSuccessListener(val -> {
            if (!isAdded() || binding == null || fetchGeneration != myGen) return;
            
            // If this is the start of a new generation, clear the list now.
            if (lastVisible == null) {
                postList.clear();
            }
            
            binding.swipeRefreshLayout.setRefreshing(false);
            if (val != null && !val.isEmpty()) {
                lastVisible = val.getDocuments().get(val.size() - 1);
                for (DocumentSnapshot doc : val.getDocuments()) {
                    ForumPost p = doc.toObject(ForumPost.class);
                    if (p != null) { p.setId(doc.getId()); if (showGraphic || !p.isHunted()) postList.add(p); }
                }
                adapter.setPosts(new ArrayList<>(postList));
                if (val.size() < PAGE_SIZE) isLastPage = true;
            } else isLastPage = true;
            isFetching = false;
        }).addOnFailureListener(e -> {
            if (fetchGeneration == myGen) { isFetching = false; if (binding != null) binding.swipeRefreshLayout.setRefreshing(false); }
        });
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

        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("forumThreads").document(p.getId()).update("likedBy." + uid, liked ? FieldValue.delete() : true)
                .addOnCompleteListener(t -> {
                    // FIX: Ensure interaction is re-enabled only after UI is definitely back in sync.
                    postLikeInFlight.remove(p.getId());
                    if (!t.isSuccessful()) {
                        // Revert on failure
                        p.setLikeCount(count); if (liked) p.getLikedBy().put(uid, true); else p.getLikedBy().remove(uid);
                        adapter.notifyDataSetChanged();
                        // Give the user immediate feedback about the result of this action.
                        if (isAdded()) Toast.makeText(getContext(), "Failed to update like status.", Toast.LENGTH_SHORT).show();
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
        PopupMenu popup = new PopupMenu(getContext(), v); FirebaseUser u = mAuth.getCurrentUser();
        if (u != null && p.getUserId().equals(u.getUid())) popup.getMenu().add("Delete");
        popup.getMenu().add("Report");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getTitle().equals("Delete")) showDeleteConfirmation(p);
            else if (item.getTitle().equals("Report")) showReportDialog(p);
            return true;
        });
        popup.show();
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
        FirebaseUser u = mAuth.getCurrentUser(); if (u == null) return;
        if (p.getBirdImageUrl() != null && !p.getBirdImageUrl().isEmpty()) {
            moveImageToArchive(u.getUid(), p.getId(), p.getBirdImageUrl(), new OnImageArchivedListener() {
                @Override public void onSuccess(String url) { p.setBirdImageUrl(url); handleCommentsArchiveAndDeletion(u.getUid(), p); }
                @Override public void onFailure(Exception e) { handleCommentsArchiveAndDeletion(u.getUid(), p); }
            });
        } else handleCommentsArchiveAndDeletion(u.getUid(), p);
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
        firebaseManager.addReport(new Report("post", p.getId(), u.getUid(), r), t -> { if (isAdded() && t.isSuccessful()) Toast.makeText(getContext(), "Reported", Toast.LENGTH_SHORT).show(); });
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
