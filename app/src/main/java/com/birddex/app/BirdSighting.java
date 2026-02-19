package com.birddex.app;

import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.ServerTimestamp;

import java.util.Date;

public class BirdSighting {
    private String id; // This will store the birdSightId
    private String userBirdId;
    private String locationId;
    private Date timeSpotted;

    public BirdSighting() {
        // Default constructor required for calls to DataSnapshot.getValue(BirdSighting.class)
    }

    public BirdSighting(String id, String userBirdId, String locationId, Date timeSpotted) {
        this.id = id;
        this.userBirdId = userBirdId;
        this.locationId = locationId;
        this.timeSpotted = timeSpotted;
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

    public String getLocationId() {
        return locationId;
    }

    public void setLocationId(String locationId) {
        this.locationId = locationId;
    }

    @ServerTimestamp
    public Date getTimeSpotted() {
        return timeSpotted;
    }

    public void setTimeSpotted(Date timeSpotted) {
        this.timeSpotted = timeSpotted;
    }
}
