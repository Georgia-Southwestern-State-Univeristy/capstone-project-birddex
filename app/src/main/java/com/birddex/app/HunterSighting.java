package com.birddex.app;

import com.google.firebase.firestore.Exclude;

/**
 * HunterSighting: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class HunterSighting {
    private String id; // This will store the hunterSightId
    private String birdSightId;
    private boolean canHunt;

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public HunterSighting() {
        // Default constructor required for calls to DataSnapshot.getValue(HunterSighting.class)
    }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public HunterSighting(String id, String birdSightId, boolean canHunt) {
        this.id = id;
        this.birdSightId = birdSightId;
        this.canHunt = canHunt;
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
    public String getBirdSightId() {
        return birdSightId;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setBirdSightId(String birdSightId) {
        this.birdSightId = birdSightId;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public boolean isCanHunt() {
        return canHunt;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setCanHunt(boolean canHunt) {
        this.canHunt = canHunt;
    }
}
