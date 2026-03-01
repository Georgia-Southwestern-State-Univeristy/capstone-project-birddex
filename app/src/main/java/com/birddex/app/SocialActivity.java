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

    private TabLayout tabLayout;
    private RecyclerView rvUserList;
    private ProgressBar progressBar;
    private TextView tvEmpty;
    private UserAdapter adapter;
    private FirebaseFirestore db;
    private String currentUserId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_social);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tabLayout = findViewById(R.id.tabLayout);
        rvUserList = findViewById(R.id.rvUserList);
        progressBar = findViewById(R.id.progressBar);
        tvEmpty = findViewById(R.id.tvEmpty);

        db = FirebaseFirestore.getInstance();
        currentUserId = FirebaseAuth.getInstance().getUid();

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

        // Initial load (Followers)
        loadUsers(true);
    }

    private void loadUsers(boolean isFollowers) {
        if (currentUserId == null) return;

        progressBar.setVisibility(View.VISIBLE);
        rvUserList.setVisibility(View.GONE);
        tvEmpty.setVisibility(View.GONE);

        String subCollection = isFollowers ? "followers" : "following";

        db.collection("users").document(currentUserId).collection(subCollection)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    List<String> userIds = new ArrayList<>();
                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        userIds.add(doc.getId());
                    }

                    if (userIds.isEmpty()) {
                        progressBar.setVisibility(View.GONE);
                        tvEmpty.setVisibility(View.VISIBLE);
                        tvEmpty.setText(isFollowers ? "You have no followers yet." : "You aren't following anyone yet.");
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
        // Navigate to the user's profile
        // We can reuse the ProfileFragment in a way, or if we are in an activity, 
        // maybe we should have a ProfileActivity. 
        // For now, let's assume we can launch HomeActivity with an intent to show a specific profile.
        // Actually, it's better to just open a new activity or replace fragment if possible.
        // Since ProfileFragment has a newInstance(userId), let's use that if we were in fragments.
        // From an Activity, the easiest is to just show a Toast or start a simple detail activity.
        
        // Since we want Instagram style, clicking a user should definitely show their profile.
        // I'll assume you have a way to show a profile from userId.
        Intent intent = new Intent(this, HomeActivity.class);
        intent.putExtra("target_user_id", user.getId());
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }
}
