package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SocialActivity: Activity class for one BirdDex screen. It owns screen setup, user actions, and navigation for this part of the app.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class SocialActivity extends AppCompatActivity implements UserAdapter.OnUserClickListener {

    private static final String TAG = "SocialActivity";
    public static final String EXTRA_USER_ID = "extra_user_id";
    public static final String EXTRA_SHOW_FOLLOWING = "extra_show_following";
    private static final int PAGE_SIZE = 30;
    private int activeTabIndex = -1;

    private TabLayout tabLayout;
    private RecyclerView rvUserList;
    private ProgressBar progressBar;
    private TextView tvEmpty;
    private UserAdapter adapter;
    private FirebaseFirestore db;
    private String targetUserId;

    private List<User> userList = new ArrayList<>();
    private DocumentSnapshot lastVisible;
    private boolean isFetching = false;
    private boolean isLastPage = false;

    // --- FIXES ---
    private boolean isNavigating = false;
    private int fetchGeneration = 0;

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_social);

        targetUserId = getIntent().getStringExtra(EXTRA_USER_ID);
        if (targetUserId == null) targetUserId = FirebaseAuth.getInstance().getUid();

        // Bind or inflate the UI pieces this method needs before it can update the screen.
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tabLayout = findViewById(R.id.tabLayout);
        rvUserList = findViewById(R.id.rvUserList);
        progressBar = findViewById(R.id.progressBar);
        tvEmpty = findViewById(R.id.tvEmpty);

        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db = FirebaseFirestore.getInstance();
        // Hook the data source to the list/grid adapter so model objects can render as UI rows/cards.
        adapter = new UserAdapter(this);
        rvUserList.setLayoutManager(new LinearLayoutManager(this));
        rvUserList.setAdapter(adapter);

        rvUserList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 0) {
                    LinearLayoutManager lm = (LinearLayoutManager) rvUserList.getLayoutManager();
                    if (lm != null && !isFetching && !isLastPage) {
                        if (lm.getChildCount() + lm.findFirstVisibleItemPosition() >= lm.getItemCount()) loadUsers(tabLayout.getSelectedTabPosition() == 0);
                    }
                }
            }
        });

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { refreshList(tab.getPosition() == 0); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        boolean showFollowing = getIntent().getBooleanExtra(EXTRA_SHOW_FOLLOWING, false);
        if (showFollowing) { tabLayout.getTabAt(1).select(); refreshList(false); }
        else refreshList(true);
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
     * Main logic block for this part of the feature.
     */
    private void refreshList(boolean isFollowers) {
        fetchGeneration++;
        isFetching = false; activeTabIndex = isFollowers ? 0 : 1;
        userList.clear(); adapter.setUsers(new ArrayList<>()); lastVisible = null; isLastPage = false;
        loadUsers(isFollowers);
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    private void loadUsers(boolean isFollowers) {
        if (targetUserId == null || isFetching || isLastPage) return;
        isFetching = true;
        final int myGen = fetchGeneration;
        final int myTab = activeTabIndex;

        if (userList.isEmpty()) progressBar.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);

        // Set up or query the Firebase layer that supplies/stores this feature's data.
        Query query = db.collection("users").document(targetUserId).collection(isFollowers ? "followers" : "following").limit(PAGE_SIZE);
        if (lastVisible != null) query = query.startAfter(lastVisible);

        query.get().addOnSuccessListener(snap -> {
            if (myGen != fetchGeneration || myTab != activeTabIndex) return;
            if (snap.isEmpty()) { isLastPage = true; if (userList.isEmpty()) displayUsers(); finishLoad(); return; }
            lastVisible = snap.getDocuments().get(snap.size() - 1);
            List<String> ids = new ArrayList<>();
            for (DocumentSnapshot doc : snap) ids.add(doc.getId());
            fetchUserDetails(ids, myGen, myTab);
        }).addOnFailureListener(e -> {
            if (myGen == fetchGeneration && myTab == activeTabIndex) finishLoad();
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
    private void fetchUserDetails(List<String> userIds, final int gen, final int tab) {
        final int total = userIds.size(); if (total == 0) { finishLoad(); return; }
        final int[] count = {0}; final List<User> fetched = Collections.synchronizedList(new ArrayList<>());
        for (String id : userIds) {
            // Set up or query the Firebase layer that supplies/stores this feature's data.
            db.collection("users").document(id).get().addOnSuccessListener(doc -> {
                if (doc.exists()) {
                    User user = doc.toObject(User.class);
                    if (user != null && !Boolean.TRUE.equals(doc.getBoolean("isDeleted"))) { user.setId(doc.getId()); fetched.add(user); }
                }
                checkIfDone(count, total, fetched, gen, tab);
            }).addOnFailureListener(e -> checkIfDone(count, total, fetched, gen, tab));
        }
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void checkIfDone(int[] count, int total, List<User> fetched, int gen, int tab) {
        count[0]++;
        if (count[0] >= total) {
            if (gen != fetchGeneration || tab != activeTabIndex) return;
            userList.addAll(fetched); displayUsers(); finishLoad();
        }
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void displayUsers() {
        adapter.setUsers(new ArrayList<>(userList));
        if (userList.isEmpty()) { tvEmpty.setVisibility(View.VISIBLE); tvEmpty.setText(tabLayout.getSelectedTabPosition() == 0 ? "No followers found." : "Not following anyone yet."); }
        else tvEmpty.setVisibility(View.GONE);
    }

    private void finishLoad() { isFetching = false; progressBar.setVisibility(View.GONE); }

    @Override public void onUserClick(User user) {
        if (isNavigating) return;
        isNavigating = true;
        startActivity(new Intent(this, UserSocialProfileActivity.class).putExtra(UserSocialProfileActivity.EXTRA_USER_ID, user.getId()));
    }
}
