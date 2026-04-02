package com.birddex.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Sends a small fire-and-forget request to the BirdDex model service so Cloud Run can start
 * booting the container before the user reaches the identification step.
 *
 * This helper is intentionally separate from FirebaseManager because the model service lives on
 * Cloud Run, not in Firebase Functions, and the warm-up should stay lightweight and isolated.
 */
public final class BirdDexApiWarmupHelper {

    private static final String TAG = "BirdDexApiWarmup";
    private static final String PREFS_NAME = "birddex_api_warmup_prefs";
    private static final String KEY_LAST_WARM_AT_MS = "last_warm_at_ms";

    // 9 minutes keeps the service reasonably warm without sending a ping on every screen open.
    private static final long WARMUP_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(9);

    // Root request is enough to spin up the Cloud Run container. A 404 still warms the service.
    private static final String WARMUP_URL = "https://birddex-api-650774648072.us-central1.run.app/";

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build();

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

        prefs.edit().putLong(KEY_LAST_WARM_AT_MS, now).apply();
        sendWarmupRequest(reason);
    }

    private static void sendWarmupRequest(String reason) {
        Request request = new Request.Builder()
                .url(WARMUP_URL)
                .get()
                .header("X-BirdDex-Warmup", "1")
                .header("X-BirdDex-Warmup-Reason", reason == null ? "unknown" : reason)
                .build();

        HTTP_CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(TAG, "Warm-up request failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response ignored = response) {
                    Log.d(TAG, "Warm-up request completed with code=" + response.code());
                }
            }
        });
    }
}
