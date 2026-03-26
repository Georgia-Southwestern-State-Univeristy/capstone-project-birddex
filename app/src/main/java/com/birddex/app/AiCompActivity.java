package com.birddex.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class AiCompActivity extends AppCompatActivity {

    private ImageView ivAiImage1, ivAiImage2;
    private TextView tvAiName1, tvAiName2;

    private Button btnRetake;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarHelper.applyStandardNavBar(this);
        setContentView(R.layout.activity_ai_comp);

        ivAiImage1 = findViewById(R.id.ivAiImage1);
        ivAiImage2 = findViewById(R.id.ivAiImage2);
        tvAiName1 = findViewById(R.id.tvAiName1);
        tvAiName2 = findViewById(R.id.tvAiName2);

        btnRetake = findViewById(R.id.btnRetake);

        // Setting names for display
        if (tvAiName1 != null) tvAiName1.setText("Northern Cardinal");
        if (tvAiName2 != null) tvAiName2.setText("Blue Jay");

        String imageUriStr = getIntent().getStringExtra("imageUri");
        if (imageUriStr != null) {
            Uri imageUri = Uri.parse(imageUriStr);
            if (ivAiImage1 != null) ivAiImage1.setImageURI(imageUri);
            if (ivAiImage2 != null) ivAiImage2.setImageURI(imageUri);
        }

        // Make ImageViews clickable to select a bird (returning to BirdInfoActivity via NotMyBirdActivity)
        if (ivAiImage1 != null) {
            ivAiImage1.setOnClickListener(v -> selectBird(
                    "Northern Cardinal",
                    "Cardinalis cardinalis",
                    "Cardinal",
                    "Cardinalidae"
            ));
        }
        if (ivAiImage2 != null) {
            ivAiImage2.setOnClickListener(v -> selectBird(
                    "Blue Jay",
                    "Cyanocitta cristata",
                    "Jay",
                    "Corvidae"
            ));
        }

        if (btnRetake != null) {
            btnRetake.setOnClickListener(v -> {
                Intent intent = new Intent(this, ImageUploadActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                finish();
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
