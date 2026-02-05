package com.example.birddex;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

public class SearchCollectionFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_search_collection, container, false);

        RecyclerView rv = v.findViewById(R.id.rvCollection);
        rv.setLayoutManager(new GridLayoutManager(getContext(), 3));

        // 12 placeholders (3x4)
        ArrayList<String> items = new ArrayList<>();
        for (int i = 1; i <= 15; i++) items.add("Bird " + i);

        rv.setAdapter(new SimpleGridAdapter(items));
        return v;
    }
}
