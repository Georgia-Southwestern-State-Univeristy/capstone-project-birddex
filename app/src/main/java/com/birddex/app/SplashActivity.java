package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import androidx.appcompat.app.AppCompatActivity;

/**
 * SplashActivity: Very early launch entry point used before handing off to the real loading/login flow.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class SplashActivity extends AppCompatActivity {

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // Bind or inflate the UI pieces this method needs before it can update the screen.
        View splashRoot = findViewById(R.id.splashRoot);
        
        // Load fancy entry animation and fade out
        Animation splashEnter = AnimationUtils.loadAnimation(this, R.anim.splash_enter);
        Animation fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out);

        // Start entrance animation
        splashRoot.startAnimation(splashEnter);

        splashEnter.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {}

            @Override
            public void onAnimationEnd(Animation animation) {
                // Hold the state for 1.2 seconds before fading out
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    splashRoot.startAnimation(fadeOut);
                }, 1200);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {}
        });

        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {}

            @Override
            public void onAnimationEnd(Animation animation) {
                // Transition to LoadingActivity
                // Move into the next screen and pass the identifiers/data that screen needs.
                startActivity(new Intent(SplashActivity.this, LoadingActivity.class));
                finish();
                // Smooth transition without blinking
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {}
        });
    }
}
