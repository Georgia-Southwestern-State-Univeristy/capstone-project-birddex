package com.birddex.app;

import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class SettingsApi {

    public interface SettingsCallback {
        void onSuccess(UserSettings settings);
        void onFailure(Exception e, String message);
    }

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    public void getSettings(String uid, SettingsCallback callback) {
        db.collection("users").document(uid)
                .get()
                .addOnSuccessListener(doc -> {
                    boolean enabled = false;
                    boolean replies = true;
                    int cooldown = 2; // Default
                    if (doc != null && doc.exists()) {
                        Boolean val = doc.getBoolean("notificationsEnabled");
                        if (val != null) enabled = val;
                        
                        Boolean replyVal = doc.getBoolean("repliesEnabled");
                        if (replyVal != null) replies = replyVal;
                        
                        Long cooldownVal = doc.getLong("notificationCooldownHours");
                        if (cooldownVal != null) cooldown = cooldownVal.intValue();
                    }
                    callback.onSuccess(new UserSettings(enabled, replies, cooldown));
                })
                .addOnFailureListener(e -> callback.onFailure(e, "Failed to load settings."));
    }

    public void setNotificationsEnabled(String uid, boolean enabled, SettingsCallback callback) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("notificationsEnabled", enabled);

        db.collection("users").document(uid)
                .update(updates)
                .addOnSuccessListener(v -> getSettings(uid, callback))
                .addOnFailureListener(e -> callback.onFailure(e, "Failed to update settings."));
    }
    
    public void setRepliesEnabled(String uid, boolean enabled, SettingsCallback callback) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("repliesEnabled", enabled);

        db.collection("users").document(uid)
                .update(updates)
                .addOnSuccessListener(v -> getSettings(uid, callback))
                .addOnFailureListener(e -> callback.onFailure(e, "Failed to update settings."));
    }

    public void setNotificationCooldownHours(String uid, int hours, SettingsCallback callback) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("notificationCooldownHours", hours);

        db.collection("users").document(uid)
                .update(updates)
                .addOnSuccessListener(v -> getSettings(uid, callback))
                .addOnFailureListener(e -> callback.onFailure(e, "Failed to update settings."));
    }
}
