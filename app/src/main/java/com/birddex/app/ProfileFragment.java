package com.birddex.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
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
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ProfileFragment extends Fragment
        implements ForumPostAdapter.OnPostClickListener,
        FavoritesAdapter.OnFavoriteSlotClickListener {

    private static final String ARG_USER_ID = "arg_user_id";
    private static final String PREFS_NAME = "profile_favorites_prefs";

    private ShapeableImageView ivPfp;
    private TextView tvUsername;
    private TextView tvPoints;
    private EditText etBio;
    private TextView tvFollowerCount;
    private TextView tvFollowingCount;
    private MaterialButton btnFollow;
    private ImageButton btnSettings;
    private ImageButton btnEditProfile;

    private RecyclerView rvFavoriteCards;
    private RecyclerView rvProfilePosts;
    private TextView tvProfileTabEmpty;
    private TabLayout profileTabLayout;

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseManager firebaseManager;

    private String profileUserId;
    private String currentUsername;
    private String currentBio;
    private String currentProfilePictureUrl;
    private boolean isCurrentUser = true;
    private boolean isFollowing = false;

    private final List<CollectionSlot> favoriteSlots = new ArrayList<>();
    private final List<CollectionSlot> availableFavoriteChoices = new ArrayList<>();
    private final List<ForumPost> userPosts = new ArrayList<>();

    private FavoritesAdapter favoritesAdapter;
    private ForumPostAdapter forumPostAdapter;
    private AppBarLayout profileAppBar;

    private int selectedTabPosition = 0;
    private int originalScrollFlags;
    private boolean areScrollFlagsStored = false;

    private ActivityResultLauncher<Intent> editProfileLauncher;
    private ProfileCacheViewModel cacheViewModel;

    private String lastBoundProfilePictureUrl = null;

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
            profileUserId = (user != null) ? user.getUid() : null;
            isCurrentUser = true;
        } else {
            isCurrentUser = false;
        }

        editProfileLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == AppCompatActivity.RESULT_OK) {
                        forceRefreshProfile();
                        forceRefreshFavorites();
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
        profileAppBar = v.findViewById(R.id.profileAppBar);
        rvFavoriteCards = v.findViewById(R.id.rvFavoriteCards);
        rvProfilePosts = v.findViewById(R.id.rvProfilePosts);
        tvProfileTabEmpty = v.findViewById(R.id.tvProfileTabEmpty);
        profileTabLayout = v.findViewById(R.id.profileTabLayout);

        lastBoundProfilePictureUrl = null;

        cacheViewModel = new ViewModelProvider(requireActivity()).get(ProfileCacheViewModel.class);
        if (!cacheViewModel.isForProfile(profileUserId)) {
            cacheViewModel.resetForProfile(profileUserId);
        }

        setupUI();
        setupRecyclerViews();
        setupTabs();
        initializeScreenData();
        restoreSelectedTab();

        return v;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        lastBoundProfilePictureUrl = null;
    }

    private void setupUI() {
        etBio.setFocusable(false);
        etBio.setClickable(false);

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

            if (cacheViewModel.followingStatusLoaded) {
                isFollowing = cacheViewModel.isFollowing;
                updateFollowButton();
            } else {
                checkFollowingStatus();
            }

            btnFollow.setOnClickListener(v -> toggleFollow());
        }
    }

    private void setupRecyclerViews() {
        ensureThreeFavoriteSlots(favoriteSlots);

        favoritesAdapter = new FavoritesAdapter(favoriteSlots, this);

        GridLayoutManager nonScrollableFavoritesLayoutManager =
                new GridLayoutManager(requireContext(), 3) {
                    @Override
                    public boolean canScrollVertically() {
                        return false;
                    }

                    @Override
                    public boolean canScrollHorizontally() {
                        return false;
                    }
                };

        rvFavoriteCards.setLayoutManager(nonScrollableFavoritesLayoutManager);
        rvFavoriteCards.setNestedScrollingEnabled(false);
        rvFavoriteCards.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rvFavoriteCards.setAdapter(favoritesAdapter);

        forumPostAdapter = new ForumPostAdapter(this);
        rvProfilePosts.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvProfilePosts.setAdapter(forumPostAdapter);
    }

    private void setupTabs() {
        profileTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showTab(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) { }

            @Override
            public void onTabReselected(TabLayout.Tab tab) { }
        });
    }

    private void initializeScreenData() {
        syncListsFromCache();

        bindProfileFromCache();
        bindPostsFromCache();
        bindFavoritesFromCache();

        if (!cacheViewModel.profileLoaded) {
            fetchUserProfile();
        }

        if (!cacheViewModel.postsLoaded) {
            loadUserPosts();
        }

        if (!cacheViewModel.favoritesLoaded) {
            loadCollectionChoicesAndFavorites();
        }

        if (!isCurrentUser && !cacheViewModel.followingStatusLoaded) {
            checkFollowingStatus();
        }
    }

    private void restoreSelectedTab() {
        int initialTab = cacheViewModel.selectedTabPosition;
        selectedTabPosition = initialTab;

        TabLayout.Tab tab = profileTabLayout.getTabAt(initialTab);
        if (tab != null) {
            tab.select();
        }

        showTab(initialTab);
    }

    private void syncListsFromCache() {
        availableFavoriteChoices.clear();
        availableFavoriteChoices.addAll(cacheViewModel.availableFavoriteChoices);

        favoriteSlots.clear();
        favoriteSlots.addAll(cacheViewModel.favoriteSlots);
        ensureThreeFavoriteSlots(favoriteSlots);

        userPosts.clear();
        userPosts.addAll(cacheViewModel.userPosts);
    }

    private void bindProfileFromCache() {
        if (!cacheViewModel.profileLoaded) return;

        currentUsername = cacheViewModel.username;
        currentBio = cacheViewModel.bio;
        currentProfilePictureUrl = cacheViewModel.profilePictureUrl;

        tvUsername.setText(currentUsername != null && !currentUsername.trim().isEmpty()
                ? currentUsername : "No Username");

        etBio.setText(currentBio != null && !currentBio.trim().isEmpty()
                ? currentBio : "No bio yet.");

        tvPoints.setText("Total Points: " + cacheViewModel.totalPoints);
        tvFollowerCount.setText(String.valueOf(cacheViewModel.followerCount));
        tvFollowingCount.setText(String.valueOf(cacheViewModel.followingCount));

        loadProfilePicture(currentProfilePictureUrl);
    }

    private void bindPostsFromCache() {
        forumPostAdapter.setPosts(new ArrayList<>(userPosts));
        updateEmptyState();
    }

    private void bindFavoritesFromCache() {
        ensureThreeFavoriteSlots(favoriteSlots);
        favoritesAdapter.setSlots(new ArrayList<>(favoriteSlots));
        updateEmptyState();
    }

    private void showTab(int position) {
        selectedTabPosition = position;
        if (cacheViewModel != null) {
            cacheViewModel.selectedTabPosition = position;
        }

        updateEmptyState();
        updateScrollBehavior();

        if (profileAppBar != null && profileAppBar.getChildCount() > 0) {
            boolean showingFavorites = selectedTabPosition == 0;
            profileAppBar.setExpanded(true, false);

            View scrollableView = profileAppBar.getChildAt(0);
            if (scrollableView instanceof androidx.constraintlayout.widget.ConstraintLayout) {
                AppBarLayout.LayoutParams params =
                        (AppBarLayout.LayoutParams) scrollableView.getLayoutParams();

                if (!areScrollFlagsStored) {
                    originalScrollFlags = params.getScrollFlags();
                    areScrollFlagsStored = true;
                }

                if (showingFavorites) {
                    params.setScrollFlags(0);
                } else {
                    params.setScrollFlags(originalScrollFlags);
                }
                scrollableView.setLayoutParams(params);
            }
        }
    }

    private void updateScrollBehavior() {
        boolean showingFavorites = selectedTabPosition == 0;

        rvFavoriteCards.setNestedScrollingEnabled(false);
        rvFavoriteCards.setOverScrollMode(View.OVER_SCROLL_NEVER);

        rvProfilePosts.setNestedScrollingEnabled(!showingFavorites);
        rvProfilePosts.setOverScrollMode(showingFavorites
                ? View.OVER_SCROLL_NEVER
                : View.OVER_SCROLL_ALWAYS);
    }

    private void updateEmptyState() {
        boolean showingFavorites = selectedTabPosition == 0;

        if (showingFavorites) {
            rvFavoriteCards.setVisibility(View.VISIBLE);
            rvProfilePosts.setVisibility(View.GONE);
            tvProfileTabEmpty.setVisibility(View.GONE);
        } else {
            boolean isEmpty = userPosts.isEmpty();

            rvFavoriteCards.setVisibility(View.GONE);
            rvProfilePosts.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            tvProfileTabEmpty.setText("No posts yet.");
            tvProfileTabEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        }
    }

    private void ensureThreeFavoriteSlots(@NonNull List<CollectionSlot> slots) {
        while (slots.size() < 3) {
            slots.add(null);
        }
        while (slots.size() > 3) {
            slots.remove(slots.size() - 1);
        }
    }

    private void checkFollowingStatus() {
        firebaseManager.isFollowing(profileUserId, task -> {
            if (task.isSuccessful() && isAdded()) {
                isFollowing = task.getResult();
                cacheViewModel.isFollowing = isFollowing;
                cacheViewModel.followingStatusLoaded = true;
                updateFollowButton();
            }
        });
    }

    private void updateFollowButton() {
        if (isFollowing) {
            btnFollow.setText("Following");
            btnFollow.setIconResource(R.drawable.ic_bolt_24);
        } else {
            btnFollow.setText("Follow");
            btnFollow.setIcon(null);
        }
    }

    private void toggleFollow() {
        btnFollow.setEnabled(false);

        if (isFollowing) {
            firebaseManager.unfollowUser(profileUserId, task -> {
                if (!isAdded()) return;

                btnFollow.setEnabled(true);
                if (task.isSuccessful()) {
                    isFollowing = false;
                    cacheViewModel.isFollowing = false;
                    cacheViewModel.followingStatusLoaded = true;
                    updateFollowButton();
                    forceRefreshProfile();
                }
            });
        } else {
            firebaseManager.followUser(profileUserId, task -> {
                if (!isAdded()) return;

                btnFollow.setEnabled(true);
                if (task.isSuccessful()) {
                    isFollowing = true;
                    cacheViewModel.isFollowing = true;
                    cacheViewModel.followingStatusLoaded = true;
                    updateFollowButton();
                    forceRefreshProfile();
                }
            });
        }
    }

    private void fetchUserProfile() {
        if (profileUserId == null) return;

        db.collection("users")
                .document(profileUserId)
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (!isAdded() || !documentSnapshot.exists()) return;

                    User userProfile = documentSnapshot.toObject(User.class);
                    if (userProfile == null) return;

                    cacheViewModel.profileLoaded = true;
                    cacheViewModel.username = userProfile.getUsername();
                    cacheViewModel.bio = userProfile.getBio();
                    cacheViewModel.profilePictureUrl = userProfile.getProfilePictureUrl();
                    cacheViewModel.totalPoints = userProfile.getTotalPoints();
                    cacheViewModel.followerCount = userProfile.getFollowerCount();
                    cacheViewModel.followingCount = userProfile.getFollowingCount();

                    bindProfileFromCache();
                });
    }

    private void loadProfilePicture(String url) {
        if (ivPfp == null) return;

        String safeUrl = (url == null) ? "" : url.trim();

        if (safeUrl.equals(lastBoundProfilePictureUrl)) {
            return;
        }

        lastBoundProfilePictureUrl = safeUrl;

        if (!safeUrl.isEmpty()) {
            RequestBuilder<Drawable> request = Glide.with(this)
                    .load(safeUrl)
                    .dontAnimate()
                    .error(R.drawable.ic_profile);

            Drawable currentDrawable = ivPfp.getDrawable();
            if (currentDrawable != null) {
                request = request.placeholder(currentDrawable);
            } else {
                request = request.placeholder(R.drawable.ic_profile);
            }

            request.into(ivPfp);
        } else {
            ivPfp.setImageResource(R.drawable.ic_profile);
        }
    }

    private void loadUserPosts() {
        if (profileUserId == null) return;

        db.collection("forumThreads")
                .whereEqualTo("userId", profileUserId)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    userPosts.clear();

                    for (DocumentSnapshot doc : queryDocumentSnapshots.getDocuments()) {
                        ForumPost post = doc.toObject(ForumPost.class);
                        if (post != null) {
                            post.setId(doc.getId());
                            userPosts.add(post);
                        }
                    }

                    cacheViewModel.postsLoaded = true;
                    cacheViewModel.userPosts.clear();
                    cacheViewModel.userPosts.addAll(userPosts);

                    bindPostsFromCache();
                })
                .addOnFailureListener(e -> loadUserPostsFallback());
    }

    private void loadUserPostsFallback() {
        if (profileUserId == null) return;

        db.collection("forumThreads")
                .whereEqualTo("userId", profileUserId)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    userPosts.clear();

                    for (DocumentSnapshot doc : queryDocumentSnapshots.getDocuments()) {
                        ForumPost post = doc.toObject(ForumPost.class);
                        if (post != null) {
                            post.setId(doc.getId());
                            userPosts.add(post);
                        }
                    }

                    cacheViewModel.postsLoaded = true;
                    cacheViewModel.userPosts.clear();
                    cacheViewModel.userPosts.addAll(userPosts);

                    bindPostsFromCache();
                });
    }

    private void loadCollectionChoicesAndFavorites() {
        if (profileUserId == null) return;

        db.collection("users")
                .document(profileUserId)
                .collection("collectionSlot")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    availableFavoriteChoices.clear();

                    for (QueryDocumentSnapshot doc : queryDocumentSnapshots) {
                        availableFavoriteChoices.add(documentToCollectionSlot(doc));
                    }

                    cacheViewModel.availableFavoriteChoices.clear();
                    cacheViewModel.availableFavoriteChoices.addAll(availableFavoriteChoices);

                    loadFavoriteSlotsFromLocal();
                })
                .addOnFailureListener(e -> loadCollectionChoicesAndFavoritesFallback());
    }

    private void loadCollectionChoicesAndFavoritesFallback() {
        if (profileUserId == null) return;

        db.collection("users")
                .document(profileUserId)
                .collection("collectionSlot")
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    availableFavoriteChoices.clear();

                    for (QueryDocumentSnapshot doc : queryDocumentSnapshots) {
                        availableFavoriteChoices.add(documentToCollectionSlot(doc));
                    }

                    cacheViewModel.availableFavoriteChoices.clear();
                    cacheViewModel.availableFavoriteChoices.addAll(availableFavoriteChoices);

                    loadFavoriteSlotsFromLocal();
                })
                .addOnFailureListener(e -> {
                    availableFavoriteChoices.clear();

                    favoriteSlots.clear();
                    ensureThreeFavoriteSlots(favoriteSlots);

                    cacheViewModel.favoritesLoaded = true;
                    cacheViewModel.availableFavoriteChoices.clear();
                    cacheViewModel.favoriteSlots.clear();
                    cacheViewModel.favoriteSlots.addAll(favoriteSlots);

                    bindFavoritesFromCache();
                });
    }

    private void loadFavoriteSlotsFromLocal() {
        List<String> savedIds = getSavedFavoriteIds();

        favoriteSlots.clear();
        for (int i = 0; i < 3; i++) {
            favoriteSlots.add(findCollectionSlotById(savedIds.get(i)));
        }
        ensureThreeFavoriteSlots(favoriteSlots);

        cacheViewModel.favoritesLoaded = true;
        cacheViewModel.favoriteSlots.clear();
        cacheViewModel.favoriteSlots.addAll(favoriteSlots);

        bindFavoritesFromCache();
    }

    @NonNull
    private CollectionSlot documentToCollectionSlot(@NonNull DocumentSnapshot document) {
        CollectionSlot slot = new CollectionSlot();
        slot.setId(document.getId());
        slot.setUserBirdId(document.getString("userBirdId"));
        slot.setBirdId(document.getString("birdId"));
        slot.setImageUrl(document.getString("imageUrl"));
        slot.setTimestamp(document.getDate("timestamp"));
        slot.setCommonName(document.getString("commonName"));
        slot.setScientificName(document.getString("scientificName"));
        slot.setState(document.getString("state"));
        slot.setLocality(document.getString("locality"));
        slot.setRarity(document.getString("rarity"));

        Long slotIndex = document.getLong("slotIndex");
        slot.setSlotIndex(slotIndex != null ? slotIndex.intValue() : 0);

        return slot;
    }

    @NonNull
    private List<String> getSavedFavoriteIds() {
        SharedPreferences prefs = requireActivity()
                .getSharedPreferences(PREFS_NAME, AppCompatActivity.MODE_PRIVATE);

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(prefs.getString("favorite_" + profileUserId + "_" + i, ""));
        }
        return ids;
    }

    private void saveFavoriteIds(List<String> ids) {
        SharedPreferences.Editor editor = requireActivity()
                .getSharedPreferences(PREFS_NAME, AppCompatActivity.MODE_PRIVATE)
                .edit();

        for (int i = 0; i < ids.size(); i++) {
            editor.putString("favorite_" + profileUserId + "_" + i, ids.get(i));
        }
        editor.apply();
    }

    @Nullable
    private CollectionSlot findCollectionSlotById(@Nullable String cardId) {
        if (cardId == null || cardId.trim().isEmpty()) return null;

        for (CollectionSlot slot : availableFavoriteChoices) {
            if (slot != null && cardId.equals(slot.getId())) {
                return slot;
            }
        }

        return null;
    }

    private boolean isAlreadyChosenInAnotherSlot(@NonNull String cardId, int currentSlotIndex) {
        for (int i = 0; i < favoriteSlots.size(); i++) {
            if (i == currentSlotIndex) continue;

            CollectionSlot existing = favoriteSlots.get(i);
            if (existing != null && cardId.equals(existing.getId())) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private String buildFavoriteChoiceLabel(@NonNull CollectionSlot slot) {
        String common = slot.getCommonName();
        String sci = slot.getScientificName();

        if (common != null && !common.trim().isEmpty() && sci != null && !sci.trim().isEmpty()) {
            return common + " (" + sci + ")";
        }

        if (common != null && !common.trim().isEmpty()) {
            return common;
        }

        if (sci != null && !sci.trim().isEmpty()) {
            return sci;
        }

        return "Unknown Bird";
    }

    @Override
    public void onFavoriteSlotClick(int position, @Nullable CollectionSlot slot) {
        if (isCurrentUser) {
            showFavoritePicker(position);
        } else if (slot != null) {
            openFavoriteCard(slot);
        }
    }

    private void showFavoritePicker(int slotPosition) {
        if (!isCurrentUser) return;

        if (availableFavoriteChoices.isEmpty()) {
            Toast.makeText(requireContext(), "You do not have any saved cards yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        final List<CollectionSlot> allChoices = new ArrayList<>();
        final List<String> allLabels = new ArrayList<>();

        allChoices.add(null);
        allLabels.add("Clear this slot");

        for (CollectionSlot slot : availableFavoriteChoices) {
            if (slot == null || slot.getId() == null || slot.getId().trim().isEmpty()) continue;
            if (isAlreadyChosenInAnotherSlot(slot.getId(), slotPosition)) continue;

            allChoices.add(slot);
            allLabels.add(buildFavoriteChoiceLabel(slot));
        }

        if (allChoices.size() == 1) {
            Toast.makeText(requireContext(), "No other cards available to choose.", Toast.LENGTH_SHORT).show();
            return;
        }

        final List<CollectionSlot> filteredChoices = new ArrayList<>(allChoices);
        final List<String> filteredLabels = new ArrayList<>(allLabels);

        float density = getResources().getDisplayMetrics().density;
        int padding = (int) (16 * density);

        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, padding, padding, padding);

        EditText searchBox = new EditText(requireContext());
        searchBox.setHint("Search cards...");
        searchBox.setSingleLine(true);
        container.addView(searchBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        ListView listView = new ListView(requireContext());
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (320 * density)
        );
        listParams.topMargin = (int) (12 * density);
        container.addView(listView, listParams);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                filteredLabels
        );
        listView.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("Choose a favorite card")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            CollectionSlot selected = filteredChoices.get(position);
            saveFavoriteCardSelection(slotPosition, selected);
            dialog.dismiss();
        });

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s == null ? "" : s.toString().trim().toLowerCase(Locale.US);

                filteredChoices.clear();
                filteredLabels.clear();

                filteredChoices.add(null);
                filteredLabels.add("Clear this slot");

                for (int i = 1; i < allChoices.size(); i++) {
                    CollectionSlot slot = allChoices.get(i);
                    String label = allLabels.get(i);

                    String commonName = slot != null && slot.getCommonName() != null
                            ? slot.getCommonName().toLowerCase(Locale.US)
                            : "";

                    String scientificName = slot != null && slot.getScientificName() != null
                            ? slot.getScientificName().toLowerCase(Locale.US)
                            : "";

                    boolean matches = query.isEmpty()
                            || label.toLowerCase(Locale.US).contains(query)
                            || commonName.contains(query)
                            || scientificName.contains(query);

                    if (matches) {
                        filteredChoices.add(slot);
                        filteredLabels.add(label);
                    }
                }

                adapter.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(Editable s) { }
        });

        dialog.show();
    }

    private void saveFavoriteCardSelection(int slotPosition, @Nullable CollectionSlot selectedSlot) {
        List<String> favoriteIds = getSavedFavoriteIds();
        String selectedId = (selectedSlot == null) ? "" : selectedSlot.getId();

        for (int i = 0; i < favoriteIds.size(); i++) {
            if (i != slotPosition
                    && selectedId != null
                    && !selectedId.isEmpty()
                    && selectedId.equals(favoriteIds.get(i))) {
                Toast.makeText(requireContext(), "This card is already chosen in another slot.", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        favoriteIds.set(slotPosition, selectedId == null ? "" : selectedId);
        saveFavoriteIds(favoriteIds);
        loadFavoriteSlotsFromLocal();
    }

    private void openFavoriteCard(@NonNull CollectionSlot slot) {
        Intent i = new Intent(requireContext(), ViewBirdCardActivity.class);
        i.putExtra(CollectionCardAdapter.EXTRA_IMAGE_URL, slot.getImageUrl());
        i.putExtra(CollectionCardAdapter.EXTRA_COMMON_NAME, slot.getCommonName());
        i.putExtra(CollectionCardAdapter.EXTRA_SCI_NAME, slot.getScientificName());
        i.putExtra(CollectionCardAdapter.EXTRA_STATE, slot.getState());
        i.putExtra(CollectionCardAdapter.EXTRA_LOCALITY, slot.getLocality());
        i.putExtra(CollectionCardAdapter.EXTRA_BIRD_ID, slot.getBirdId());

        if (slot.getTimestamp() != null) {
            i.putExtra(CollectionCardAdapter.EXTRA_CAUGHT_TIME, slot.getTimestamp().getTime());
        }

        startActivity(i);
    }

    @Override
    public void onLikeClick(ForumPost post) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null || post == null || post.getId() == null) return;

        String userId = user.getUid();
        boolean currentlyLiked = post.getLikedBy() != null && post.getLikedBy().containsKey(userId);

        if (currentlyLiked) {
            db.collection("forumThreads")
                    .document(post.getId())
                    .update(
                            "likeCount", FieldValue.increment(-1),
                            "likedBy." + userId, FieldValue.delete()
                    )
                    .addOnSuccessListener(unused -> forceRefreshPosts());
        } else {
            db.collection("forumThreads")
                    .document(post.getId())
                    .update(
                            "likeCount", FieldValue.increment(1),
                            "likedBy." + userId, true
                    )
                    .addOnSuccessListener(unused -> forceRefreshPosts());
        }
    }

    @Override
    public void onCommentClick(ForumPost post) {
        onPostClick(post);
    }

    @Override
    public void onPostClick(ForumPost post) {
        if (post == null || post.getId() == null) return;

        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) {
            String userId = user.getUid();
            if (post.getViewedBy() == null || !post.getViewedBy().containsKey(userId)) {
                db.collection("forumThreads")
                        .document(post.getId())
                        .update(
                                "viewCount", FieldValue.increment(1),
                                "viewedBy." + userId, true
                        );
            }
        }

        Intent intent = new Intent(requireContext(), PostDetailActivity.class);
        intent.putExtra(PostDetailActivity.EXTRA_POST_ID, post.getId());
        startActivity(intent);
    }

    @Override
    public void onOptionsClick(ForumPost post, View view) {
        if (post == null) return;

        PopupMenu popup = new PopupMenu(requireContext(), view);
        FirebaseUser user = mAuth.getCurrentUser();

        if (user != null && post.getUserId() != null && post.getUserId().equals(user.getUid())) {
            popup.getMenu().add("Delete");
        }
        popup.getMenu().add("Report");

        popup.setOnMenuItemClickListener(item -> {
            if ("Delete".contentEquals(item.getTitle())) {
                showDeleteConfirmation(post);
            } else if ("Report".contentEquals(item.getTitle())) {
                showReportDialog(post);
            }
            return true;
        });

        popup.show();
    }

    @Override
    public void onUserClick(String userId) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null || userId == null) return;

        if (!userId.equals(currentUser.getUid())) {
            Intent intent = new Intent(requireContext(), UserSocialProfileActivity.class);
            intent.putExtra(UserSocialProfileActivity.EXTRA_USER_ID, userId);
            startActivity(intent);
        }
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
                            forceRefreshPosts();
                        } else {
                            Toast.makeText(getContext(), "Failed to delete post.", Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showReportDialog(ForumPost post) {
        String[] reasons = {
                "Inappropriate Language",
                "Inappropriate Image",
                "Spam",
                "Harassment",
                "Other"
        };

        new AlertDialog.Builder(requireContext())
                .setTitle("Report Post")
                .setItems(reasons, (dialog, which) -> {
                    String selectedReason = reasons[which];
                    submitReport(post, selectedReason);
                })
                .show();
    }

    private void submitReport(ForumPost post, String reason) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null || post == null || post.getId() == null) return;

        Report report = new Report("post", post.getId(), user.getUid(), reason);
        firebaseManager.addReport(report, task -> {
            if (!isAdded()) return;

            if (task.isSuccessful()) {
                Toast.makeText(
                        getContext(),
                        "Thank you for reporting. We will review this post.",
                        Toast.LENGTH_LONG
                ).show();
            } else {
                Toast.makeText(getContext(), "Failed to submit report.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void forceRefreshProfile() {
        cacheViewModel.profileLoaded = false;
        fetchUserProfile();
    }

    private void forceRefreshPosts() {
        cacheViewModel.postsLoaded = false;
        loadUserPosts();
    }

    private void forceRefreshFavorites() {
        cacheViewModel.favoritesLoaded = false;
        loadCollectionChoicesAndFavorites();
    }

    public static class ProfileCacheViewModel extends ViewModel {
        public String cachedProfileUserId;
        public boolean profileLoaded = false;
        public boolean postsLoaded = false;
        public boolean favoritesLoaded = false;
        public boolean followingStatusLoaded = false;

        public String username;
        public String bio;
        public String profilePictureUrl;
        public int totalPoints = 0;
        public int followerCount = 0;
        public int followingCount = 0;

        public boolean isFollowing = false;
        public int selectedTabPosition = 0;

        public final ArrayList<CollectionSlot> favoriteSlots = new ArrayList<>();
        public final ArrayList<CollectionSlot> availableFavoriteChoices = new ArrayList<>();
        public final ArrayList<ForumPost> userPosts = new ArrayList<>();

        public boolean isForProfile(String userId) {
            if (cachedProfileUserId == null && userId == null) return true;
            if (cachedProfileUserId == null) return false;
            return cachedProfileUserId.equals(userId);
        }

        public void resetForProfile(String userId) {
            cachedProfileUserId = userId;

            profileLoaded = false;
            postsLoaded = false;
            favoritesLoaded = false;
            followingStatusLoaded = false;

            username = null;
            bio = null;
            profilePictureUrl = null;
            totalPoints = 0;
            followerCount = 0;
            followingCount = 0;

            isFollowing = false;
            selectedTabPosition = 0;

            favoriteSlots.clear();
            favoriteSlots.add(null);
            favoriteSlots.add(null);
            favoriteSlots.add(null);

            availableFavoriteChoices.clear();
            userPosts.clear();
        }
    }
}