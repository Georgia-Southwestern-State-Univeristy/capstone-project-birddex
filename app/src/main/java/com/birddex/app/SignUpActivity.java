package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseUser;

/**
 * SignUpActivity handles the user registration process.
 * Fixes: Added isNavigating guard to prevent redundant activity launches.
 */
public class SignUpActivity extends AppCompatActivity {

    private FirebaseManager firebaseManager;
    private sign_IN_upValidator signINupValidator;
    private View loadingOverlay;
    private Button btnSignUp;
    private boolean isNavigating = false;

    private EditText usernameEditText;
    private EditText emailEditText;
    private EditText passwordEditText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_up);

        firebaseManager = new FirebaseManager(this);
        signINupValidator = new sign_IN_upValidator();
        loadingOverlay = findViewById(R.id.loadingOverlay);

        usernameEditText = findViewById(R.id.etUsername);
        emailEditText = findViewById(R.id.etEmail);
        passwordEditText = findViewById(R.id.etPassword);

        btnSignUp = findViewById(R.id.btnSignUp);
        TextView tvAlready = findViewById(R.id.tvAlready);

        btnSignUp.setOnClickListener(v -> {
            if (isNavigating) return;
            if (signINupValidator.validateSignUpForm(usernameEditText, emailEditText, passwordEditText)) {
                String username = usernameEditText.getText().toString().trim();
                String email = emailEditText.getText().toString().trim();
                String password = passwordEditText.getText().toString();
                
                setLoadingState(true);
                firebaseManager.createAccount(username, email, password, new FirebaseManager.AuthListener() {
                    @Override
                    public void onSuccess(FirebaseUser user) {
                        if (isNavigating) return;
                        isNavigating = true;
                        
                        setLoadingState(false);
                        Toast.makeText(SignUpActivity.this, "Sign up successful. Please verify your email.", Toast.LENGTH_LONG).show();
                        startActivity(new Intent(SignUpActivity.this, SignUpCompleteActivity.class));
                        finish();
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        setLoadingState(false);
                        Toast.makeText(SignUpActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onUsernameTaken() { 
                        setLoadingState(false);
                        usernameEditText.setError("Username already taken.");
                    }

                    @Override
                    public void onEmailTaken() {
                        setLoadingState(false);
                        emailEditText.setError("Email already taken.");
                    }
                });
            }
        });

        tvAlready.setOnClickListener(v -> {
            if (!isNavigating) {
                startActivity(new Intent(SignUpActivity.this, LoginActivity.class));
                finish();
            }
        });
    }

    private void setLoadingState(boolean loading) {
        if (loadingOverlay != null) loadingOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (btnSignUp != null) btnSignUp.setEnabled(!loading);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (loadingOverlay != null && loadingOverlay.getVisibility() == View.VISIBLE) {
            loadingOverlay.setVisibility(View.GONE);
        }
    }
}
