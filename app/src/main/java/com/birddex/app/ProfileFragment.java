package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.google.android.material.imageview.ShapeableImageView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Arrays;
import java.util.List;

public class ProfileFragment extends Fragment {

    private static final String TAG = "ProfileFragment";

    private ShapeableImageView ivPfp; // Changed to ShapeableImageView
    private TextView tvUsername;
    private TextView tvPoints;
    private TextView tvBio;

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    private String currentUsername;
    private String currentBio;
    private String currentProfilePictureUrl;

    private ActivityResultLauncher<Intent> editProfileLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        editProfileLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == AppCompatActivity.RESULT_OK && result.getData() != null) {
                        String newUsername = result.getData().getStringExtra("newUsername");
                        String newBio = result.getData().getStringExtra("newBio");
                        String newProfilePictureUrl = result.getData().getStringExtra("newProfilePictureUrl");

                        // Update local state and UI immediately
                        if (newUsername != null) {
                            currentUsername = newUsername;
                            tvUsername.setText(newUsername);
                        }
                        if (newBio != null) {
                            currentBio = newBio;
                            tvBio.setText(newBio);
                        }
                        if (newProfilePictureUrl != null) {
                            currentProfilePictureUrl = newProfilePictureUrl;
                            loadProfilePicture(newProfilePictureUrl);
                        } else if (currentProfilePictureUrl == null || currentProfilePictureUrl.isEmpty()) {
                            // If no new URL and no old URL, load default
                            ivPfp.setImageResource(R.drawable.ic_profile);
                        }
                        // If a new URL was not returned, but the existing one might have changed (e.g., deleted)
                        // it will be re-fetched onResume, so no special handling here for deletion.

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
        ivPfp = v.findViewById(R.id.ivPfp);
        tvUsername = v.findViewById(R.id.tvUsername);
        tvPoints = v.findViewById(R.id.tvPoints);
        tvBio = v.findViewById(R.id.tvBio);
        RecyclerView rvFavorites = v.findViewById(R.id.rvFavorites);
        ImageButton btnSettings = v.findViewById(R.id.btnSettings);
        ImageButton btnEditProfile = v.findViewById(R.id.btnEditProfile);

        // Set initial loading values
        tvUsername.setText("Loading...");
        tvPoints.setText("Total Points: --");
        tvBio.setText("Loading bio...");

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
            editProfileLauncher.launch(intent);
        });

        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        fetchUserProfile(); // Fetch user data every time the fragment is resumed
    }

    private void fetchUserProfile() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            Log.e(TAG, "No user logged in.");
            // Optionally redirect to login or show a message
            tvUsername.setText("Guest");
            tvBio.setText("Please log in.");
            ivPfp.setImageResource(R.drawable.ic_profile);
            return;
        }

        String userId = user.getUid();
        db.collection("users").document(userId).get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        currentUsername = documentSnapshot.getString("username");
                        currentBio = documentSnapshot.getString("bio");
                        currentProfilePictureUrl = documentSnapshot.getString("profilePictureUrl");

                        tvUsername.setText(currentUsername != null ? currentUsername : "No Username");
                        tvBio.setText(currentBio != null ? currentBio : "No bio yet.");

                        // Load profile picture
                        loadProfilePicture(currentProfilePictureUrl);

                        // TODO: Fetch and display actual total points
                        Long totalPoints = documentSnapshot.getLong("totalPoints");
                        tvPoints.setText("Total Points: " + (totalPoints != null ? totalPoints : 0));

                    } else {
                        Log.w(TAG, "User document does not exist for ID: " + userId);
                        tvUsername.setText("New User");
                        tvBio.setText("Welcome! Update your profile.");
                        ivPfp.setImageResource(R.drawable.ic_profile);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error fetching user profile: " + e.getMessage(), e);
                    Toast.makeText(requireContext(), "Failed to load profile.", Toast.LENGTH_SHORT).show();
                    tvUsername.setText("Error");
                    tvBio.setText("Error loading bio.");
                    ivPfp.setImageResource(R.drawable.ic_profile);
                });
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
