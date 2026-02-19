package com.birddex.app;

import com.google.firebase.firestore.Exclude;
import java.util.Date;

public class CollectionSlot {
    private String id; // This will store the collectionSlotId
    private String userBirdId; // Reference to the UserBird entry
    private Date timestamp;
    private String imageUrl;

    public CollectionSlot() {
        // Default constructor
    }

    public CollectionSlot(String id, String userBirdId, Date timestamp, String imageUrl) {
        this.id = id;
        this.userBirdId = userBirdId;
        this.timestamp = timestamp;
        this.imageUrl = imageUrl;
    }

    @Exclude
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserBirdId() {
        return userBirdId;
    }

    public void setUserBirdId(String userBirdId) {
        this.userBirdId = userBirdId;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
}
