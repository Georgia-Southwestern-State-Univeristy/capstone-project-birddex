package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.birddex.app.databinding.ActivitySignUpCompleteBinding;

/**
 * SignUpCompleteActivity: Activity class for one BirdDex screen. It owns screen setup, user actions, and navigation for this part of the app.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class SignUpCompleteActivity extends AppCompatActivity {

    private ActivitySignUpCompleteBinding binding;

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarHelper.applyStandardNavBar(this);
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        binding = ActivitySignUpCompleteBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Attach the user interaction that should run when this control is tapped.
        binding.btnGoToLogin.setOnClickListener(v -> {
            // Move into the next screen and pass the identifiers/data that screen needs.
            startActivity(new Intent(SignUpCompleteActivity.this, LoginActivity.class));
            finish(); // Finish this activity so they can't go back to it with the back button
        });
    }
}