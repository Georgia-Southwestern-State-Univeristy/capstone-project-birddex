package com.example.birddex;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class SettingsApi {

    public interface SettingsCallback {
        void onSuccess(UserSettings settings);
        void onFailure(Exception e, String message);
    }

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    // Reads from users/{uid}.notificationsEnabled (defaults false if missing)
    public void getSettings(String uid, SettingsCallback callback) {
        db.collection("users").document(uid)
                .get()
                .addOnSuccessListener(doc -> {
                    boolean enabled = false;
                    if (doc != null && doc.exists()) {
                        Boolean val = doc.getBoolean("notificationsEnabled");
                        if (val != null) enabled = val;
                    }
                    callback.onSuccess(new UserSettings(enabled));
                })
                .addOnFailureListener(e -> callback.onFailure(e, "Failed to load settings."));
    }

    // Writes users/{uid}.notificationsEnabled
    public void setNotificationsEnabled(String uid, boolean enabled, SettingsCallback callback) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("notificationsEnabled", enabled);

        db.collection("users").document(uid)
                .update(updates)
                .addOnSuccessListener(v -> callback.onSuccess(new UserSettings(enabled)))
                .addOnFailureListener(e -> callback.onFailure(e, "Failed to update settings."));
    }
}
