package com.birddex.app;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class NotificationsSettingsActivity extends AppCompatActivity {

    private SwitchCompat switchNotifications, switchReplies;
    private Spinner spinnerCooldown;
    private ImageView btnBack;
    private final SettingsApi settingsApi = new SettingsApi();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notifications_settings);

        switchNotifications = findViewById(R.id.switchNotifications);
        switchReplies = findViewById(R.id.switchReplies);
        spinnerCooldown = findViewById(R.id.spinnerCooldown);
        btnBack = findViewById(R.id.btnBack);

        btnBack.setOnClickListener(v -> finish());

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }

        setupCooldownSpinner();
        loadSettings(user.getUid());
    }

    private void setupCooldownSpinner() {
        String[] options = {"Off", "2 Hours", "6 Hours"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCooldown.setAdapter(adapter);
    }

    private void loadSettings(String uid) {
        settingsApi.getSettings(uid, new SettingsApi.SettingsCallback() {
            @Override
            public void onSuccess(UserSettings settings) {
                // Set initial states without triggering listeners
                switchNotifications.setOnCheckedChangeListener(null);
                switchNotifications.setChecked(settings.notificationsEnabled);
                
                switchReplies.setOnCheckedChangeListener(null);
                switchReplies.setChecked(settings.repliesEnabled);

                int selection = 0;
                if (settings.notificationCooldownHours == 2) selection = 1;
                else if (settings.notificationCooldownHours == 6) selection = 2;
                spinnerCooldown.setSelection(selection);

                // Re-attach listeners
                setupListeners(uid);
            }

            @Override
            public void onFailure(Exception e, String message) {
                // Handle failure
            }
        });
    }

    private void setupListeners(String uid) {
        switchNotifications.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settingsApi.setNotificationsEnabled(uid, isChecked, null);
        });

        switchReplies.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settingsApi.setRepliesEnabled(uid, isChecked, null);
        });

        spinnerCooldown.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int hours = 0;
                if (position == 1) hours = 2;
                else if (position == 2) hours = 6;
                settingsApi.setNotificationCooldownHours(uid, hours, null);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
}
