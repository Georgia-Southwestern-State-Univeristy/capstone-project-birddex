package com.birddex.app;

import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;

public class ViewBirdCardActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_view_bird_card);

        ImageButton btnBack = findViewById(R.id.btnBack);

        // These IDs come from view_bird_card.xml (included in the activity layout)
        TextView txtBirdName = findViewById(R.id.txtBirdName);
        TextView txtScientific = findViewById(R.id.txtScientific);
        TextView txtRarity = findViewById(R.id.txtRarity);
        TextView txtConfidence = findViewById(R.id.txtConfidence);
        TextView txtFooter = findViewById(R.id.txtFooter);
        ImageView imgBird = findViewById(R.id.imgBird);

        // Get data from intent
        String imageUrl = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_IMAGE_URL);
        String commonName = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_COMMON_NAME);
        String sciName = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_SCI_NAME);
        String rarity = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_RARITY);

        // Name (common -> fallback to scientific -> fallback text)
        if (commonName != null && !commonName.trim().isEmpty()) {
            txtBirdName.setText(commonName);
        } else if (sciName != null && !sciName.trim().isEmpty()) {
            txtBirdName.setText(sciName);
        } else {
            txtBirdName.setText("Unknown Bird");
        }

        // Scientific line
        if (sciName != null && !sciName.trim().isEmpty()) {
            txtScientific.setText(sciName);
        } else {
            txtScientific.setText("--");
        }

        // Rarity line (view_bird_card expects "Rarity: X")
        if (rarity != null && !rarity.trim().isEmpty()) {
            txtRarity.setText("Rarity: " + rarity);
        } else {
            txtRarity.setText("Rarity: --");
        }

        // Collection slots don’t store confidence right now
        txtConfidence.setText("Confidence: --");

        // Footer text
        txtFooter.setText("BirdDex • From your collection");

        // Load image
        Glide.with(this)
                .load(imageUrl)
                .fitCenter()
                .into(imgBird);

        // Back button
        btnBack.setOnClickListener(v -> finish());
    }
}