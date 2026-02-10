package com.example.birddex;

import android.content.Intent;
import android.os.Bundle;
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
            // Validate that the email field is not empty and is in the correct format.
            if (signINupValidator.validateForgotPasswordForm(forgotPasswordEmailEditText)) {
                String email = forgotPasswordEmailEditText.getText().toString();
                
                // Trigger the Firebase password reset email.
                firebaseManager.sendPasswordResetEmail(email, new FirebaseManager.PasswordResetListener() {
                    @Override
                    public void onSuccess() {
                        // Notify the user that the email was sent successfully.
                        Toast.makeText(ForgotPasswordActivity.this, "Password reset email sent.", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        // Display error message if the operation fails.
                        Toast.makeText(ForgotPasswordActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        // Navigate back to the LoginActivity.
        tvBackToLogin.setOnClickListener(v -> {
            startActivity(new Intent(ForgotPasswordActivity.this, LoginActivity.class));
            finish(); // Finish current activity.
        });
    }
}
