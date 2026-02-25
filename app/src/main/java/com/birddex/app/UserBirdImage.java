package com.birddex.app;

import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.ServerTimestamp;

import java.util.Date;

public class UserBirdImage {
    private String id;
    private String userId;
    private String birdId; // The ID of the bird species
    private String imageUrl;
    @ServerTimestamp
    private Date timestamp; // Timestamp of when the image was uploaded
    private String userBirdRefId; // New: Reference to the associated UserBird document ID

    public UserBirdImage() {
        // Required for Firebase Firestore
    }

    public UserBirdImage(String id, String userId, String birdId, String imageUrl, String userBirdRefId) {
        this.id = id;
        this.userId = userId;
        this.birdId = birdId;
        this.imageUrl = imageUrl;
        this.userBirdRefId = userBirdRefId;
    }

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

    public String getBirdId() {
        return birdId;
    }

    public void setBirdId(String birdId) {
        this.birdId = birdId;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    public String getUserBirdRefId() {
        return userBirdRefId;
    }

    public void setUserBirdRefId(String userBirdRefId) {
        this.userBirdRefId = userBirdRefId;
    }
}
