package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

/**
 * SignUpCompleteActivity is shown after a successful account creation (if required by flow).
 * It provides a simple confirmation and a way to navigate to the Login screen.
 */
public class SignUpCompleteActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_signup_complete);

        // Bind the "Go to Login" button.
        Button btnGoLogin = findViewById(R.id.btnGoLogin);

        // Set up the listener to redirect the user to the Login screen.
        btnGoLogin.setOnClickListener(v -> {
            startActivity(new Intent(SignUpCompleteActivity.this, LoginActivity.class));
            // finish() is called to remove this activity from the back stack,
            // preventing the user from returning to the "Success" screen via the back button.
            finish();
        });
    }
}
