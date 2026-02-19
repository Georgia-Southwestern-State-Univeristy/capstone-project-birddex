package com.birddex.app;

import com.google.firebase.firestore.Exclude;

public class HunterSighting {
    private String id; // This will store the hunterSightId
    private String birdSightId;
    private boolean canHunt;

    public HunterSighting() {
        // Default constructor required for calls to DataSnapshot.getValue(HunterSighting.class)
    }

    public HunterSighting(String id, String birdSightId, boolean canHunt) {
        this.id = id;
        this.birdSightId = birdSightId;
        this.canHunt = canHunt;
    }

    @Exclude
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getBirdSightId() {
        return birdSightId;
    }

    public void setBirdSightId(String birdSightId) {
        this.birdSightId = birdSightId;
    }

    public boolean isCanHunt() {
        return canHunt;
    }

    public void setCanHunt(boolean canHunt) {
        this.canHunt = canHunt;
    }
}
