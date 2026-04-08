package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseUser;

/**
 * SignUpActivity handles the user registration process.
 * Fixes: Added isNavigating guard to prevent redundant activity launches.
 */
/**
 * SignUpActivity: Activity class for one BirdDex screen. It owns screen setup, user actions, and navigation for this part of the app.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class SignUpActivity extends AppCompatActivity {

    private FirebaseManager firebaseManager;
    private SignInUpValidator signINupValidator;
    private View loadingOverlay;
    private Button btnSignUp;
    private boolean isNavigating = false;

    private EditText usernameEditText;
    private EditText emailEditText;
    private EditText passwordEditText;

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
        setContentView(R.layout.activity_sign_up);

        // Set up or query the Firebase layer that supplies/stores this feature's data.
        firebaseManager = new FirebaseManager(this);
        signINupValidator = new SignInUpValidator();
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        loadingOverlay = findViewById(R.id.loadingOverlay);

        usernameEditText = findViewById(R.id.etUsername);
        emailEditText = findViewById(R.id.etEmail);
        passwordEditText = findViewById(R.id.etPassword);

        btnSignUp = findViewById(R.id.btnSignUp);
        TextView tvAlready = findViewById(R.id.tvAlready);

        // Attach the user interaction that should run when this control is tapped.
        btnSignUp.setOnClickListener(v -> {
            if (isNavigating) return;
            if (signINupValidator.validateSignUpForm(usernameEditText, emailEditText, passwordEditText)) {
                String username = usernameEditText.getText().toString().trim();
                String email = emailEditText.getText().toString().trim();
                String password = passwordEditText.getText().toString();

                if (ContentFilter.containsInappropriateContent(username)) {
                    firebaseManager.logFilteredContentAttempt("username_signup_client_block", "username", username, null, null);
                    usernameEditText.setError("Inappropriate username.");
                    return;
                }

                setLoadingState(true);
                firebaseManager.createAccount(username, email, password, new FirebaseManager.AuthListener() {
                    @Override
                    public void onSuccess(FirebaseUser user) {
                        if (isNavigating) return;
                        isNavigating = true;

                        setLoadingState(false);
                        // Give the user immediate feedback about the result of this action.
                        MessagePopupHelper.show(SignUpActivity.this, "Sign up successful. Please verify your email.");
                        startActivity(new Intent(SignUpActivity.this, SignUpCompleteActivity.class));
                        finish();
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        setLoadingState(false);
                        MessagePopupHelper.show(SignUpActivity.this, errorMessage);
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

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    private void setLoadingState(boolean loading) {
        if (loadingOverlay != null) loadingOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (btnSignUp != null) btnSignUp.setEnabled(!loading);
    }

    /**
     * Runs when the screen is leaving the foreground, so it is used to pause work or save
     * transient state.
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (loadingOverlay != null && loadingOverlay.getVisibility() == View.VISIBLE) {
            loadingOverlay.setVisibility(View.GONE);
        }
    }
}
