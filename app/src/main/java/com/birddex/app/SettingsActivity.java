package com.birddex.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException;
import com.google.firebase.auth.FirebaseUser;

import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class SettingsActivity extends AppCompatActivity {

    private TextView tvUserEmail, tvUserName, tvOpenAiRequestsRemaining, tvPfpChangesRemaining;
    private SwitchCompat switchNotifications;
    private Button btnLogout, btnUpdateEmail, btnChangePassword, btnChangePfp;

    private final SettingsApi settingsApi = new SettingsApi();
    private FirebaseManager firebaseManager;

    private static final String TAG = "SettingsActivity";
    private static final long COOLDOWN_PERIOD_MS = 24 * 60 * 60 * 1000;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Initialize UI components
        tvUserEmail = findViewById(R.id.tvUserEmail);
        tvUserName = findViewById(R.id.tvUserName);
        switchNotifications = findViewById(R.id.switchNotifications);
        btnLogout = findViewById(R.id.btnLogout);
        btnUpdateEmail = findViewById(R.id.btnUpdateEmail);
        btnChangePassword = findViewById(R.id.btnChangePassword);
        btnChangePfp = findViewById(R.id.btnChangePfp);
        tvOpenAiRequestsRemaining = findViewById(R.id.tvOpenAiRequestsRemaining);
        tvPfpChangesRemaining = findViewById(R.id.tvPfpChangesRemaining);

        firebaseManager = new FirebaseManager(this);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            goToWelcomeAndClear();
            return;
        }

        // NEW: Reload user to check for verification status and sync Firestore email
        user.reload().addOnCompleteListener(task -> {
            loadUserProfile(user);
        });

        // Initialize settings from API
        settingsApi.getSettings(user.getUid(), new SettingsApi.SettingsCallback() {
            @Override
            public void onSuccess(UserSettings settings) {
                switchNotifications.setOnCheckedChangeListener(null);
                switchNotifications.setChecked(settings.notificationsEnabled);

                switchNotifications.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    settingsApi.setNotificationsEnabled(user.getUid(), isChecked, new SettingsApi.SettingsCallback() {
                        @Override public void onSuccess(UserSettings s) { /* ok */ }
                        @Override public void onFailure(Exception e, String message) { /* optional: toast */ }
                    });
                });
            }

            @Override
            public void onFailure(Exception e, String message) {
                switchNotifications.setChecked(false);
            }
        });

        // Set up click listeners for all buttons
        btnLogout.setOnClickListener(v -> {
            FirebaseAuth.getInstance().signOut();
            goToWelcomeAndClear();
        });


        btnUpdateEmail.setOnClickListener(v -> {
            promptForNewEmail();
        });

        tvUserEmail.setOnClickListener(v -> {
            promptForNewEmail();
        });

        btnChangePassword.setOnClickListener(v -> {
            initiatePasswordReset();
            Toast.makeText(SettingsActivity.this, "Please check your email to update your password.", Toast.LENGTH_LONG).show();
        });

        btnChangePfp.setOnClickListener(v -> {
            // TODO: Handle profile picture change
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        fetchOpenAiRequestsRemaining();
        fetchPfpChangesRemaining();
    }

    /**
     * NEW: Centralized method to load user profile and sync Auth email with Firestore.
     */
    private void loadUserProfile(FirebaseUser user) {
        firebaseManager.getUserProfile(user.getUid(), task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                User userProfile = task.getResult().toObject(User.class);
                if (userProfile != null) {
                    tvUserName.setText(userProfile.getUsername());

                    // Sync Firestore email if the Auth email has been updated and verified
                    String currentAuthEmail = user.getEmail();
                    if (currentAuthEmail != null && !currentAuthEmail.equals(userProfile.getEmail())) {
                        Log.d(TAG, "Email mismatch detected. Syncing Firestore with Auth email.");
                        userProfile.setEmail(currentAuthEmail);
                        firebaseManager.updateUserProfile(userProfile, new FirebaseManager.AuthListener() {
                            @Override public void onSuccess(FirebaseUser user) { Log.d(TAG, "Firestore email synced successfully."); }
                            @Override public void onFailure(String errorMessage) { Log.e(TAG, "Failed to sync email to Firestore: " + errorMessage); }
                            @Override public void onUsernameTaken() {}

                            @Override
                            public void onEmailTaken() {

                            }
                        });
                    }
                    tvUserEmail.setText(currentAuthEmail != null ? currentAuthEmail : userProfile.getEmail());
                }
            } else {
                Log.e(TAG, "Failed to fetch user profile", task.getException());
                tvUserEmail.setText(user.getEmail() != null ? user.getEmail() : "(no email)");
            }
        });
    }

    private void goToWelcomeAndClear() {
        Intent intent = new Intent(SettingsActivity.this, WelcomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    /**
     * Displays a dialog to prompt the user for a new email address.
     */
    private void promptForNewEmail() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Update Email");
        builder.setMessage("Enter your new email address. A verification link will be sent to the new email.");

        final EditText emailInput = new EditText(this);
        emailInput.setHint("New Email");
        emailInput.setInputType(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        int margin = (int) (16 * getResources().getDisplayMetrics().density);
        params.setMargins(margin, 0, margin, 0);
        emailInput.setLayoutParams(params);
        layout.addView(emailInput);
        builder.setView(layout);

        builder.setPositiveButton("Update", (dialog, which) -> {
            String newEmail = emailInput.getText().toString().trim();
            if (!newEmail.isEmpty()) {
                attemptUpdateEmail(newEmail);
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    /**
     * CHANGED: Initiates the email update flow using verification email.
     */

    private void attemptUpdateEmail(String newEmail) {
        firebaseManager.updateUserEmail(newEmail, authUpdateTask -> {
            if (authUpdateTask.isSuccessful()) {
                // Email update finalized only AFTER user verifies. No immediate Firestore update here.
                new AlertDialog.Builder(this)
                        .setTitle("Verification Sent")
                        .setMessage("A verification link has been sent to " + newEmail + ". Please click it to finalize the update. Your profile will sync once verified.")
                        .setPositiveButton("OK", null)
                        .show();
            } else {
                Exception exception = authUpdateTask.getException();
                if (exception instanceof FirebaseAuthRecentLoginRequiredException) {
                    promptForReauthenticationAndRetry(newEmail);
                } else {
                    Log.e(TAG, "Failed to initiate email update", exception);
                    Toast.makeText(SettingsActivity.this, "Failed to initiate update: " + exception.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    //UI Layer Input
    private void promptForReauthenticationAndRetry(String newEmail) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Re-authenticate");
        builder.setMessage("Please re-enter your current password to continue with the email update.");

        final EditText passwordInput = new EditText(this);
        passwordInput.setHint("Password");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        int margin = (int) (16 * getResources().getDisplayMetrics().density);
        params.setMargins(margin, 0, margin, 0);
        passwordInput.setLayoutParams(params);
        layout.addView(passwordInput);
        builder.setView(layout);

        builder.setPositiveButton("Verify", (dialog, which) -> {
            String password = passwordInput.getText().toString().trim();
            if (!password.isEmpty()) {
                reauthenticateUserAndRetryUpdateEmail(password, newEmail);
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    //Logic & Security Layer
    private void reauthenticateUserAndRetryUpdateEmail(String currentPassword, String newEmail) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        AuthCredential credential = EmailAuthProvider.getCredential(user.getEmail(), currentPassword);
        user.reauthenticate(credential)
                .addOnCompleteListener(reauthTask -> {
                    if (reauthTask.isSuccessful()) {
                        Log.d(TAG, "Re-authentication successful. Retrying email update.");
                        attemptUpdateEmail(newEmail);
                    } else {
                        Toast.makeText(SettingsActivity.this, "Re-authentication failed.", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void initiatePasswordReset() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null && user.getEmail() != null) {
            firebaseManager.sendPasswordResetEmail(user.getEmail(), task -> {
                if(task.isSuccessful()) {
                    Toast.makeText(SettingsActivity.this, "Password reset email sent.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(SettingsActivity.this, "Failed to send password reset email.", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void fetchOpenAiRequestsRemaining() {
        firebaseManager.getOpenAiRequestsRemaining(new FirebaseManager.OpenAiRequestLimitListener() {
            @Override
            public void onCheckComplete(boolean hasRequestsRemaining, int remaining, Date cooldownResetTimestamp) {
                updateOpenAiRequestsRemainingUI(remaining, cooldownResetTimestamp);
            }
            @Override
            public void onFailure(String errorMessage) {}
        });
    }

    private void fetchPfpChangesRemaining() {
        firebaseManager.getPfpChangesRemaining(new FirebaseManager.PfpChangeLimitListener() {
            @Override
            public void onSuccess(int pfpChangesToday, Date cooldownResetTimestamp) {
                updatePfpChangesRemainingUI(pfpChangesToday, cooldownResetTimestamp);
            }
            @Override
            public void onFailure(String errorMessage) {}
            @Override
            public void onLimitExceeded() {}
        });
    }

    private void updateOpenAiRequestsRemainingUI(int remaining, Date cooldownResetTimestamp) {
        if (remaining <= 0 && cooldownResetTimestamp != null) {
            long timeLeftMs = (cooldownResetTimestamp.getTime() + COOLDOWN_PERIOD_MS) - System.currentTimeMillis();
            if (timeLeftMs > 0) {
                long hours = TimeUnit.MILLISECONDS.toHours(timeLeftMs);
                long minutes = TimeUnit.MILLISECONDS.toMinutes(timeLeftMs) % 60;
                tvOpenAiRequestsRemaining.setText(String.format(Locale.getDefault(), "AI Requests: Refreshes in %dh %dm", hours, minutes));
                return;
            }
        }
        tvOpenAiRequestsRemaining.setText("AI Requests: " + remaining);
    }

    private void updatePfpChangesRemainingUI(int remaining, Date cooldownResetTimestamp) {
        if (remaining <= 0 && cooldownResetTimestamp != null) {
            long timeLeftMs = (cooldownResetTimestamp.getTime() + COOLDOWN_PERIOD_MS) - System.currentTimeMillis();
            if (timeLeftMs > 0) {
                long hours = TimeUnit.MILLISECONDS.toHours(timeLeftMs);
                long minutes = TimeUnit.MILLISECONDS.toMinutes(timeLeftMs) % 60;
                tvPfpChangesRemaining.setText(String.format(Locale.getDefault(), "PFP Changes: Refreshes in %dh %dm", hours, minutes));
                return;
            }
        }
        tvPfpChangesRemaining.setText("PFP Changes: " + remaining);
    }
}
