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
/**
 * LoginActivity: Activity class for one BirdDex screen. It owns screen setup, user actions, and navigation for this part of the app.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class LoginActivity extends AppCompatActivity {

    private FirebaseManager firebaseManager;
    private sign_IN_upValidator signINupValidator;

    private EditText emailEditText;
    private EditText passwordEditText;
    private Button btnLogin;
    private View loadingOverlay;
    private boolean isNavigating = false;

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
        setContentView(R.layout.activity_login);

        // Set up or query the Firebase layer that supplies/stores this feature's data.
        firebaseManager = new FirebaseManager(this);
        signINupValidator = new sign_IN_upValidator();

        // Bind or inflate the UI pieces this method needs before it can update the screen.
        emailEditText = findViewById(R.id.etEmail);
        passwordEditText = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        loadingOverlay = findViewById(R.id.loadingOverlay);

        TextView tvForgot = findViewById(R.id.tvForgot);
        TextView tvSignUp = findViewById(R.id.tvSignUp);

        // Attach the user interaction that should run when this control is tapped.
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
                                // Move into the next screen and pass the identifiers/data that screen needs.
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

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    private void setLoadingState(boolean loading) {
        if (loadingOverlay != null) loadingOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (btnLogin != null) btnLogin.setEnabled(!loading);
    }

    /**
     * Runs when the screen is leaving the foreground, so it is used to pause work or save
     * transient state.
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
    }
}
