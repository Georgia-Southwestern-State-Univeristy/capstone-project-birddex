package com.birddex.app;

import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.ServerTimestamp;

import java.util.Date;

/**
 * UserBirdImage: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class UserBirdImage {
    private String id;
    private String userId;
    private String birdId; // The ID of the bird species
    private String imageUrl;
    @ServerTimestamp
    private Date timestamp; // Timestamp of when the image was uploaded
    private String userBirdRefId; // New: Reference to the associated UserBird document ID

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public UserBirdImage() {
        // Required for Firebase Firestore
    }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public UserBirdImage(String id, String userId, String birdId, String imageUrl, String userBirdRefId) {
        this.id = id;
        this.userId = userId;
        this.birdId = birdId;
        this.imageUrl = imageUrl;
        this.userBirdRefId = userBirdRefId;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
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
    public String getBirdId() {
        return birdId;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setBirdId(String birdId) {
        this.birdId = birdId;
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
    public Date getTimestamp() {
        return timestamp;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getUserBirdRefId() {
        return userBirdRefId;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setUserBirdRefId(String userBirdRefId) {
        this.userBirdRefId = userBirdRefId;
    }
}
