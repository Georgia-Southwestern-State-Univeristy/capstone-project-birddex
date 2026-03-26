package com.birddex.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;

import java.util.ArrayList;

public class AiCompActivity extends AppCompatActivity {

    private ArrayList<Bundle> openAiAlternatives = new ArrayList<>();
    private String identificationLogId;
    private String identificationId;
    private final OpenAiApi openAiApi = new OpenAiApi();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarHelper.applyStandardNavBar(this);
        setContentView(R.layout.activity_ai_comp);

        ImageView ivMain = findViewById(R.id.ivMain);
        ImageView ivAiImage1 = findViewById(R.id.ivAiImage1);
        ImageView ivAiImage2 = findViewById(R.id.ivAiImage2);
        TextView tvAiName1 = findViewById(R.id.tvAiName1);
        TextView tvAiName2 = findViewById(R.id.tvAiName2);
        LinearLayout llAiBird1 = findViewById(R.id.llAiBird1);
        LinearLayout llAiBird2 = findViewById(R.id.llAiBird2);
        Button btnRetake = findViewById(R.id.btnRetake);

        identificationLogId = getIntent().getStringExtra("identificationLogId");
        identificationId = getIntent().getStringExtra("identificationId");

        ArrayList<Bundle> incomingAlternatives = getIntent().getParcelableArrayListExtra("openAiAlternatives");
        if (incomingAlternatives != null) {
            openAiAlternatives = incomingAlternatives;
        }

        String imageUriStr = getIntent().getStringExtra("imageUri");
        if (imageUriStr != null) {
            Glide.with(this)
                    .load(Uri.parse(imageUriStr))
                    .into(ivMain);
        }

        bindCandidate(llAiBird1, ivAiImage1, tvAiName1, getCandidateAt(0));
        bindCandidate(llAiBird2, ivAiImage2, tvAiName2, getCandidateAt(1));

        btnRetake.setOnClickListener(v -> {
            openAiApi.syncIdentificationFeedback(
                    identificationLogId,
                    identificationId,
                    "retake_after_openai_review",
                    null,
                    null,
                    null
            );
            Intent intent = new Intent(this, ImageUploadActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
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
                    "select_openai_alternative",
                    selectedBirdId,
                    "openai_review",
                    null
            );
            Intent resultIntent = new Intent();
            resultIntent.putExtra("selectedBirdId", candidate.getString("candidateBirdId"));
            resultIntent.putExtra("selectedCommonName", candidate.getString("candidateCommonName"));
            resultIntent.putExtra("selectedScientificName", candidate.getString("candidateScientificName"));
            resultIntent.putExtra("selectedSpecies", candidate.getString("candidateSpecies"));
            resultIntent.putExtra("selectedFamily", candidate.getString("candidateFamily"));
            resultIntent.putExtra("selectedSource", "openai_review");
            setResult(RESULT_OK, resultIntent);
            finish();
        };

        container.setOnClickListener(clickListener);
        imageView.setOnClickListener(clickListener);
        nameView.setOnClickListener(clickListener);
    }

    @Nullable
    private Bundle getCandidateAt(int index) {
        if (openAiAlternatives == null || index < 0 || index >= openAiAlternatives.size()) {
            return null;
        }
        return openAiAlternatives.get(index);
    }
}
