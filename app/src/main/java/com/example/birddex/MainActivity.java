package com.example.birddex;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * MainActivity acts as a splash or entry dispatcher.
 * It checks the user's authentication state and redirects them to either
 * the HomeActivity (if logged in) or WelcomeActivity (if not).
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check if a user is currently signed in via Firebase Auth.
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        
        if (currentUser != null) {
            // User is already authenticated, redirect to the main Home screen.
            startActivity(new Intent(this, HomeActivity.class));
        } else {
            // No authenticated user found, redirect to the Welcome/Landing screen.
            startActivity(new Intent(this, WelcomeActivity.class));
        }
        
        // Finish MainActivity so it is removed from the task stack.
        finish();
    }
}
