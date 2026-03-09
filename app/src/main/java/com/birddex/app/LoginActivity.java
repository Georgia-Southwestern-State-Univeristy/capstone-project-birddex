package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * LoginActivity handles the user authentication process.
 * Fixes: Added isNavigating guard to prevent redundant activity launches.
 */
public class LoginActivity extends AppCompatActivity {

    private FirebaseManager firebaseManager;
    private sign_IN_upValidator signINupValidator;

    private EditText emailEditText;
    private EditText passwordEditText;
    private Button btnLogin;
    private View loadingOverlay;
    private boolean isNavigating = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        firebaseManager = new FirebaseManager(this);
        signINupValidator = new sign_IN_upValidator();

        emailEditText = findViewById(R.id.etEmail);
        passwordEditText = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        loadingOverlay = findViewById(R.id.loadingOverlay);

        TextView tvForgot = findViewById(R.id.tvForgot);
        TextView tvSignUp = findViewById(R.id.tvSignUp);

        btnLogin.setOnClickListener(v -> {
            if (isNavigating) return;
            if (signINupValidator.validateSignInForm(emailEditText, passwordEditText)) {
                String email = emailEditText.getText().toString().trim();
                String password = passwordEditText.getText().toString();
                
                setLoadingState(true);
                firebaseManager.signIn(email, password, new FirebaseManager.AuthListener() {
                    @Override
                    public void onSuccess(FirebaseUser user) {
                        if (user != null && user.isEmailVerified()) {
                            if (isNavigating) return;
                            isNavigating = true;
                            
                            SessionManager sessionManager = new SessionManager(LoginActivity.this);
                            String sessionId = sessionManager.createSession(user.getUid());
                            firebaseManager.updateSessionId(user.getUid(), sessionId, task -> {
                                setLoadingState(false);
                                startActivity(new Intent(LoginActivity.this, HomeActivity.class));
                                finish();
                            });
                        } else if (user != null) {
                            FirebaseAuth.getInstance().signOut();
                            setLoadingState(false);
                            Toast.makeText(LoginActivity.this, "Please verify your email address.", Toast.LENGTH_LONG).show();
                            user.sendEmailVerification();
                        } else {
                            setLoadingState(false);
                            Toast.makeText(LoginActivity.this, "Login failed.", Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        setLoadingState(false);
                        Toast.makeText(LoginActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                    }

                    @Override public void onUsernameTaken() {}
                    @Override public void onEmailTaken() {}
                });
            }
        });

        tvForgot.setOnClickListener(v -> { if (!isNavigating) startActivity(new Intent(this, ForgotPasswordActivity.class)); });
        tvSignUp.setOnClickListener(v -> { if (!isNavigating) startActivity(new Intent(this, SignUpActivity.class)); });
    }

    private void setLoadingState(boolean loading) {
        if (loadingOverlay != null) loadingOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (btnLogin != null) btnLogin.setEnabled(!loading);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
    }
}
