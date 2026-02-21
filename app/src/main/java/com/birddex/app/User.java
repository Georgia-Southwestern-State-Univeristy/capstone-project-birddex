package com.birddex.app;

import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.ServerTimestamp;

import java.util.Date;

public class User {
    private String id; // This will store the userId, typically the Firebase Auth UID
    private String email;
    private String username;
    private Date createdAt;
    private String defaultLocationId; // Renamed from locationId
    private int totalBirds; // New field
    private int duplicateBirds; // New field
    private int totalPoints; // New field
    private String profilePictureUrl; // Added profile picture URL

    public User() {
        // Default constructor required for calls to DataSnapshot.getValue(User.class)
    }

    // Constructor updated to reflect the renamed field and new fields
    public User(String id, String email, String username, Date createdAt, String defaultLocationId) {
        this.id = id;
        this.email = email;
        this.username = username;
        this.createdAt = createdAt;
        this.defaultLocationId = defaultLocationId;
        this.totalBirds = 0;       // Initialize new fields
        this.duplicateBirds = 0;   // Initialize new fields
        this.totalPoints = 0;      // Initialize new fields
        this.profilePictureUrl = null; // Initialize
    }

    // Full constructor including all fields for completeness
    public User(String id, String email, String username, Date createdAt, String defaultLocationId, int totalBirds, int duplicateBirds, int totalPoints, String profilePictureUrl) {
        this.id = id;
        this.email = email;
        this.username = username;
        this.createdAt = createdAt;
        this.defaultLocationId = defaultLocationId;
        this.totalBirds = totalBirds;
        this.duplicateBirds = duplicateBirds;
        this.totalPoints = totalPoints;
        this.profilePictureUrl = profilePictureUrl;
    }

    @Exclude
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    @ServerTimestamp
    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public String getDefaultLocationId() {
        return defaultLocationId;
    }

    public void setDefaultLocationId(String defaultLocationId) {
        this.defaultLocationId = defaultLocationId;
    }

    public int getTotalBirds() {
        return totalBirds;
    }

    public void setTotalBirds(int totalBirds) {
        this.totalBirds = totalBirds;
    }

    public int getDuplicateBirds() {
        return duplicateBirds;
    }

    public void setDuplicateBirds(int duplicateBirds) {
        this.duplicateBirds = duplicateBirds;
    }

    public int getTotalPoints() {
        return totalPoints;
    }

    public void setTotalPoints(int totalPoints) {
        this.totalPoints = totalPoints;
    }

    public String getProfilePictureUrl() { return profilePictureUrl; }

    public void setProfilePictureUrl(String profilePictureUrl) { this.profilePictureUrl = profilePictureUrl; }
}