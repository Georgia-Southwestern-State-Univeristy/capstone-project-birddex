package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class LoginActivity extends AppCompatActivity {

    private FirebaseManager firebaseManager;
    private SignInUpValidator signINupValidator;

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
        signINupValidator = new SignInUpValidator();

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
                        if (user == null) {
                            setLoadingState(false);
                            MessagePopupHelper.show(LoginActivity.this, "Login failed.");
                            return;
                        }

                        user.reload().addOnCompleteListener(reloadTask -> {
                            FirebaseUser refreshedUser = FirebaseAuth.getInstance().getCurrentUser();

                            if (reloadTask.isSuccessful() && refreshedUser != null && refreshedUser.isEmailVerified()) {
                                continueVerifiedLogin(refreshedUser);
                                return;
                            }

                            if (refreshedUser == null) {
                                setLoadingState(false);
                                MessagePopupHelper.show(LoginActivity.this, "Login failed.");
                                return;
                            }

                            refreshedUser.sendEmailVerification().addOnCompleteListener(emailTask -> {
                                FirebaseAuth.getInstance().signOut();
                                setLoadingState(false);

                                if (emailTask.isSuccessful()) {
                                    showPopup(
                                            "Email Not Verified",
                                            "A new verification link has been sent to your email. Please check your inbox and spam folder before trying to log in again."
                                    );
                                    return;
                                }

                                Exception e = emailTask.getException();
                                String message;

                                if (e instanceof FirebaseTooManyRequestsException) {
                                    message = "Too many verification emails were requested in a short time. Please wait a little and try again.";
                                } else if (e instanceof FirebaseNetworkException) {
                                    message = "BirdDex could not resend the verification email because of a network issue. Check your connection and try again.";
                                } else {
                                    String firebaseMessage = e != null ? e.getMessage() : null;
                                    if (firebaseMessage == null || firebaseMessage.trim().isEmpty()) {
                                        firebaseMessage = "BirdDex could not resend a new verification link right now.";
                                    }
                                    message = firebaseMessage;
                                }

                                showPopup("Email Not Verified", message);
                            });
                        });
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        setLoadingState(false);
                        MessagePopupHelper.show(LoginActivity.this, errorMessage);
                    }

                    @Override
                    public void onUsernameTaken() {}

                    @Override
                    public void onEmailTaken() {}
                });
            }
        });

        tvForgot.setOnClickListener(v -> {
            if (!isNavigating) {
                startActivity(new Intent(this, ForgotPasswordActivity.class));
            }
        });

        tvSignUp.setOnClickListener(v -> {
            if (!isNavigating) {
                startActivity(new Intent(this, SignUpActivity.class));
            }
        });
    }

    private void continueVerifiedLogin(FirebaseUser user) {
        if (isNavigating) return;
        isNavigating = true;

        SessionManager sessionManager = new SessionManager(LoginActivity.this);
        String sessionId = sessionManager.createSession(user.getUid());

        firebaseManager.updateSessionId(user.getUid(), sessionId, task -> {
            setLoadingState(false);

            if (task.isSuccessful()) {
                // Warm up the BirdDex model API as soon as the user logs in.
                BirdDexApiWarmupHelper.maybeWarmup(LoginActivity.this, "login_success");

                startActivity(new Intent(LoginActivity.this, HomeActivity.class));
                finish();
            } else {
                FirebaseAuth.getInstance().signOut();
                sessionManager.clearSession(user.getUid());
                isNavigating = false;

                String msg = (task.getException() != null)
                        ? task.getException().getMessage()
                        : "Failed to start session.";

                MessagePopupHelper.showBrief(
                        LoginActivity.this,
                        "Login failed: " + msg
                );
            }
        });
    }

    private void showPopup(String title, String message) {
        if (isFinishing() || isDestroyed()) return;

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void setLoadingState(boolean loading) {
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        if (btnLogin != null) {
            btnLogin.setEnabled(!loading);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(View.GONE);
        }
    }
}