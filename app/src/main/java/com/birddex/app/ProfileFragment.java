package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Arrays;
import java.util.List;

public class ProfileFragment extends Fragment {

    private TextView tvUsername;
    private EditText etBio;
    private ActivityResultLauncher<Intent> editProfileLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        editProfileLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == AppCompatActivity.RESULT_OK && result.getData() != null) {
                        String newUsername = result.getData().getStringExtra("newUsername");
                        String newBio = result.getData().getStringExtra("newBio");
                        if (newUsername != null && !newUsername.trim().isEmpty()) {
                            tvUsername.setText(newUsername);
                        }
                        if (newBio != null) {
                            etBio.setText(newBio);
                        }
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_profile, container, false);
        ImageButton btnSettings = v.findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(view -> {
            startActivity(new Intent(requireContext(), SettingsActivity.class));
        });

        TextView tvPoints = v.findViewById(R.id.tvPoints);
        etBio = v.findViewById(R.id.etBio);
        RecyclerView rvFavorites = v.findViewById(R.id.rvFavorites);

        tvUsername = v.findViewById(R.id.tvUsername);
        tvUsername.setText("BirdDexUser"); // TODO: real username later

        // Temporary placeholder data
        tvPoints.setText("Total Points: 120");
        etBio.setText("BirdDex user. I like hawks and shorebirds!");

        // 3-grid favorites
        rvFavorites.setLayoutManager(new GridLayoutManager(requireContext(), 3));

        List<String> favorites = Arrays.asList("Fav 1", "Fav 2", "Fav 3");
        rvFavorites.setAdapter(new FavoritesAdapter(favorites));

        ImageButton btnEditProfile = v.findViewById(R.id.btnEditProfile);
        btnEditProfile.setOnClickListener(view -> {
            Intent intent = new Intent(requireContext(), EditProfileActivity.class);
            intent.putExtra("username", tvUsername.getText().toString());
            intent.putExtra("bio", etBio.getText().toString());
            editProfileLauncher.launch(intent);
        });


        return v;

    }
}
