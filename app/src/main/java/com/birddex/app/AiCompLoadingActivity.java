package com.birddex.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;

/**
 * AiCompLoadingActivity: Loading screen while BirdDex asks OpenAI for two more options.
 */
public class AiCompLoadingActivity extends AppCompatActivity {

    private static final String TAG = "AiCompLoadingActivity";

    private TextView tvLoadingMessage;
    private final String[] messages = {
            "Consulting AI...",
            "Comparing your bird against more species...",
            "Checking plumage and shape...",
            "Preparing two more options...",
            "Almost there..."
    };
    private int messageIndex = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable messageSwitcher = new Runnable() {
        @Override
        public void run() {
            if (tvLoadingMessage != null) {
                tvLoadingMessage.setText(messages[messageIndex]);
                messageIndex = (messageIndex + 1) % messages.length;
                handler.postDelayed(this, 1200);
            }
        }
    };

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
        setContentView(R.layout.activity_ai_loading);

        tvLoadingMessage = findViewById(R.id.tvLoadingMessage);
        handler.post(messageSwitcher);

        String imageUriStr = getIntent().getStringExtra("imageUri");
        String imageUrl = getIntent().getStringExtra("imageUrl");
        String identificationLogId = getIntent().getStringExtra("identificationLogId");
        String identificationId = getIntent().getStringExtra("identificationId");
        String currentBirdId = getIntent().getStringExtra("birdId");
        String currentCommonName = getIntent().getStringExtra("commonName");
        String currentScientificName = getIntent().getStringExtra("scientificName");

        if (imageUriStr == null || imageUriStr.trim().isEmpty()) {
            MessagePopupHelper.showBrief(this, "Could not load your bird photo for AI review.", () -> {
                if (!isFinishing() && !isDestroyed()) finish();
            });
            return;
        }
        if (identificationLogId == null || identificationLogId.trim().isEmpty()) {
            MessagePopupHelper.showBrief(this, "Identification log is missing for AI review.", () -> {
                if (!isFinishing() && !isDestroyed()) finish();
            });
            return;
        }

        Uri imageUri = Uri.parse(imageUriStr);
        String base64Image = encodeImage(imageUri);
        if (base64Image == null || base64Image.trim().isEmpty()) {
            MessagePopupHelper.showBrief(this, "Failed to prepare your image for AI review.", () -> {
                if (!isFinishing() && !isDestroyed()) finish();
            });
            return;
        }

        openAiApi.requestOpenAiReviewCandidates(
                base64Image,
                imageUrl,
                identificationLogId,
                UUID.randomUUID().toString(),
                new OpenAiApi.BirdChoicesCallback() {
                    @Override
                    public void onSuccess(ArrayList<OpenAiApi.BirdChoice> candidates, @Nullable String userMessage, boolean isGore) {
                        if (isFinishing() || isDestroyed()) return;

                        if (isGore) {
                            MessagePopupHelper.showBrief(AiCompLoadingActivity.this,
                                    userMessage != null && !userMessage.trim().isEmpty()
                                            ? userMessage
                                            : "Please take a picture of a non-gore picture of a bird.",
                                    () -> {
                                        if (!isFinishing() && !isDestroyed()) finish();
                                    });
                            return;
                        }

                        Intent intent = new Intent(AiCompLoadingActivity.this, AiCompActivity.class);
                        intent.putExtra("imageUri", imageUriStr);
                        intent.putExtra("imageUrl", imageUrl);
                        intent.putExtra("identificationLogId", identificationLogId);
                        intent.putExtra("identificationId", identificationId);
                        intent.putExtra("birdId", currentBirdId);
                        intent.putExtra("commonName", currentCommonName);
                        intent.putExtra("scientificName", currentScientificName);
                        intent.putExtra("openAiUserMessage", userMessage);
                        intent.putParcelableArrayListExtra("openAiAlternatives", toCandidateBundles(candidates));

                        // Anti-cheat: Forward CaptureGuard report
                        CaptureGuardHelper.putGuardExtras(intent, CaptureGuardHelper.readReportFromIntent(getIntent(), true));

                        aiCompLauncher.launch(intent);
                    }

                    @Override
                    public void onFailure(Exception e, String message) {
                        if (isFinishing() || isDestroyed()) return;
                        Log.e(TAG, "requestOpenAiReviewCandidates failed", e);
                        MessagePopupHelper.showBrief(AiCompLoadingActivity.this,
                                message != null && !message.trim().isEmpty()
                                        ? message
                                        : "AI review failed.",
                                () -> {
                                    if (!isFinishing() && !isDestroyed()) finish();
                                });
                    }
                }
        );
    }

    private ArrayList<Bundle> toCandidateBundles(ArrayList<OpenAiApi.BirdChoice> candidates) {
        ArrayList<Bundle> bundles = new ArrayList<>();
        if (candidates == null) {
            return bundles;
        }

        for (OpenAiApi.BirdChoice candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            boolean hasText = (candidate.commonName != null && !candidate.commonName.trim().isEmpty())
                    || (candidate.scientificName != null && !candidate.scientificName.trim().isEmpty())
                    || (candidate.birdId != null && !candidate.birdId.trim().isEmpty());
            if (!hasText) {
                continue;
            }
            Bundle bundle = new Bundle();
            bundle.putString("candidateBirdId", candidate.birdId);
            bundle.putString("candidateCommonName", candidate.commonName);
            bundle.putString("candidateScientificName", candidate.scientificName);
            bundle.putString("candidateSpecies", candidate.species);
            bundle.putString("candidateFamily", candidate.family);
            bundle.putString("candidateSource", candidate.source);
            bundle.putString("candidateReasonText", candidate.alternativeReasonText);
            bundles.add(bundle);
        }
        return bundles;
    }

    private String encodeImage(Uri imageUri) {
        try {
            Bitmap bitmap = (Build.VERSION.SDK_INT >= 28)
                    ? ImageDecoder.decodeBitmap(ImageDecoder.createSource(this.getContentResolver(), imageUri))
                    : MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);

            int maxWidth = 1024;
            int maxHeight = 1024;
            float ratio = Math.min((float) maxWidth / bitmap.getWidth(), (float) maxHeight / bitmap.getHeight());
            if (ratio < 1.0f) {
                bitmap = Bitmap.createScaledBitmap(bitmap,
                        Math.round(ratio * bitmap.getWidth()),
                        Math.round(ratio * bitmap.getHeight()),
                        true);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (IOException e) {
            Log.e(TAG, "encodeImage failed", e);
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(messageSwitcher);
    }
}
