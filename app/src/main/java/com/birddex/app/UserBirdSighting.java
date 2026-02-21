package com.birddex.app;

import java.util.Date;
import java.util.Map;

public class UserBirdSighting {
    private String id;
    private Map<String, Object> user_sighting;
    private Location location; // Denormalized location data
    private String birdId; // Denormalized bird ID
    private String commonName; // Denormalized common name
    private String imageUrl; // Denormalized image URL
    private Date timestamp; // Sighting timestamp

    public UserBirdSighting() {
        // Required for Firestore deserialization
    }

    // Constructor for creating a new user bird sighting
    public UserBirdSighting(String id, Map<String, Object> user_sighting, Location location, String birdId, String commonName, String imageUrl, Date timestamp) {
        this.id = id;
        this.user_sighting = user_sighting;
        this.location = location;
        this.birdId = birdId;
        this.commonName = commonName;
        this.imageUrl = imageUrl;
        this.timestamp = timestamp;
    }

    // Getters and setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Map<String, Object> getUser_sighting() {
        return user_sighting;
    }

    public void setUser_sighting(Map<String, Object> user_sighting) {
        this.user_sighting = user_sighting;
    }

    public Location getLocation() {
        return location;
    }

    public void setLocation(Location location) {
        this.location = location;
    }

    public String getBirdId() {
        return birdId;
    }

    public void setBirdId(String birdId) {
        this.birdId = birdId;
    }

    public String getCommonName() {
        return commonName;
    }

    public void setCommonName(String commonName) {
        this.commonName = commonName;
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
}
