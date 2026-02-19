package com.birddex.app;

import com.google.firebase.firestore.Exclude;

public class BirdFact {
    private String id; // This will store the birdFactsId
    private String birdId;
    private String facts;
    private String hunterFacts;

    public BirdFact() {
        // Default constructor required for calls to DataSnapshot.getValue(BirdFact.class)
    }

    public BirdFact(String id, String birdId, String facts, String hunterFacts) {
        this.id = id;
        this.birdId = birdId;
        this.facts = facts;
        this.hunterFacts = hunterFacts;
    }

    @Exclude
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getBirdId() {
        return birdId;
    }

    public void setBirdId(String birdId) {
        this.birdId = birdId;
    }

    public String getFacts() {
        return facts;
    }

    public void setFacts(String facts) {
        this.facts = facts;
    }

    public String getHunterFacts() {
        return hunterFacts;
    }

    public void setHunterFacts(String hunterFacts) {
        this.hunterFacts = hunterFacts;
    }
}
