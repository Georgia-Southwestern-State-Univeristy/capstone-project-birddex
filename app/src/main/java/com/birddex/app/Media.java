package com.birddex.app;

import com.google.firebase.firestore.Exclude;

import java.util.Map;

public class Media {
    private String id; // This will store the mediaId
    private String userId;
    private String userBirdId;
    private String imageUrl;
    private boolean verified;
    private Map<String, Object> metadata; // Using Map for flexible metadata

    public Media() {
        // Default constructor required for calls to DataSnapshot.getValue(Media.class)
    }

    public Media(String id, String userId, String userBirdId, String imageUrl, boolean verified, Map<String, Object> metadata) {
        this.id = id;
        this.userId = userId;
        this.userBirdId = userBirdId;
        this.imageUrl = imageUrl;
        this.verified = verified;
        this.metadata = metadata;
    }

    @Exclude
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserBirdId() {
        return userBirdId;
    }

    public void setUserBirdId(String userBirdId) {
        this.userBirdId = userBirdId;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
