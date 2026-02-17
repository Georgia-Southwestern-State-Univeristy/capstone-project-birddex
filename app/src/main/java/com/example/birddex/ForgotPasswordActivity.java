package com.example.birddex;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * ForgotPasswordActivity allows users to request a password reset email.
 * It uses Firebase Authentication to handle the reset process.
 */
public class ForgotPasswordActivity extends AppCompatActivity {

    private static final String TAG = "ForgotPasswordActivity";
    private FirebaseManager firebaseManager;
    private sign_IN_upValidator signINupValidator;

    private EditText forgotPasswordEmailEditText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        // Initialize helpers for Firebase operations and input validation.
        firebaseManager = new FirebaseManager();
        signINupValidator = new sign_IN_upValidator();

        // Bind UI components.
        forgotPasswordEmailEditText = findViewById(R.id.etForgotEmail);
        Button btnSendReset = findViewById(R.id.btnSendReset);
        TextView tvBackToLogin = findViewById(R.id.tvBackToLogin);

        // Handle the 'Send Reset' button click.
        btnSendReset.setOnClickListener(v -> {
            Log.d(TAG, "Send Reset button clicked.");
            // Validate that the email field is not empty and is in the correct format.
            if (signINupValidator.validateForgotPasswordForm(forgotPasswordEmailEditText)) {
                String email = forgotPasswordEmailEditText.getText().toString();
                Log.d(TAG, "Validation successful. Sending reset email to: " + email);

                // Trigger the Firebase password reset email.
                firebaseManager.sendPasswordResetEmail(email, new FirebaseManager.PasswordResetListener() {
                    @Override
                    public void onSuccess() {
                        Log.d(TAG, "onSuccess: Password reset email sent successfully.");
                        // Notify the user that the email was sent successfully.
                        Toast.makeText(ForgotPasswordActivity.this, "Password reset email sent. Please check your inbox (and spam folder).", Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        Log.e(TAG, "onFailure: " + errorMessage);
                        // Display error message if the operation fails.
                        Toast.makeText(ForgotPasswordActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                Log.w(TAG, "Validation failed. Email field likely empty or invalid.");
            }
        });

        // Navigate back to the LoginActivity.
        tvBackToLogin.setOnClickListener(v -> {
            startActivity(new Intent(ForgotPasswordActivity.this, LoginActivity.class));
            finish(); // Finish current activity.
        });
    }
}
