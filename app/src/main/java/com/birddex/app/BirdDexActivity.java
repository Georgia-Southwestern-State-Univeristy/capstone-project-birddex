package com.birddex.app;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

public class BirdDexActivity extends AppCompatActivity {

    private Button btnBack;
    private ListView listBirds;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bird_dex);

        btnBack = findViewById(R.id.btnBack);
        listBirds = findViewById(R.id.listBirds);

        btnBack.setOnClickListener(v -> finish());

        // Placeholder BirdDex list (replace later with RecyclerView + real database)
        ArrayList<String> demoBirds = new ArrayList<>();
        demoBirds.add("Northern Cardinal");
        demoBirds.add("Blue Jay");
        demoBirds.add("American Robin");

        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, demoBirds);
        listBirds.setAdapter(adapter);
    }
}