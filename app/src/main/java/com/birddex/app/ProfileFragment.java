package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
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
import com.google.android.material.imageview.ShapeableImageView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class ProfileFragment extends Fragment {

    private static final String TAG = "ProfileFragment";
    private static final long COOLDOWN_PERIOD_MS = 24 * 60 * 60 * 1000; // 24 hours in milliseconds

    private ShapeableImageView ivPfp; // Changed to ShapeableImageView
    private TextView tvUsername;
    private TextView tvPoints;
    private EditText etBio;
    private TextView tvOpenAiRequestsRemaining; // New TextView for OpenAI requests
    private TextView tvPfpChangesRemaining; // New TextView for PFP changes remaining

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseManager firebaseManager; // Added FirebaseManager

    private String currentUsername;
    private String currentBio;
    private String currentProfilePictureUrl;
    // These local variables will store the *current state* including cooldown timestamps
    private int currentOpenAiRequestsRemaining = 0;
    private Date openAiCooldownResetTimestamp = null;
    private int currentPfpChangesToday = 0;
    private Date pfpCooldownResetTimestamp = null;

    private ActivityResultLauncher<Intent> editProfileLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        firebaseManager = new FirebaseManager(requireContext()); // Initialize FirebaseManager

        editProfileLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == AppCompatActivity.RESULT_OK && result.getData() != null) {
                        // Re-fetch all data to ensure consistency with Firestore after EditProfileActivity
                        fetchUserProfile();
                        fetchOpenAiRequestsRemaining();
                        fetchPfpChangesRemaining();
                        Toast.makeText(requireContext(), "Profile updated!", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_profile, container, false);

        // Initialize UI components
        ivPfp = v.findViewById(R.id.ivPfp); // Initialize ShapeableImageView
        tvUsername = v.findViewById(R.id.tvUsername);
        tvPoints = v.findViewById(R.id.tvPoints);
        etBio = v.findViewById(R.id.etBio);
        tvOpenAiRequestsRemaining = v.findViewById(R.id.tvOpenAiRequestsRemaining);
        tvPfpChangesRemaining = v.findViewById(R.id.tvPfpChangesRemaining);
        RecyclerView rvFavorites = v.findViewById(R.id.rvFavorites);
        ImageButton btnSettings = v.findViewById(R.id.btnSettings);
        ImageButton btnEditProfile = v.findViewById(R.id.btnEditProfile);

        // Set initial hardcoded values (will be overwritten by Firestore)
        tvUsername.setText("Loading...");
        tvPoints.setText("Total Points: --");
        etBio.setText("Loading bio...");
        tvOpenAiRequestsRemaining.setText("AI Requests: --");
        tvPfpChangesRemaining.setText("PFP Changes: --");

        // 3-grid favorites
        rvFavorites.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        List<String> favorites = Arrays.asList("Fav 1", "Fav 2", "Fav 3"); // Placeholder
        rvFavorites.setAdapter(new FavoritesAdapter(favorites));

        btnSettings.setOnClickListener(view -> {
            startActivity(new Intent(requireContext(), SettingsActivity.class));
        });

        btnEditProfile.setOnClickListener(view -> {
            Intent intent = new Intent(requireContext(), EditProfileActivity.class);
            intent.putExtra("username", currentUsername);
            intent.putExtra("bio", currentBio);
            intent.putExtra("profilePictureUrl", currentProfilePictureUrl);
            // Do NOT pass pfpChangesToday or pfpCooldownResetTimestamp directly as client editable
            // The EditProfileActivity will call the Cloud Function to decrement the count
            editProfileLauncher.launch(intent);
        });

        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        fetchUserProfile(); // Fetch user data every time the fragment is resumed
        fetchOpenAiRequestsRemaining(); // Fetch OpenAI requests on resume
        fetchPfpChangesRemaining(); // Fetch PFP changes on resume
    }

    private void fetchUserProfile() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            Log.e(TAG, "No user logged in.");
            tvUsername.setText("Guest");
            etBio.setText("Please log in.");
            ivPfp.setImageResource(R.drawable.ic_profile);
            updateOpenAiRequestsRemainingUI(0, null); // Reset UI if no user
            updatePfpChangesRemainingUI(0, null); // Reset UI if no user
            return;
        }

        String userId = user.getUid();
        db.collection("users").document(userId).get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        User userProfile = documentSnapshot.toObject(User.class);
                        if (userProfile != null) {
                            currentUsername = userProfile.getUsername();
                            currentBio = userProfile.getBio();
                            currentProfilePictureUrl = userProfile.getProfilePictureUrl();

                            tvUsername.setText(currentUsername != null ? currentUsername : "No Username");
                            etBio.setText(currentBio != null ? currentBio : "No bio yet.");

                            // Load profile picture
                            loadProfilePicture(currentProfilePictureUrl);

                            // Fetch and display actual total points
                            tvPoints.setText("Total Points: " + userProfile.getTotalPoints());

                            // Update PFP changes remaining from fetched User object
                            currentPfpChangesToday = userProfile.getPfpChangesToday();
                            pfpCooldownResetTimestamp = userProfile.getPfpCooldownResetTimestamp();
                            updatePfpChangesRemainingUI(currentPfpChangesToday, pfpCooldownResetTimestamp);

                            // Update OpenAI requests remaining from fetched User object
                            currentOpenAiRequestsRemaining = userProfile.getOpenAiRequestsRemaining();
                            openAiCooldownResetTimestamp = userProfile.getOpenAiCooldownResetTimestamp();
                            updateOpenAiRequestsRemainingUI(currentOpenAiRequestsRemaining, openAiCooldownResetTimestamp);
                        }
                    } else {
                        Log.w(TAG, "User document does not exist for ID: " + userId);
                        tvUsername.setText("New User");
                        etBio.setText("Welcome! Update your profile.");
                        ivPfp.setImageResource(R.drawable.ic_profile);
                        updateOpenAiRequestsRemainingUI(0, null); // Reset UI if no user doc
                        updatePfpChangesRemainingUI(0, null); // Reset UI if no user doc
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error fetching user profile: " + e.getMessage(), e);
                    Toast.makeText(requireContext(), "Failed to load profile.", Toast.LENGTH_SHORT).show();
                    tvUsername.setText("Error");
                    etBio.setText("Error loading bio.");
                    ivPfp.setImageResource(R.drawable.ic_profile);
                    updateOpenAiRequestsRemainingUI(0, null); // Reset UI on error
                    updatePfpChangesRemainingUI(0, null); // Reset UI on error
                });
    }

    private void fetchOpenAiRequestsRemaining() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            updateOpenAiRequestsRemainingUI(0, null);
            return;
        }

        firebaseManager.getOpenAiRequestsRemaining(new FirebaseManager.OpenAiRequestLimitListener() {
            @Override
            public void onCheckComplete(boolean hasRequestsRemaining, int remaining, Date cooldownResetTimestamp) {
                currentOpenAiRequestsRemaining = remaining;
                openAiCooldownResetTimestamp = cooldownResetTimestamp;
                updateOpenAiRequestsRemainingUI(remaining, cooldownResetTimestamp);
            }

            @Override
            public void onFailure(String errorMessage) {
                Log.e(TAG, "Error fetching OpenAI requests remaining: " + errorMessage);
                updateOpenAiRequestsRemainingUI(0, null); // Show error state
                Toast.makeText(requireContext(), "Failed to load AI request limits.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void fetchPfpChangesRemaining() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            updatePfpChangesRemainingUI(0, null);
            return;
        }

        firebaseManager.getPfpChangesRemaining(new FirebaseManager.PfpChangeLimitListener() {
            @Override
            public void onSuccess(int pfpChangesToday, Date cooldownResetTimestamp) {
                currentPfpChangesToday = pfpChangesToday;
                pfpCooldownResetTimestamp = cooldownResetTimestamp;
                updatePfpChangesRemainingUI(pfpChangesToday, cooldownResetTimestamp);
            }

            @Override
            public void onFailure(String errorMessage) {
                Log.e(TAG, "Error fetching PFP changes remaining: " + errorMessage);
                updatePfpChangesRemainingUI(0, null); // Show error state
                Toast.makeText(requireContext(), "Failed to load PFP change limits.", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onLimitExceeded() {
                // This state should be handled by the Cloud Function, but for local UI consistency:
                updatePfpChangesRemainingUI(0, pfpCooldownResetTimestamp); // Assuming cooldown timestamp is available
                Toast.makeText(requireContext(), "PFP change limit exceeded!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateOpenAiRequestsRemainingUI(int remaining, Date cooldownResetTimestamp) {
        if (remaining <= 0 && cooldownResetTimestamp != null) {
            long currentTime = System.currentTimeMillis();
            long cooldownEndTime = cooldownResetTimestamp.getTime() + COOLDOWN_PERIOD_MS;
            long timeLeftMs = cooldownEndTime - currentTime;

            if (timeLeftMs > 0) {
                long hours = TimeUnit.MILLISECONDS.toHours(timeLeftMs);
                long minutes = TimeUnit.MILLISECONDS.toMinutes(timeLeftMs) - TimeUnit.HOURS.toMinutes(hours);
                tvOpenAiRequestsRemaining.setText(String.format(Locale.getDefault(), "AI Requests: Refreshes in %dh %dm", hours, minutes));
            } else {
                // Cooldown expired, should display max requests (or trigger a refresh)
                tvOpenAiRequestsRemaining.setText("AI Requests: Max"); // Optimistic update
            }
        } else {
            tvOpenAiRequestsRemaining.setText("AI Requests: " + remaining);
        }
    }

    private void updatePfpChangesRemainingUI(int remaining, Date cooldownResetTimestamp) {
        if (remaining <= 0 && cooldownResetTimestamp != null) {
            long currentTime = System.currentTimeMillis();
            long cooldownEndTime = cooldownResetTimestamp.getTime() + COOLDOWN_PERIOD_MS;
            long timeLeftMs = cooldownEndTime - currentTime;

            if (timeLeftMs > 0) {
                long hours = TimeUnit.MILLISECONDS.toHours(timeLeftMs);
                long minutes = TimeUnit.MILLISECONDS.toMinutes(timeLeftMs) - TimeUnit.HOURS.toMinutes(hours);
                tvPfpChangesRemaining.setText(String.format(Locale.getDefault(), "PFP Changes: Refreshes in %dh %dm", hours, minutes));
            } else {
                // Cooldown expired, should display max changes (or trigger a refresh)
                tvPfpChangesRemaining.setText("PFP Changes: Max"); // Optimistic update
            }
        } else {
            tvPfpChangesRemaining.setText("PFP Changes: " + remaining);
        }
    }

    private void loadProfilePicture(String url) {
        if (url != null && !url.isEmpty()) {
            Glide.with(this)
                    .load(url)
                    .placeholder(R.drawable.ic_profile) // Show default placeholder while loading
                    .error(R.drawable.ic_profile)       // Show default placeholder if error occurs
                    .into(ivPfp);
        } else {
            ivPfp.setImageResource(R.drawable.ic_profile);
        }
    }
}
