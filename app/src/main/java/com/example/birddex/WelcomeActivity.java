package com.example.birddex;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.TimeUnit;

/**
 * WelcomeActivity serves as the entry point of the app.
 * It handles automatic login redirection and session expiry checks.
 */
public class WelcomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Retrieve the current Firebase user if they are already authenticated.
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        
        if (currentUser != null) {
            // Check when the user last signed in to enforce a 30-day session limit.
            long lastSignInTimestamp = currentUser.getMetadata().getLastSignInTimestamp();
            long thirtyDaysInMillis = TimeUnit.DAYS.toMillis(30);
            long currentTime = System.currentTimeMillis();

            if ((currentTime - lastSignInTimestamp) > thirtyDaysInMillis) {
                // If the session has expired (older than 30 days), sign out the user
                // and show the welcome screen (activity_main).
                FirebaseAuth.getInstance().signOut();
                showWelcomeScreen();
            } else {
                // If the session is still valid, redirect the user directly to the Home screen.
                startActivity(new Intent(WelcomeActivity.this, HomeActivity.class));
                finish(); // Close WelcomeActivity so it's removed from the back stack.
            }
            return;
        }

        // If no user is logged in, display the initial welcome/landing screen.
        showWelcomeScreen();
    }

    /**
     * Initializes and displays the welcome screen layout.
     * Uses activity_main as requested, which contains Sign Up and Login options.
     */
    private void showWelcomeScreen() {
        // Setting the content view to activity_main which serves as our landing page.
        setContentView(R.layout.activity_main);

        // Initialize UI components from the layout.
        Button btnSignup = findViewById(R.id.btnSignup);
        TextView tvAlready = findViewById(R.id.tvAlready);

        // Set up navigation to the SignUpActivity when the 'Sign Up' button is clicked.
        btnSignup.setOnClickListener(v -> {
            startActivity(new Intent(WelcomeActivity.this, SignUpActivity.class));
        });

        // Set up navigation to the LoginActivity when the 'Already have an account' text is clicked.
        tvAlready.setOnClickListener(v -> {
            startActivity(new Intent(WelcomeActivity.this, LoginActivity.class));
        });
    }
}
