package com.birddex.app;

import android.os.SystemClock;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public final class IdentificationFeedbackHelper {

    private static final long CLIENT_FEEDBACK_DEBOUNCE_MS = 1500L;
    private static long lastSubmitAttemptAtMs = 0L;

    public interface SubmitAction {
        void onSubmit(@NonNull String feedbackText, @NonNull SubmitResultCallback callback);
    }

    public interface SubmitResultCallback {
        void onResult(boolean success, @NonNull String userMessage);
    }

    private IdentificationFeedbackHelper() {
    }

    public static void showFeedbackDialog(@NonNull AppCompatActivity activity,
                                          @NonNull SubmitAction submitAction) {
        final EditText feedbackInput = new EditText(activity);
        feedbackInput.setHint(R.string.submit_feedback_hint);
        feedbackInput.setMinLines(3);
        feedbackInput.setMaxLines(6);
        feedbackInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        final int horizontalPadding = dpToPx(activity, 20);
        final int verticalPadding = dpToPx(activity, 8);

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(horizontalPadding, verticalPadding, horizontalPadding, 0);
        container.addView(feedbackInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.submit_feedback_title)
                .setMessage(R.string.submit_feedback_message)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.submit_feedback_send, null)
                .create();

        dialog.setOnShowListener(d -> {
            final Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            final Button negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            positiveButton.setOnClickListener(v -> {
                String feedbackText = feedbackInput.getText() != null
                        ? feedbackInput.getText().toString().trim()
                        : "";
                if (feedbackText.isEmpty()) {
                    feedbackInput.setError(activity.getString(R.string.submit_feedback_required));
                    return;
                }

                long now = SystemClock.elapsedRealtime();
                if (now - lastSubmitAttemptAtMs < CLIENT_FEEDBACK_DEBOUNCE_MS) {
                    feedbackInput.setError(activity.getString(R.string.submit_feedback_debounce));
                    return;
                }
                lastSubmitAttemptAtMs = now;

                positiveButton.setEnabled(false);
                negativeButton.setEnabled(false);
                positiveButton.setText(R.string.submit_feedback_sending);

                submitAction.onSubmit(feedbackText, (success, userMessage) -> activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }

                    String finalMessage = userMessage != null && !userMessage.trim().isEmpty()
                            ? userMessage.trim()
                            : activity.getString(success ? R.string.submit_feedback_thanks : R.string.submit_feedback_failed);

                    if (success) {
                        if (dialog.isShowing()) {
                            dialog.dismiss();
                        }
                        Toast.makeText(activity, finalMessage, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    positiveButton.setEnabled(true);
                    negativeButton.setEnabled(true);
                    positiveButton.setText(R.string.submit_feedback_send);
                    feedbackInput.setError(finalMessage);
                    Toast.makeText(activity, finalMessage, Toast.LENGTH_LONG).show();
                }));
            });
        });

        dialog.show();
    }

    public static void submitFeedback(@NonNull OpenAiApi openAiApi,
                                      @NonNull AppCompatActivity activity,
                                      @Nullable String identificationLogId,
                                      @Nullable String identificationId,
                                      @NonNull String stage,
                                      @NonNull String feedbackText,
                                      @NonNull SubmitResultCallback callback) {
        if (identificationLogId == null || identificationLogId.trim().isEmpty()) {
            callback.onResult(false, activity.getString(R.string.submit_feedback_log_not_ready));
            return;
        }
        openAiApi.submitIdentificationFeedback(
                identificationLogId,
                identificationId,
                stage,
                feedbackText,
                new OpenAiApi.FeedbackSyncCallback() {
                    @Override
                    public void onSuccess(String userMessage) {
                        callback.onResult(true, userMessage != null && !userMessage.trim().isEmpty()
                                ? userMessage
                                : activity.getString(R.string.submit_feedback_thanks));
                    }

                    @Override
                    public void onFailure(Exception e, @NonNull String message) {
                        callback.onResult(false, message);
                    }
                }
        );
    }

    private static int dpToPx(@NonNull AppCompatActivity activity, int dp) {
        float density = activity.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
