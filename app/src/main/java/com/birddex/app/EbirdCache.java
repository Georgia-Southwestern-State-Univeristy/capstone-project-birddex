package com.birddex.app;

import com.google.firebase.firestore.Exclude;

import java.util.List;

public class EbirdCache {
    private String id; // This will store the document ID, likely "data"
    private List<Bird> birds;
    private long lastUpdated;
    private String lastUpdatedReadable;

    public EbirdCache() {
        // Default constructor required for calls to DataSnapshot.getValue(EbirdCache.class)
    }

    public EbirdCache(String id, List<Bird> birds, long lastUpdated, String lastUpdatedReadable) {
        this.id = id;
        this.birds = birds;
        this.lastUpdated = lastUpdated;
        this.lastUpdatedReadable = lastUpdatedReadable;
    }

    @Exclude
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<Bird> getBirds() {
        return birds;
    }

    public void setBirds(List<Bird> birds) {
        this.birds = birds;
    }

    public long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public String getLastUpdatedReadable() {
        return lastUpdatedReadable;
    }

    public void setLastUpdatedReadable(String lastUpdatedReadable) {
        this.lastUpdatedReadable = lastUpdatedReadable;
    }
}
