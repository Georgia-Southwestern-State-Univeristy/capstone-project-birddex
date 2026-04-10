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
import androidx.constraintlayout.widget.ConstraintLayout;

import com.bumptech.glide.Glide;

import java.util.ArrayList;

public class AiCompActivity extends AppCompatActivity {

    private ArrayList<Bundle> openAiAlternatives = new ArrayList<>();
    private String identificationLogId;
    private String identificationId;
    private String currentCaptureSource;
    private boolean couldntFindBirdSubmitting = false;
    private final OpenAiApi openAiApi = new OpenAiApi();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_comp);

        ImageView ivMain = findViewById(R.id.ivMain);
        ImageView ivAiImage1 = findViewById(R.id.ivAiImage1);
        ImageView ivAiImage2 = findViewById(R.id.ivAiImage2);
        View progressAiBird1 = findViewById(R.id.progressAiBird1);
        View progressAiBird2 = findViewById(R.id.progressAiBird2);
        TextView tvAiStatus1 = findViewById(R.id.tvAiStatus1);
        TextView tvAiStatus2 = findViewById(R.id.tvAiStatus2);
        TextView tvAiAttribution1 = findViewById(R.id.tvAiAttribution1);
        TextView tvAiAttribution2 = findViewById(R.id.tvAiAttribution2);
        TextView tvReason1 = findViewById(R.id.tvAiReason1);
        TextView tvReason2 = findViewById(R.id.tvAiReason2);
        TextView tvAiName1 = findViewById(R.id.tvAiName1);
        TextView tvAiName2 = findViewById(R.id.tvAiName2);
        LinearLayout llAiBird1 = findViewById(R.id.llAiBird1);
        LinearLayout llAiBird2 = findViewById(R.id.llAiBird2);
        TextView tvInstruction = findViewById(R.id.tvInstruction);
        Button btnRetake = findViewById(R.id.btnRetake);
        Button btnCouldntFindYourBird = findViewById(R.id.btnCouldntFindYourBird);
        TextView tvSubmitFeedback = findViewById(R.id.tvSubmitFeedback);

        identificationLogId = getIntent().getStringExtra("identificationLogId");
        identificationId = getIntent().getStringExtra("identificationId");
        currentCaptureSource = CaptureGuardHelper.readReportFromIntent(getIntent(), true).captureSource;

        ArrayList<Bundle> incomingAlternatives = getIntent().getParcelableArrayListExtra("openAiAlternatives");
        if (incomingAlternatives != null) {
            openAiAlternatives = incomingAlternatives;
        }

        String reviewUserMessage = getIntent().getStringExtra("openAiUserMessage");
        String imageUriStr = getIntent().getStringExtra("imageUri");
        if (imageUriStr != null) {
            Glide.with(this)
                    .load(Uri.parse(imageUriStr))
                    .into(ivMain);
        }

        if (tvInstruction != null) {
            if (openAiAlternatives == null || openAiAlternatives.isEmpty()) {
                tvInstruction.setText((reviewUserMessage != null && !reviewUserMessage.trim().isEmpty())
                        ? reviewUserMessage
                        : "AI could not find more supported BirdDex matches for this photo. You can retake the image or let us know we couldn't find your bird.");
            } else if (openAiAlternatives.size() == 1) {
                tvInstruction.setText("AI found one more supported BirdDex match. Select it if it looks right.");
            } else if (reviewUserMessage != null && !reviewUserMessage.trim().isEmpty()) {
                tvInstruction.setText(reviewUserMessage);
            }
        }

        Bundle candidate1 = getCandidateAt(0);
        Bundle candidate2 = getCandidateAt(1);

        bindCandidate(llAiBird1, ivAiImage1, progressAiBird1, tvAiStatus1, tvAiAttribution1, tvReason1, tvAiName1, candidate1);
        bindCandidate(llAiBird2, ivAiImage2, progressAiBird2, tvAiStatus2, tvAiAttribution2, tvReason2, tvAiName2, candidate2);

        applySingleVisibleLayoutIfNeeded(llAiBird1, llAiBird2);

        if (isGalleryImportFlow()) {
            btnRetake.setText(R.string.retry_upload);
        }

        tvSubmitFeedback.setOnClickListener(v -> IdentificationFeedbackHelper.showFeedbackDialog(
                this,
                (feedbackText, callback) -> IdentificationFeedbackHelper.submitFeedback(
                        openAiApi,
                        this,
                        identificationLogId,
                        identificationId,
                        "ai_review_choices",
                        feedbackText,
                        callback
                )
        ));

        btnCouldntFindYourBird.setOnClickListener(v -> {
            if (couldntFindBirdSubmitting) {
                return;
            }
            couldntFindBirdSubmitting = true;
            btnCouldntFindYourBird.setEnabled(false);
            btnCouldntFindYourBird.setAlpha(0.6f);

            openAiApi.syncIdentificationFeedback(
                    identificationLogId,
                    identificationId,
                    "couldnt_find_your_bird",
                    null,
                    "ai_review_choices",
                    "User reported BirdDex and AI review could not find the correct bird from the AI comparison screen.",
                    null,
                    null,
                    null,
                    null,
                    new OpenAiApi.FeedbackSyncCallback() {
                        @Override
                        public void onSuccess(String userMessage) {
                            couldntFindBirdSubmitting = false;
                            String finalMessage = (userMessage != null && !userMessage.trim().isEmpty())
                                    ? userMessage
                                    : getString(R.string.couldnt_find_your_bird_thanks);
                            MessagePopupHelper.showBrief(AiCompActivity.this, finalMessage);
                        }

                        @Override
                        public void onFailure(Exception e, String message) {
                            couldntFindBirdSubmitting = false;
                            btnCouldntFindYourBird.setEnabled(true);
                            btnCouldntFindYourBird.setAlpha(1f);
                            MessagePopupHelper.showBrief(AiCompActivity.this,
                                    (message != null && !message.trim().isEmpty())
                                            ? message
                                            : getString(R.string.couldnt_find_your_bird_failed));
                        }
                    }
            );
        });

        btnRetake.setOnClickListener(v -> {
            openAiApi.syncIdentificationFeedback(
                    identificationLogId,
                    identificationId,
                    "retake_after_openai_review",
                    null,
                    null,
                    null
            );
            if (isGalleryImportFlow()) {
                Intent home = new Intent(this, HomeActivity.class);
                home.putExtra(HomeActivity.EXTRA_OPEN_COLLECTION_TAB, true);
                home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(home);
            } else {
                Intent intent = new Intent(this, ImageUploadActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            }
            finish();
        });
    }

    private boolean isGalleryImportFlow() {
        return CaptureGuardHelper.CAPTURE_SOURCE_GALLERY_IMPORT.equals(currentCaptureSource);
    }

    private void bindCandidate(LinearLayout container,
                               ImageView imageView,
                               View progressView,
                               TextView statusView,
                               TextView attributionView,
                               TextView reasonView,
                               TextView nameView,
                               @Nullable Bundle candidate) {
        if (candidate == null) {
            container.setVisibility(View.GONE);
            container.setClickable(false);
            return;
        }

        final String source = candidate.getString("candidateSource", "openai_review");

        container.setVisibility(View.VISIBLE);
        nameView.setText(candidate.getString("candidateCommonName", "Unknown Bird"));

        if (attributionView != null) {
            attributionView.setText("");
            attributionView.setVisibility(View.GONE);
        }

        if (reasonView != null) {
            String reasonText = candidate.getString("candidateReasonText");
            if (reasonText != null && !reasonText.trim().isEmpty()) {
                reasonView.setText(reasonText);
                reasonView.setVisibility(View.VISIBLE);
            } else {
                reasonView.setText("");
                reasonView.setVisibility(View.GONE);
            }
        }

        if (progressView != null) {
            progressView.setVisibility(View.VISIBLE);
        }

        statusView.setText("Loading reference photo...");
        statusView.setVisibility(View.VISIBLE);

        BirdImageLoader.loadBirdImageIntoWithFetch(
                this,
                imageView,
                progressView,
                statusView,
                candidate.getString("candidateBirdId"),
                candidate.getString("candidateCommonName"),
                candidate.getString("candidateScientificName"),
                new BirdImageLoader.MetadataLoadCallback() {
                    @Override
                    public void onLoaded(@Nullable BirdImageLoader.ImageMetadata metadata) {
                        if (attributionView == null || isFinishing() || isDestroyed()) {
                            return;
                        }
                        BirdImageLoader.applyAttributionText(attributionView, metadata);
                    }

                    @Override
                    public void onNotFound() {
                        if (attributionView != null) {
                            attributionView.setText("");
                            attributionView.setVisibility(View.GONE);
                        }
                    }
                }
        );

        View.OnClickListener clickListener = v -> {
            String selectedBirdId = candidate.getString("candidateBirdId");
            String selectedCommonName = candidate.getString("candidateCommonName");
            String selectedScientificName = candidate.getString("candidateScientificName");
            String selectedSpecies = candidate.getString("candidateSpecies");
            String selectedFamily = candidate.getString("candidateFamily");

            openAiApi.syncIdentificationFeedback(
                    identificationLogId,
                    identificationId,
                    "select_openai_alternative",
                    selectedBirdId,
                    source,
                    candidate.getString("candidateReasonText"),
                    selectedCommonName,
                    selectedScientificName,
                    selectedSpecies,
                    selectedFamily
            );

            Intent resultIntent = new Intent();
            resultIntent.putExtra("selectedBirdId", selectedBirdId);
            resultIntent.putExtra("selectedCommonName", selectedCommonName);
            resultIntent.putExtra("selectedScientificName", selectedScientificName);
            resultIntent.putExtra("selectedSpecies", selectedSpecies);
            resultIntent.putExtra("selectedFamily", selectedFamily);
            resultIntent.putExtra("selectedSource", source);

            CaptureGuardHelper.putGuardExtras(
                    resultIntent,
                    CaptureGuardHelper.readReportFromIntent(getIntent(), true)
            );

            setResult(RESULT_OK, resultIntent);
            finish();
        };

        container.setOnClickListener(clickListener);
        imageView.setOnClickListener(clickListener);
        nameView.setOnClickListener(clickListener);
    }

    private void applySingleVisibleLayoutIfNeeded(LinearLayout llAiBird1, LinearLayout llAiBird2) {
        boolean bird1Visible = llAiBird1.getVisibility() == View.VISIBLE;
        boolean bird2Visible = llAiBird2.getVisibility() == View.VISIBLE;

        resetCardLayout(llAiBird1, true);
        resetCardLayout(llAiBird2, false);

        if (bird1Visible && !bird2Visible) {
            centerCard(llAiBird1);
        } else if (!bird1Visible && bird2Visible) {
            centerCard(llAiBird2);
        }
    }

    private void resetCardLayout(LinearLayout card, boolean isLeftCard) {
        ConstraintLayout.LayoutParams params =
                (ConstraintLayout.LayoutParams) card.getLayoutParams();

        params.width = 0;
        params.topToBottom = R.id.viewDivider;
        params.topToTop = ConstraintLayout.LayoutParams.UNSET;
        params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;

        if (isLeftCard) {
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
            params.endToStart = R.id.llAiBird2;
            params.startToEnd = ConstraintLayout.LayoutParams.UNSET;
            params.endToEnd = ConstraintLayout.LayoutParams.UNSET;
            params.setMarginStart(0);
            params.setMarginEnd(dpToPx(8));
        } else {
            params.startToEnd = R.id.llAiBird1;
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
            params.startToStart = ConstraintLayout.LayoutParams.UNSET;
            params.endToStart = ConstraintLayout.LayoutParams.UNSET;
            params.setMarginStart(dpToPx(8));
            params.setMarginEnd(0);
        }

        card.setLayoutParams(params);
    }

    private void centerCard(LinearLayout card) {
        ConstraintLayout.LayoutParams params =
                (ConstraintLayout.LayoutParams) card.getLayoutParams();

        params.width = 0;
        params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
        params.startToEnd = ConstraintLayout.LayoutParams.UNSET;
        params.endToStart = ConstraintLayout.LayoutParams.UNSET;
        params.setMarginStart(0);
        params.setMarginEnd(0);

        card.setLayoutParams(params);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Nullable
    private Bundle getCandidateAt(int index) {
        if (openAiAlternatives == null || index < 0 || index >= openAiAlternatives.size()) {
            return null;
        }
        return openAiAlternatives.get(index);
    }
}