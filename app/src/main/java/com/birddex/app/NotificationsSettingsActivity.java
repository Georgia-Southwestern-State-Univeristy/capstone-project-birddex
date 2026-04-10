package com.birddex.app;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * NotificationsSettingsActivity: Manages user notification preferences.
 *
 * This activity allows users to toggle general notifications, reply alerts,
 * and tracked bird alerts. It also allows configuring cooldowns and distance
 * filters for these notifications.
 */
public class NotificationsSettingsActivity extends AppCompatActivity {

    private static final String TAG = "NotificationsSettings";
    private boolean isApplyingToggleState = false;
    private SwitchCompat switchNotifications, switchReplies, switchTrackedBirds;
    private Spinner spinnerCooldown, spinnerTrackedCooldown, spinnerTrackedDistance;
    private ImageView btnBack;
    private final SettingsApi settingsApi = new SettingsApi();

    /**
     * Android calls this when the Activity is first created. This is where the screen
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notifications_settings);

        // Bind UI components
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

        // Initialize UI components with default values and load user preferences from backend.
        setupSpinners();
        loadSettings(user.getUid());
    }

    /**
     * Initializes the dropdown spinners with human-readable options for cooldowns and distances.
     */
    private void setupSpinners() {
        // General Notification Cooldown
        String[] options = {"Off", "2 Hours", "6 Hours"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (spinnerCooldown != null) {
            spinnerCooldown.setAdapter(adapter);
        }

        // Tracked Birds Notification Cooldown
        String[] trackedOptions = {"Every spotting", "Every 2 hours"};
        ArrayAdapter<String> trackedAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, trackedOptions);
        trackedAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (spinnerTrackedCooldown != null) {
            spinnerTrackedCooldown.setAdapter(trackedAdapter);
        }

        // Tracked Birds Proximity Distance
        String[] distanceOptions = {"Any distance", "Within 150 miles"};
        ArrayAdapter<String> distanceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, distanceOptions);
        distanceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (spinnerTrackedDistance != null) {
            spinnerTrackedDistance.setAdapter(distanceAdapter);
        }
    }

    /**
     * Pulls settings data from the backend and prepares it for the UI.
     * It temporarily disables listeners while updating view state to prevent recursive triggers.
     */
    private void loadSettings(String uid) {
        settingsApi.getSettings(uid, new SettingsApi.SettingsCallback() {
            @Override
            public void onSuccess(UserSettings settings) {
                if (isFinishing() || isDestroyed()) return;

                // Sync toggle states with user preferences.
                applyToggleState(
                        settings.notificationsEnabled,
                        settings.repliesEnabled,
                        settings.trackedBirdsNotificationsEnabled
                );

                // Sync spinner selections with user preferences.
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

                // Re-attach listeners once UI is synchronized.
                setupListeners(uid);
            }

            @Override
            public void onFailure(Exception e, String message) {
                Log.e(TAG, "Load settings failed: " + message, e);
                if (!isFinishing() && !isDestroyed()) {
                    MessagePopupHelper.show(NotificationsSettingsActivity.this, message);
                    setupListeners(uid);
                }
            }
        });
    }

    private void applyToggleState(boolean notificationsEnabled,
                                  boolean repliesEnabled,
                                  boolean trackedBirdsEnabled) {
        isApplyingToggleState = true;
        try {
            if (switchNotifications != null) {
                switchNotifications.setChecked(notificationsEnabled);
            }
            if (switchReplies != null) {
                switchReplies.setChecked(repliesEnabled);
            }
            if (switchTrackedBirds != null) {
                switchTrackedBirds.setChecked(trackedBirdsEnabled);
            }
        } finally {
            isApplyingToggleState = false;
        }
    }

    /**
     * Wires user interaction for all toggles and spinners.
     * Each change triggers an update back to the persistence layer.
     */
    private void setupListeners(String uid) {
        if (switchNotifications != null) {
            switchNotifications.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isApplyingToggleState) return;

                applyToggleState(isChecked, isChecked, isChecked);
                settingsApi.setAllNotificationsState(uid, isChecked, null);
            });
        }

        if (switchReplies != null) {
            switchReplies.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isApplyingToggleState) return;
                settingsApi.setRepliesEnabled(uid, isChecked, null);
            });
        }

        if (switchTrackedBirds != null) {
            switchTrackedBirds.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isApplyingToggleState) return;
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