package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.List;

public class SocialActivity extends AppCompatActivity implements UserAdapter.OnUserClickListener {

    public static final String EXTRA_USER_ID = "extra_user_id";
    public static final String EXTRA_SHOW_FOLLOWING = "extra_show_following";

    private TabLayout tabLayout;
    private RecyclerView rvUserList;
    private ProgressBar progressBar;
    private TextView tvEmpty;
    private UserAdapter adapter;
    private FirebaseFirestore db;
    private String targetUserId;

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
        rvUserList.setLayoutManager(new LinearLayoutManager(this));
        rvUserList.setAdapter(adapter);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                loadUsers(tab.getPosition() == 0);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        if (showFollowingInitially) {
            tabLayout.getTabAt(1).select();
            loadUsers(false);
        } else {
            loadUsers(true);
        }
    }

    private void loadUsers(boolean isFollowers) {
        if (targetUserId == null) return;

        // Don't show progress bar if we likely have cached data
        if (adapter.getItemCount() == 0) {
            progressBar.setVisibility(View.VISIBLE);
        }
        
        tvEmpty.setVisibility(View.GONE);

        String subCollection = isFollowers ? "followers" : "following";

        // Try to get IDs from CACHE first
        db.collection("users").document(targetUserId).collection(subCollection)
                .get(Source.CACHE)
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (queryDocumentSnapshots != null && !queryDocumentSnapshots.isEmpty()) {
                        processIds(queryDocumentSnapshots.getDocuments(), isFollowers);
                    }
                    // Sync with server in background
                    fetchIdsFromServer(subCollection, isFollowers);
                })
                .addOnFailureListener(e -> fetchIdsFromServer(subCollection, isFollowers));
    }

    private void fetchIdsFromServer(String subCollection, boolean isFollowers) {
        db.collection("users").document(targetUserId).collection(subCollection)
                .get(Source.SERVER)
                .addOnSuccessListener(queryDocumentSnapshots -> processIds(queryDocumentSnapshots.getDocuments(), isFollowers));
    }

    private void processIds(List<DocumentSnapshot> docs, boolean isFollowers) {
        List<String> userIds = new ArrayList<>();
        for (DocumentSnapshot doc : docs) {
            userIds.add(doc.getId());
        }

        if (userIds.isEmpty()) {
            progressBar.setVisibility(View.GONE);
            if (adapter.getItemCount() == 0) {
                tvEmpty.setVisibility(View.VISIBLE);
                tvEmpty.setText(isFollowers ? "No followers found." : "Not following anyone yet.");
            }
            return;
        }

        fetchUserDetails(userIds);
    }

    private void fetchUserDetails(List<String> userIds) {
        List<User> users = new ArrayList<>();
        final int total = userIds.size();
        final int[] count = {0};

        for (String id : userIds) {
            // Try cache for each user profile
            db.collection("users").document(id).get(Source.CACHE)
                    .addOnSuccessListener(doc -> {
                        User user = doc.toObject(User.class);
                        if (user != null) {
                            user.setId(doc.getId());
                            addUserToList(user, users, count, total);
                        } else {
                            fetchUserFromServer(id, users, count, total);
                        }
                    })
                    .addOnFailureListener(e -> fetchUserFromServer(id, users, count, total));
        }
    }

    private void fetchUserFromServer(String id, List<User> users, int[] count, int total) {
        db.collection("users").document(id).get(Source.SERVER)
                .addOnSuccessListener(doc -> {
                    User user = doc.toObject(User.class);
                    if (user != null) {
                        user.setId(doc.getId());
                        addUserToList(user, users, count, total);
                    } else {
                        incrementAndCheck(users, count, total);
                    }
                })
                .addOnFailureListener(e -> incrementAndCheck(users, count, total));
    }

    private synchronized void addUserToList(User user, List<User> users, int[] count, int total) {
        // Prevent duplicates in the temporary list
        boolean exists = false;
        for (User u : users) {
            if (u.getId().equals(user.getId())) {
                exists = true;
                break;
            }
        }
        if (!exists) users.add(user);
        incrementAndCheck(users, count, total);
    }

    private void incrementAndCheck(List<User> users, int[] count, int total) {
        count[0]++;
        if (count[0] >= total) {
            displayUsers(users);
        }
    }

    private void displayUsers(List<User> users) {
        progressBar.setVisibility(View.GONE);
        if (users.isEmpty() && adapter.getItemCount() == 0) {
            tvEmpty.setVisibility(View.VISIBLE);
            rvUserList.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            rvUserList.setVisibility(View.VISIBLE);
            adapter.setUsers(users);
        }
    }

    @Override
    public void onUserClick(User user) {
        Intent intent = new Intent(this, UserSocialProfileActivity.class);
        intent.putExtra(UserSocialProfileActivity.EXTRA_USER_ID, user.getId());
        startActivity(intent);
    }
}
