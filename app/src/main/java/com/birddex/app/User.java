package com.birddex.app;

import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.ServerTimestamp;

import java.util.Date;

public class User {
    private String id; // This will store the userId, typically the Firebase Auth UID
    private String email;
    private String displayName;
    private Date createdAt;
    private String defaultLocationId; // Renamed from locationId

    public User() {
        // Default constructor required for calls to DataSnapshot.getValue(User.class)
    }

    // Constructor updated to reflect the renamed field
    public User(String id, String email, String displayName, Date createdAt, String defaultLocationId) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.createdAt = createdAt;
        this.defaultLocationId = defaultLocationId;
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

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    @ServerTimestamp
    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    // Getter and Setter for the renamed field
    public String getDefaultLocationId() {
        return defaultLocationId;
    }

    public void setDefaultLocationId(String defaultLocationId) {
        this.defaultLocationId = defaultLocationId;
    }
}
