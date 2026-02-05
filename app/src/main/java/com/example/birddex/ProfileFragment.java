package com.example.birddex;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.Intent;
import android.widget.ImageButton;

import java.util.Arrays;
import java.util.List;

public class ProfileFragment extends Fragment {

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
        EditText etBio = v.findViewById(R.id.etBio);
        RecyclerView rvFavorites = v.findViewById(R.id.rvFavorites);

        TextView tvUsername = v.findViewById(R.id.tvUsername);
        tvUsername.setText("BirdDexUser"); // TODO: real username later

        // Temporary placeholder data
        tvPoints.setText("Total Points: 120");
        etBio.setText("BirdDex user. I like hawks and shorebirds!");

        // 3-grid favorites
        rvFavorites.setLayoutManager(new GridLayoutManager(requireContext(), 3));

        List<String> favorites = Arrays.asList("Fav 1", "Fav 2", "Fav 3");
        rvFavorites.setAdapter(new FavoritesAdapter(favorites));

        return v;
    }
}
