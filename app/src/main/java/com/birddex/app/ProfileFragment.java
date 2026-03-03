package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.content.SharedPreferences;
import android.view.View;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
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
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.Source;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ProfileFragment extends Fragment implements
        FavoritesAdapter.OnFavoriteInteractionListener,
        ForumPostAdapter.OnPostClickListener {

    private static final String TAG = "ProfileFragment";
    private static final String ARG_USER_ID = "arg_user_id";
    private static final int FAVORITE_SLOT_COUNT = 3;
    private static final String FAVORITES_PREFS = "profile_favorite_cards";

    private ShapeableImageView ivPfp;
    private TextView tvUsername;
    private TextView tvPoints;
    private EditText etBio;
    private TextView tvFollowerCount;
    private TextView tvFollowingCount;
    private MaterialButton btnFollow;
    private ImageButton btnSettings;
    private ImageButton btnEditProfile;
    private View btnFollowers;
    private View btnFollowing;

    private TabLayout profileTabLayout;
    private TextView tvProfileTabEmpty;
    private RecyclerView rvFavoriteCards;
    private RecyclerView rvProfilePosts;

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseManager firebaseManager;

    private FavoritesAdapter favoritesAdapter;
    private ForumPostAdapter postsAdapter;
    private ListenerRegistration postsListener;

    private String profileUserId;
    private String currentUsername;
    private String currentBio;
    private String currentProfilePictureUrl;
    private boolean isCurrentUser = true;
    private boolean isFollowing = false;

    private final List<String> favoriteCardKeys = new ArrayList<>();
    private final List<CollectionSlot> allCollectionSlots = new ArrayList<>();
    private final List<ForumPost> currentPosts = new ArrayList<>();

    private ActivityResultLauncher<Intent> editProfileLauncher;

    public static ProfileFragment newInstance(String userId) {
        ProfileFragment fragment = new ProfileFragment();
        Bundle args = new Bundle();
        args.putString(ARG_USER_ID, userId);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        firebaseManager = new FirebaseManager(requireContext());

        if (getArguments() != null) {
            profileUserId = getArguments().getString(ARG_USER_ID);
        }

        FirebaseUser user = mAuth.getCurrentUser();
        if (profileUserId == null || (user != null && profileUserId.equals(user.getUid()))) {
            profileUserId = user != null ? user.getUid() : null;
            isCurrentUser = true;
        } else {
            isCurrentUser = false;
        }

        editProfileLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == AppCompatActivity.RESULT_OK) {
                        fetchUserProfile();
                    }
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_profile, container, false);

        ivPfp = v.findViewById(R.id.ivPfp);
        tvUsername = v.findViewById(R.id.tvUsername);
        tvPoints = v.findViewById(R.id.tvPoints);
        etBio = v.findViewById(R.id.etBio);
        tvFollowerCount = v.findViewById(R.id.tvFollowerCount);
        tvFollowingCount = v.findViewById(R.id.tvFollowingCount);
        btnFollow = v.findViewById(R.id.btnFollow);
        btnSettings = v.findViewById(R.id.btnSettings);
        btnEditProfile = v.findViewById(R.id.btnEditProfile);
        btnFollowers = v.findViewById(R.id.btnFollowers);
        btnFollowing = v.findViewById(R.id.btnFollowing);

        profileTabLayout = v.findViewById(R.id.profileTabLayout);
        tvProfileTabEmpty = v.findViewById(R.id.tvProfileTabEmpty);
        rvFavoriteCards = v.findViewById(R.id.rvFavoriteCards);
        rvProfilePosts = v.findViewById(R.id.rvProfilePosts);

        setupRecyclerViews();
        setupTabs();
        setupUI();
        setupSocialCountClicks();

        return v;
    }

    private void setupRecyclerViews() {
        favoritesAdapter = new FavoritesAdapter(isCurrentUser, this);

        GridLayoutManager noScrollGrid = new GridLayoutManager(requireContext(), 3) {
            @Override
            public boolean canScrollVertically() {
                return false;
            }
        };

        rvFavoriteCards.setLayoutManager(noScrollGrid);
        rvFavoriteCards.setNestedScrollingEnabled(false);
        rvFavoriteCards.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rvFavoriteCards.setAdapter(favoritesAdapter);

        postsAdapter = new ForumPostAdapter(this);
        rvProfilePosts.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvProfilePosts.setAdapter(postsAdapter);
    }

    private void setupTabs() {
        applyTabState(0);

        profileTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                applyTabState(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
    }

    private void applyTabState(int position) {
        if (!isAdded()) return;

        if (position == 0) {
            rvFavoriteCards.setVisibility(View.VISIBLE);
            rvProfilePosts.setVisibility(View.GONE);
            tvProfileTabEmpty.setVisibility(View.GONE);
        } else {
            rvFavoriteCards.setVisibility(View.GONE);

            if (currentPosts.isEmpty()) {
                tvProfileTabEmpty.setText("No posts yet.");
                tvProfileTabEmpty.setVisibility(View.VISIBLE);
                rvProfilePosts.setVisibility(View.GONE);
            } else {
                tvProfileTabEmpty.setVisibility(View.GONE);
                rvProfilePosts.setVisibility(View.VISIBLE);
            }
        }
    }

    private void setupUI() {
        etBio.setFocusable(false);
        etBio.setClickable(false);
        etBio.setLongClickable(false);

        if (isCurrentUser) {
            btnEditProfile.setVisibility(View.VISIBLE);
            btnSettings.setVisibility(View.VISIBLE);
            btnFollow.setVisibility(View.GONE);

            btnSettings.setOnClickListener(view ->
                    startActivity(new Intent(requireContext(), SettingsActivity.class)));

            btnEditProfile.setOnClickListener(view -> {
                Intent intent = new Intent(requireContext(), EditProfileActivity.class);
                intent.putExtra("username", currentUsername);
                intent.putExtra("bio", currentBio);
                intent.putExtra("profilePictureUrl", currentProfilePictureUrl);
                editProfileLauncher.launch(intent);
            });
        } else {
            btnEditProfile.setVisibility(View.GONE);
            btnSettings.setVisibility(View.GONE);
            btnFollow.setVisibility(View.VISIBLE);

            checkFollowingStatus();
            btnFollow.setOnClickListener(v -> toggleFollow());
        }
    }

    private void setupSocialCountClicks() {
        btnFollowers.setOnClickListener(v -> openSocialScreen(false));
        btnFollowing.setOnClickListener(v -> openSocialScreen(true));
    }

    private void openSocialScreen(boolean showFollowing) {
        if (profileUserId == null) return;

        Intent intent = new Intent(requireContext(), SocialActivity.class);
        intent.putExtra(SocialActivity.EXTRA_USER_ID, profileUserId);
        intent.putExtra(SocialActivity.EXTRA_SHOW_FOLLOWING, showFollowing);
        startActivity(intent);
    }

    private void checkFollowingStatus() {
        firebaseManager.isFollowing(profileUserId, task -> {
            if (task.isSuccessful() && isAdded()) {
                Boolean result = task.getResult();
                isFollowing = result != null && result;
                updateFollowButton();
            }
        });
    }

    private void updateFollowButton() {
        btnFollow.setText(isFollowing ? "Following" : "Follow");
    }

    private void toggleFollow() {
        btnFollow.setEnabled(false);

        if (isFollowing) {
            firebaseManager.unfollowUser(profileUserId, task -> {
                if (!isAdded()) return;
                btnFollow.setEnabled(true);

                if (task.isSuccessful()) {
                    isFollowing = false;
                    updateFollowButton();
                    fetchUserProfile();
                }
            });
        } else {
            firebaseManager.followUser(profileUserId, task -> {
                if (!isAdded()) return;
                btnFollow.setEnabled(true);

                if (task.isSuccessful()) {
                    isFollowing = true;
                    updateFollowButton();
                    fetchUserProfile();
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        fetchUserProfile();
        startPostsListener();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (postsListener != null) {
            postsListener.remove();
            postsListener = null;
        }
    }

    private void fetchUserProfile() {
        if (profileUserId == null) return;

        db.collection("users").document(profileUserId).get(Source.CACHE)
                .addOnSuccessListener(this::handleUserSnapshot)
                .addOnFailureListener(e -> fetchUserProfileFromServer());

        fetchUserProfileFromServer();
    }

    private void fetchUserProfileFromServer() {
        db.collection("users").document(profileUserId).get(Source.SERVER)
                .addOnSuccessListener(this::handleUserSnapshot)
                .addOnFailureListener(e -> Log.e(TAG, "Server fetch failed", e));
    }

    @SuppressWarnings("unchecked")
    private void handleUserSnapshot(DocumentSnapshot documentSnapshot) {
        if (!isAdded() || documentSnapshot == null || !documentSnapshot.exists()) return;

        User userProfile = documentSnapshot.toObject(User.class);
        if (userProfile == null) return;

        currentUsername = userProfile.getUsername();
        currentBio = userProfile.getBio();
        currentProfilePictureUrl = userProfile.getProfilePictureUrl();

        tvUsername.setText(currentUsername != null ? currentUsername : "No Username");
        etBio.setText(currentBio != null && !currentBio.trim().isEmpty() ? currentBio : "No bio yet.");
        tvPoints.setText("Total Points: " + userProfile.getTotalPoints());
        tvFollowerCount.setText(String.valueOf(userProfile.getFollowerCount()));
        tvFollowingCount.setText(String.valueOf(userProfile.getFollowingCount()));

        loadProfilePicture(currentProfilePictureUrl);

        favoriteCardKeys.clear();

        if (isCurrentUser) {
            favoriteCardKeys.addAll(loadFavoriteKeysFromPrefs());
        } else {
            List<String> savedKeys = (List<String>) documentSnapshot.get("favoriteCardKeys");
            if (savedKeys != null) {
                favoriteCardKeys.addAll(savedKeys);
            }
        }

        loadFavoriteCards();
    }

    private void loadProfilePicture(String url) {
        if (url != null && !url.isEmpty()) {
            Glide.with(this)
                    .load(url)
                    .placeholder(R.drawable.ic_profile)
                    .error(R.drawable.ic_profile)
                    .into(ivPfp);
        } else {
            ivPfp.setImageResource(R.drawable.ic_profile);
        }
    }

    private void loadFavoriteCards() {
        if (profileUserId == null) return;

        db.collection("users")
                .document(profileUserId)
                .collection("collectionSlot")
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    allCollectionSlots.clear();

                    for (DocumentSnapshot doc : queryDocumentSnapshots.getDocuments()) {
                        CollectionSlot slot = doc.toObject(CollectionSlot.class);
                        if (slot == null) continue;
                        slot.setId(doc.getId());
                        allCollectionSlots.add(slot);
                    }

                    Collections.sort(allCollectionSlots, (a, b) ->
                            getSortableName(a).compareToIgnoreCase(getSortableName(b)));

                    refreshFavoritesDisplay();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed loading collection slots", e);
                    refreshFavoritesDisplay();
                });
    }

    private void refreshFavoritesDisplay() {
        ensureFavoriteSize();

        List<CollectionSlot> favoriteSlots = new ArrayList<>();
        for (int i = 0; i < FAVORITE_SLOT_COUNT; i++) {
            favoriteSlots.add(findSlotById(favoriteCardKeys.get(i)));
        }

        favoritesAdapter.submitList(favoriteSlots);
    }

    private void ensureFavoriteSize() {
        while (favoriteCardKeys.size() < FAVORITE_SLOT_COUNT) {
            favoriteCardKeys.add("");
        }
        while (favoriteCardKeys.size() > FAVORITE_SLOT_COUNT) {
            favoriteCardKeys.remove(favoriteCardKeys.size() - 1);
        }
    }

    @Nullable
    private CollectionSlot findSlotById(@Nullable String id) {
        if (id == null || id.trim().isEmpty()) return null;

        for (CollectionSlot slot : allCollectionSlots) {
            if (slot != null && id.equals(slot.getId())) {
                return slot;
            }
        }
        return null;
    }

    private void startPostsListener() {
        if (profileUserId == null || !isAdded()) return;

        if (postsListener != null) {
            postsListener.remove();
        }

        postsListener = db.collection("forumThreads")
                .whereEqualTo("userId", profileUserId)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .addSnapshotListener((value, error) -> {
                    if (!isAdded()) return;

                    if (error != null) {
                        loadPostsFallback();
                        return;
                    }

                    List<ForumPost> posts = new ArrayList<>();
                    if (value != null) {
                        for (DocumentSnapshot doc : value.getDocuments()) {
                            ForumPost post = doc.toObject(ForumPost.class);
                            if (post == null) continue;
                            post.setId(doc.getId());
                            posts.add(post);
                        }
                    }

                    applyPosts(posts);
                });
    }

    private void loadPostsFallback() {
        db.collection("forumThreads")
                .whereEqualTo("userId", profileUserId)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    List<ForumPost> posts = new ArrayList<>();

                    for (DocumentSnapshot doc : queryDocumentSnapshots.getDocuments()) {
                        ForumPost post = doc.toObject(ForumPost.class);
                        if (post == null) continue;
                        post.setId(doc.getId());
                        posts.add(post);
                    }

                    Collections.sort(posts, (a, b) -> {
                        long aTime = a.getTimestamp() != null ? a.getTimestamp().toDate().getTime() : 0L;
                        long bTime = b.getTimestamp() != null ? b.getTimestamp().toDate().getTime() : 0L;
                        return Long.compare(bTime, aTime);
                    });

                    applyPosts(posts);
                });
    }

    private void applyPosts(@NonNull List<ForumPost> posts) {
        currentPosts.clear();
        currentPosts.addAll(posts);
        postsAdapter.setPosts(posts);
        applyTabState(profileTabLayout.getSelectedTabPosition());
    }

    @Override
    public void onFavoriteClicked(int position, @Nullable CollectionSlot slot) {
        if (slot == null) {
            if (isCurrentUser) {
                showFavoritePickerDialog(position);
            }
            return;
        }

        openFavoriteCard(slot);
    }

    @Override
    public boolean onFavoriteLongPressed(int position, @Nullable CollectionSlot slot) {
        if (!isCurrentUser) return false;
        showFavoritePickerDialog(position);
        return true;
    }

    private void showFavoritePickerDialog(int position) {
        if (!isAdded()) return;

        if (allCollectionSlots.isEmpty()) {
            Toast.makeText(requireContext(), "No collection cards found.", Toast.LENGTH_SHORT).show();
            return;
        }

        List<FavoriteChoice> allChoices = new ArrayList<>();
        for (CollectionSlot slot : allCollectionSlots) {
            if (slot == null || isBlank(slot.getId())) continue;
            allChoices.add(new FavoriteChoice(slot, buildChoiceLabel(slot)));
        }

        Collections.sort(allChoices, (a, b) ->
                a.label.toLowerCase(Locale.US).compareTo(b.label.toLowerCase(Locale.US)));

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        EditText search = new EditText(requireContext());
        search.setHint("Search birds");
        search.setSingleLine(true);
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        ListView listView = new ListView(requireContext());
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(320)
        );
        listParams.topMargin = dp(12);
        root.addView(listView, listParams);

        List<FavoriteChoice> filteredChoices = new ArrayList<>(allChoices);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                toLabels(filteredChoices)
        );
        listView.setAdapter(adapter);

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
                .setTitle("Choose favorite card")
                .setView(root)
                .setNegativeButton("Cancel", null);

        if (position < favoriteCardKeys.size() && !isBlank(favoriteCardKeys.get(position))) {
            builder.setNeutralButton("Clear slot", (dialog, which) -> clearFavoriteSlot(position));
        }

        AlertDialog dialog = builder.create();

        listView.setOnItemClickListener((parent, view, which, id) -> {
            FavoriteChoice selected = filteredChoices.get(which);
            saveFavoriteSelection(position, selected.slot.getId());
            dialog.dismiss();
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s == null ? "" : s.toString().trim().toLowerCase(Locale.US);

                filteredChoices.clear();
                for (FavoriteChoice choice : allChoices) {
                    if (query.isEmpty() || choice.label.toLowerCase(Locale.US).contains(query)) {
                        filteredChoices.add(choice);
                    }
                }

                adapter.clear();
                adapter.addAll(toLabels(filteredChoices));
                adapter.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        dialog.show();
    }

    private void saveFavoriteSelection(int position, @NonNull String selectedKey) {
        ensureFavoriteSize();

        int existingIndex = favoriteCardKeys.indexOf(selectedKey);
        if (existingIndex >= 0 && existingIndex != position) {
            favoriteCardKeys.set(existingIndex, "");
        }

        favoriteCardKeys.set(position, selectedKey);
        persistFavoriteKeysToPrefs();
        refreshFavoritesDisplay();
    }

    private void clearFavoriteSlot(int position) {
        ensureFavoriteSize();
        favoriteCardKeys.set(position, "");
        persistFavoriteKeysToPrefs();
        refreshFavoritesDisplay();
    }

    private void openFavoriteCard(@NonNull CollectionSlot slot) {
        Intent intent = new Intent(requireContext(), ViewBirdCardActivity.class);
        intent.putExtra(CollectionCardAdapter.EXTRA_IMAGE_URL, slot.getImageUrl());
        intent.putExtra(CollectionCardAdapter.EXTRA_COMMON_NAME, slot.getCommonName());
        intent.putExtra(CollectionCardAdapter.EXTRA_SCI_NAME, slot.getScientificName());
        intent.putExtra(CollectionCardAdapter.EXTRA_STATE, slot.getState());
        intent.putExtra(CollectionCardAdapter.EXTRA_LOCALITY, slot.getLocality());
        intent.putExtra(CollectionCardAdapter.EXTRA_BIRD_ID, slot.getBirdId());

        if (slot.getTimestamp() != null) {
            intent.putExtra(CollectionCardAdapter.EXTRA_CAUGHT_TIME, slot.getTimestamp().getTime());
        }

        startActivity(intent);
    }

    private String buildChoiceLabel(@NonNull CollectionSlot slot) {
        String common = slot.getCommonName();
        String sci = slot.getScientificName();
        String name;

        if (!isBlank(common)) {
            name = common;
        } else if (!isBlank(sci)) {
            name = sci;
        } else {
            name = "Unknown Bird";
        }

        String date = "";
        if (slot.getTimestamp() != null) {
            date = " • " + new SimpleDateFormat("M/d/yy", Locale.US).format(slot.getTimestamp());
        }

        return name + date;
    }

    private List<String> toLabels(@NonNull List<FavoriteChoice> choices) {
        List<String> labels = new ArrayList<>();
        for (FavoriteChoice choice : choices) {
            labels.add(choice.label);
        }
        return labels;
    }

    private String getSortableName(@Nullable CollectionSlot slot) {
        if (slot == null) return "";
        if (!isBlank(slot.getCommonName())) return slot.getCommonName();
        if (!isBlank(slot.getScientificName())) return slot.getScientificName();
        return "Unknown Bird";
    }

    private int dp(int value) {
        return Math.round(value * requireContext().getResources().getDisplayMetrics().density);
    }

    private boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    public void onLikeClick(ForumPost post) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || post.getId() == null) return;

        String userId = user.getUid();
        boolean currentlyLiked = post.getLikedBy() != null && post.getLikedBy().containsKey(userId);

        if (currentlyLiked) {
            db.collection("forumThreads")
                    .document(post.getId())
                    .update(
                            "likeCount", FieldValue.increment(-1),
                            "likedBy." + userId, FieldValue.delete()
                    );
        } else {
            db.collection("forumThreads")
                    .document(post.getId())
                    .update(
                            "likeCount", FieldValue.increment(1),
                            "likedBy." + userId, true
                    );
        }
    }

    @Override
    public void onCommentClick(ForumPost post) {
        onPostClick(post);
    }

    @Override
    public void onPostClick(ForumPost post) {
        Intent intent = new Intent(requireContext(), PostDetailActivity.class);
        intent.putExtra(PostDetailActivity.EXTRA_POST_ID, post.getId());
        startActivity(intent);
    }

    @Override
    public void onOptionsClick(ForumPost post, View view) {
        PopupMenu popup = new PopupMenu(requireContext(), view);
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

        if (user != null && post.getUserId() != null && post.getUserId().equals(user.getUid())) {
            popup.getMenu().add("Delete");
        }
        popup.getMenu().add("Report");

        popup.setOnMenuItemClickListener(item -> {
            String title = String.valueOf(item.getTitle());

            if ("Delete".equals(title)) {
                showDeleteConfirmation(post);
                return true;
            } else if ("Report".equals(title)) {
                showReportDialog(post);
                return true;
            }

            return false;
        });

        popup.show();
    }

    @Override
    public void onUserClick(String userId) {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null && userId.equals(currentUser.getUid())) {
            return;
        }

        Intent intent = new Intent(requireContext(), UserSocialProfileActivity.class);
        intent.putExtra(UserSocialProfileActivity.EXTRA_USER_ID, userId);
        startActivity(intent);
    }

    private static class FavoriteChoice {
        final CollectionSlot slot;
        final String label;

        FavoriteChoice(CollectionSlot slot, String label) {
            this.slot = slot;
            this.label = label;
        }
    }
    private void persistFavoriteKeysToPrefs() {
        if (!isAdded() || profileUserId == null) return;

        ensureFavoriteSize();

        SharedPreferences prefs = requireContext().getSharedPreferences(FAVORITES_PREFS, android.content.Context.MODE_PRIVATE);
        prefs.edit()
                .putString(profileUserId + "_fav_0", favoriteCardKeys.get(0))
                .putString(profileUserId + "_fav_1", favoriteCardKeys.get(1))
                .putString(profileUserId + "_fav_2", favoriteCardKeys.get(2))
                .apply();
    }

    private List<String> loadFavoriteKeysFromPrefs() {
        List<String> keys = new ArrayList<>();
        if (!isAdded() || profileUserId == null) return keys;

        SharedPreferences prefs = requireContext().getSharedPreferences(FAVORITES_PREFS, android.content.Context.MODE_PRIVATE);
        keys.add(prefs.getString(profileUserId + "_fav_0", ""));
        keys.add(prefs.getString(profileUserId + "_fav_1", ""));
        keys.add(prefs.getString(profileUserId + "_fav_2", ""));
        return keys;
    }
    private void showDeleteConfirmation(ForumPost post) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Post")
                .setMessage("Are you sure you want to delete this post?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    if (post.getId() == null) return;

                    firebaseManager.deleteForumPost(post.getId(), task -> {
                        if (!isAdded()) return;

                        if (task.isSuccessful()) {
                            Toast.makeText(requireContext(), "Post deleted", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(requireContext(), "Failed to delete post.", Toast.LENGTH_SHORT).show();
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

                    if ("Other".equals(selectedReason)) {
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
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.leftMargin = dp(20);
        params.rightMargin = dp(20);
        input.setLayoutParams(params);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("Submit", (dialog, which) -> {
            String reason = input.getText().toString().trim();

            if (reason.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter a reason", Toast.LENGTH_SHORT).show();
                return;
            }

            if (ContentFilter.containsInappropriateContent(reason)) {
                Toast.makeText(requireContext(), "Inappropriate language detected in your report.", Toast.LENGTH_SHORT).show();
                return;
            }

            listener.onReasonEntered(reason);
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    private void submitReport(ForumPost post, String reason) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || post.getId() == null) return;

        Report report = new Report("post", post.getId(), user.getUid(), reason);

        firebaseManager.addReport(report, task -> {
            if (!isAdded()) return;

            if (task.isSuccessful()) {
                Toast.makeText(requireContext(), "Thank you for reporting. We will review this post.", Toast.LENGTH_LONG).show();
            } else {
                String error = "Failed to submit report.";
                if (task.getException() != null && task.getException().getMessage() != null) {
                    error = task.getException().getMessage();
                }
                Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private interface OnReasonEnteredListener {
        void onReasonEntered(String reason);
    }
}