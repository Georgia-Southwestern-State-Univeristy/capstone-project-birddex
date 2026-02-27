package com.birddex.app;

import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;

import java.util.Date;

public class ViewBirdCardActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_view_bird_card);

        ImageButton btnBack = findViewById(R.id.btnBack);

        TextView txtBirdName = findViewById(R.id.txtBirdName);
        TextView txtScientific = findViewById(R.id.txtScientific);
        TextView txtLocation = findViewById(R.id.txtLocation);
        TextView txtDateCaught = findViewById(R.id.txtDateCaught);
        TextView txtFooter = findViewById(R.id.txtFooter);
        ImageView imgBird = findViewById(R.id.imgBird);

        String imageUrl = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_IMAGE_URL);
        String commonName = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_COMMON_NAME);
        String sciName = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_SCI_NAME);
        String state = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_STATE);
        String locality = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_LOCALITY);
        long caughtTime = getIntent().getLongExtra(CollectionCardAdapter.EXTRA_CAUGHT_TIME, -1L);

        if (commonName != null && !commonName.trim().isEmpty()) {
            txtBirdName.setText(commonName);
        } else if (sciName != null && !sciName.trim().isEmpty()) {
            txtBirdName.setText(sciName);
        } else {
            txtBirdName.setText("Unknown Bird");
        }

        if (sciName != null && !sciName.trim().isEmpty()) {
            txtScientific.setText(sciName);
        } else {
            txtScientific.setText("--");
        }

        txtLocation.setText(CardFormatUtils.formatLocation(state, locality));
        txtDateCaught.setText(CardFormatUtils.formatCaughtDate(caughtTime > 0 ? new Date(caughtTime) : null));
        txtFooter.setText("BirdDex • From your collection");
        txtFooter.setVisibility(android.view.View.GONE);

        Glide.with(this)
                .load(imageUrl)
                .fitCenter()
                .into(imgBird);

        btnBack.setOnClickListener(v -> finish());
    }
}