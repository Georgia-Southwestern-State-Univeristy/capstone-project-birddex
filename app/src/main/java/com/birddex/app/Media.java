package com.birddex.app;

import com.google.firebase.firestore.Exclude;

import java.util.Map;

/**
 * Media: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class Media {
    private String id; // This will store the mediaId
    private String userId;
    private String userBirdId;
    private String imageUrl;
    private boolean verified;
    private Map<String, Object> metadata; // Using Map for flexible metadata

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public Media() {
        // Default constructor required for calls to DataSnapshot.getValue(Media.class)
    }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public Media(String id, String userId, String userBirdId, String imageUrl, boolean verified, Map<String, Object> metadata) {
        this.id = id;
        this.userId = userId;
        this.userBirdId = userBirdId;
        this.imageUrl = imageUrl;
        this.verified = verified;
        this.metadata = metadata;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    @Exclude
    public String getId() {
        return id;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getUserBirdId() {
        return userBirdId;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setUserBirdId(String userBirdId) {
        this.userBirdId = userBirdId;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getImageUrl() {
        return imageUrl;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public boolean isVerified() {
        return verified;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
