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
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class SocialActivity extends AppCompatActivity implements UserAdapter.OnUserClickListener {

    private static final String TAG = "SocialActivity";
    public static final String EXTRA_USER_ID = "extra_user_id";
    public static final String EXTRA_SHOW_FOLLOWING = "extra_show_following";
    private static final int PAGE_SIZE = 30;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_social);

        targetUserId = getIntent().getStringExtra(EXTRA_USER_ID);
        if (targetUserId == null) {
            targetUserId = FirebaseAuth.getInstance().getUid();
        }

        boolean showFollowingInitially = getIntent().getBooleanExtra(EXTRA_SHOW_FOLLOWING, false);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tabLayout = findViewById(R.id.tabLayout);
        rvUserList = findViewById(R.id.rvUserList);
        progressBar = findViewById(R.id.progressBar);
        tvEmpty = findViewById(R.id.tvEmpty);

        db = FirebaseFirestore.getInstance();

        adapter = new UserAdapter(this);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        rvUserList.setLayoutManager(layoutManager);
        rvUserList.setAdapter(adapter);

        rvUserList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0) {
                    int visibleItemCount = layoutManager.getChildCount();
                    int totalItemCount = layoutManager.getItemCount();
                    int pastVisibleItems = layoutManager.findFirstVisibleItemPosition();

                    if (!isFetching && !isLastPage) {
                        if ((visibleItemCount + pastVisibleItems) >= totalItemCount) {
                            loadUsers(tabLayout.getSelectedTabPosition() == 0);
                        }
                    }
                }
            }
        });

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                refreshList(tab.getPosition() == 0);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        if (showFollowingInitially) {
            tabLayout.getTabAt(1).select();
            refreshList(false);
        } else {
            refreshList(true);
        }
    }

    private void refreshList(boolean isFollowers) {
        userList.clear();
        adapter.setUsers(new ArrayList<>());
        lastVisible = null;
        isLastPage = false;
        loadUsers(isFollowers);
    }

    private void loadUsers(boolean isFollowers) {
        if (targetUserId == null || isFetching || isLastPage) return;
        isFetching = true;

        if (userList.isEmpty()) {
            progressBar.setVisibility(View.VISIBLE);
        }
        tvEmpty.setVisibility(View.GONE);

        String subCollection = isFollowers ? "followers" : "following";

        // Query the subcollection for IDs with pagination
        Query query = db.collection("users").document(targetUserId).collection(subCollection)
                .limit(PAGE_SIZE);

        if (lastVisible != null) {
            query = query.startAfter(lastVisible);
        }

        query.get().addOnSuccessListener(queryDocumentSnapshots -> {
            if (queryDocumentSnapshots == null || queryDocumentSnapshots.isEmpty()) {
                isLastPage = true;
                finishLoad();
                return;
            }

            lastVisible = queryDocumentSnapshots.getDocuments().get(queryDocumentSnapshots.size() - 1);
            if (queryDocumentSnapshots.size() < PAGE_SIZE) {
                isLastPage = true;
            }

            List<String> userIds = new ArrayList<>();
            for (DocumentSnapshot doc : queryDocumentSnapshots) {
                userIds.add(doc.getId());
            }

            fetchUserDetails(userIds);
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Error fetching user IDs", e);
            finishLoad();
        });
    }

    private void fetchUserDetails(List<String> userIds) {
        final int totalToFetch = userIds.size();
        final AtomicInteger fetchedCount = new AtomicInteger(0);
        final List<User> fetchedUsers = Collections.synchronizedList(new ArrayList<>());

        for (String id : userIds) {
            db.collection("users").document(id).get()
                    .addOnSuccessListener(doc -> {
                        if (doc.exists()) {
                            User user = doc.toObject(User.class);
                            // SAFETY FILTER: Skip users marked as deleted
                            Boolean isDeleted = doc.getBoolean("isDeleted");
                            if (user != null && (isDeleted == null || !isDeleted)) {
                                user.setId(doc.getId());
                                fetchedUsers.add(user);
                            }
                        }
                        checkIfDone(fetchedCount, totalToFetch, fetchedUsers);
                    })
                    .addOnFailureListener(e -> {
                        checkIfDone(fetchedCount, totalToFetch, fetchedUsers);
                    });
        }
    }

    private void checkIfDone(AtomicInteger count, int total, List<User> fetchedUsers) {
        if (count.incrementAndGet() >= total) {
            // All requests finished, update UI on main thread
            runOnUiThread(() -> {
                userList.addAll(fetchedUsers);
                displayUsers();
                finishLoad();
            });
        }
    }

    private void displayUsers() {
        adapter.setUsers(new ArrayList<>(userList));
        if (userList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText(tabLayout.getSelectedTabPosition() == 0 ? "No followers found." : "Not following anyone yet.");
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }

    private void finishLoad() {
        isFetching = false;
        progressBar.setVisibility(View.GONE);
    }

    @Override
    public void onUserClick(User user) {
        Intent intent = new Intent(this, UserSocialProfileActivity.class);
        intent.putExtra(UserSocialProfileActivity.EXTRA_USER_ID, user.getId());
        startActivity(intent);
    }
}