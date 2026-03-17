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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.birddex.app.databinding.ActivityPostDetailBinding;
import com.bumptech.glide.Glide;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.WriteBatch;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PostDetailActivity displays a single forum post and its comments.
 * Fixes: Added isNavigating guard and fixed variable naming bug in toggleLike.
 */
/**
 * PostDetailActivity: Detailed post view that loads one post plus its comments/replies and handles interactions.
 *
 * Updated for the forum moderation hardening pass:
 * - new comments and text edits now go through callable Cloud Functions
 * - the frontend still validates immediately, but the backend now re-checks moderation and ownership
 * - likes, loading, and delete/archive behavior were left on their existing paths
 */
public class PostDetailActivity extends AppCompatActivity implements ForumCommentAdapter.OnCommentInteractionListener {

    private static final String TAG = "PostDetailActivity";
    public static final String EXTRA_POST_ID = "extra_post_id";
    private static final long EDIT_WINDOW_MS = 5 * 60 * 1000;
    private static final int COMMENTS_PAGE_SIZE = 25;
    private static final int MAX_POST_LENGTH = 500;
    private static final int MAX_COMMENT_LENGTH = 300;

    private ActivityPostDetailBinding binding;
    private FirebaseFirestore db;
    private FirebaseAuth mAuth;
    private FirebaseManager firebaseManager;
    private String postId;
    private ForumPost originalPost;
    private ForumCommentAdapter adapter;
    private String currentUsername;
    private String currentUserPfpUrl;

    private List<ForumComment> commentList = new ArrayList<>();
    private DocumentSnapshot lastCommentVisible;
    private boolean isFetchingComments = false;
    private boolean isLastCommentsPage = false;
    private boolean postLikeInFlight = false;

    private ForumComment replyingToComment = null;
    private boolean hasMarkedAsViewed = false;
    private final java.util.Set<String> commentLikeInFlight =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private boolean isNavigating = false;

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        binding = ActivityPostDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db = FirebaseFirestore.getInstance();
        mAuth = FirebaseAuth.getInstance();
        firebaseManager = new FirebaseManager(this);
        postId = getIntent().getStringExtra(EXTRA_POST_ID);

        if (postId == null) { finish(); return; }

        setupUI();
        loadCurrentUser();
        loadPostDetails();
        setupCommentsRecyclerView();
        fetchComments();
    }

    /**
     * Runs when the screen returns to the foreground, so it often refreshes UI state or restarts
     * listeners.
     */
    @Override
    protected void onResume() {
        super.onResume();
        isNavigating = false;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     */
    private void setupUI() {
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        // Attach the user interaction that should run when this control is tapped.
        binding.btnSendComment.setOnClickListener(v -> postComment());
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
    private void loadCurrentUser() {
        FirebaseUser user = mAuth.getCurrentUser();
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        if (user != null) db.collection("users").document(user.getUid()).get()
                .addOnSuccessListener(doc -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (doc.exists()) {
                        currentUsername = doc.getString("username");
                        currentUserPfpUrl = doc.getString("profilePictureUrl");
                        // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
                        Glide.with(this).load(currentUserPfpUrl).placeholder(R.drawable.ic_profile).into(binding.ivCurrentUserPfp);
                    }
                });
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Because it uses a snapshot listener, this method keeps the UI synced with live Firestore
     * updates instead of doing a one-time read.
     */
    private void loadPostDetails() {
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("forumThreads").document(postId)
                // Listen for real-time Firestore changes so the UI refreshes automatically when backend data changes.
                .addSnapshotListener(MetadataChanges.INCLUDE, (doc, error) -> {
                    if (isFinishing() || isDestroyed() || error != null || doc == null || !doc.exists()) return;
                    originalPost = doc.toObject(ForumPost.class);
                    if (originalPost != null) {
                        originalPost.setId(doc.getId());
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null && !hasMarkedAsViewed && !doc.getMetadata().hasPendingWrites()) handleViewTracking(user.getUid(), originalPost);
                        bindPostToLayout(originalPost);
                    }
                });
    }

    /**
     * Central handler that reacts to an event/input and decides what the next app action should
     * be.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void handleViewTracking(String userId, ForumPost post) {
        hasMarkedAsViewed = true;
        Map<String, Object> updates = new HashMap<>();
        if (post.getViewedBy() == null || !post.getViewedBy().containsKey(userId)) updates.put("viewedBy." + userId, true);
        if (userId.equals(post.getUserId())) {
            if (post.isNotificationSent()) updates.put("notificationSent", false);
            if (post.isLikeNotificationSent()) updates.put("likeNotificationSent", false);
            updates.put("lastViewedAt", FieldValue.serverTimestamp());
        }
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        if (!updates.isEmpty()) db.collection("forumThreads").document(postId).update(updates);
    }

    /**
     * Connects already-fetched data to views so the user can see the current state.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * Image loading happens here, which is why placeholder/error behavior for profile
     * photos/cards/posts usually traces back to this code path.
     */
    private void bindPostToLayout(ForumPost post) {
        if (isFinishing() || isDestroyed()) return;
        View v = binding.postContent.getRoot();
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        ((TextView) v.findViewById(R.id.tvPostUsername)).setText(post.getUsername());
        ((TextView) v.findViewById(R.id.tvPostMessage)).setText(post.isEdited() ? post.getMessage() + " (edited)" : post.getMessage());
        ((TextView) v.findViewById(R.id.tvLikeCount)).setText(String.valueOf(post.getLikeCount()));
        ((TextView) v.findViewById(R.id.tvCommentCount)).setText(String.valueOf(post.getCommentCount()));
        ((TextView) v.findViewById(R.id.tvViewCount)).setText(post.getViewCount() + " views");

        View sp = v.findViewById(R.id.tvSpottedBadge), hu = v.findViewById(R.id.tvHuntedBadge);
        if (sp != null) sp.setVisibility(post.isSpotted() ? View.VISIBLE : View.GONE);
        if (hu != null) hu.setVisibility(post.isHunted() ? View.VISIBLE : View.GONE);

        if (post.getTimestamp() != null) ((TextView) v.findViewById(R.id.tvPostTimestamp)).setText(DateUtils.getRelativeTimeSpanString(post.getTimestamp().toDate().getTime()));
        // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
        Glide.with(this).load(post.getUserProfilePictureUrl()).placeholder(R.drawable.ic_profile).into((ImageView) v.findViewById(R.id.ivPostUserProfilePicture));

        View cv = v.findViewById(R.id.cvPostImage);
        if (post.getBirdImageUrl() != null && !post.getBirdImageUrl().isEmpty()) { cv.setVisibility(View.VISIBLE); Glide.with(this).load(post.getBirdImageUrl()).into((ImageView) v.findViewById(R.id.ivPostBirdImage)); }
        else cv.setVisibility(View.GONE);

        View mapBtn = v.findViewById(R.id.btnViewOnMap);
        if (mapBtn != null) {
            if ((post.isSpotted() || post.isHunted()) && post.isShowLocation() && post.getLatitude() != null) {
                mapBtn.setVisibility(View.VISIBLE);
                // Attach the user interaction that should run when this control is tapped.
                mapBtn.setOnClickListener(view -> {
                    if (isNavigating) return;
                    isNavigating = true;
                    // Move into the next screen and pass the identifiers/data that screen needs.
                    startActivity(new Intent(this, NearbyHeatmapActivity.class).putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LAT, post.getLatitude()).putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LNG, post.getLongitude()).putExtra("extra_post_id", post.getId()));
                });
            } else mapBtn.setVisibility(View.GONE);
        }

        FirebaseUser user = mAuth.getCurrentUser();
        ((ImageView) v.findViewById(R.id.ivLikeIcon)).setImageResource((user != null && post.getLikedBy() != null && post.getLikedBy().containsKey(user.getUid())) ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        v.findViewById(R.id.btnLike).setOnClickListener(view -> toggleLike());
        v.findViewById(R.id.btnPostOptions).setOnClickListener(view -> showPostOptions(post, view));

        v.findViewById(R.id.ivPostUserProfilePicture).setOnClickListener(view -> openProfile(post.getUserId()));
        v.findViewById(R.id.tvPostUsername).setOnClickListener(view -> openProfile(post.getUserId()));
    }

    /**
     * Moves the user to another screen or flow and passes along the required extras.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    private void openProfile(String uid) {
        if (isNavigating) return;
        isNavigating = true;
        // Move into the next screen and pass the identifiers/data that screen needs.
        startActivity(new Intent(this, UserSocialProfileActivity.class).putExtra(UserSocialProfileActivity.EXTRA_USER_ID, uid));
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void showPostOptions(ForumPost post, View view) {
        PopupMenu popup = new PopupMenu(this, view);
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null && post.getUserId().equals(user.getUid())) {
            if (post.getTimestamp() != null && (System.currentTimeMillis() - post.getTimestamp().toDate().getTime() <= EDIT_WINDOW_MS)) popup.getMenu().add("Edit");
            popup.getMenu().add("Delete");
        }
        popup.getMenu().add("Report");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getTitle().equals("Edit")) showEditPostDialog(post);
            else if (item.getTitle().equals("Delete")) showDeleteConfirmation(post);
            else if (item.getTitle().equals("Report")) showReportDialog(post);
            return true;
        });
        popup.show();
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private void showEditPostDialog(ForumPost post) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Edit Post");
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(40, 20, 40, 20);
        final EditText i = new EditText(this); i.setText(post.getMessage()); i.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_POST_LENGTH)}); l.addView(i);
        final CheckBox s = new CheckBox(this); s.setText("Spotted"); s.setChecked(post.isSpotted()); l.addView(s);
        final CheckBox h = new CheckBox(this); h.setText("Hunted"); h.setChecked(post.isHunted()); l.addView(h);
        final SwitchMaterial loc = new SwitchMaterial(this); loc.setText("Show location"); loc.setChecked(post.isShowLocation()); l.addView(loc);
        b.setView(l);
        b.setPositiveButton("Save", (d, w) -> updatePost(post, i.getText().toString().trim(), s.isChecked(), h.isChecked(), loc.isChecked()));
        b.setNegativeButton("Cancel", null); b.show();
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void updatePost(ForumPost post, String msg, boolean spotted, boolean hunted, boolean loc) {
        if (!ContentFilter.isSafe(this, msg, "Post")) return;

        firebaseManager.updateForumPostContent(post.getId(), msg, spotted, hunted, loc, new FirebaseManager.ForumWriteListener() {
            @Override
            public void onSuccess() {
                if (isFinishing()) return;
                Toast.makeText(PostDetailActivity.this, "Updated", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(String errorMessage) {
                if (isFinishing()) return;
                Toast.makeText(PostDetailActivity.this, errorMessage != null ? errorMessage : "Failed to update post", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void showDeleteConfirmation(ForumPost post) {
        new AlertDialog.Builder(this).setTitle("Delete Post").setMessage("Are you sure?").setPositiveButton("Delete", (d, w) -> archiveAndDeletePost(post)).setNegativeButton("Cancel", null).show();
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void archiveAndDeletePost(ForumPost post) {
        firebaseManager.deleteForumPost(post.getId(), task -> {
            if (isFinishing() || isDestroyed()) return;
            if (task.isSuccessful()) {
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                String error = task.getException() != null && task.getException().getMessage() != null
                        ? task.getException().getMessage()
                        : "Failed to delete post.";
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
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
    private void handleCommentsArchiveAndDeletion(String uid, ForumPost post) {
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("forumThreads").document(post.getId()).collection("comments").get().addOnSuccessListener(snap -> {
            WriteBatch b = db.batch();
            for (DocumentSnapshot d : snap) {
                Map<String, Object> c = new HashMap<>(); c.put("type", "comment_archived_with_post"); c.put("originalId", d.getId()); c.put("postId", post.getId()); c.put("data", d.getData()); c.put("deletedBy", uid); c.put("deletedAt", FieldValue.serverTimestamp());
                // Persist the new state so the action is saved outside the current screen.
                b.set(db.collection("deletedforum_backlog").document(), c); b.delete(d.getReference());
            }
            Map<String, Object> p = new HashMap<>(); p.put("type", "post"); p.put("originalId", post.getId()); p.put("data", post); p.put("deletedBy", uid); p.put("deletedAt", FieldValue.serverTimestamp());
            b.set(db.collection("deletedforum_backlog").document(), p); b.delete(db.collection("forumThreads").document(post.getId()));
            // Give the user immediate feedback about the result of this action.
            b.commit().addOnSuccessListener(v -> { if (!isFinishing()) { Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show(); finish(); } });
        }).addOnFailureListener(e -> savePostToBacklogAndFirestore(uid, post));
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
    private void savePostToBacklogAndFirestore(String uid, ForumPost post) {
        WriteBatch b = db.batch();
        Map<String, Object> m = new HashMap<>(); m.put("type", "post"); m.put("originalId", post.getId()); m.put("data", post); m.put("deletedBy", uid); m.put("deletedAt", FieldValue.serverTimestamp());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        b.set(db.collection("deletedforum_backlog").document(), m);
        // Persist the new state so the action is saved outside the current screen.
        b.delete(db.collection("forumThreads").document(post.getId()));
        b.commit().addOnSuccessListener(v -> { if (!isFinishing()) finish(); });
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void showReportDialog(ForumPost post) {
        String[] rs = {"Inappropriate Language", "Inappropriate Image", "Spam", "Harassment", "Other"};
        new AlertDialog.Builder(this).setTitle("Report Post").setItems(rs, (d, w) -> {
            if (rs[w].equals("Other")) showOtherReportDialog(reason -> submitReport(post, reason));
            else submitReport(post, rs[w]);
        }).show();
    }

    /**
     * Main logic block for this part of the feature.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void submitReport(ForumPost post, String reason) {
        FirebaseUser u = mAuth.getCurrentUser(); if (u == null) return;
        // Give the user immediate feedback about the result of this action.
        firebaseManager.addReport(new Report("post", post.getId(), u.getUid(), reason), t -> { if (t.isSuccessful()) Toast.makeText(this, "Reported", Toast.LENGTH_SHORT).show(); });
    }

    /**
     * Flips a UI/data state and then updates the screen/backend to match the new value.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void toggleLike() {
        if (postLikeInFlight || originalPost == null) return;
        FirebaseUser user = mAuth.getCurrentUser(); if (user == null) return;
        postLikeInFlight = true;
        String uid = user.getUid(); boolean liked = originalPost.getLikedBy() != null && originalPost.getLikedBy().containsKey(uid);
        int count = originalPost.getLikeCount();
        if (liked) { originalPost.setLikeCount(Math.max(0, count - 1)); originalPost.getLikedBy().remove(uid); }
        else { originalPost.setLikeCount(count + 1); if (originalPost.getLikedBy() == null) originalPost.setLikedBy(new HashMap<>()); originalPost.getLikedBy().put(uid, true); }
        bindPostToLayout(originalPost);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("forumThreads").document(postId).update("likedBy." + uid, liked ? FieldValue.delete() : true).addOnCompleteListener(t -> { postLikeInFlight = false; if (!t.isSuccessful()) { originalPost.setLikeCount(count); if (liked) originalPost.getLikedBy().put(uid, true); else originalPost.getLikedBy().remove(uid); bindPostToLayout(originalPost); } });
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    private void setupCommentsRecyclerView() {
        // Hook the data source to the list/grid adapter so model objects can render as UI rows/cards.
        adapter = new ForumCommentAdapter(this);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        binding.rvComments.setLayoutManager(lm);
        binding.rvComments.setAdapter(adapter);
        binding.rvComments.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 0 && !isFetchingComments && !isLastCommentsPage) { if ((lm.getChildCount() + lm.findFirstVisibleItemPosition()) >= lm.getItemCount()) fetchComments(); }
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
     */
    private void fetchComments() {
        if (isFetchingComments || isLastCommentsPage) return;
        isFetchingComments = true;
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        Query q = db.collection("forumThreads").document(postId).collection("comments").orderBy("timestamp", Query.Direction.ASCENDING).limit(COMMENTS_PAGE_SIZE);
        if (lastCommentVisible != null) q = q.startAfter(lastCommentVisible);
        q.get().addOnSuccessListener(val -> {
            if (isFinishing() || isDestroyed()) return;
            if (val != null && !val.isEmpty()) {
                lastCommentVisible = val.getDocuments().get(val.size() - 1);
                for (DocumentSnapshot d : val.getDocuments()) { ForumComment c = d.toObject(ForumComment.class); if (c != null) { c.setId(d.getId()); commentList.add(c); } }
                adapter.setComments(new ArrayList<>(commentList));
                if (val.size() < COMMENTS_PAGE_SIZE) isLastCommentsPage = true;
            } else isLastCommentsPage = true;
            isFetchingComments = false;
        }).addOnFailureListener(e -> isFetchingComments = false);
    }

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void postComment() {
        String msg = binding.etComment.getText().toString().trim();
        if (msg.isEmpty() || msg.length() > MAX_COMMENT_LENGTH || !ContentFilter.isSafe(this, msg, "Comment")) return;
        FirebaseUser user = mAuth.getCurrentUser(); if (user == null) return;
        binding.btnSendComment.setEnabled(false);

        String cid = db.collection("forumThreads").document(postId).collection("comments").document().getId();
        String parentCommentId = replyingToComment != null ? replyingToComment.getId() : "";

        firebaseManager.createForumComment(postId, cid, msg, parentCommentId, new FirebaseManager.ForumWriteListener() {
            @Override
            public void onSuccess() {
                if (isFinishing()) return;
                binding.etComment.setText("");
                binding.etComment.setHint("Write a comment...");
                replyingToComment = null;
                binding.btnSendComment.setEnabled(true);
                lastCommentVisible = null;
                isLastCommentsPage = false;
                commentList.clear();
                fetchComments();
            }

            @Override
            public void onFailure(String errorMessage) {
                if (isFinishing()) return;
                binding.btnSendComment.setEnabled(true);
                Toast.makeText(PostDetailActivity.this, errorMessage != null ? errorMessage : "Failed", Toast.LENGTH_SHORT).show();
            }
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
    public void onCommentLikeClick(ForumComment comment) {
        FirebaseUser user = mAuth.getCurrentUser(); if (user == null) return;
        String uid = user.getUid();
        if (commentLikeInFlight.contains(comment.getId())) return;
        commentLikeInFlight.add(comment.getId());
        if (comment.getLikedBy() == null) comment.setLikedBy(new HashMap<>());
        boolean liked = comment.getLikedBy().containsKey(uid);
        int count = comment.getLikeCount();
        if (liked) { comment.setLikeCount(Math.max(0, count - 1)); comment.getLikedBy().remove(uid); }
        else { comment.setLikeCount(count + 1); comment.getLikedBy().put(uid, true); }
        adapter.notifyDataSetChanged();

        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("forumThreads").document(postId).collection("comments").document(comment.getId())
                // Persist the new state so the action is saved outside the current screen.
                .update("likedBy." + uid, liked ? FieldValue.delete() : true)
                .addOnCompleteListener(t -> {
                    commentLikeInFlight.remove(comment.getId());
                    if (!t.isSuccessful()) { comment.setLikeCount(count); if (liked) comment.getLikedBy().put(uid, true); else comment.getLikedBy().remove(uid); adapter.notifyDataSetChanged(); }
                });
    }

    @Override public void onCommentReplyClick(ForumComment c) { replyingToComment = c; binding.etComment.setHint("Replying to " + c.getUsername() + "..."); binding.etComment.requestFocus(); }
    @Override public void onCommentOptionsClick(ForumComment c, View v) { showCommentOptions(c, v); }
    @Override public void onUserClick(String uid) {
        if (isNavigating) return;
        isNavigating = true;
        startActivity(new Intent(this, UserSocialProfileActivity.class).putExtra(UserSocialProfileActivity.EXTRA_USER_ID, uid));
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void showCommentOptions(ForumComment c, View v) {
        PopupMenu popup = new PopupMenu(this, v); FirebaseUser user = mAuth.getCurrentUser();
        if (user != null && c.getUserId().equals(user.getUid())) {
            if (c.getTimestamp() != null && (System.currentTimeMillis() - c.getTimestamp().toDate().getTime() <= EDIT_WINDOW_MS)) popup.getMenu().add("Edit");
            popup.getMenu().add("Delete");
        }
        popup.getMenu().add("Report");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getTitle().equals("Edit")) showEditCommentDialog(c);
            else if (item.getTitle().equals("Delete")) showCommentDeleteConfirmation(c);
            else if (item.getTitle().equals("Report")) showCommentReportDialog(c);
            return true;
        });
        popup.show();
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void showEditCommentDialog(ForumComment c) {
        AlertDialog.Builder b = new AlertDialog.Builder(this); b.setTitle("Edit Comment");
        final EditText i = new EditText(this); i.setText(c.getText()); i.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_COMMENT_LENGTH)});
        FrameLayout container = new FrameLayout(this); FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); p.leftMargin = p.rightMargin = 40; i.setLayoutParams(p); container.addView(i); b.setView(container);
        b.setPositiveButton("Save", (d, w) -> { String msg = i.getText().toString().trim(); if (!msg.isEmpty() && !msg.equals(c.getText())) updateComment(c, msg); });
        b.setNegativeButton("Cancel", null); b.show();
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void updateComment(ForumComment c, String msg) {
        if (!ContentFilter.isSafe(this, msg, "Comment")) return;

        firebaseManager.updateForumCommentContent(postId, c.getId(), msg, new FirebaseManager.ForumWriteListener() {
            @Override
            public void onSuccess() {
                if (isFinishing()) return;
                Toast.makeText(PostDetailActivity.this, "Updated", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(String errorMessage) {
                if (isFinishing()) return;
                Toast.makeText(PostDetailActivity.this, errorMessage != null ? errorMessage : "Failed to update comment", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void showCommentDeleteConfirmation(ForumComment c) {
        new AlertDialog.Builder(this).setTitle("Delete Comment").setMessage("Are you sure?").setPositiveButton("Delete", (d, w) -> deleteCommentAndReplies(c)).setNegativeButton("Cancel", null).show();
    }

    /**
     * Removes data/listeners/items and then updates local state so the UI matches the deletion.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void deleteCommentAndReplies(ForumComment c) {
        firebaseManager.deleteForumComment(postId, c.getId(), task -> {
            if (isFinishing() || isDestroyed()) return;
            if (task.isSuccessful()) {
                lastCommentVisible = null;
                isLastCommentsPage = false;
                commentList.clear();
                fetchComments();
            } else {
                String error = task.getException() != null && task.getException().getMessage() != null
                        ? task.getException().getMessage()
                        : "Failed to delete comment.";
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void backlogByUserId(WriteBatch b, String uid, String type, String oid, Object data) {
        Map<String, Object> m = new HashMap<>(); m.put("type", type); m.put("originalId", oid); m.put("data", data); m.put("deletedBy", uid); m.put("deletedAt", FieldValue.serverTimestamp());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        b.set(db.collection("deletedforum_backlog").document(), m);
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void showCommentReportDialog(ForumComment c) {
        String[] rs = {"Inappropriate Language", "Spam", "Harassment", "Other"};
        new AlertDialog.Builder(this).setTitle("Report Comment").setItems(rs, (d, w) -> {
            if (rs[w].equals("Other")) showOtherReportDialog(reason -> submitCommentReport(c, reason));
            else submitCommentReport(c, rs[w]);
        }).show();
    }

    /**
     * Main logic block for this part of the feature.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void submitCommentReport(ForumComment c, String reason) {
        FirebaseUser u = mAuth.getCurrentUser(); if (u == null) return;
        // Give the user immediate feedback about the result of this action.
        firebaseManager.addReport(new Report("comment", c.getId(), u.getUid(), reason), t -> { if (t.isSuccessful()) Toast.makeText(this, "Reported", Toast.LENGTH_SHORT).show(); });
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void showOtherReportDialog(OnReasonEnteredListener l) {
        AlertDialog.Builder b = new AlertDialog.Builder(this); b.setTitle("Report Reason");
        final EditText i = new EditText(this); i.setHint("Specify reason..."); i.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        FrameLayout container = new FrameLayout(this); FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); p.leftMargin = p.rightMargin = 40; i.setLayoutParams(p); container.addView(i); b.setView(container);
        b.setPositiveButton("Submit", (d, w) -> { String r = i.getText().toString().trim(); if (!r.isEmpty()) l.onReasonEntered(r); });
        b.setNegativeButton("Cancel", null); b.show();
    }

    private interface OnReasonEnteredListener { void onReasonEntered(String r); }
}
