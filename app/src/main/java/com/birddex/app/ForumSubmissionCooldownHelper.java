package com.birddex.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Shared forum submission cooldown helper.
 *
 * The backend is still the final authority, but this gives the user instant feedback and prevents
 * obvious rapid re-taps from the current device before the callable runs.
 */
public final class ForumSubmissionCooldownHelper {

    private static final String PREFS_NAME = "BirdDexPrefs";
    private static final String KEY_LAST_FORUM_SUBMISSION_MS = "last_forum_submission_ms";
    public static final long FORUM_SUBMISSION_COOLDOWN_MS = 15_000L;

    private ForumSubmissionCooldownHelper() {
        // Utility class.
    }

    public static long getRemainingCooldownMs(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastSubmissionMs = prefs.getLong(KEY_LAST_FORUM_SUBMISSION_MS, 0L);
        long elapsedMs = System.currentTimeMillis() - lastSubmissionMs;
        long remainingMs = FORUM_SUBMISSION_COOLDOWN_MS - elapsedMs;
        return Math.max(0L, remainingMs);
    }

    public static boolean isCoolingDown(Context context) {
        return getRemainingCooldownMs(context) > 0L;
    }

    public static void markSubmissionSuccess(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_FORUM_SUBMISSION_MS, System.currentTimeMillis())
                .apply();
    }

    public static String buildCooldownMessage(Context context) {
        long remainingMs = getRemainingCooldownMs(context);
        long remainingSeconds = Math.max(1L, (long) Math.ceil(remainingMs / 1000.0));
        return "Please wait " + remainingSeconds + " second" + (remainingSeconds == 1L ? "" : "s") + " before submitting again.";
    }
}
