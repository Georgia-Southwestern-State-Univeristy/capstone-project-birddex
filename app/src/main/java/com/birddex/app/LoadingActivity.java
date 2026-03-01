package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicInteger;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.TimeUnit;

public class LoadingActivity extends AppCompatActivity {

    private static final long MIN_LOADING_MS = 3000;
    private static final long MAX_PRELOAD_WAIT_MS = 6500;

    private TextView tvLoadingText;
    private final String[] loadingMessages = {
            "Finding nearby birds...",
            "Setting up your nest...",
            "Cleaning the binoculars...",
            "Synchronizing BirdDex...",
            "Consulting the field guides..."
    };

    private int currentMessageIndex = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable textCycler;
    private boolean preloadFinished = false;
    private boolean navigated = false;
    private long loadingStartMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_loading);

        tvLoadingText = findViewById(R.id.tvLoadingText);
        loadingStartMs = System.currentTimeMillis();

        startTextCycling();
        startStartupPreload();
    }

    private void startTextCycling() {
        textCycler = new Runnable() {
            @Override
            public void run() {
                tvLoadingText.setText(loadingMessages[currentMessageIndex]);
                currentMessageIndex = (currentMessageIndex + 1) % loadingMessages.length;
                handler.postDelayed(this, 1500);
            }
        };
        handler.post(textCycler);
    }

    private void startStartupPreload() {
        if (!hasValidSignedInUser()) {
            preloadFinished = true;
            maybeProceed();
            return;
        }

        AtomicInteger pendingPreloads = new AtomicInteger(2);
        Runnable onPreloadDone = () -> {
            if (pendingPreloads.decrementAndGet() <= 0) {
                preloadFinished = true;
                maybeProceed();
            }
        };

        NearbyPreloadManager.getInstance().preload(this, onPreloadDone);
        ForumPreloadManager.getInstance().preload(onPreloadDone);

        handler.postDelayed(() -> {
            if (!preloadFinished) {
                preloadFinished = true;
                maybeProceed();
            }
        }, MAX_PRELOAD_WAIT_MS);
    }

    private boolean hasValidSignedInUser() {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null || currentUser.getMetadata() == null) {
            return false;
        }

        long lastSignInTimestamp = currentUser.getMetadata().getLastSignInTimestamp();
        long thirtyDaysInMillis = TimeUnit.DAYS.toMillis(30);
        long currentTime = System.currentTimeMillis();

        return (currentTime - lastSignInTimestamp) <= thirtyDaysInMillis;
    }

    private void maybeProceed() {
        if (navigated || !preloadFinished) {
            return;
        }

        long elapsed = System.currentTimeMillis() - loadingStartMs;
        long remaining = MIN_LOADING_MS - elapsed;

        if (remaining > 0) {
            handler.postDelayed(this::checkAuthAndProceed, remaining);
        } else {
            checkAuthAndProceed();
        }
    }

    private void checkAuthAndProceed() {
        if (navigated) {
            return;
        }

        navigated = true;

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();

        if (currentUser != null && currentUser.getMetadata() != null) {
            long lastSignInTimestamp = currentUser.getMetadata().getLastSignInTimestamp();
            long thirtyDaysInMillis = TimeUnit.DAYS.toMillis(30);
            long currentTime = System.currentTimeMillis();

            if ((currentTime - lastSignInTimestamp) > thirtyDaysInMillis) {
                FirebaseAuth.getInstance().signOut();
                navigateToWelcome();
            } else {
                startActivity(new Intent(LoadingActivity.this, HomeActivity.class));
                finish();
            }
        } else {
            navigateToWelcome();
        }
    }

    private void navigateToWelcome() {
        startActivity(new Intent(LoadingActivity.this, WelcomeActivity.class));
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (textCycler != null) {
            handler.removeCallbacks(textCycler);
        }
        handler.removeCallbacksAndMessages(null);
    }
}