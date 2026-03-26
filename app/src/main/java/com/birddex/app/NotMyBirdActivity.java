package com.birddex.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

public class NotMyBirdActivity extends AppCompatActivity {

    private ArrayList<Bundle> modelAlternatives = new ArrayList<>();
    private String identificationLogId;
    private String identificationId;
    private String imageUriStr;
    private String imageUrl;
    private final OpenAiApi openAiApi = new OpenAiApi();

    private final ActivityResultLauncher<Intent> aiCompLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                setResult(result.getResultCode(), result.getData());
                finish();
            }
    );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarHelper.applyStandardNavBar(this);
        setContentView(R.layout.activity_not_my_bird);

        ImageView ivMain = findViewById(R.id.ivMain);
        ImageView iv1 = findViewById(R.id.iv1);
        ImageView iv2 = findViewById(R.id.iv2);
        TextView tvName1 = findViewById(R.id.tvName1);
        TextView tvName2 = findViewById(R.id.tvName2);
        TextView tvInstruction = findViewById(R.id.tvInstruction);
        LinearLayout llBird1 = findViewById(R.id.llBird1);
        LinearLayout llBird2 = findViewById(R.id.llBird2);
        Button btnStillNotMyBird = findViewById(R.id.btnStillNotMyBird);

        identificationLogId = getIntent().getStringExtra("identificationLogId");
        identificationId = getIntent().getStringExtra("identificationId");
        imageUriStr = getIntent().getStringExtra("imageUri");
        imageUrl = getIntent().getStringExtra("imageUrl");

        ArrayList<Bundle> incomingAlternatives = getIntent().getParcelableArrayListExtra("modelAlternatives");
        if (incomingAlternatives != null) {
            modelAlternatives = incomingAlternatives;
        }

        if (imageUriStr != null) {
            Uri imageUri = Uri.parse(imageUriStr);
            ivMain.setImageURI(imageUri);
        }

        bindCandidate(llBird1, iv1, tvName1, getCandidateAt(0));
        bindCandidate(llBird2, iv2, tvName2, getCandidateAt(1));

        if (modelAlternatives.isEmpty()) {
            tvInstruction.setText("No other BirdDex matches were available. Tap below to ask AI for two more supported options.");
        }

        btnStillNotMyBird.setOnClickListener(v -> {
            openAiApi.syncIdentificationFeedback(
                    identificationLogId,
                    identificationId,
                    "request_openai_review",
                    null,
                    null,
                    null
            );

            Intent intent = new Intent(NotMyBirdActivity.this, AiCompLoadingActivity.class);
            intent.putExtra("imageUri", imageUriStr);
            intent.putExtra("imageUrl", imageUrl);
            intent.putExtra("identificationLogId", identificationLogId);
            intent.putExtra("identificationId", identificationId);
            aiCompLauncher.launch(intent);
        });
    }

    private void bindCandidate(LinearLayout container, ImageView imageView, TextView nameView, @Nullable Bundle candidate) {
        if (candidate == null) {
            container.setVisibility(View.INVISIBLE);
            container.setClickable(false);
            return;
        }

        container.setVisibility(View.VISIBLE);
        nameView.setText(candidate.getString("candidateCommonName", "Unknown Bird"));
        BirdImageLoader.loadBirdImageInto(
                imageView,
                candidate.getString("candidateBirdId"),
                candidate.getString("candidateCommonName"),
                candidate.getString("candidateScientificName")
        );

        View.OnClickListener clickListener = v -> {
            String selectedBirdId = candidate.getString("candidateBirdId");
            openAiApi.syncIdentificationFeedback(
                    identificationLogId,
                    identificationId,
                    "select_model_alternative",
                    selectedBirdId,
                    "model_alternative",
                    null
            );
            Intent resultIntent = buildSelectionIntent(candidate, "model_alternative");
            setResult(RESULT_OK, resultIntent);
            finish();
        };

        container.setOnClickListener(clickListener);
        imageView.setOnClickListener(clickListener);
        nameView.setOnClickListener(clickListener);
    }

    @Nullable
    private Bundle getCandidateAt(int index) {
        if (modelAlternatives == null || index < 0 || index >= modelAlternatives.size()) {
            return null;
        }
        return modelAlternatives.get(index);
    }

    private Intent buildSelectionIntent(Bundle candidate, String source) {
        Intent resultIntent = new Intent();
        resultIntent.putExtra("selectedBirdId", candidate.getString("candidateBirdId"));
        resultIntent.putExtra("selectedCommonName", candidate.getString("candidateCommonName"));
        resultIntent.putExtra("selectedScientificName", candidate.getString("candidateScientificName"));
        resultIntent.putExtra("selectedSpecies", candidate.getString("candidateSpecies"));
        resultIntent.putExtra("selectedFamily", candidate.getString("candidateFamily"));
        resultIntent.putExtra("selectedSource", source);
        return resultIntent;
    }
}
