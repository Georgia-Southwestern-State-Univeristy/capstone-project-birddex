package com.birddex.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException;
import com.google.firebase.auth.FirebaseUser;

import java.util.Map;

/**
 * SettingsActivity handles user settings and account management.
 * Fixed Race Conditions:
 * 1. Action Spam: Added isNavigating guard to prevent multiple activity/dialog launches.
 *
 * This activity acts as the main hub for user preferences, account security,
 * and support interactions like bug reporting and moderation history.
 */
public class SettingsActivity extends AppCompatActivity {

    private TextView tvUserEmail, tvUserName, tvIdentificationsRemaining;
    private Button btnLogout, btnUpdateEmail, btnChangePassword, btnNotifications, btnDeleteAccount, btnReportBug, btnModerationHistory, btnModeratorDashboard;
    private MaterialSwitch switchGraphicContent;
    private ImageView btnBack;

    private FirebaseManager firebaseManager;
    private SessionManager sessionManager;
    private SharedPreferences sharedPreferences;

    private static final String PREFS_NAME = "BirdDexPrefs";
    private static final String KEY_GRAPHIC_CONTENT = "show_graphic_content";

    private boolean isNavigating = false;

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     */
    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Bind or inflate the UI pieces this method needs before it can update the screen.
        btnBack = findViewById(R.id.btnBack);
        tvUserEmail = findViewById(R.id.tvUserEmail);
        tvUserName = findViewById(R.id.tvUserName);
        tvIdentificationsRemaining = findViewById(R.id.tvIdentificationsRemaining);
        btnNotifications = findViewById(R.id.btnNotifications);
        btnReportBug = findViewById(R.id.btnReportBug);
        btnModerationHistory = findViewById(R.id.btnModerationHistory);
        btnModeratorDashboard = findViewById(R.id.btnModeratorDashboard);
        btnLogout = findViewById(R.id.btnLogout);
        btnUpdateEmail = findViewById(R.id.btnUpdateEmail);
        btnChangePassword = findViewById(R.id.btnChangePassword);
        btnDeleteAccount = findViewById(R.id.btnDeleteAccount);
        switchGraphicContent = findViewById(R.id.switchGraphicContent);

        // Set initial switch state for content filtering.
        switchGraphicContent.setChecked(sharedPreferences.getBoolean(KEY_GRAPHIC_CONTENT, false));

        // Set up the Firebase layer that supplies/stores this feature's data.
        firebaseManager = new FirebaseManager(this);
        sessionManager = new SessionManager(this);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            goToWelcomeAndClear();
            return;
        }

        // Reload user to ensure state is fresh (e.g. email verification status).
        user.reload().addOnCompleteListener(task -> {
            loadUserProfile(user);
            fetchIdentificationsRemaining();
            checkModeratorRole(user);
        });

        btnBack.setOnClickListener(v -> finish());

        // Attach the user interaction that should run when the Notifications button is tapped.
        btnNotifications.setOnClickListener(v -> {
            if (isNavigating) return;
            isNavigating = true;
            startActivity(new Intent(this, NotificationsSettingsActivity.class));
        });

        // Open the bug reporting screen.
        btnReportBug.setOnClickListener(v -> {
            if (isNavigating) return;
            isNavigating = true;
            startActivity(new Intent(this, ReportBugActivity.class));
        });

        // View the user's moderation and appeal history.
        btnModerationHistory.setOnClickListener(v -> {
            if (isNavigating) return;
            isNavigating = true;
            startActivity(new Intent(this, ModerationHistoryActivity.class));
        });

        // Open the moderator dashboard if the user has staff privileges.
        btnModeratorDashboard.setOnClickListener(v -> {
            if (isNavigating) return;
            isNavigating = true;
            startActivity(new Intent(this, ModeratorActivity.class));
        });

        // Sign out the user and clear session state.
        btnLogout.setOnClickListener(v -> {
            if (isNavigating) return;
            isNavigating = true;
            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
            String uid = (currentUser != null) ? currentUser.getUid() : null;

            FirebaseAuth.getInstance().signOut();
            if (uid != null) {
                sessionManager.clearSession(uid);
            }
            goToWelcomeAndClear();
        });

        btnUpdateEmail.setOnClickListener(v -> promptForNewEmail());
        tvUserEmail.setOnClickListener(v -> promptForNewEmail());

        // Send a password reset link to the current user's email.
        btnChangePassword.setOnClickListener(v -> {
            initiatePasswordReset();
            Toast.makeText(SettingsActivity.this, "Please check your email to update your password.", Toast.LENGTH_LONG).show();
        });

        btnDeleteAccount.setOnClickListener(v -> showDeleteAccountConfirmation());

        // Persist graphic content preference toggle.
        switchGraphicContent.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_GRAPHIC_CONTENT, isChecked).apply();
        });
    }

    /**
     * Checks if the user has staff roles (admin, moderator, staff) and shows the dashboard button.
     */
    private void checkModeratorRole(FirebaseUser user) {
        user.getIdToken(false).addOnSuccessListener(result -> {
            Map<String, Object> claims = result.getClaims();
            boolean isStaff = Boolean.TRUE.equals(claims.get("admin")) ||
                    Boolean.TRUE.equals(claims.get("moderator")) ||
                    Boolean.TRUE.equals(claims.get("staff"));

            if (isStaff) {
                btnModeratorDashboard.setVisibility(View.VISIBLE);
            }
        });
    }

    /**
     * Runs when the screen returns to the foreground, so it often refreshes UI state or restarts
     * listeners.
     */
    @Override
    protected void onResume() {
        super.onResume();
        isNavigating = false;

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            loadUserProfile(user);
            fetchIdentificationsRemaining();
        }
    }

    /**
     * Pulls the remaining identification quota from Firestore and shows it on the Settings screen.
     */
    private void fetchIdentificationsRemaining() {
        firebaseManager.getOpenAiRequestsRemaining(new FirebaseManager.OpenAiRequestLimitListener() {
            @Override
            public void onCheckComplete(boolean hasRequestsRemaining, int remaining, java.util.Date openAiCooldownResetTimestamp) {
                tvIdentificationsRemaining.setText("Identifications left: " + remaining);
            }

            @Override
            public void onFailure(String errorMessage) {
                tvIdentificationsRemaining.setText("Identifications left: --");
            }
        });
    }

    /**
     * Pulls data from Firebase and prepares it for the UI.
     * It syncs the Auth email with the Firestore profile if they are out of sync.
     */
    private void loadUserProfile(FirebaseUser user) {
        firebaseManager.getUserProfile(user.getUid(), task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                User userProfile = task.getResult().toObject(User.class);
                if (userProfile != null) {
                    tvUserName.setText(userProfile.getUsername());
                    String currentAuthEmail = user.getEmail();
                    if (currentAuthEmail != null && !currentAuthEmail.equals(userProfile.getEmail())) {
                        userProfile.setEmail(currentAuthEmail);
                        firebaseManager.updateUserProfile(userProfile, new FirebaseManager.AuthListener() {
                            @Override public void onSuccess(FirebaseUser u) {}
                            @Override public void onFailure(String errorMessage) {}
                            @Override public void onUsernameTaken() {}
                            @Override public void onEmailTaken() {}
                        });
                    }
                    tvUserEmail.setText(currentAuthEmail != null ? currentAuthEmail : userProfile.getEmail());
                }
            } else {
                tvUserEmail.setText(user.getEmail() != null ? user.getEmail() : "(no email)");
            }
        });
    }

    /**
     * Navigates back to the Welcome screen and clears the activity task stack.
     */
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
        if (isNavigating) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Update Email");
        builder.setMessage("Enter your new email address. A verification link will be sent to the new email.");

        final EditText emailInput = new EditText(this);
        emailInput.setHint("New Email");
        emailInput.setInputType(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        int margin = (int) (16 * getResources().getDisplayMetrics().density);
        params.setMargins(margin, 0, margin, 0);
        emailInput.setLayoutParams(params);
        layout.addView(emailInput);
        builder.setView(layout);

        builder.setPositiveButton("Update", (dialog, which) -> {
            String newEmail = emailInput.getText().toString().trim();
            if (!newEmail.isEmpty()) attemptUpdateEmail(newEmail);
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    /**
     * Attempts to update the user's email in Firebase Auth.
     * Handles the case where re-authentication is required for sensitive operations.
     */
    private void attemptUpdateEmail(String newEmail) {
        firebaseManager.updateUserEmail(newEmail, authUpdateTask -> {
            if (authUpdateTask.isSuccessful()) {
                new AlertDialog.Builder(this)
                        .setTitle("Verification Sent")
                        .setMessage("A verification link has been sent to " + newEmail + ". Please click it to finalize the update.")
                        .setPositiveButton("OK", null)
                        .show();
            } else {
                Exception exception = authUpdateTask.getException();
                if (exception instanceof FirebaseAuthRecentLoginRequiredException) {
                    promptForReauthenticationAndRetry(newEmail);
                } else {
                    Toast.makeText(SettingsActivity.this, "Failed: " + (exception != null ? exception.getMessage() : "Unknown error"), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /**
     * Displays a re-authentication dialog (password entry) before retrying the email update.
     */
    private void promptForReauthenticationAndRetry(String newEmail) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Re-authenticate");
        final EditText passwordInput = new EditText(this);
        passwordInput.setHint("Password");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.setView(passwordInput);
        builder.setPositiveButton("Verify", (dialog, which) -> {
            String password = passwordInput.getText().toString().trim();
            if (!password.isEmpty()) reauthenticateUserAndRetryUpdateEmail(password, newEmail);
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    /**
     * Re-authenticates the user with their current password and retries the email update.
     */
    private void reauthenticateUserAndRetryUpdateEmail(String currentPassword, String newEmail) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        AuthCredential credential = EmailAuthProvider.getCredential(user.getEmail(), currentPassword);
        user.reauthenticate(credential).addOnCompleteListener(reauthTask -> {
            if (reauthTask.isSuccessful()) attemptUpdateEmail(newEmail);
            else Toast.makeText(SettingsActivity.this, "Re-authentication failed.", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Sends a password reset email to the current user's email address.
     */
    private void initiatePasswordReset() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null && user.getEmail() != null) {
            firebaseManager.sendPasswordResetEmail(user.getEmail(), task -> {
                if (task.isSuccessful()) Toast.makeText(SettingsActivity.this, "Password reset email sent.", Toast.LENGTH_LONG).show();
            });
        }
    }

    /**
     * Shows a confirmation dialog before proceeding with account deletion.
     */
    private void showDeleteAccountConfirmation() {
        if (isNavigating) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_delete_account_title)
                .setMessage(R.string.dialog_delete_account_message)
                .setPositiveButton("Delete", (dialog, which) -> promptForReauthenticationAndDelete())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Prompts for password entry before deleting the account.
     */
    private void promptForReauthenticationAndDelete() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.dialog_confirm_deletion_title);
        final EditText passwordInput = new EditText(this);
        passwordInput.setHint("Password");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.setView(passwordInput);
        builder.setPositiveButton("Confirm Delete", (dialog, which) -> {
            String password = passwordInput.getText().toString().trim();
            if (!password.isEmpty()) reauthenticateAndDeleteAccount(password);
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    /**
     * Re-authenticates the user and starts the permanent account deletion process.
     */
    private void reauthenticateAndDeleteAccount(String password) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        AuthCredential credential = EmailAuthProvider.getCredential(user.getEmail(), password);
        user.reauthenticate(credential).addOnCompleteListener(reauthTask -> {
            if (reauthTask.isSuccessful()) processAccountDeletion(user);
            else Toast.makeText(SettingsActivity.this, "Re-authentication failed.", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Orchestrates the deletion of the user's data from Firestore and their Auth record.
     */
    private void processAccountDeletion(FirebaseUser user) {
        if (isNavigating) return;
        isNavigating = true;
        firebaseManager.archiveAndDeleteUser(task -> {
            if (task.isSuccessful()) {
                user.delete().addOnCompleteListener(deleteTask -> {
                    if (deleteTask.isSuccessful()) {
                        sessionManager.clearSession(user.getUid());
                        goToWelcomeAndClear();
                    } else {
                        isNavigating = false;
                    }
                });
            } else {
                isNavigating = false;
            }
        });
    }
}