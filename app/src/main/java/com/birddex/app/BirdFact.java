package com.birddex.app;

import com.google.firebase.firestore.Exclude;

/**
 * BirdFact: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class BirdFact {
    private String id; // This will store the birdFactsId
    private String birdId;
    private String facts;
    private String hunterFacts;

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public BirdFact() {
        // Default constructor required for calls to DataSnapshot.getValue(BirdFact.class)
    }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public BirdFact(String id, String birdId, String facts, String hunterFacts) {
        this.id = id;
        this.birdId = birdId;
        this.facts = facts;
        this.hunterFacts = hunterFacts;
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
    public String getBirdId() {
        return birdId;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setBirdId(String birdId) {
        this.birdId = birdId;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getFacts() {
        return facts;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setFacts(String facts) {
        this.facts = facts;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getHunterFacts() {
        return hunterFacts;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setHunterFacts(String hunterFacts) {
        this.hunterFacts = hunterFacts;
    }
}
