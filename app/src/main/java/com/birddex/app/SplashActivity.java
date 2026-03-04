package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

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
