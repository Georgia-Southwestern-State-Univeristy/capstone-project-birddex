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
/**
 * SettingsApi: Interface/model contract used to keep different parts of the app communicating with a shared shape.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class SettingsApi {

    private static final String TAG = "SettingsApi";
    public interface SettingsCallback {
        void onSuccess(UserSettings settings);
        void onFailure(Exception e, String message);
    }

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    public void getSettings(String uid, SettingsCallback callback) {
        if (uid == null) return;
        
        // Target top-level user doc where Cloud Functions look for notification preferences
        // Set up or query the Firebase layer that supplies/stores this feature's data.
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

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setNotificationsEnabled(String uid, boolean enabled, SettingsCallback callback) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("notificationsEnabled", enabled);
        saveToFirestore(uid, updates, callback);
    }
    
    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setRepliesEnabled(String uid, boolean enabled, SettingsCallback callback) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("repliesEnabled", enabled);
        saveToFirestore(uid, updates, callback);
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setNotificationCooldownHours(String uid, int hours, SettingsCallback callback) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("notificationCooldownHours", hours);
        saveToFirestore(uid, updates, callback);
    }

    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void saveToFirestore(String uid, Map<String, Object> updates, SettingsCallback callback) {
        if (uid == null) return;

        // Set up or query the Firebase layer that supplies/stores this feature's data.
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
