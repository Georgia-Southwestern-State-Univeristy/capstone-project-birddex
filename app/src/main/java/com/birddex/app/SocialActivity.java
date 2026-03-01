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

        progressBar.setVisibility(View.VISIBLE);
        rvUserList.setVisibility(View.GONE);
        tvEmpty.setVisibility(View.GONE);

        String subCollection = isFollowers ? "followers" : "following";

        db.collection("users").document(targetUserId).collection(subCollection)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    List<String> userIds = new ArrayList<>();
                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        userIds.add(doc.getId());
                    }

                    if (userIds.isEmpty()) {
                        progressBar.setVisibility(View.GONE);
                        tvEmpty.setVisibility(View.VISIBLE);
                        tvEmpty.setText(isFollowers ? "No followers found." : "Not following anyone yet.");
                        return;
                    }

                    fetchUserDetails(userIds);
                })
                .addOnFailureListener(e -> {
                    progressBar.setVisibility(View.GONE);
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText("Failed to load users.");
                });
    }

    private void fetchUserDetails(List<String> userIds) {
        List<User> users = new ArrayList<>();
        final int total = userIds.size();
        final int[] count = {0};

        for (String id : userIds) {
            db.collection("users").document(id).get()
                    .addOnSuccessListener(doc -> {
                        User user = doc.toObject(User.class);
                        if (user != null) {
                            user.setId(doc.getId());
                            users.add(user);
                        }
                        count[0]++;
                        if (count[0] == total) {
                            displayUsers(users);
                        }
                    })
                    .addOnFailureListener(e -> {
                        count[0]++;
                        if (count[0] == total) {
                            displayUsers(users);
                        }
                    });
        }
    }

    private void displayUsers(List<User> users) {
        progressBar.setVisibility(View.GONE);
        if (users.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
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
