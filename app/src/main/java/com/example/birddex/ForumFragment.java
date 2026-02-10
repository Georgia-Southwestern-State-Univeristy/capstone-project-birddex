package com.example.birddex;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * ForumFragment serves as the community hub for users to interact,
 * share sightings, and discuss birds.
 */
public class ForumFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Inflate the layout for this fragment.
        // Currently, it just displays the basic forum layout.
        return inflater.inflate(R.layout.fragment_forum, container, false);
    }
}
