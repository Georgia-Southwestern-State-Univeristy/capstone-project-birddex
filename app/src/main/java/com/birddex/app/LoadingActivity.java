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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_loading);

        tvLoadingText = findViewById(R.id.tvLoadingText);

        startTextCycling();

        // Simulate some loading time (e.g., 3 seconds) before proceeding
        handler.postDelayed(this::checkAuthAndProceed, 3000);
    }

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
        if (handler != null && textCycler != null) {
            handler.removeCallbacks(textCycler);
        }
    }
}
