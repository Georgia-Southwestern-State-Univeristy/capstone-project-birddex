package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class ProfileFragment extends Fragment {

    private static final String TAG = "ProfileFragment";
    private static final String ARG_USER_ID = "arg_user_id";
    private static final long COOLDOWN_PERIOD_MS = 24 * 60 * 60 * 1000;

    private ShapeableImageView ivPfp;
    private TextView tvUsername;
    private TextView tvPoints;
    private EditText etBio;
    private TextView tvOpenAiRequestsRemaining;
    private TextView tvPfpChangesRemaining;
    private TextView tvFollowerCount, tvFollowingCount;
    private MaterialButton btnFollow;
    private ImageButton btnSettings, btnEditProfile;
    private View llStats;

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseManager firebaseManager;

    private String profileUserId; // The user whose profile we are viewing
    private String currentUsername;
    private String currentBio;
    private String currentProfilePictureUrl;
    private boolean isCurrentUser = true;
    private boolean isFollowing = false;

    private int currentOpenAiRequestsRemaining = 0;
    private Date openAiCooldownResetTimestamp = null;
    private int currentPfpChangesToday = 0;
    private Date pfpCooldownResetTimestamp = null;

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
            profileUserId = (user != null) ? user.getUid() : null;
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
                });
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
        tvOpenAiRequestsRemaining = v.findViewById(R.id.tvOpenAiRequestsRemaining);
        tvPfpChangesRemaining = v.findViewById(R.id.tvPfpChangesRemaining);
        tvFollowerCount = v.findViewById(R.id.tvFollowerCount);
        tvFollowingCount = v.findViewById(R.id.tvFollowingCount);
        btnFollow = v.findViewById(R.id.btnFollow);
        btnSettings = v.findViewById(R.id.btnSettings);
        btnEditProfile = v.findViewById(R.id.btnEditProfile);
        llStats = v.findViewById(R.id.llStats);
        RecyclerView rvFavorites = v.findViewById(R.id.rvFavorites);

        setupUI();

        rvFavorites.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        List<String> favorites = Arrays.asList("Fav 1", "Fav 2", "Fav 3");
        rvFavorites.setAdapter(new FavoritesAdapter(favorites));

        return v;
    }

    private void setupUI() {
        if (isCurrentUser) {
            btnEditProfile.setVisibility(View.VISIBLE);
            btnSettings.setVisibility(View.VISIBLE);
            btnFollow.setVisibility(View.GONE);
            tvOpenAiRequestsRemaining.setVisibility(View.VISIBLE);
            tvPfpChangesRemaining.setVisibility(View.VISIBLE);
            etBio.setFocusable(false);
            etBio.setClickable(false);

            btnSettings.setOnClickListener(view -> startActivity(new Intent(requireContext(), SettingsActivity.class)));
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
            tvOpenAiRequestsRemaining.setVisibility(View.GONE);
            tvPfpChangesRemaining.setVisibility(View.GONE);
            etBio.setFocusable(false);
            etBio.setClickable(false);

            checkFollowingStatus();
            btnFollow.setOnClickListener(v -> toggleFollow());
        }
    }

    private void checkFollowingStatus() {
        firebaseManager.isFollowing(profileUserId, task -> {
            if (task.isSuccessful() && isAdded()) {
                isFollowing = task.getResult();
                updateFollowButton();
            }
        });
    }

    private void updateFollowButton() {
        if (isFollowing) {
            btnFollow.setText("Following");
        } else {
            btnFollow.setText("Follow");
        }
    }

    private void toggleFollow() {
        btnFollow.setEnabled(false);
        if (isFollowing) {
            firebaseManager.unfollowUser(profileUserId, task -> {
                if (isAdded()) {
                    btnFollow.setEnabled(true);
                    if (task.isSuccessful()) {
                        isFollowing = false;
                        updateFollowButton();
                        fetchUserProfile();
                    }
                }
            });
        } else {
            firebaseManager.followUser(profileUserId, task -> {
                if (isAdded()) {
                    btnFollow.setEnabled(true);
                    if (task.isSuccessful()) {
                        isFollowing = true;
                        updateFollowButton();
                        fetchUserProfile();
                    }
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        fetchUserProfile();
        if (isCurrentUser) {
            fetchOpenAiRequestsRemaining();
            fetchPfpChangesRemaining();
        }
    }

    private void fetchUserProfile() {
        if (profileUserId == null) return;

        // Fetch from CACHE first for instant load
        db.collection("users").document(profileUserId).get(Source.CACHE)
                .addOnSuccessListener(this::handleUserSnapshot)
                .addOnFailureListener(e -> {
                    // Cache might be empty or missing, fetch from server
                    fetchUserProfileFromServer();
                });

        // Always fetch from server in background to ensure data is fresh
        fetchUserProfileFromServer();
    }

    private void fetchUserProfileFromServer() {
        db.collection("users").document(profileUserId).get(Source.SERVER)
                .addOnSuccessListener(this::handleUserSnapshot)
                .addOnFailureListener(e -> Log.e(TAG, "Server fetch failed", e));
    }

    private void handleUserSnapshot(DocumentSnapshot documentSnapshot) {
        if (!isAdded() || documentSnapshot == null || !documentSnapshot.exists()) return;

        User userProfile = documentSnapshot.toObject(User.class);
        if (userProfile != null) {
            currentUsername = userProfile.getUsername();
            currentBio = userProfile.getBio();
            currentProfilePictureUrl = userProfile.getProfilePictureUrl();

            tvUsername.setText(currentUsername != null ? currentUsername : "No Username");
            etBio.setText(currentBio != null ? currentBio : "No bio yet.");
            tvPoints.setText("Total Points: " + userProfile.getTotalPoints());
            tvFollowerCount.setText(String.valueOf(userProfile.getFollowerCount()));
            tvFollowingCount.setText(String.valueOf(userProfile.getFollowingCount()));

            loadProfilePicture(currentProfilePictureUrl);

            if (isCurrentUser) {
                currentPfpChangesToday = userProfile.getPfpChangesToday();
                pfpCooldownResetTimestamp = userProfile.getPfpCooldownResetTimestamp();
                updatePfpChangesRemainingUI(currentPfpChangesToday, pfpCooldownResetTimestamp);

                currentOpenAiRequestsRemaining = userProfile.getOpenAiRequestsRemaining();
                openAiCooldownResetTimestamp = userProfile.getOpenAiCooldownResetTimestamp();
                updateOpenAiRequestsRemainingUI(currentOpenAiRequestsRemaining, openAiCooldownResetTimestamp);
            }
        }
    }

    private void fetchOpenAiRequestsRemaining() {
        firebaseManager.getOpenAiRequestsRemaining(new FirebaseManager.OpenAiRequestLimitListener() {
            @Override
            public void onCheckComplete(boolean hasRequestsRemaining, int remaining, Date cooldownResetTimestamp) {
                if (!isAdded()) return;
                updateOpenAiRequestsRemainingUI(remaining, cooldownResetTimestamp);
            }
            @Override
            public void onFailure(String errorMessage) {}
        });
    }

    private void fetchPfpChangesRemaining() {
        firebaseManager.getPfpChangesRemaining(new FirebaseManager.PfpChangeLimitListener() {
            @Override
            public void onSuccess(int pfpChangesToday, Date cooldownResetTimestamp) {
                if (!isAdded()) return;
                updatePfpChangesRemainingUI(pfpChangesToday, cooldownResetTimestamp);
            }
            @Override
            public void onFailure(String errorMessage) {}
            @Override
            public void onLimitExceeded() {}
        });
    }

    private void updateOpenAiRequestsRemainingUI(int remaining, Date cooldownResetTimestamp) {
        if (remaining <= 0 && cooldownResetTimestamp != null) {
            long timeLeftMs = (cooldownResetTimestamp.getTime() + COOLDOWN_PERIOD_MS) - System.currentTimeMillis();
            if (timeLeftMs > 0) {
                long hours = TimeUnit.MILLISECONDS.toHours(timeLeftMs);
                long minutes = TimeUnit.MILLISECONDS.toMinutes(timeLeftMs) % 60;
                tvOpenAiRequestsRemaining.setText(String.format(Locale.getDefault(), "AI Requests: Refreshes in %dh %dm", hours, minutes));
                return;
            }
        }
        tvOpenAiRequestsRemaining.setText("AI Requests: " + remaining);
    }

    private void updatePfpChangesRemainingUI(int remaining, Date cooldownResetTimestamp) {
        if (remaining <= 0 && cooldownResetTimestamp != null) {
            long timeLeftMs = (cooldownResetTimestamp.getTime() + COOLDOWN_PERIOD_MS) - System.currentTimeMillis();
            if (timeLeftMs > 0) {
                long hours = TimeUnit.MILLISECONDS.toHours(timeLeftMs);
                long minutes = TimeUnit.MILLISECONDS.toMinutes(timeLeftMs) % 60;
                tvPfpChangesRemaining.setText(String.format(Locale.getDefault(), "PFP Changes: Refreshes in %dh %dm", hours, minutes));
                return;
            }
        }
        tvPfpChangesRemaining.setText("PFP Changes: " + remaining);
    }

    private void loadProfilePicture(String url) {
        if (url != null && !url.isEmpty()) {
            Glide.with(this).load(url).placeholder(R.drawable.ic_profile).error(R.drawable.ic_profile).into(ivPfp);
        } else {
            ivPfp.setImageResource(R.drawable.ic_profile);
        }
    }
}
