package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.TimeUnit;

/**
 * LoadingActivity: Entry/loading screen that preps app state before the main UI appears.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class LoadingActivity extends AppCompatActivity {

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

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarHelper.applyStandardNavBar(this);
        setContentView(R.layout.activity_loading);

        // Bind or inflate the UI pieces this method needs before it can update the screen.
        tvLoadingText = findViewById(R.id.tvLoadingText);

        startTextCycling();

        // Simulate some loading time (e.g., 3 seconds) before proceeding
        handler.postDelayed(this::checkAuthAndProceed, 3000);
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void startTextCycling() {
        textCycler = new Runnable() {
            @Override
            public void run() {
                tvLoadingText.setText(loadingMessages[currentMessageIndex]);
                currentMessageIndex = (currentMessageIndex + 1) % loadingMessages.length;
                handler.postDelayed(this, 1500); // Change text every 1.5 seconds
            }
        };
        handler.post(textCycler);
    }

    /**
     * Main logic block for this part of the feature.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    private void checkAuthAndProceed() {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();

        if (currentUser != null) {
            // Check if login is still valid (e.g., within 30 days)
            long lastSignInTimestamp = currentUser.getMetadata().getLastSignInTimestamp();
            long thirtyDaysInMillis = TimeUnit.DAYS.toMillis(30);
            long currentTime = System.currentTimeMillis();

            if ((currentTime - lastSignInTimestamp) > thirtyDaysInMillis) {
                FirebaseAuth.getInstance().signOut();
                navigateToWelcome();
            } else {
                // Move into the next screen and pass the identifiers/data that screen needs.
                startActivity(new Intent(LoadingActivity.this, HomeActivity.class));
                finish();
            }
        } else {
            navigateToWelcome();
        }
    }

    /**
     * Moves the user to another screen or flow and passes along the required extras.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    private void navigateToWelcome() {
        // Move into the next screen and pass the identifiers/data that screen needs.
        startActivity(new Intent(LoadingActivity.this, WelcomeActivity.class));
        finish();
    }

    /**
     * Final cleanup point when the Activity/Fragment instance is being destroyed.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null && textCycler != null) {
            handler.removeCallbacks(textCycler);
        }
    }
}
