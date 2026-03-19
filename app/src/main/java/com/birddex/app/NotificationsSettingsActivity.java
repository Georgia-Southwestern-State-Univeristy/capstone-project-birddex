package com.birddex.app;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * NotificationsSettingsActivity: Activity class for managing user notification preferences.
 */
public class NotificationsSettingsActivity extends AppCompatActivity {

    private static final String TAG = "NotificationsSettings";
    private SwitchCompat switchNotifications, switchReplies, switchTrackedBirds;
    private Spinner spinnerCooldown, spinnerTrackedCooldown, spinnerTrackedDistance;
    private ImageView btnBack;
    private final SettingsApi settingsApi = new SettingsApi();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarHelper.applyStandardNavBar(this);
        setContentView(R.layout.activity_notifications_settings);

        switchNotifications = findViewById(R.id.switchNotifications);
        switchReplies = findViewById(R.id.switchReplies);
        switchTrackedBirds = findViewById(R.id.switchTrackedBirds);
        spinnerCooldown = findViewById(R.id.spinnerCooldown);
        spinnerTrackedCooldown = findViewById(R.id.spinnerTrackedCooldown);
        spinnerTrackedDistance = findViewById(R.id.spinnerTrackedDistance);
        btnBack = findViewById(R.id.btnBack);

        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }

        setupSpinners();
        loadSettings(user.getUid());
    }

    private void setupSpinners() {
        // General Cooldown
        String[] options = {"Off", "2 Hours", "6 Hours"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (spinnerCooldown != null) {
            spinnerCooldown.setAdapter(adapter);
        }

        // Tracked Birds Cooldown
        String[] trackedOptions = {"Every spotting", "Every 2 hours"};
        ArrayAdapter<String> trackedAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, trackedOptions);
        trackedAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (spinnerTrackedCooldown != null) {
            spinnerTrackedCooldown.setAdapter(trackedAdapter);
        }

        // Tracked Birds Distance
        String[] distanceOptions = {"Any distance", "Within 150 miles"};
        ArrayAdapter<String> distanceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, distanceOptions);
        distanceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (spinnerTrackedDistance != null) {
            spinnerTrackedDistance.setAdapter(distanceAdapter);
        }
    }

    private void loadSettings(String uid) {
        settingsApi.getSettings(uid, new SettingsApi.SettingsCallback() {
            @Override
            public void onSuccess(UserSettings settings) {
                if (isFinishing() || isDestroyed()) return;

                if (switchNotifications != null) {
                    switchNotifications.setOnCheckedChangeListener(null);
                    switchNotifications.setChecked(settings.notificationsEnabled);
                }
                
                if (switchReplies != null) {
                    switchReplies.setOnCheckedChangeListener(null);
                    switchReplies.setChecked(settings.repliesEnabled);
                }

                if (switchTrackedBirds != null) {
                    switchTrackedBirds.setOnCheckedChangeListener(null);
                    switchTrackedBirds.setChecked(settings.trackedBirdsNotificationsEnabled);
                }

                if (spinnerCooldown != null) {
                    spinnerCooldown.setOnItemSelectedListener(null);
                    int selection = 0;
                    if (settings.notificationCooldownHours == 2) selection = 1;
                    else if (settings.notificationCooldownHours == 6) selection = 2;
                    spinnerCooldown.setSelection(selection);
                }

                if (spinnerTrackedCooldown != null) {
                    spinnerTrackedCooldown.setOnItemSelectedListener(null);
                    int selection = (settings.trackedBirdsCooldownHours == 2) ? 1 : 0;
                    spinnerTrackedCooldown.setSelection(selection);
                }

                if (spinnerTrackedDistance != null) {
                    spinnerTrackedDistance.setOnItemSelectedListener(null);
                    int selection = (settings.trackedBirdsMaxDistanceMiles == 150) ? 1 : 0;
                    spinnerTrackedDistance.setSelection(selection);
                }

                setupListeners(uid);
            }

            @Override
            public void onFailure(Exception e, String message) {
                Log.e(TAG, "Load settings failed: " + message, e);
                if (!isFinishing() && !isDestroyed()) {
                    Toast.makeText(NotificationsSettingsActivity.this, message, Toast.LENGTH_SHORT).show();
                    setupListeners(uid);
                }
            }
        });
    }

    private void setupListeners(String uid) {
        if (switchNotifications != null) {
            switchNotifications.setOnCheckedChangeListener((buttonView, isChecked) -> {
                settingsApi.setNotificationsEnabled(uid, isChecked, null);
            });
        }

        if (switchReplies != null) {
            switchReplies.setOnCheckedChangeListener((buttonView, isChecked) -> {
                settingsApi.setRepliesEnabled(uid, isChecked, null);
            });
        }

        if (switchTrackedBirds != null) {
            switchTrackedBirds.setOnCheckedChangeListener((buttonView, isChecked) -> {
                settingsApi.setTrackedBirdsNotificationsEnabled(uid, isChecked, null);
            });
        }

        if (spinnerCooldown != null) {
            spinnerCooldown.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    int hours = 0;
                    if (position == 1) hours = 2;
                    else if (position == 2) hours = 6;
                    settingsApi.setNotificationCooldownHours(uid, hours, null);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        if (spinnerTrackedCooldown != null) {
            spinnerTrackedCooldown.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    int hours = (position == 1) ? 2 : 0;
                    settingsApi.setTrackedBirdsCooldownHours(uid, hours, null);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        if (spinnerTrackedDistance != null) {
            spinnerTrackedDistance.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    int miles = (position == 1) ? 150 : -1;
                    settingsApi.setTrackedBirdsMaxDistanceMiles(uid, miles, null);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
    }
}
