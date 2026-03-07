package com.birddex.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;
import com.google.android.material.appbar.AppBarLayout;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ProfileFragment extends Fragment implements
        FavoritesAdapter.OnFavoriteInteractionListener,
        ForumPostAdapter.OnPostClickListener {

    private static final String TAG = "ProfileFragment";
    private static final String ARG_USER_ID = "arg_user_id";
    private static final int PAGE_SIZE = 20;
    private static final int FAVORITE_SLOT_COUNT = 3;
    private static final String FAVORITES_PREFS = "profile_favorite_cards";

    private ShapeableImageView ivPfp;
    private TextView tvUsername, tvPoints, tvBio, tvFollowerCount, tvFollowingCount, tvProfileTabEmpty;
    private MaterialButton btnFollow;
    private ImageButton btnSettings, btnEditProfile;
    private View btnFollowers, btnFollowing;

    private TabLayout profileTabLayout;
    private RecyclerView rvFavoriteCards, rvProfilePosts;
    private SwipeRefreshLayout swipeRefreshLayout;

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseManager firebaseManager;

    private FavoritesAdapter favoritesAdapter;
    private ForumPostAdapter postsAdapter;

    private List<ForumPost> postList = new ArrayList<>();
    private DocumentSnapshot lastVisible;
    private boolean isFetching = false;
    private boolean isLastPage = false;

    private String profileUserId;
    private String currentUsername, currentBio, currentProfilePictureUrl;
    private boolean isCurrentUser = true;
    private boolean isFollowing = false;

    private final List<String> favoriteCardKeys = new ArrayList<>();
    private final List<CollectionSlot> allCollectionSlots = new ArrayList<>();

    private ActivityResultLauncher<Intent> editProfileLauncher;
    private ListenerRegistration profileListener;

    public ProfileFragment() {}

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
                        Log.d(TAG, "Edit profile result OK.");
                        fetchUserProfile();
                    }
                }
        );
        Log.d(TAG, "Profile created for UID: " + profileUserId);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_profile, container, false);

        ivPfp = v.findViewById(R.id.ivPfp);
        tvUsername = v.findViewById(R.id.tvUsername);
        tvPoints = v.findViewById(R.id.tvPoints);
        tvBio = v.findViewById(R.id.etBio);
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
        swipeRefreshLayout = v.findViewById(R.id.swipeRefreshLayout);

        // Disable dragging on AppBarLayout to prevent unwanted scrolling
        AppBarLayout appBarLayout = v.findViewById(R.id.profileAppBar);
        if (appBarLayout.getLayoutParams() instanceof CoordinatorLayout.LayoutParams) {
            CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) appBarLayout.getLayoutParams();
            AppBarLayout.Behavior behavior = new AppBarLayout.Behavior();
            behavior.setDragCallback(new AppBarLayout.Behavior.DragCallback() {
                @Override
                public boolean canDrag(@NonNull AppBarLayout appBarLayout) {
                    return false;
                }
            });
            params.setBehavior(behavior);
        }

        setupRecyclerViews();
        setupTabs();
        setupUI();
        setupSocialCountClicks();
        setupSwipeRefresh();

        return v;
    }

    private void setupRecyclerViews() {
        favoritesAdapter = new FavoritesAdapter(isCurrentUser, this);
        rvFavoriteCards.setLayoutManager(new GridLayoutManager(requireContext(), 3) {
            @Override public boolean canScrollVertically() { return false; }
        });
        rvFavoriteCards.setAdapter(favoritesAdapter);

        postsAdapter = new ForumPostAdapter(this);
        rvProfilePosts.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvProfilePosts.setAdapter(postsAdapter);

        rvProfilePosts.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0) {
                    LinearLayoutManager lm = (LinearLayoutManager) rvProfilePosts.getLayoutManager();
                    if (lm != null && !isFetching && !isLastPage) {
                        if (lm.getChildCount() + lm.findFirstVisibleItemPosition() >= lm.getItemCount()) {
                            fetchPosts();
                        }
                    }
                }
            }
        });
    }

    private void setupTabs() {
        profileTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { applyTabState(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
        applyTabState(0);
    }

    private void applyTabState(int position) {
        if (!isAdded()) return;
        if (position == 0) {
            rvFavoriteCards.setVisibility(View.VISIBLE);
            rvProfilePosts.setVisibility(View.GONE);
            tvProfileTabEmpty.setVisibility(View.GONE);
        } else {
            rvFavoriteCards.setVisibility(View.GONE);
            rvProfilePosts.setVisibility(postList.isEmpty() ? View.GONE : View.VISIBLE);
            tvProfileTabEmpty.setVisibility(postList.isEmpty() ? View.VISIBLE : View.GONE);
            if (postList.isEmpty()) tvProfileTabEmpty.setText("No posts yet.");
        }
    }

    private void setupUI() {
        if (isCurrentUser) {
            btnEditProfile.setVisibility(View.VISIBLE);
            btnSettings.setVisibility(View.VISIBLE);
            btnFollow.setVisibility(View.GONE);
            btnSettings.setOnClickListener(v -> startActivity(new Intent(requireContext(), SettingsActivity.class)));
            btnEditProfile.setOnClickListener(v -> {
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
            btnFollow.setOnClickListener(v -> toggleFollow());
            checkFollowingStatus();
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

    private void fetchUserProfile() {
        if (profileUserId == null) return;
        if (profileListener != null) profileListener.remove();
        Log.d(TAG, "Listening to profile updates for: " + profileUserId);
        profileListener = db.collection("users").document(profileUserId)
                .addSnapshotListener((snapshot, e) -> {
                    if (e != null) { Log.e(TAG, "Profile listen failed.", e); return; }
                    if (snapshot != null && snapshot.exists() && isAdded()) handleUserSnapshot(snapshot);
                });
    }

    private void handleUserSnapshot(DocumentSnapshot doc) {
        User user = doc.toObject(User.class);
        if (user == null) return;

        currentUsername = user.getUsername();
        currentBio = user.getBio();
        currentProfilePictureUrl = user.getProfilePictureUrl();

        tvUsername.setText(currentUsername != null ? currentUsername : "User");
        tvBio.setText(currentBio != null && !currentBio.isEmpty() ? currentBio : "No bio yet.");
        tvPoints.setText("Total Points: " + user.getTotalPoints());
        tvFollowerCount.setText(String.valueOf(user.getFollowerCount()));
        tvFollowingCount.setText(String.valueOf(user.getFollowingCount()));

        if (currentProfilePictureUrl != null && !currentProfilePictureUrl.isEmpty()) {
            Glide.with(this).load(currentProfilePictureUrl).placeholder(R.drawable.ic_profile).into(ivPfp);
        } else {
            ivPfp.setImageResource(R.drawable.ic_profile);
        }

        favoriteCardKeys.clear();
        List<String> cloudKeys = (List<String>) doc.get("favoriteCardKeys");
        if (cloudKeys != null) favoriteCardKeys.addAll(cloudKeys);
        
        loadFavoriteCards();
    }

    private void loadFavoriteCards() {
        if (profileUserId == null) return;
        db.collection("users").document(profileUserId).collection("collectionSlot").get()
                .addOnSuccessListener(querySnapshot -> {
                    if (!isAdded()) return;
                    allCollectionSlots.clear();
                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        CollectionSlot slot = doc.toObject(CollectionSlot.class);
                        if (slot != null) { slot.setId(doc.getId()); allCollectionSlots.add(slot); }
                    }
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
        while (favoriteCardKeys.size() < FAVORITE_SLOT_COUNT) favoriteCardKeys.add("");
    }

    private CollectionSlot findSlotById(String id) {
        if (id == null || id.isEmpty()) return null;
        for (CollectionSlot slot : allCollectionSlots) {
            if (id.equals(slot.getId())) return slot;
        }
        return null;
    }

    private void checkFollowingStatus() {
        if (profileUserId == null || isCurrentUser) return;
        firebaseManager.isFollowing(profileUserId, task -> {
            if (task.isSuccessful() && isAdded()) {
                isFollowing = task.getResult() != null && task.getResult();
                btnFollow.setText(isFollowing ? "Following" : "Follow");
            }
        });
    }

    private void toggleFollow() {
        btnFollow.setEnabled(false);
        if (isFollowing) {
            firebaseManager.unfollowUser(profileUserId, task -> {
                if (!isAdded()) return;
                btnFollow.setEnabled(true);
                if (task.isSuccessful()) { isFollowing = false; btnFollow.setText("Follow"); refreshPosts(); }
            });
        } else {
            firebaseManager.followUser(profileUserId, task -> {
                if (!isAdded()) return;
                btnFollow.setEnabled(true);
                if (task.isSuccessful()) { isFollowing = true; btnFollow.setText("Following"); refreshPosts(); }
            });
        }
    }

    private void fetchPosts() {
        if (profileUserId == null || isFetching || isLastPage || !isAdded()) return;
        isFetching = true;
        Query query = db.collection("forumThreads").whereEqualTo("userId", profileUserId).orderBy("timestamp", Query.Direction.DESCENDING).limit(PAGE_SIZE);
        if (lastVisible != null) query = query.startAfter(lastVisible);

        query.get().addOnSuccessListener(value -> {
            if (!isAdded()) return;
            if (value != null && !value.isEmpty()) {
                lastVisible = value.getDocuments().get(value.size() - 1);
                for (DocumentSnapshot doc : value.getDocuments()) {
                    ForumPost post = doc.toObject(ForumPost.class);
                    if (post != null) { post.setId(doc.getId()); postList.add(post); }
                }
                postsAdapter.setPosts(new ArrayList<>(postList));
                if (value.size() < PAGE_SIZE) isLastPage = true;
            } else { isLastPage = true; }
            isFetching = false;
            applyTabState(profileTabLayout.getSelectedTabPosition());
        }).addOnFailureListener(e -> { isFetching = false; Log.e(TAG, "Post fetch failed.", e); });
    }

    private void refreshPosts() {
        isFetching = false; lastVisible = null; isLastPage = false; postList.clear();
        if (postsAdapter != null) postsAdapter.setPosts(new ArrayList<>());
        fetchPosts();
    }

    private void setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener(() -> { fetchUserProfile(); refreshPosts(); });
    }

    @Override public void onLikeClick(ForumPost post) { Log.d(TAG, "Like: " + post.getId()); }
    @Override public void onCommentClick(ForumPost post) { onPostClick(post); }
    @Override public void onPostClick(ForumPost post) {
        Intent intent = new Intent(requireContext(), PostDetailActivity.class);
        intent.putExtra(PostDetailActivity.EXTRA_POST_ID, post.getId());
        startActivity(intent);
    }
    @Override public void onOptionsClick(ForumPost post, View view) {
        PopupMenu popup = new PopupMenu(requireContext(), view);
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null && user.getUid().equals(post.getUserId())) popup.getMenu().add("Delete");
        popup.getMenu().add("Report");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getTitle().equals("Delete")) showDeleteConfirmation(post);
            else showReportDialog(post);
            return true;
        });
        popup.show();
    }
    @Override public void onUserClick(String userId) {
        if (!userId.equals(profileUserId)) {
            Intent intent = new Intent(requireContext(), UserSocialProfileActivity.class);
            intent.putExtra(UserSocialProfileActivity.EXTRA_USER_ID, userId);
            startActivity(intent);
        }
    }
    @Override public void onMapClick(ForumPost post) {
        if (post.getLatitude() != null && post.getLongitude() != null) {
            Intent intent = new Intent(requireContext(), NearbyHeatmapActivity.class);
            intent.putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LAT, post.getLatitude());
            intent.putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LNG, post.getLongitude());
            intent.putExtra("extra_post_id", post.getId());
            startActivity(intent);
        }
    }

    @Override public void onFavoriteClicked(int position, @Nullable CollectionSlot slot) {
        if (slot == null) { if (isCurrentUser) showFavoritePickerDialog(position); }
        else {
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
    }
    @Override public boolean onFavoriteLongPressed(int position, @Nullable CollectionSlot slot) {
        if (!isCurrentUser) return false;
        showFavoritePickerDialog(position);
        return true;
    }

    private void showFavoritePickerDialog(int position) {
        if (allCollectionSlots.isEmpty()) { Toast.makeText(getContext(), "No cards found.", Toast.LENGTH_SHORT).show(); return; }
        
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_favorite_picker, null);
        EditText etSearch = dialogView.findViewById(R.id.etSearch);
        ListView listView = dialogView.findViewById(R.id.listView);
        Button btnClear = dialogView.findViewById(R.id.btnClear);

        List<CollectionSlot> filteredSlots = new ArrayList<>(allCollectionSlots);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, getSlotNames(filteredSlots));
        listView.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("Pick Favorite")
                .setView(dialogView)
                .setNegativeButton("Cancel", null)
                .create();

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filteredSlots.clear();
                String query = s.toString().toLowerCase();
                for (CollectionSlot slot : allCollectionSlots) {
                    if (slot.getCommonName() != null && slot.getCommonName().toLowerCase().contains(query)) {
                        filteredSlots.add(slot);
                    }
                }
                adapter.clear();
                adapter.addAll(getSlotNames(filteredSlots));
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        listView.setOnItemClickListener((parent, view, which, id) -> {
            saveFavoriteSelection(position, filteredSlots.get(which).getId());
            dialog.dismiss();
        });

        btnClear.setOnClickListener(v -> {
            saveFavoriteSelection(position, "");
            dialog.dismiss();
        });

        dialog.show();
    }

    private List<String> getSlotNames(List<CollectionSlot> slots) {
        List<String> names = new ArrayList<>();
        for (CollectionSlot s : slots) names.add(s.getCommonName());
        return names;
    }

    private void saveFavoriteSelection(int pos, String key) {
        ensureFavoriteSize();
        favoriteCardKeys.set(pos, key);
        db.collection("users").document(profileUserId).update("favoriteCardKeys", favoriteCardKeys);
        refreshFavoritesDisplay();
    }

    private void showDeleteConfirmation(ForumPost post) {
        new AlertDialog.Builder(requireContext()).setTitle("Delete Post").setPositiveButton("Delete", (d, w) -> {
            firebaseManager.deleteForumPost(post.getId(), t -> refreshPosts());
        }).show();
    }

    private void showReportDialog(ForumPost post) {
        firebaseManager.addReport(new Report("post", post.getId(), mAuth.getUid(), "Inappropriate Content"), t -> {
            Toast.makeText(getContext(), "Reported", Toast.LENGTH_SHORT).show();
        });
    }

    @Override public void onResume() { super.onResume(); fetchUserProfile(); refreshPosts(); }
    @Override public void onStop() { super.onStop(); if (profileListener != null) profileListener.remove(); }
}
