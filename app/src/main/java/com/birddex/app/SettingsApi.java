package com.birddex.app;

import android.util.Log;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * SettingsApi handles reading and writing user preferences.
 * Fixes: Corrected path to top-level User document to match Cloud Function expectations.
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
        
        // Target top-level user doc where Cloud Functions look for notification preferences
        db.collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    boolean enabled = true;
                    boolean replies = true;
                    int cooldown = 2;
                    if (doc != null && doc.exists()) {
                        Boolean val = doc.getBoolean("notificationsEnabled");
                        if (val != null) enabled = val;
                        
                        Boolean replyVal = doc.getBoolean("repliesEnabled");
                        if (replyVal != null) replies = replyVal;
                        
                        Long cooldownVal = doc.getLong("notificationCooldownHours");
                        if (cooldownVal != null) cooldown = cooldownVal.intValue();
                    }
                    if (callback != null) callback.onSuccess(new UserSettings(enabled, replies, cooldown));
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
