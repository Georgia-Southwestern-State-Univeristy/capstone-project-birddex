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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private boolean isNavigating = false;
    private int fetchGeneration = 0;
    private int favoriteFetchGeneration = 0;

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

        if (getArguments() != null) profileUserId = getArguments().getString(ARG_USER_ID);
        FirebaseUser user = mAuth.getCurrentUser();
        if (profileUserId == null || (user != null && profileUserId.equals(user.getUid()))) {
            profileUserId = user != null ? user.getUid() : null;
            isCurrentUser = true;
        } else isCurrentUser = false;

        editProfileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        });
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

        setupRecyclerViews();
        setupTabs();
        setupUI();
        setupSocialCountClicks();
        setupSwipeRefresh();
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        isNavigating = false;
        if (profileListener == null) fetchUserProfile();
        refreshPosts();
    }

    private void setupRecyclerViews() {
        favoritesAdapter = new FavoritesAdapter(isCurrentUser, this);
        rvFavoriteCards.setLayoutManager(new GridLayoutManager(requireContext(), 3) { @Override public boolean canScrollVertically() { return false; } });
        rvFavoriteCards.setAdapter(favoritesAdapter);

        postsAdapter = new ForumPostAdapter(this);
        rvProfilePosts.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvProfilePosts.setAdapter(postsAdapter);
        rvProfilePosts.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 0 && !isFetching && !isLastPage) {
                    LinearLayoutManager lm = (LinearLayoutManager) rvProfilePosts.getLayoutManager();
                    if (lm != null && lm.getChildCount() + lm.findFirstVisibleItemPosition() >= lm.getItemCount()) fetchPosts();
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
            rvFavoriteCards.setVisibility(View.VISIBLE); rvProfilePosts.setVisibility(View.GONE); tvProfileTabEmpty.setVisibility(View.GONE);
        } else {
            rvFavoriteCards.setVisibility(View.GONE);
            rvProfilePosts.setVisibility(postList.isEmpty() ? View.GONE : View.VISIBLE);
            tvProfileTabEmpty.setVisibility(postList.isEmpty() ? View.VISIBLE : View.GONE);
            if (postList.isEmpty()) tvProfileTabEmpty.setText("No posts yet.");
        }
    }

    private void setupUI() {
        if (isCurrentUser) {
            btnEditProfile.setVisibility(View.VISIBLE); btnSettings.setVisibility(View.VISIBLE); btnFollow.setVisibility(View.GONE);
            btnSettings.setOnClickListener(v -> { if (!isNavigating) { isNavigating = true; startActivity(new Intent(requireContext(), SettingsActivity.class)); } });
            btnEditProfile.setOnClickListener(v -> {
                if (isNavigating) return;
                isNavigating = true;
                editProfileLauncher.launch(new Intent(requireContext(), EditProfileActivity.class).putExtra("username", currentUsername).putExtra("bio", currentBio).putExtra("profilePictureUrl", currentProfilePictureUrl));
            });
        } else {
            btnEditProfile.setVisibility(View.GONE); btnSettings.setVisibility(View.GONE); btnFollow.setVisibility(View.VISIBLE);
            btnFollow.setOnClickListener(v -> toggleFollow());
            checkFollowingStatus();
        }
    }

    private void setupSocialCountClicks() {
        btnFollowers.setOnClickListener(v -> openSocialScreen(false));
        btnFollowing.setOnClickListener(v -> openSocialScreen(true));
    }

    private void openSocialScreen(boolean showFollowing) {
        if (profileUserId == null || isNavigating) return;
        isNavigating = true;
        startActivity(new Intent(requireContext(), SocialActivity.class).putExtra(SocialActivity.EXTRA_USER_ID, profileUserId).putExtra(SocialActivity.EXTRA_SHOW_FOLLOWING, showFollowing));
    }

    private void fetchUserProfile() {
        if (profileUserId == null) return;
        if (profileListener != null) profileListener.remove();
        profileListener = db.collection("users").document(profileUserId).addSnapshotListener((snapshot, e) -> {
            if (e != null || snapshot == null || !snapshot.exists() || !isAdded()) return;
            handleUserSnapshot(snapshot);
        });
    }

    private void handleUserSnapshot(DocumentSnapshot doc) {
        User user = doc.toObject(User.class); if (user == null) return;
        Log.d(TAG, "handleUserSnapshot — bio: " + user.getBio());
        currentUsername = user.getUsername(); currentBio = user.getBio(); currentProfilePictureUrl = user.getProfilePictureUrl();
        tvUsername.setText(currentUsername != null ? currentUsername : "User");
        tvBio.setText(currentBio != null && !currentBio.isEmpty() ? currentBio : "No bio yet.");
        tvPoints.setText("Total Points: " + user.getTotalPoints());
        tvFollowerCount.setText(String.valueOf(user.getFollowerCount()));
        tvFollowingCount.setText(String.valueOf(user.getFollowingCount()));
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

    private void loadFavoriteCards() {
        if (profileUserId == null) return;
        final int myGen = ++favoriteFetchGeneration;
        db.collection("users").document(profileUserId).collection("collectionSlot").get().addOnSuccessListener(querySnapshot -> {
            if (!isAdded() || myGen != favoriteFetchGeneration) return;
            allCollectionSlots.clear();
            for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                CollectionSlot slot = doc.toObject(CollectionSlot.class);
                if (slot != null) { slot.setId(doc.getId()); allCollectionSlots.add(slot); }
            }
            refreshFavoritesDisplay();
        });
    }

    private void refreshFavoritesDisplay() {
        while (favoriteCardKeys.size() < FAVORITE_SLOT_COUNT) favoriteCardKeys.add("");
        List<CollectionSlot> favoriteSlots = new ArrayList<>();
        for (int i = 0; i < FAVORITE_SLOT_COUNT; i++) favoriteSlots.add(findSlotById(favoriteCardKeys.get(i)));
        favoritesAdapter.submitList(favoriteSlots);
    }

    private CollectionSlot findSlotById(String id) {
        if (id == null || id.isEmpty()) return null;
        for (CollectionSlot slot : allCollectionSlots) if (id.equals(slot.getId())) return slot;
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
        final int myGen = fetchGeneration;
        Query query = db.collection("forumThreads").whereEqualTo("userId", profileUserId).orderBy("timestamp", Query.Direction.DESCENDING).limit(PAGE_SIZE);
        if (lastVisible != null) query = query.startAfter(lastVisible);

        query.get().addOnSuccessListener(value -> {
            if (!isAdded() || myGen != fetchGeneration) return;
            if (value != null && !value.isEmpty()) {
                lastVisible = value.getDocuments().get(value.size() - 1);
                for (DocumentSnapshot doc : value.getDocuments()) {
                    ForumPost post = doc.toObject(ForumPost.class);
                    if (post != null) { post.setId(doc.getId()); postList.add(post); }
                }
                postsAdapter.setPosts(new ArrayList<>(postList));
                if (value.size() < PAGE_SIZE) isLastPage = true;
            } else isLastPage = true;
            isFetching = false;
            applyTabState(profileTabLayout.getSelectedTabPosition());
        }).addOnFailureListener(e -> { if (myGen == fetchGeneration) isFetching = false; });
    }

    private void refreshPosts() {
        fetchGeneration++;
        isFetching = false; lastVisible = null; isLastPage = false;
        postList.clear();
        if (postsAdapter != null) postsAdapter.setPosts(new ArrayList<>());
        fetchPosts();
    }

    private void setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener(() -> { fetchUserProfile(); refreshPosts(); swipeRefreshLayout.setRefreshing(false); });
    }

    @Override public void onLikeClick(ForumPost post) {
        FirebaseUser u = mAuth.getCurrentUser();
        if (u == null || post.getId() == null) return;
        String uid = u.getUid();
        if (post.getLikedBy() == null) post.setLikedBy(new HashMap<>());
        boolean liked = post.getLikedBy().containsKey(uid);
        int count = post.getLikeCount();

        if (liked) { post.setLikeCount(Math.max(0, count - 1)); post.getLikedBy().remove(uid); }
        else { post.setLikeCount(count + 1); post.getLikedBy().put(uid, true); }
        postsAdapter.notifyDataSetChanged();

        db.collection("forumThreads").document(post.getId()).update("likedBy." + uid, liked ? FieldValue.delete() : true)
                .addOnFailureListener(e -> {
                    post.setLikeCount(count); if (liked) post.getLikedBy().put(uid, true); else post.getLikedBy().remove(uid);
                    postsAdapter.notifyDataSetChanged();
                });
    }

    @Override public void onCommentClick(ForumPost post) { onPostClick(post); }
    @Override public void onPostClick(ForumPost post) {
        if (isNavigating) return;
        isNavigating = true;
        startActivity(new Intent(requireContext(), PostDetailActivity.class).putExtra(PostDetailActivity.EXTRA_POST_ID, post.getId()));
    }

    @Override public void onOptionsClick(ForumPost post, View view) {
        PopupMenu popup = new PopupMenu(requireContext(), view);
        if (mAuth.getUid() != null && mAuth.getUid().equals(post.getUserId())) popup.getMenu().add("Delete");
        popup.getMenu().add("Report");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getTitle().equals("Delete")) showDeleteConfirmation(post);
            else showReportDialog(post);
            return true;
        });
        popup.show();
    }

    @Override public void onUserClick(String userId) {
        if (isNavigating || userId.equals(profileUserId)) return;
        isNavigating = true;
        startActivity(new Intent(requireContext(), UserSocialProfileActivity.class).putExtra(UserSocialProfileActivity.EXTRA_USER_ID, userId));
    }

    @Override public void onMapClick(ForumPost post) {
        if (isNavigating || post.getLatitude() == null) return;
        isNavigating = true;
        startActivity(new Intent(requireContext(), NearbyHeatmapActivity.class).putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LAT, post.getLatitude()).putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LNG, post.getLongitude()).putExtra("extra_post_id", post.getId()));
    }

    @Override public void onFavoriteClicked(int position, @Nullable CollectionSlot slot) {
        if (slot == null) { if (isCurrentUser) showFavoritePickerDialog(position); }
        else {
            if (isNavigating) return;
            isNavigating = true;
            Intent intent = new Intent(requireContext(), ViewBirdCardActivity.class);
            intent.putExtra(CollectionCardAdapter.EXTRA_IMAGE_URL, slot.getImageUrl()); intent.putExtra(CollectionCardAdapter.EXTRA_COMMON_NAME, slot.getCommonName());
            intent.putExtra(CollectionCardAdapter.EXTRA_SCI_NAME, slot.getScientificName()); intent.putExtra(CollectionCardAdapter.EXTRA_STATE, slot.getState());
            intent.putExtra(CollectionCardAdapter.EXTRA_LOCALITY, slot.getLocality()); intent.putExtra(CollectionCardAdapter.EXTRA_BIRD_ID, slot.getBirdId());
            if (slot.getTimestamp() != null) intent.putExtra(CollectionCardAdapter.EXTRA_CAUGHT_TIME, slot.getTimestamp().getTime());
            startActivity(intent);
        }
    }

    @Override public boolean onFavoriteLongPressed(int position, @Nullable CollectionSlot slot) { if (!isCurrentUser) return false; showFavoritePickerDialog(position); return true; }

    private void showFavoritePickerDialog(int position) {
        if (allCollectionSlots.isEmpty()) return;
        View dv = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_favorite_picker, null);
        EditText et = dv.findViewById(R.id.etSearch); ListView lv = dv.findViewById(R.id.listView); Button clr = dv.findViewById(R.id.btnClear);
        List<CollectionSlot> filtered = new ArrayList<>(allCollectionSlots);
        ArrayAdapter<String> adp = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, getSlotNames(filtered));
        lv.setAdapter(adp);
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setTitle("Pick Favorite").setView(dv).setNegativeButton("Cancel", null).create();
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filtered.clear(); String q = s.toString().toLowerCase();
                for (CollectionSlot sl : allCollectionSlots) if (sl.getCommonName() != null && sl.getCommonName().toLowerCase().contains(q)) filtered.add(sl);
                adp.clear(); adp.addAll(getSlotNames(filtered)); adp.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        lv.setOnItemClickListener((parent, view, which, id) -> { saveFavoriteSelection(position, filtered.get(which).getId()); dialog.dismiss(); });
        clr.setOnClickListener(v -> { saveFavoriteSelection(position, ""); dialog.dismiss(); });
        dialog.show();
    }

    private List<String> getSlotNames(List<CollectionSlot> slots) { List<String> ns = new ArrayList<>(); for (CollectionSlot s : slots) ns.add(s.getCommonName()); return ns; }

    private void saveFavoriteSelection(int pos, String key) {
        while (favoriteCardKeys.size() < FAVORITE_SLOT_COUNT) favoriteCardKeys.add("");
        favoriteCardKeys.set(pos, key); refreshFavoritesDisplay();
        if (profileUserId == null) return;
        DocumentReference ref = db.collection("users").document(profileUserId);
        db.runTransaction(t -> {
            DocumentSnapshot snap = t.get(ref); List<String> keys = (List<String>) snap.get("favoriteCardKeys");
            keys = (keys == null) ? new ArrayList<>() : new ArrayList<>(keys);
            while (keys.size() < FAVORITE_SLOT_COUNT) keys.add("");
            keys.set(pos, key); t.update(ref, "favoriteCardKeys", keys);
            return null;
        });
    }

    private void showDeleteConfirmation(ForumPost post) { new AlertDialog.Builder(requireContext()).setTitle("Delete Post").setPositiveButton("Delete", (d, w) -> firebaseManager.deleteForumPost(post.getId(), t -> refreshPosts())).show(); }

    private void showReportDialog(ForumPost post) {
        String[] rs = {"Inappropriate Language", "Inappropriate Image", "Spam", "Harassment", "Other"};
        new AlertDialog.Builder(requireContext()).setTitle("Report Post").setItems(rs, (d, w) -> {
            if (rs[w].equals("Other")) showOtherReportDialog(reason -> submitReport(post, reason));
            else submitReport(post, rs[w]);
        }).show();
    }

    private void showOtherReportDialog(OnReasonEnteredListener l) {
        AlertDialog.Builder b = new AlertDialog.Builder(requireContext()); b.setTitle("Report Reason");
        final EditText i = new EditText(requireContext()); i.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); i.setHint("Reason..."); i.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        FrameLayout c = new FrameLayout(requireContext()); FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); p.leftMargin = p.rightMargin = 40; i.setLayoutParams(p); c.addView(i); b.setView(c);
        b.setPositiveButton("Submit", (d, w) -> { String r = i.getText().toString().trim(); if (!r.isEmpty()) l.onReasonEntered(r); });
        b.setNegativeButton("Cancel", (d, w) -> d.cancel()); b.show();
    }

    private interface OnReasonEnteredListener { void onReasonEntered(String r); }

    private void submitReport(ForumPost post, String r) {
        FirebaseUser u = mAuth.getCurrentUser(); if (u == null) return;
        firebaseManager.addReport(new Report("post", post.getId(), u.getUid(), r), t -> { if (isAdded() && t.isSuccessful()) Toast.makeText(getContext(), "Reported", Toast.LENGTH_SHORT).show(); });
    }

    @Override public void onStop() { super.onStop(); if (profileListener != null) { profileListener.remove(); profileListener = null; } }
}
