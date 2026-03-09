package com.birddex.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
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

/**
 * SettingsActivity handles user settings and account management.
 * Fixed Race Conditions:
 * 1. Action Spam: Added isNavigating guard to prevent multiple activity/dialog launches.
 */
public class SettingsActivity extends AppCompatActivity {

    private TextView tvUserEmail, tvUserName;
    private Button btnLogout, btnUpdateEmail, btnChangePassword, btnNotifications, btnDeleteAccount;
    private MaterialSwitch switchGraphicContent;

    private FirebaseManager firebaseManager;
    private SessionManager sessionManager;
    private SharedPreferences sharedPreferences;

    private static final String TAG = "SettingsActivity";
    private static final String PREFS_NAME = "BirdDexPrefs";
    private static final String KEY_GRAPHIC_CONTENT = "show_graphic_content";

    private boolean isNavigating = false;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        tvUserEmail = findViewById(R.id.tvUserEmail);
        tvUserName = findViewById(R.id.tvUserName);
        btnNotifications = findViewById(R.id.btnNotifications);
        btnLogout = findViewById(R.id.btnLogout);
        btnUpdateEmail = findViewById(R.id.btnUpdateEmail);
        btnChangePassword = findViewById(R.id.btnChangePassword);
        btnDeleteAccount = findViewById(R.id.btnDeleteAccount);
        switchGraphicContent = findViewById(R.id.switchGraphicContent);

        switchGraphicContent.setChecked(sharedPreferences.getBoolean(KEY_GRAPHIC_CONTENT, false));

        firebaseManager = new FirebaseManager(this);
        sessionManager = new SessionManager(this);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            goToWelcomeAndClear();
            return;
        }

        user.reload().addOnCompleteListener(task -> {
            loadUserProfile(user);
        });

        btnNotifications.setOnClickListener(v -> {
            if (isNavigating) return;
            isNavigating = true;
            startActivity(new Intent(this, NotificationsSettingsActivity.class));
        });

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
        btnChangePassword.setOnClickListener(v -> {
            initiatePasswordReset();
            Toast.makeText(SettingsActivity.this, "Please check your email to update your password.", Toast.LENGTH_LONG).show();
        });

        btnDeleteAccount.setOnClickListener(v -> showDeleteAccountConfirmation());

        switchGraphicContent.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_GRAPHIC_CONTENT, isChecked).apply();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        isNavigating = false;
    }

    private void loadUserProfile(FirebaseUser user) {
        firebaseManager.getUserProfile(user.getUid(), task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                User userProfile = task.getResult().toObject(User.class);
                if (userProfile != null) {
                    tvUserName.setText(userProfile.getUsername());
                    String currentAuthEmail = user.getEmail();
                    if (currentAuthEmail != null && !currentAuthEmail.equals(userProfile.getEmail())) {
                        Log.d(TAG, "Email mismatch detected. Syncing Firestore with Auth email.");
                        userProfile.setEmail(currentAuthEmail);
                        firebaseManager.updateUserProfile(userProfile, new FirebaseManager.AuthListener() {
                            @Override public void onSuccess(FirebaseUser u) { Log.d(TAG, "Firestore email synced successfully."); }
                            @Override public void onFailure(String errorMessage) { Log.e(TAG, "Failed to sync email to Firestore: " + errorMessage); }
                            @Override public void onUsernameTaken() {}
                            @Override public void onEmailTaken() {}
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

    private void attemptUpdateEmail(String newEmail) {
        firebaseManager.updateUserEmail(newEmail, authUpdateTask -> {
            if (authUpdateTask.isSuccessful()) {
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

    private void promptForReauthenticationAndRetry(String newEmail) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Re-authenticate");
        builder.setMessage("Please re-enter your current password to continue with the email update.");

        final EditText passwordInput = new EditText(this);
        passwordInput.setHint("Password");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        int margin = (int) (16 * getResources().getDisplayMetrics().density);
        params.setMargins(margin, 0, margin, 0);
        passwordInput.setLayoutParams(params);
        layout.addView(passwordInput);
        builder.setView(layout);

        builder.setPositiveButton("Verify", (dialog, which) -> {
            String password = passwordInput.getText().toString().trim();
            if (!password.isEmpty()) reauthenticateUserAndRetryUpdateEmail(password, newEmail);
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

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
                if (task.isSuccessful()) {
                    Toast.makeText(SettingsActivity.this, "Password reset email sent.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(SettingsActivity.this, "Failed to send password reset email.", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void showDeleteAccountConfirmation() {
        if (isNavigating) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_delete_account_title)
                .setMessage(R.string.dialog_delete_account_message)
                .setPositiveButton("Delete", (dialog, which) -> promptForReauthenticationAndDelete())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptForReauthenticationAndDelete() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.dialog_confirm_deletion_title);
        builder.setMessage(R.string.dialog_confirm_deletion_message);

        final EditText passwordInput = new EditText(this);
        passwordInput.setHint("Password");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        int margin = (int) (16 * getResources().getDisplayMetrics().density);
        params.setMargins(margin, 0, margin, 0);
        passwordInput.setLayoutParams(params);
        layout.addView(passwordInput);
        builder.setView(layout);

        builder.setPositiveButton("Confirm Delete", (dialog, which) -> {
            String password = passwordInput.getText().toString().trim();
            if (!password.isEmpty()) reauthenticateAndDeleteAccount(password);
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void reauthenticateAndDeleteAccount(String password) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        AuthCredential credential = EmailAuthProvider.getCredential(user.getEmail(), password);
        user.reauthenticate(credential)
                .addOnCompleteListener(reauthTask -> {
                    if (reauthTask.isSuccessful()) processAccountDeletion(user);
                    else Toast.makeText(SettingsActivity.this, "Re-authentication failed. Incorrect password.", Toast.LENGTH_SHORT).show();
                });
    }

    private void processAccountDeletion(FirebaseUser user) {
        if (isNavigating) return;
        isNavigating = true;
        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setMessage("Archiving and deleting account... Please wait.")
                .setCancelable(false)
                .show();

        firebaseManager.archiveAndDeleteUser(task -> {
            if (task.isSuccessful()) {
                user.delete().addOnCompleteListener(deleteTask -> {
                    progressDialog.dismiss();
                    if (deleteTask.isSuccessful()) {
                        Toast.makeText(SettingsActivity.this, "Account deleted successfully.", Toast.LENGTH_SHORT).show();
                        sessionManager.clearSession(user.getUid());
                        goToWelcomeAndClear();
                    } else {
                        isNavigating = false;
                        Log.e(TAG, "Auth deletion failed", deleteTask.getException());
                        Toast.makeText(SettingsActivity.this, "Data archived, but failed to remove login.", Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                isNavigating = false;
                progressDialog.dismiss();
                Log.e(TAG, "Cloud Function Archive Error: ", task.getException());
                Toast.makeText(SettingsActivity.this, "Failed to archive account data.", Toast.LENGTH_LONG).show();
            }
        });
    }
}