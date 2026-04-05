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
/**
 * ForgotPasswordActivity: Activity class for one BirdDex screen. It owns screen setup, user actions, and navigation for this part of the app.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class ForgotPasswordActivity extends AppCompatActivity {

    private static final String TAG = "ForgotPasswordActivity";
    private FirebaseManager firebaseManager;
    private SignInUpValidator signINupValidator;

    private EditText forgotPasswordEmailEditText;
    private Button btnSendReset;
    private boolean isSending = false;

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        // Set up or query the Firebase layer that supplies/stores this feature's data.
        firebaseManager = new FirebaseManager(this);
        signINupValidator = new SignInUpValidator();

        // Bind or inflate the UI pieces this method needs before it can update the screen.
        forgotPasswordEmailEditText = findViewById(R.id.etForgotEmail);
        btnSendReset = findViewById(R.id.btnSendReset);
        TextView tvBackToLogin = findViewById(R.id.tvBackToLogin);

        // Attach the user interaction that should run when this control is tapped.
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
                        // Give the user immediate feedback about the result of this action.
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
