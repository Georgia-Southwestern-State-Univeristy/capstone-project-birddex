package com.example.birddex;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class SettingsActivity extends AppCompatActivity {

    private TextView tvUserEmail;
    private SwitchCompat switchNotifications;
    private Button btnLogout;

    private final SettingsApi settingsApi = new SettingsApi();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        tvUserEmail = findViewById(R.id.tvUserEmail);
        switchNotifications = findViewById(R.id.switchNotifications);
        btnLogout = findViewById(R.id.btnLogout);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            // No session -> go back to Welcome
            goToWelcomeAndClear();
            return;
        }

        tvUserEmail.setText(user.getEmail() != null ? user.getEmail() : "(no email)");

        // Load settings from Firestore
        settingsApi.getSettings(user.getUid(), new SettingsApi.SettingsCallback() {
            @Override
            public void onSuccess(UserSettings settings) {
                // Avoid triggering listener while setting initial value
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
                // default false already
                switchNotifications.setChecked(false);
            }
        });

        btnLogout.setOnClickListener(v -> {
            FirebaseAuth.getInstance().signOut();
            goToWelcomeAndClear();
        });
    }

    private void goToWelcomeAndClear() {
        Intent intent = new Intent(SettingsActivity.this, WelcomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
