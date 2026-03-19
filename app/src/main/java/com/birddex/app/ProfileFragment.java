package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
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
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ProfileFragment: Current-user profile screen that loads profile data, favorite cards, and the user's posts.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class ProfileFragment extends Fragment implements
        FavoritesAdapter.OnFavoriteInteractionListener,
        ForumPostAdapter.OnPostClickListener {

    private static final String TAG = "ProfileFragment";
    private static final String ARG_USER_ID = "arg_user_id";
    private static final int PAGE_SIZE = 20;
    private static final int FAVORITE_SLOT_COUNT = 3;

    private ShapeableImageView ivPfp;
    private TextView tvUsername, tvPoints, tvBio, tvFollowerCount, tvFollowingCount, tvProfileTabEmpty;
    private MaterialButton btnFollow;
    private ImageButton btnSettings, btnEditProfile;
    private View btnFollowers, btnFollowing;

    private TabLayout profileTabLayout;
    private RecyclerView rvFavoriteCards, rvProfilePosts;
    private SwipeRefreshLayout swipeRefreshLayout;
    private AppBarLayout profileAppBar;
    private View profileHeader;

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseManager firebaseManager;

    private FavoritesAdapter favoritesAdapter;
    private ForumPostAdapter postsAdapter;

    private List<ForumPost> postList = new ArrayList<>();
    private final List<ForumPost> savedPostList = new ArrayList<>();
    private DocumentSnapshot lastVisible;
    private DocumentSnapshot lastSavedVisible;
    private boolean isFetching = false;
    private boolean isFetchingSaved = false;
    private boolean isLastPage = false;
    private boolean isSavedLastPage = false;

    private String profileUserId;
    private String currentUsername, currentBio, currentProfilePictureUrl;
    private boolean isCurrentUser = true;
    private boolean isFollowing = false;

    private final List<String> favoriteCardKeys = new ArrayList<>();
    private final List<CollectionSlot> allCollectionSlots = new ArrayList<>();

    private ActivityResultLauncher<Intent> editProfileLauncher;
    private ListenerRegistration profileListener;

    private boolean isNavigating = false;
    private int fetchGeneration = 0;
    private int savedFetchGeneration = 0;
    private int favoriteFetchGeneration = 0;

    public ProfileFragment() {}

    /**
     * Main logic block for this part of the feature.
     */
    public static ProfileFragment newInstance(String userId) {
        ProfileFragment fragment = new ProfileFragment();
        Bundle args = new Bundle();
        args.putString(ARG_USER_ID, userId);
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAuth = FirebaseAuth.getInstance();
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db = FirebaseFirestore.getInstance();
        firebaseManager = new FirebaseManager(requireContext());

        if (getArguments() != null) profileUserId = getArguments().getString(ARG_USER_ID);
        FirebaseUser user = mAuth.getCurrentUser();
        if (profileUserId == null || (user != null && profileUserId.equals(user.getUid()))) {
            profileUserId = user != null ? user.getUid() : null;
            isCurrentUser = true;
        } else isCurrentUser = false;

        editProfileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        });
    }

    /**
     * Android calls this to inflate the Fragment's XML and return the root view that will be shown
     * on screen.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Bind or inflate the UI pieces this method needs before it can update the screen.
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
        profileAppBar = v.findViewById(R.id.profileAppBar);
        profileHeader = v.findViewById(R.id.profileHeader);
        profileTabLayout = v.findViewById(R.id.profileTabLayout);
        tvProfileTabEmpty = v.findViewById(R.id.tvProfileTabEmpty);
        rvFavoriteCards = v.findViewById(R.id.rvFavoriteCards);
        rvProfilePosts = v.findViewById(R.id.rvProfilePosts);
        swipeRefreshLayout = v.findViewById(R.id.swipeRefreshLayout);

        setupRecyclerViews();
        setupTabs();
        setupUI();
        setupSocialCountClicks();
        setupSwipeRefresh();
        return v;
    }

    /**
     * Runs when the screen returns to the foreground, so it often refreshes UI state or restarts
     * listeners.
     */
    @Override
    public void onResume() {
        super.onResume();
        isNavigating = false;
        if (profileListener == null) fetchUserProfile();
        refreshPosts();
        if (isCurrentUser) refreshSavedPosts();
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    private void setupRecyclerViews() {
        // Hook the data source to the list/grid adapter so model objects can render as UI rows/cards.
        favoritesAdapter = new FavoritesAdapter(isCurrentUser, this);
        rvFavoriteCards.setLayoutManager(new GridLayoutManager(requireContext(), 3) {
            @Override
            public boolean canScrollVertically() {
                return false;
            }
        });
        rvFavoriteCards.setAdapter(favoritesAdapter);

        postsAdapter = new ForumPostAdapter(this);
        rvProfilePosts.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvProfilePosts.setAdapter(postsAdapter);
        rvProfilePosts.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return;
                LinearLayoutManager lm = (LinearLayoutManager) rvProfilePosts.getLayoutManager();
                if (lm == null || lm.getChildCount() + lm.findFirstVisibleItemPosition() < lm.getItemCount()) return;

                if (isSavedTabSelected()) {
                    if (!isFetchingSaved && !isSavedLastPage) fetchSavedPosts();
                } else if (!isFetching && !isLastPage) {
                    fetchPosts();
                }
            }
        });
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    private void setupTabs() {
        if (isCurrentUser && profileTabLayout.getTabCount() == 2) {
            profileTabLayout.addTab(profileTabLayout.newTab().setIcon(R.drawable.outline_bookmark_24));
        } else if (isCurrentUser && profileTabLayout.getTabCount() == 0) {
            profileTabLayout.addTab(profileTabLayout.newTab().setIcon(R.drawable.ic_collection));
            profileTabLayout.addTab(profileTabLayout.newTab().setIcon(R.drawable.ic_forum));
            profileTabLayout.addTab(profileTabLayout.newTab().setIcon(R.drawable.outline_bookmark_24));
        }

        profileTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                applyTabState(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
        applyTabState(0);
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void applyTabState(int position) {
        if (!isAdded()) return;

        updateAppBarScrollBehavior(position);

        if (position == 0) {
            rvFavoriteCards.setVisibility(View.VISIBLE);
            rvProfilePosts.setVisibility(View.GONE);
            tvProfileTabEmpty.setVisibility(View.GONE);
            return;
        }

        rvFavoriteCards.setVisibility(View.GONE);

        if (isCurrentUser && position == getSavedTabPosition()) {
            if (savedPostList.isEmpty() && !isFetchingSaved && !isSavedLastPage) fetchSavedPosts();
            postsAdapter.setPosts(new ArrayList<>(savedPostList));
            rvProfilePosts.setVisibility(savedPostList.isEmpty() ? View.GONE : View.VISIBLE);
            tvProfileTabEmpty.setVisibility(savedPostList.isEmpty() ? View.VISIBLE : View.GONE);
            if (savedPostList.isEmpty()) tvProfileTabEmpty.setText("No saved posts yet.");
            return;
        }

        if (postList.isEmpty() && !isFetching && !isLastPage) fetchPosts();
        postsAdapter.setPosts(new ArrayList<>(postList));
        rvProfilePosts.setVisibility(postList.isEmpty() ? View.GONE : View.VISIBLE);
        tvProfileTabEmpty.setVisibility(postList.isEmpty() ? View.VISIBLE : View.GONE);
        if (postList.isEmpty()) tvProfileTabEmpty.setText("No posts yet.");
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    private void updateAppBarScrollBehavior(int position) {
        if (profileAppBar == null || profileHeader == null || profileTabLayout == null || swipeRefreshLayout == null) return;

        AppBarLayout.LayoutParams headerParams = (AppBarLayout.LayoutParams) profileHeader.getLayoutParams();
        AppBarLayout.LayoutParams tabParams = (AppBarLayout.LayoutParams) profileTabLayout.getLayoutParams();

        if (position == 0) {
            headerParams.setScrollFlags(0);
            tabParams.setScrollFlags(0);
            profileHeader.setLayoutParams(headerParams);
            profileTabLayout.setLayoutParams(tabParams);
            profileAppBar.setLiftOnScroll(false);
            profileAppBar.setExpanded(true, false);
            swipeRefreshLayout.setNestedScrollingEnabled(false);
            rvFavoriteCards.setNestedScrollingEnabled(false);
            rvFavoriteCards.stopScroll();
            return;
        }

        headerParams.setScrollFlags(
                AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
                        | AppBarLayout.LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED
        );
        tabParams.setScrollFlags(0);
        profileHeader.setLayoutParams(headerParams);
        profileTabLayout.setLayoutParams(tabParams);
        profileAppBar.setLiftOnScroll(true);
        swipeRefreshLayout.setNestedScrollingEnabled(true);
        rvFavoriteCards.setNestedScrollingEnabled(false);
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    private void setupUI() {
        if (isCurrentUser) {
            btnEditProfile.setVisibility(View.VISIBLE);
            btnSettings.setVisibility(View.VISIBLE);
            btnFollow.setVisibility(View.GONE);
            // Attach the user interaction that should run when this control is tapped.
            btnSettings.setOnClickListener(v -> {
                if (!isNavigating) {
                    isNavigating = true;
                    startActivity(new Intent(requireContext(), SettingsActivity.class));
                }
            });
            btnEditProfile.setOnClickListener(v -> {
                if (isNavigating) return;
                isNavigating = true;
                editProfileLauncher.launch(new Intent(requireContext(), EditProfileActivity.class)
                        .putExtra("username", currentUsername)
                        .putExtra("bio", currentBio)
                        .putExtra("profilePictureUrl", currentProfilePictureUrl));
            });
        } else {
            btnEditProfile.setVisibility(View.GONE);
            btnSettings.setVisibility(View.GONE);
            btnFollow.setVisibility(View.VISIBLE);
            btnFollow.setOnClickListener(v -> toggleFollow());
            checkFollowingStatus();
        }
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     */
    private void setupSocialCountClicks() {
        // Attach the user interaction that should run when this control is tapped.
        btnFollowers.setOnClickListener(v -> openSocialScreen(false));
        btnFollowing.setOnClickListener(v -> openSocialScreen(true));
    }

    /**
     * Moves the user to another screen or flow and passes along the required extras.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    private void openSocialScreen(boolean showFollowing) {
        if (profileUserId == null || isNavigating) return;
        isNavigating = true;
        // Move into the next screen and pass the identifiers/data that screen needs.
        startActivity(new Intent(requireContext(), SocialActivity.class)
                .putExtra(SocialActivity.EXTRA_USER_ID, profileUserId)
                .putExtra(SocialActivity.EXTRA_SHOW_FOLLOWING, showFollowing));
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Because it uses a snapshot listener, this method keeps the UI synced with live Firestore
     * updates instead of doing a one-time read.
     */
    private void fetchUserProfile() {
        if (profileUserId == null) return;
        if (profileListener != null) profileListener.remove();
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        profileListener = db.collection("users").document(profileUserId).addSnapshotListener((snapshot, e) -> {
            if (e != null || snapshot == null || !snapshot.exists() || !isAdded()) return;
            handleUserSnapshot(snapshot);
        });
    }

    /**
     * Central handler that reacts to an event/input and decides what the next app action should
     * be.
     * Image loading happens here, which is why placeholder/error behavior for profile
     * photos/cards/posts usually traces back to this code path.
     */
    private void handleUserSnapshot(DocumentSnapshot doc) {
        User user = doc.toObject(User.class);
        if (user == null) return;
        Log.d(TAG, "handleUserSnapshot — bio: " + user.getBio());
        currentUsername = user.getUsername();
        currentBio = user.getBio();
        currentProfilePictureUrl = user.getProfilePictureUrl();
        tvUsername.setText(currentUsername != null ? currentUsername : "User");
        tvBio.setText(currentBio != null && !currentBio.isEmpty() ? currentBio : "No bio yet.");
        tvPoints.setText("Total Points: " + user.getTotalPoints());
        tvFollowerCount.setText(String.valueOf(user.getFollowerCount()));
        tvFollowingCount.setText(String.valueOf(user.getFollowingCount()));
        // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
        Glide.with(this)
                .load((currentProfilePictureUrl != null && !currentProfilePictureUrl.isEmpty()) ? currentProfilePictureUrl : null)
                .placeholder(R.drawable.ic_profile)
                .error(R.drawable.ic_profile)
                .into(ivPfp);
        favoriteCardKeys.clear();
        List<String> cloudKeys = (List<String>) doc.get("favoriteCardKeys");
        if (cloudKeys != null) favoriteCardKeys.addAll(cloudKeys);
        loadFavoriteCards();
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    private void loadFavoriteCards() {
        if (profileUserId == null) return;
        final int myGen = ++favoriteFetchGeneration;
        db.collection("users").document(profileUserId).collection("collectionSlot")
                .get(Source.CACHE)
                .addOnSuccessListener(querySnapshot -> {
                    if (!isAdded() || myGen != favoriteFetchGeneration) return;
                    if (querySnapshot != null && !querySnapshot.isEmpty()) {
                        processFavoriteSlotSnapshot(querySnapshot, myGen);
                    }
                    fetchFavoriteCardsFromServer(profileUserId, myGen);
                })
                .addOnFailureListener(e -> fetchFavoriteCardsFromServer(profileUserId, myGen));
    }

    private void fetchFavoriteCardsFromServer(String userId, int generation) {
        db.collection("users").document(userId).collection("collectionSlot")
                .get(Source.SERVER)
                .addOnSuccessListener(querySnapshot -> {
                    if (!isAdded() || generation != favoriteFetchGeneration) return;
                    processFavoriteSlotSnapshot(querySnapshot, generation);
                });
    }

    private void processFavoriteSlotSnapshot(com.google.firebase.firestore.QuerySnapshot querySnapshot, int generation) {
        if (!isAdded() || generation != favoriteFetchGeneration) return;
        allCollectionSlots.clear();
        for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
            CollectionSlot slot = doc.toObject(CollectionSlot.class);
            if (slot != null) {
                slot.setId(doc.getId());
                allCollectionSlots.add(slot);
            }
        }
        refreshFavoritesDisplay();
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void refreshFavoritesDisplay() {
        while (favoriteCardKeys.size() < FAVORITE_SLOT_COUNT) favoriteCardKeys.add("");
        List<CollectionSlot> favoriteSlots = new ArrayList<>();
        for (int i = 0; i < FAVORITE_SLOT_COUNT; i++) favoriteSlots.add(findSlotById(favoriteCardKeys.get(i)));
        favoritesAdapter.submitList(favoriteSlots);
    }

    /**
     * Main logic block for this part of the feature.
     */
    private CollectionSlot findSlotById(String id) {
        if (id == null || id.isEmpty()) return null;
        for (CollectionSlot slot : allCollectionSlots) {
            if (id.equals(slot.getId())) return slot;
        }
        return null;
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void checkFollowingStatus() {
        if (profileUserId == null || isCurrentUser) return;
        firebaseManager.isFollowing(profileUserId, task -> {
            if (task.isSuccessful() && isAdded()) {
                isFollowing = task.getResult() != null && task.getResult();
                btnFollow.setText(isFollowing ? "Following" : "Follow");
            }
        });
    }

    /**
     * Flips a UI/data state and then updates the screen/backend to match the new value.
     */
    private void toggleFollow() {
        btnFollow.setEnabled(false);
        if (isFollowing) {
            firebaseManager.unfollowUser(profileUserId, task -> {
                if (!isAdded()) return;
                btnFollow.setEnabled(true);
                if (task.isSuccessful()) {
                    isFollowing = false;
                    btnFollow.setText("Follow");
                    refreshPosts();
                }
            });
        } else {
            firebaseManager.followUser(profileUserId, task -> {
                if (!isAdded()) return;
                btnFollow.setEnabled(true);
                if (task.isSuccessful()) {
                    isFollowing = true;
                    btnFollow.setText("Following");
                    refreshPosts();
                }
            });
        }
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    private void fetchPosts() {
        if (profileUserId == null || isFetching || isLastPage || !isAdded()) return;
        isFetching = true;
        final int myGen = fetchGeneration;

        if (lastVisible == null) {
            Query firstPageQuery = buildProfilePostsBaseQuery();
            firstPageQuery.get(Source.CACHE).addOnSuccessListener(value -> {
                if (!isAdded() || myGen != fetchGeneration) return;
                if (value != null && !value.isEmpty()) {
                    applyProfilePostFirstPage(value, false, myGen);
                }
                fetchProfilePostsFirstPageFromServer(firstPageQuery, myGen);
            }).addOnFailureListener(e -> fetchProfilePostsFirstPageFromServer(firstPageQuery, myGen));
            return;
        }

        buildProfilePostsBaseQuery().startAfter(lastVisible).get(Source.SERVER)
                .addOnSuccessListener(value -> {
                    if (!isAdded() || myGen != fetchGeneration) return;
                    appendProfilePosts(value, myGen);
                    finishProfilePostsFetch(myGen);
                })
                .addOnFailureListener(e -> finishProfilePostsFetch(myGen));
    }

    private Query buildProfilePostsBaseQuery() {
        return db.collection("forumThreads")
                .whereEqualTo("userId", profileUserId)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(PAGE_SIZE);
    }

    private void fetchProfilePostsFirstPageFromServer(Query firstPageQuery, int generation) {
        firstPageQuery.get(Source.SERVER).addOnSuccessListener(value -> {
            if (!isAdded() || generation != fetchGeneration) return;
            applyProfilePostFirstPage(value, true, generation);
            finishProfilePostsFetch(generation);
        }).addOnFailureListener(e -> finishProfilePostsFetch(generation));
    }

    private void applyProfilePostFirstPage(com.google.firebase.firestore.QuerySnapshot value, boolean fromServer, int generation) {
        if (!isAdded() || generation != fetchGeneration) return;
        if (fromServer || (value != null && !value.isEmpty())) {
            postList.clear();
            lastVisible = null;
            isLastPage = false;
        }

        if (value != null && !value.isEmpty()) {
            lastVisible = value.getDocuments().get(value.size() - 1);
            for (DocumentSnapshot doc : value.getDocuments()) {
                ForumPost post = doc.toObject(ForumPost.class);
                if (post != null) {
                    post.setId(doc.getId());
                    postList.add(post);
                }
            }
            if (value.size() < PAGE_SIZE) isLastPage = true;
        } else if (fromServer) {
            isLastPage = true;
        }

        if (!isSavedTabSelected()) postsAdapter.setPosts(new ArrayList<>(postList));
        applyTabState(profileTabLayout.getSelectedTabPosition());
    }

    private void appendProfilePosts(com.google.firebase.firestore.QuerySnapshot value, int generation) {
        if (!isAdded() || generation != fetchGeneration) return;
        if (value != null && !value.isEmpty()) {
            lastVisible = value.getDocuments().get(value.size() - 1);
            for (DocumentSnapshot doc : value.getDocuments()) {
                ForumPost post = doc.toObject(ForumPost.class);
                if (post != null) {
                    post.setId(doc.getId());
                    postList.add(post);
                }
            }
            if (value.size() < PAGE_SIZE) isLastPage = true;
        } else {
            isLastPage = true;
        }
        if (!isSavedTabSelected()) postsAdapter.setPosts(new ArrayList<>(postList));
        applyTabState(profileTabLayout.getSelectedTabPosition());
    }

    private void finishProfilePostsFetch(int generation) {
        if (generation == fetchGeneration) isFetching = false;
    }

    private void fetchSavedPosts() {
        if (!isCurrentUser || profileUserId == null || isFetchingSaved || isSavedLastPage || !isAdded()) return;
        isFetchingSaved = true;
        final int myGen = savedFetchGeneration;

        if (lastSavedVisible == null) {
            Query firstPageQuery = buildSavedPostsBaseQuery();
            firstPageQuery.get(Source.CACHE).addOnSuccessListener(value -> {
                if (!isAdded() || myGen != savedFetchGeneration) return;
                if (value != null && !value.isEmpty()) {
                    resolveSavedPostsFromSnapshot(value, Source.CACHE, myGen, false);
                }
                fetchSavedPostsFirstPageFromServer(firstPageQuery, myGen);
            }).addOnFailureListener(e -> fetchSavedPostsFirstPageFromServer(firstPageQuery, myGen));
            return;
        }

        buildSavedPostsBaseQuery().startAfter(lastSavedVisible).get(Source.SERVER)
                .addOnSuccessListener(value -> resolveSavedPostsFromSnapshot(value, Source.SERVER, myGen, false))
                .addOnFailureListener(e -> {
                    if (myGen == savedFetchGeneration) isFetchingSaved = false;
                });
    }

    private Query buildSavedPostsBaseQuery() {
        return db.collection("users")
                .document(profileUserId)
                .collection("savedPosts")
                .orderBy("savedAt", Query.Direction.DESCENDING)
                .limit(PAGE_SIZE);
    }

    private void fetchSavedPostsFirstPageFromServer(Query firstPageQuery, int generation) {
        firstPageQuery.get(Source.SERVER)
                .addOnSuccessListener(value -> resolveSavedPostsFromSnapshot(value, Source.SERVER, generation, true))
                .addOnFailureListener(e -> {
                    if (generation == savedFetchGeneration) isFetchingSaved = false;
                });
    }

    private void resolveSavedPostsFromSnapshot(com.google.firebase.firestore.QuerySnapshot value, Source source, int generation, boolean fromServer) {
        if (!isAdded() || generation != savedFetchGeneration) return;

        if (fromServer || (value != null && !value.isEmpty())) {
            if (lastSavedVisible == null || fromServer) {
                savedPostList.clear();
                lastSavedVisible = null;
                isSavedLastPage = false;
            }
        }

        if (value == null || value.isEmpty()) {
            if (fromServer) {
                isSavedLastPage = true;
                isFetchingSaved = false;
                if (isSavedTabSelected()) postsAdapter.setPosts(new ArrayList<>(savedPostList));
                applyTabState(profileTabLayout.getSelectedTabPosition());
            }
            return;
        }

        DocumentSnapshot pageLastVisible = value.getDocuments().get(value.size() - 1);
        List<com.google.android.gms.tasks.Task<DocumentSnapshot>> postTasks = new ArrayList<>();
        for (DocumentSnapshot savedDoc : value.getDocuments()) {
            String threadId = savedDoc.getString("threadId");
            if (threadId != null && !threadId.isEmpty()) {
                postTasks.add(db.collection("forumThreads").document(threadId).get(source));
            }
        }

        if (postTasks.isEmpty()) {
            lastSavedVisible = pageLastVisible;
            if (value.size() < PAGE_SIZE) isSavedLastPage = true;
            if (fromServer) isFetchingSaved = false;
            if (isSavedTabSelected()) postsAdapter.setPosts(new ArrayList<>(savedPostList));
            applyTabState(profileTabLayout.getSelectedTabPosition());
            return;
        }

        com.google.android.gms.tasks.Tasks.whenAllComplete(postTasks).addOnSuccessListener(done -> {
            if (!isAdded() || generation != savedFetchGeneration) return;

            List<ForumPost> resolvedPosts = new ArrayList<>();
            for (com.google.android.gms.tasks.Task<DocumentSnapshot> task : postTasks) {
                if (!task.isSuccessful() || task.getResult() == null || !task.getResult().exists()) continue;
                DocumentSnapshot doc = task.getResult();
                ForumPost post = doc.toObject(ForumPost.class);
                if (post != null) {
                    post.setId(doc.getId());
                    resolvedPosts.add(post);
                }
            }

            if (lastSavedVisible == null || fromServer) {
                savedPostList.clear();
            }
            savedPostList.addAll(resolvedPosts);
            lastSavedVisible = pageLastVisible;
            if (value.size() < PAGE_SIZE) isSavedLastPage = true;
            if (fromServer) isFetchingSaved = false;
            if (isSavedTabSelected()) postsAdapter.setPosts(new ArrayList<>(savedPostList));
            applyTabState(profileTabLayout.getSelectedTabPosition());
        }).addOnFailureListener(e -> {
            if (generation == savedFetchGeneration && fromServer) isFetchingSaved = false;
        });
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void refreshPosts() {
        fetchGeneration++;
        isFetching = false;
        lastVisible = null;
        isLastPage = false;
        postList.clear();
        if (postsAdapter != null && !isSavedTabSelected()) postsAdapter.setPosts(new ArrayList<>());
        fetchPosts();
    }

    private void refreshSavedPosts() {
        if (!isCurrentUser) return;
        savedFetchGeneration++;
        isFetchingSaved = false;
        lastSavedVisible = null;
        isSavedLastPage = false;
        savedPostList.clear();
        if (postsAdapter != null && isSavedTabSelected()) postsAdapter.setPosts(new ArrayList<>());
        fetchSavedPosts();
    }

    private boolean isSavedTabSelected() {
        return isCurrentUser && profileTabLayout != null && profileTabLayout.getSelectedTabPosition() == getSavedTabPosition();
    }

    private int getSavedTabPosition() {
        return isCurrentUser ? 2 : -1;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    private void setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener(() -> {
            fetchUserProfile();
            refreshPosts();
            if (isCurrentUser) refreshSavedPosts();
            swipeRefreshLayout.setRefreshing(false);
        });
    }

    @Override
    public void onLikeClick(ForumPost post) {
        FirebaseUser u = mAuth.getCurrentUser();
        if (u == null || post.getId() == null) return;
        String uid = u.getUid();
        if (post.getLikedBy() == null) post.setLikedBy(new HashMap<>());
        boolean liked = post.getLikedBy().containsKey(uid);
        int count = post.getLikeCount();

        if (liked) {
            post.setLikeCount(Math.max(0, count - 1));
            post.getLikedBy().remove(uid);
        } else {
            post.setLikeCount(count + 1);
            post.getLikedBy().put(uid, true);
        }
        postsAdapter.notifyDataSetChanged();

        db.collection("forumThreads").document(post.getId()).update("likedBy." + uid, liked ? FieldValue.delete() : true)
                .addOnFailureListener(e -> {
                    post.setLikeCount(count);
                    if (liked) post.getLikedBy().put(uid, true);
                    else post.getLikedBy().remove(uid);
                    postsAdapter.notifyDataSetChanged();
                });
    }

    @Override
    public void onCommentClick(ForumPost post) {
        onPostClick(post);
    }

    @Override
    public void onPostClick(ForumPost post) {
        if (isNavigating) return;
        isNavigating = true;
        startActivity(new Intent(requireContext(), PostDetailActivity.class)
                .putExtra(PostDetailActivity.EXTRA_POST_ID, post.getId()));
    }

    @Override
    public void onOptionsClick(ForumPost post, View view) {
        if (mAuth.getCurrentUser() == null) {
            showResolvedPostOptions(post, view, false);
            return;
        }

        firebaseManager.isForumPostSaved(post.getId(), task -> {
            boolean isSaved = task.isSuccessful() && task.getResult() != null && task.getResult();
            if (!isAdded()) return;
            showResolvedPostOptions(post, view, isSaved);
        });
    }

    private void showResolvedPostOptions(ForumPost post, View view, boolean isSaved) {
        PopupMenu popup = new PopupMenu(requireContext(), view);
        if (mAuth.getUid() != null && mAuth.getUid().equals(post.getUserId())) popup.getMenu().add("Delete");
        popup.getMenu().add(isSaved ? "Unsave Post" : "Save Post");
        popup.getMenu().add("Report");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getTitle().equals("Delete")) showDeleteConfirmation(post);
            else if (item.getTitle().equals("Save Post")) savePostForLater(post);
            else if (item.getTitle().equals("Unsave Post")) unsavePost(post);
            else showReportDialog(post);
            return true;
        });
        popup.show();
    }

    private void savePostForLater(ForumPost post) {
        firebaseManager.saveForumPost(post.getId(), new FirebaseManager.ForumWriteListener() {
            @Override
            public void onSuccess() {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), "Post saved", Toast.LENGTH_SHORT).show();
                refreshSavedPosts();
            }

            @Override
            public void onFailure(String errorMessage) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), errorMessage != null ? errorMessage : "Failed to save post.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void unsavePost(ForumPost post) {
        firebaseManager.unsaveForumPost(post.getId(), new FirebaseManager.ForumWriteListener() {
            @Override
            public void onSuccess() {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), "Post unsaved", Toast.LENGTH_SHORT).show();
                refreshSavedPosts();
            }

            @Override
            public void onFailure(String errorMessage) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), errorMessage != null ? errorMessage : "Failed to unsave post.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onUserClick(String userId) {
        if (isNavigating || userId.equals(profileUserId)) return;
        isNavigating = true;
        startActivity(new Intent(requireContext(), UserSocialProfileActivity.class)
                .putExtra(UserSocialProfileActivity.EXTRA_USER_ID, userId));
    }

    @Override
    public void onMapClick(ForumPost post) {
        if (isNavigating || post.getLatitude() == null) return;
        isNavigating = true;
        startActivity(new Intent(requireContext(), NearbyHeatmapActivity.class)
                .putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LAT, post.getLatitude())
                .putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LNG, post.getLongitude())
                .putExtra("extra_post_id", post.getId()));
    }

    @Override
    public void onFavoriteClicked(int position, @Nullable CollectionSlot slot) {
        if (slot == null) {
            if (isCurrentUser) showFavoritePickerDialog(position);
        } else {
            if (isNavigating) return;
            isNavigating = true;
            Intent intent = new Intent(requireContext(), ViewBirdCardActivity.class);
            intent.putExtra(CollectionCardAdapter.EXTRA_IMAGE_URL, slot.getImageUrl());
            intent.putExtra(CollectionCardAdapter.EXTRA_COMMON_NAME, slot.getCommonName());
            intent.putExtra(CollectionCardAdapter.EXTRA_SCI_NAME, slot.getScientificName());
            intent.putExtra(CollectionCardAdapter.EXTRA_STATE, slot.getState());
            intent.putExtra(CollectionCardAdapter.EXTRA_LOCALITY, slot.getLocality());
            intent.putExtra(CollectionCardAdapter.EXTRA_BIRD_ID, slot.getBirdId());
            if (slot.getTimestamp() != null) intent.putExtra(CollectionCardAdapter.EXTRA_CAUGHT_TIME, slot.getTimestamp().getTime());
            startActivity(intent);
        }
    }

    @Override
    public boolean onFavoriteLongPressed(int position, @Nullable CollectionSlot slot) {
        if (!isCurrentUser) return false;
        showFavoritePickerDialog(position);
        return true;
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    private void showFavoritePickerDialog(int position) {
        if (allCollectionSlots.isEmpty()) return;
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        View dv = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_favorite_picker, null);
        EditText et = dv.findViewById(R.id.etSearch);
        ListView lv = dv.findViewById(R.id.listView);
        Button clr = dv.findViewById(R.id.btnClear);
        List<CollectionSlot> filtered = new ArrayList<>(allCollectionSlots);
        ArrayAdapter<String> adp = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, getSlotNames(filtered));
        // Hook the data source to the list/grid adapter so model objects can render as UI rows/cards.
        lv.setAdapter(adp);
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("Pick Favorite")
                .setView(dv)
                .setNegativeButton("Cancel", null)
                .create();

        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filtered.clear();
                String q = s.toString().toLowerCase();
                for (CollectionSlot sl : allCollectionSlots) {
                    if (sl.getCommonName() != null && sl.getCommonName().toLowerCase().contains(q)) filtered.add(sl);
                }
                adp.clear();
                adp.addAll(getSlotNames(filtered));
                adp.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        lv.setOnItemClickListener((parent, view, which, id) -> {
            saveFavoriteSelection(position, filtered.get(which).getId());
            dialog.dismiss();
        });

        // Attach the user interaction that should run when this control is tapped.
        clr.setOnClickListener(v -> {
            saveFavoriteSelection(position, "");
            dialog.dismiss();
        });

        dialog.show();
    }

    private List<String> getSlotNames(List<CollectionSlot> slots) {
        List<String> ns = new ArrayList<>();
        for (CollectionSlot s : slots) ns.add(s.getCommonName());
        return ns;
    }

    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void saveFavoriteSelection(int pos, String key) {
        while (favoriteCardKeys.size() < FAVORITE_SLOT_COUNT) favoriteCardKeys.add("");
        // Persist the new state so the action is saved outside the current screen.
        favoriteCardKeys.set(pos, key);
        refreshFavoritesDisplay();
        if (profileUserId == null) return;
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        DocumentReference ref = db.collection("users").document(profileUserId);
        db.runTransaction(t -> {
            DocumentSnapshot snap = t.get(ref);
            List<String> keys = (List<String>) snap.get("favoriteCardKeys");
            keys = (keys == null) ? new ArrayList<>() : new ArrayList<>(keys);
            while (keys.size() < FAVORITE_SLOT_COUNT) keys.add("");
            keys.set(pos, key);
            t.update(ref, "favoriteCardKeys", keys);
            return null;
        });
    }

    private void showDeleteConfirmation(ForumPost post) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Post")
                .setPositiveButton("Delete", (d, w) -> firebaseManager.deleteForumPost(post.getId(), t -> {
                    refreshPosts();
                    if (isCurrentUser) refreshSavedPosts();
                }))
                .show();
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void showReportDialog(ForumPost post) {
        String[] rs = {"Inappropriate Language", "Inappropriate Image", "Spam", "Harassment", "Other"};
        new AlertDialog.Builder(requireContext()).setTitle("Report Post").setItems(rs, (d, w) -> {
            if (rs[w].equals("Other")) showOtherReportDialog(reason -> submitReport(post, reason));
            else submitReport(post, rs[w]);
        }).show();
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void showOtherReportDialog(OnReasonEnteredListener l) {
        AlertDialog.Builder b = new AlertDialog.Builder(requireContext());
        b.setTitle("Report Reason");
        final EditText i = new EditText(requireContext());
        i.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        i.setHint("Reason...");
        i.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        FrameLayout c = new FrameLayout(requireContext());
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = p.rightMargin = 40;
        i.setLayoutParams(p);
        c.addView(i);
        b.setView(c);
        b.setPositiveButton("Submit", (d, w) -> {
            String r = i.getText().toString().trim();
            if (!r.isEmpty()) l.onReasonEntered(r);
        });
        b.setNegativeButton("Cancel", (d, w) -> d.cancel());
        b.show();
    }

    private interface OnReasonEnteredListener {
        void onReasonEntered(String r);
    }

    /**
     * Main logic block for this part of the feature.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void submitReport(ForumPost post, String r) {
        FirebaseUser u = mAuth.getCurrentUser();
        if (u == null) return;
        // Give the user immediate feedback about the result of this action.
        firebaseManager.addReport(new Report("post", post.getId(), u.getUid(), r), t -> {
            if (isAdded() && t.isSuccessful()) Toast.makeText(getContext(), "Reported", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onStop() {
        super.onStop();
        if (profileListener != null) {
            profileListener.remove();
            profileListener = null;
        }
    }
}