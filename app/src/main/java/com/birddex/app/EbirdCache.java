package com.birddex.app;

import com.google.firebase.firestore.Exclude;

import java.util.List;

/**
 * EbirdCache: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class EbirdCache {
    private String id; // This will store the document ID, likely "data"
    private List<Bird> birds;
    private long lastUpdated;
    private String lastUpdatedReadable;

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public EbirdCache() {
        // Default constructor required for calls to DataSnapshot.getValue(EbirdCache.class)
    }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public EbirdCache(String id, List<Bird> birds, long lastUpdated, String lastUpdatedReadable) {
        this.id = id;
        this.birds = birds;
        this.lastUpdated = lastUpdated;
        this.lastUpdatedReadable = lastUpdatedReadable;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    @Exclude
    public String getId() {
        return id;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public List<Bird> getBirds() {
        return birds;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setBirds(List<Bird> birds) {
        this.birds = birds;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public long getLastUpdated() {
        return lastUpdated;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getLastUpdatedReadable() {
        return lastUpdatedReadable;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setLastUpdatedReadable(String lastUpdatedReadable) {
        this.lastUpdatedReadable = lastUpdatedReadable;
    }
}
