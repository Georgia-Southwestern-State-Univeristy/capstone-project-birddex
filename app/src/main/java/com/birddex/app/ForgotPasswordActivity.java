package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

/**
 * ForgotPasswordActivity allows users to request a password reset email.
 * Fixes: Added isSending guard to prevent email spamming.
 */
public class ForgotPasswordActivity extends AppCompatActivity {

    private static final String TAG = "ForgotPasswordActivity";
    private FirebaseManager firebaseManager;
    private sign_IN_upValidator signINupValidator;

    private EditText forgotPasswordEmailEditText;
    private Button btnSendReset;
    private boolean isSending = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        firebaseManager = new FirebaseManager(this);
        signINupValidator = new sign_IN_upValidator();

        forgotPasswordEmailEditText = findViewById(R.id.etForgotEmail);
        btnSendReset = findViewById(R.id.btnSendReset);
        TextView tvBackToLogin = findViewById(R.id.tvBackToLogin);

        btnSendReset.setOnClickListener(v -> {
            if (isSending) return;
            
            if (signINupValidator.validateForgotPasswordForm(forgotPasswordEmailEditText)) {
                String email = forgotPasswordEmailEditText.getText().toString().trim();
                isSending = true;
                btnSendReset.setEnabled(false);

                firebaseManager.sendPasswordResetEmail(email, task -> {
                    isSending = false;
                    btnSendReset.setEnabled(true);
                    if (task.isSuccessful()) {
                        Toast.makeText(ForgotPasswordActivity.this, "Password reset email sent.", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(ForgotPasswordActivity.this, task.getException() != null ? task.getException().getMessage() : "Error", Toast.LENGTH_LONG).show();
                    }
                });
            }
        });

        tvBackToLogin.setOnClickListener(v -> {
            startActivity(new Intent(ForgotPasswordActivity.this, LoginActivity.class));
            finish();
        });
    }
}
