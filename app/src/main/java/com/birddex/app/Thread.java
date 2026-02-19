package com.birddex.app;

import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.ServerTimestamp;

import java.util.Date;

public class Thread {
    private String id; // This will store the threadId
    private String title;
    private String userId;
    private Date createdAt;
    private Date lastUpdated;

    public Thread() {
        // Default constructor required for calls to DataSnapshot.getValue(Thread.class)
    }

    public Thread(String id, String title, String userId, Date createdAt, Date lastUpdated) {
        this.id = id;
        this.title = title;
        this.userId = userId;
        this.createdAt = createdAt;
        this.lastUpdated = lastUpdated;
    }

    @Exclude
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    @ServerTimestamp
    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    @ServerTimestamp
    public Date getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Date lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
}
