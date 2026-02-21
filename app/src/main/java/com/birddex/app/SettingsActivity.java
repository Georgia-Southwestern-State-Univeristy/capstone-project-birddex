package com.birddex.app;

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

import com.google.android.gms.tasks.OnCompleteListener; // Added import
import com.google.android.gms.tasks.Task; // Added import
import com.google.firebase.auth.AuthCredential; 
import com.google.firebase.auth.EmailAuthProvider; 
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException; 
import com.google.firebase.auth.FirebaseUser;

public class SettingsActivity extends AppCompatActivity {

    private TextView tvUserEmail;
    private SwitchCompat switchNotifications;
    private Button btnLogout;

    private final SettingsApi settingsApi = new SettingsApi();
    private FirebaseManager firebaseManager; 

    private static final String TAG = "SettingsActivity"; 

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        tvUserEmail = findViewById(R.id.tvUserEmail);
        switchNotifications = findViewById(R.id.switchNotifications);
        btnLogout = findViewById(R.id.btnLogout);

        firebaseManager = new FirebaseManager(this); 

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            goToWelcomeAndClear();
            return;
        }

        tvUserEmail.setText(user.getEmail() != null ? user.getEmail() : "(no email)");

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

        btnLogout.setOnClickListener(v -> {
            FirebaseAuth.getInstance().signOut();
            goToWelcomeAndClear();
        });

        // Example of how you would call these methods later from a UI element:
        // Button changeEmailButton = findViewById(R.id.btn_change_email);
        // changeEmailButton.setOnClickListener(v -> {
        //     String newEmail = "new.email@example.com"; // Get from an EditText
        //     attemptUpdateEmail(newEmail);
        // });

        // Button resetPasswordButton = findViewById(R.id.btn_reset_password);
        // resetPasswordButton.setOnClickListener(v -> {
        //     // Choose which reset method to call:
        //     // initiatePasswordReset(); 
        //     promptForPasswordResetEmail();
        // });
    }

    private void goToWelcomeAndClear() {
        Intent intent = new Intent(SettingsActivity.this, WelcomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    /**
     * Attempts to update the user's email. If re-authentication is required, it prompts the user.
     * @param newEmail The new email address to set.
     */
    private void attemptUpdateEmail(String newEmail) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "No user is currently logged in.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Call the updateUserEmail method from your FirebaseManager
        firebaseManager.updateUserEmail(newEmail, task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "User email address updated successfully.");
                Toast.makeText(SettingsActivity.this, "Email updated to " + newEmail, Toast.LENGTH_LONG).show();
                tvUserEmail.setText(newEmail); // Update displayed email
                // Optionally clear the input field if it was an EditText
                // etNewEmail.setText(""); 
            } else {
                Exception exception = task.getException();
                if (exception instanceof FirebaseAuthRecentLoginRequiredException) {
                    Log.w(TAG, "Re-authentication is required for email update.");
                    promptForReauthenticationAndRetry(newEmail);
                } else {
                    Log.e(TAG, "Failed to update email: " + exception.getMessage(), exception);
                    Toast.makeText(SettingsActivity.this, "Failed to update email: " + exception.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /**
     * Displays a dialog to prompt the user for their current password for re-authentication,
     * then retries the email update.
     * @param newEmail The new email address that was being set.
     */
    private void promptForReauthenticationAndRetry(String newEmail) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Re-authenticate to change email");
        builder.setMessage("For security reasons, please re-enter your current password.");

        // Set up the input for password
        final EditText passwordInput = new EditText(this);
        passwordInput.setHint("Current Password");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        // Add padding to the EditText
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        int margin = (int) (16 * getResources().getDisplayMetrics().density); // 16dp margin
        params.setMargins(margin, 0, margin, 0);
        passwordInput.setLayoutParams(params);
        layout.addView(passwordInput);
        builder.setView(layout);


        // Set up the buttons
        builder.setPositiveButton("Verify", (dialog, which) -> {
            String password = passwordInput.getText().toString().trim();
            if (password.isEmpty()) {
                Toast.makeText(SettingsActivity.this, "Password cannot be empty.", Toast.LENGTH_SHORT).show();
                return;
            }
            reauthenticateUserAndRetryUpdateEmail(password, newEmail);
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    /**
     * Re-authenticates the user with the provided password and, if successful, retries
     * the email update.
     * @param currentPassword The user's current password.
     * @param newEmail The new email address to set.
     */
    private void reauthenticateUserAndRetryUpdateEmail(String currentPassword, String newEmail) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(SettingsActivity.this, "No user is currently logged in.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Create a credential using the user's current email and the provided password
        AuthCredential credential = EmailAuthProvider.getCredential(user.getEmail(), currentPassword);

        user.reauthenticate(credential)
            .addOnCompleteListener(reauthTask -> {
                if (reauthTask.isSuccessful()) {
                    Log.d(TAG, "User re-authenticated successfully.");
                    // Now that the user is re-authenticated, retry the email update
                    firebaseManager.updateUserEmail(newEmail, updateTask -> {
                        if (updateTask.isSuccessful()) {
                            Log.d(TAG, "User email address updated successfully after re-auth.");
                            Toast.makeText(SettingsActivity.this, "Email updated to " + newEmail, Toast.LENGTH_LONG).show();
                            tvUserEmail.setText(newEmail); // Update displayed email
                            // Optionally clear the input field if it was an EditText
                            // etNewEmail.setText(""); 
                        } else {
                            Log.e(TAG, "Failed to update email after re-auth: " + updateTask.getException().getMessage(), updateTask.getException());
                            Toast.makeText(SettingsActivity.this, "Failed to update email: " + updateTask.getException().getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                } else {
                    Log.e(TAG, "Re-authentication failed: " + reauthTask.getException().getMessage(), reauthTask.getException());
                    Toast.makeText(SettingsActivity.this, "Re-authentication failed: Invalid password.", Toast.LENGTH_LONG).show();
                }
            });
    }

    /**
     * Initiates the password reset process by sending a password reset email.
     * This method can be called directly, or after prompting the user for an email address.
     */
    private void initiatePasswordReset() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String emailToReset = null;

        if (user != null && user.getEmail() != null) {
            // If user is logged in and has an email, suggest that email for reset
            emailToReset = user.getEmail();
        } else {
            // If no user or no email, you would need to prompt the user to enter their email.
            // For now, we will show an error, but you'd replace this with a dialog.
            Toast.makeText(this, "Please provide an email to reset the password.", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // If an email is available (either from current user or user input),
        // proceed to send the password reset email.
        if (emailToReset != null) {
            final String finalEmailToReset = emailToReset; // Create a final copy
            firebaseManager.sendPasswordResetEmail(finalEmailToReset, new OnCompleteListener<Void>() { // Corrected this line
                @Override
                public void onComplete(Task<Void> task) {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "Password reset email sent to " + finalEmailToReset);
                        Toast.makeText(SettingsActivity.this, "Password reset email sent to " + finalEmailToReset, Toast.LENGTH_LONG).show();
                    } else {
                        Log.e(TAG, "Failed to send password reset email: " + task.getException().getMessage(), task.getException());
                        Toast.makeText(SettingsActivity.this, "Failed to send password reset email: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });
        }
    }

    /**
     * This is an example of how you might prompt for an email for password reset
     * if the current user's email is not available or if you want to allow resetting
     * for any email.
     */
    private void promptForPasswordResetEmail() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Password Reset");
        builder.setMessage("Enter your email to receive a password reset link.");

        final EditText emailInput = new EditText(this);
        emailInput.setHint("Email");
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

        builder.setPositiveButton("Send Reset Email", (dialog, which) -> {
            String email = emailInput.getText().toString().trim();
            if (email.isEmpty()) {
                Toast.makeText(SettingsActivity.this, "Email cannot be empty.", Toast.LENGTH_SHORT).show();
                return;
            }
            firebaseManager.sendPasswordResetEmail(email, new OnCompleteListener<Void>() { // Corrected this line
                @Override
                public void onComplete(Task<Void> task) {
                    if (task.isSuccessful()) {
                        Toast.makeText(SettingsActivity.this, "Password reset email sent to " + email, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(SettingsActivity.this, "Failed to send reset email: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }
}