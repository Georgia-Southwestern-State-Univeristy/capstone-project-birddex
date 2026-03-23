package com.birddex.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class NotMyBirdActivity extends AppCompatActivity {

    private ImageView ivMain, iv1, iv2;
    private TextView tvName1, tvName2;
    private TextView tvConf1, tvConf2;
    private Button btnStillNotMyBird;
    private String imageUriStr;

    private final ActivityResultLauncher<Intent> aiCompLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    setResult(RESULT_OK, result.getData());
                    finish();
                }
            }
    );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarHelper.applyStandardNavBar(this);
        setContentView(R.layout.activity_not_my_bird);

        // Initialize Views
        ivMain = findViewById(R.id.ivMain);
        iv1 = findViewById(R.id.iv1);
        iv2 = findViewById(R.id.iv2);

        tvName1 = findViewById(R.id.tvName1);
        tvName2 = findViewById(R.id.tvName2);

        tvConf1 = findViewById(R.id.tvConf1);
        tvConf2 = findViewById(R.id.tvConf2);

        btnStillNotMyBird = findViewById(R.id.btnStillNotMyBird);

        // Example data for the alternative choices
        tvName1.setText("Northern Cardinal");
        tvName2.setText("Blue Jay");

        // Setting the images passed from BirdInfoActivity
        imageUriStr = getIntent().getStringExtra("imageUri");
        if (imageUriStr != null) {
            Uri imageUri = Uri.parse(imageUriStr);
            if (ivMain != null) ivMain.setImageURI(imageUri);
            if (iv1 != null) iv1.setImageURI(imageUri);
            if (iv2 != null) iv2.setImageURI(imageUri);
        }

        // Make ImageViews clickable to select a bird
        if (iv1 != null) {
            iv1.setOnClickListener(v -> selectBird(
                    "Northern Cardinal",
                    "Cardinalis cardinalis",
                    "Cardinal",
                    "Cardinalidae"
            ));
        }
        if (iv2 != null) {
            iv2.setOnClickListener(v -> selectBird(
                    "Blue Jay",
                    "Cyanocitta cristata",
                    "Jay",
                    "Corvidae"
            ));
        }

        if (btnStillNotMyBird != null) {
            btnStillNotMyBird.setOnClickListener(v -> {
                Intent intent = new Intent(NotMyBirdActivity.this, AiCompLoadingActivity.class);
                intent.putExtra("imageUri", imageUriStr);
                aiCompLauncher.launch(intent);
            });
        }
    }

    private void selectBird(String commonName, String scientificName, String species, String family) {
        Intent resultIntent = new Intent();
        resultIntent.putExtra("selectedCommonName", commonName);
        resultIntent.putExtra("selectedScientificName", scientificName);
        resultIntent.putExtra("selectedSpecies", species);
        resultIntent.putExtra("selectedFamily", family);
        setResult(RESULT_OK, resultIntent);
        finish();
    }
}
