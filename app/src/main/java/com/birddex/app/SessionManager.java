package com.birddex.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/**
 * SessionManager handles the local storage of a unique session identifier.
 * This ID is compared against a remote session ID in Firestore to ensure
 * that only one device is logged into an account at a time.
 */
public class SessionManager {
    private static final String PREF_NAME = "BirdDexSession";
    private static final String KEY_SESSION_ID_PREFIX = "session_id_";
    private final SharedPreferences pref;

    public SessionManager(Context context) {
        pref = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Creates a new unique session ID for the given user and saves it locally.
     * @param uid The Firebase User ID
     * @return The newly generated session ID string.
     */
    public String createSession(String uid) {
        String sessionId = UUID.randomUUID().toString();
        pref.edit().putString(KEY_SESSION_ID_PREFIX + uid, sessionId).apply();
        return sessionId;
    }

    /**
     * Retrieves the locally stored session ID for the given user.
     * @param uid The Firebase User ID
     * @return The session ID or null if not found.
     */
    public String getSessionId(String uid) {
        return pref.getString(KEY_SESSION_ID_PREFIX + uid, null);
    }

    /**
     * Clears the session ID for the given user from local storage.
     * @param uid The Firebase User ID
     */
    public void clearSession(String uid) {
        pref.edit().remove(KEY_SESSION_ID_PREFIX + uid).apply();
    }
}
