package com.birddex.app;

import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.PropertyName;
import com.google.firebase.firestore.ServerTimestamp;

import java.util.Date;

public class UserBird {
    private String id; // This will store the userBirdId
    private String userId;
    private String birdId;
    private Date captureDate;
    private String locationId;
    private String birdFactsId; // optional
    private String hunterFactsId; // optional

    public UserBird() {
        // Default constructor required for calls to DataSnapshot.getValue(UserBird.class)
    }

    // Constructor removed to favor using setters for clarity and to prevent misuse.

    @Exclude
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @PropertyName("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    @PropertyName("birdId")
    public String getBirdId() {
        return birdId;
    }

    public void setBirdId(String birdId) {
        this.birdId = birdId;
    }

    @ServerTimestamp
    @PropertyName("captureDate")
    public Date getCaptureDate() {
        return captureDate;
    }

    public void setCaptureDate(Date captureDate) {
        this.captureDate = captureDate;
    }

    @PropertyName("locationId")
    public String getLocationId() {
        return locationId;
    }

    public void setLocationId(String locationId) {
        this.locationId = locationId;
    }

    @PropertyName("birdFactsId")
    public String getBirdFactsId() {
        return birdFactsId;
    }

    public void setBirdFactsId(String birdFactsId) {
        this.birdFactsId = birdFactsId;
    }

    @PropertyName("hunterFactsId")
    public String getHunterFactsId() {
        return hunterFactsId;
    }

    public void setHunterFactsId(String hunterFactsId) {
        this.hunterFactsId = hunterFactsId;
    }
}
