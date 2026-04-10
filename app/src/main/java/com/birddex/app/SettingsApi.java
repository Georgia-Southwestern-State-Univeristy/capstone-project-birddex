package com.birddex.app;

import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * SettingsApi handles reading and writing user preferences.
 */
public class SettingsApi {

    private static final String TAG = "SettingsApi";
    public interface SettingsCallback {
        void onSuccess(UserSettings settings);
        void onFailure(Exception e, String message);
    }

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    public void getSettings(String uid, SettingsCallback callback) {
        if (uid == null) return;

        db.collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    boolean enabled = false;
                    boolean replies = true;
                    int cooldown = 2;
                    boolean trackedEnabled = false;
                    int trackedCooldown = 0;
                    int trackedMaxDistance = -1;

                    if (doc != null && doc.exists()) {
                        Boolean val = doc.getBoolean("notificationsEnabled");
                        if (val != null) enabled = val;

                        Boolean replyVal = doc.getBoolean("repliesEnabled");
                        if (replyVal != null) replies = replyVal;

                        Long cooldownVal = doc.getLong("notificationCooldownHours");
                        if (cooldownVal != null) cooldown = cooldownVal.intValue();

                        Boolean trackedVal = doc.getBoolean("trackedBirdsNotificationsEnabled");
                        if (trackedVal != null) trackedEnabled = trackedVal;

                        Long trackedCooldownVal = doc.getLong("trackedBirdsCooldownHours");
                        if (trackedCooldownVal != null) trackedCooldown = trackedCooldownVal.intValue();

                        Long trackedMaxDistanceVal = doc.getLong("trackedBirdsMaxDistanceMiles");
                        if (trackedMaxDistanceVal != null) trackedMaxDistance = trackedMaxDistanceVal.intValue();
                    }
                    if (callback != null) {
                        callback.onSuccess(new UserSettings(enabled, replies, cooldown, trackedEnabled, trackedCooldown, trackedMaxDistance));
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error getting settings", e);
                    if (callback != null) callback.onFailure(e, "Failed to load settings.");
                });
    }

    public void setNotificationsEnabled(String uid, boolean enabled, SettingsCallback callback) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("notificationsEnabled", enabled);
        saveToFirestore(uid, updates, callback);
    }

    public void setAllNotificationsState(String uid, boolean enabled, SettingsCallback callback) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("notificationsEnabled", enabled);
        updates.put("repliesEnabled", enabled);
        updates.put("trackedBirdsNotificationsEnabled", enabled);
        saveToFirestore(uid, updates, callback);
    }

    public void setRepliesEnabled(String uid, boolean enabled, SettingsCallback callback) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("repliesEnabled", enabled);
        saveToFirestore(uid, updates, callback);
    }

    public void setNotificationCooldownHours(String uid, int hours, SettingsCallback callback) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("notificationCooldownHours", hours);
        saveToFirestore(uid, updates, callback);
    }

    public void setTrackedBirdsNotificationsEnabled(String uid, boolean enabled, SettingsCallback callback) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("trackedBirdsNotificationsEnabled", enabled);
        saveToFirestore(uid, updates, callback);
    }

    public void setTrackedBirdsCooldownHours(String uid, int hours, SettingsCallback callback) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("trackedBirdsCooldownHours", hours);
        saveToFirestore(uid, updates, callback);
    }

    public void setTrackedBirdsMaxDistanceMiles(String uid, int miles, SettingsCallback callback) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("trackedBirdsMaxDistanceMiles", miles);
        saveToFirestore(uid, updates, callback);
    }

    private void saveToFirestore(String uid, Map<String, Object> updates, SettingsCallback callback) {
        if (uid == null) return;

        db.collection("users").document(uid).update(updates)
                .addOnSuccessListener(v -> {
                    if (callback != null) getSettings(uid, callback);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Save failed", e);
                    if (callback != null) callback.onFailure(e, "Failed to update settings.");
                });
    }
}