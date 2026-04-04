package com.birddex.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.firebase.functions.FirebaseFunctions;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Sends a lightweight callable request so the backend can warm the BirdDex Cloud Run service
 * without exposing the raw Cloud Run URL to the client.
 */
public final class BirdDexApiWarmupHelper {

    private static final String TAG = "BirdDexApiWarmup";
    private static final String PREFS_NAME = "birddex_api_warmup_prefs";
    private static final String KEY_LAST_WARM_AT_MS = "last_warm_at_ms";

    // 9 minutes keeps the service reasonably warm without sending a request on every screen open.
    private static final long WARMUP_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(9);

    private BirdDexApiWarmupHelper() {
        // No instances.
    }

    public static void maybeWarmup(Context context, String reason) {
        if (context == null) return;

        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        long now = System.currentTimeMillis();
        long lastWarmAt = prefs.getLong(KEY_LAST_WARM_AT_MS, 0L);
        long elapsed = now - lastWarmAt;

        if (elapsed < WARMUP_COOLDOWN_MS) {
            Log.d(TAG, "Skipping warm-up; cooldown active. reason=" + reason + ", elapsedMs=" + elapsed);
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("reason", reason == null ? "unknown" : reason);

        FirebaseFunctions.getInstance()
                .getHttpsCallable("warmBirdDexModel")
                .call(data)
                .addOnCompleteListener(task -> handleWarmupResult(prefs, now, reason, task));
    }

    private static void handleWarmupResult(SharedPreferences prefs, long attemptedAt, String reason, Task<?> task) {
        if (task.isSuccessful()) {
            prefs.edit().putLong(KEY_LAST_WARM_AT_MS, attemptedAt).apply();
            Log.d(TAG, "Warm-up callable succeeded. reason=" + reason);
        } else {
            Exception e = task.getException();
            Log.w(TAG, "Warm-up callable failed: " + (e != null ? e.getMessage() : "unknown"));
        }
    }
}
