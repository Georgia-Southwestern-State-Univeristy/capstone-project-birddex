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
 * NotificationsSettingsActivity: Activity class for one BirdDex screen. It owns screen setup, user actions, and navigation for this part of the app.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class NotificationsSettingsActivity extends AppCompatActivity {

    private static final String TAG = "NotificationsSettings";
    private SwitchCompat switchNotifications, switchReplies;
    private Spinner spinnerCooldown;
    private ImageView btnBack;
    private final SettingsApi settingsApi = new SettingsApi();

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarHelper.applyStandardNavBar(this);
        setContentView(R.layout.activity_notifications_settings);

        // Bind or inflate the UI pieces this method needs before it can update the screen.
        switchNotifications = findViewById(R.id.switchNotifications);
        switchReplies = findViewById(R.id.switchReplies);
        spinnerCooldown = findViewById(R.id.spinnerCooldown);
        btnBack = findViewById(R.id.btnBack);

        if (btnBack != null) {
            // Attach the user interaction that should run when this control is tapped.
            btnBack.setOnClickListener(v -> finish());
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }

        setupCooldownSpinner();
        loadSettings(user.getUid());
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    private void setupCooldownSpinner() {
        String[] options = {"Off", "2 Hours", "6 Hours"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (spinnerCooldown != null) {
            // Hook the data source to the list/grid adapter so model objects can render as UI rows/cards.
            spinnerCooldown.setAdapter(adapter);
        }
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void loadSettings(String uid) {
        settingsApi.getSettings(uid, new SettingsApi.SettingsCallback() {
            @Override
            public void onSuccess(UserSettings settings) {
                if (isFinishing() || isDestroyed()) return;

                // Disable listeners while setting initial values to avoid loops
                if (switchNotifications != null) {
                    switchNotifications.setOnCheckedChangeListener(null);
                    switchNotifications.setChecked(settings.notificationsEnabled);
                }
                
                if (switchReplies != null) {
                    switchReplies.setOnCheckedChangeListener(null);
                    switchReplies.setChecked(settings.repliesEnabled);
                }

                if (spinnerCooldown != null) {
                    spinnerCooldown.setOnItemSelectedListener(null);
                    int selection = 0;
                    if (settings.notificationCooldownHours == 2) selection = 1;
                    else if (settings.notificationCooldownHours == 6) selection = 2;
                    spinnerCooldown.setSelection(selection);
                }

                // Re-attach listeners after UI is ready
                setupListeners(uid);
            }

            @Override
            public void onFailure(Exception e, String message) {
                Log.e(TAG, "Load settings failed: " + message, e);
                if (!isFinishing() && !isDestroyed()) {
                    // Give the user immediate feedback about the result of this action.
                    Toast.makeText(NotificationsSettingsActivity.this, message, Toast.LENGTH_SHORT).show();
                    setupListeners(uid); // Still attach listeners so they can try to save
                }
            }
        });
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
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

        if (spinnerCooldown != null) {
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
}
