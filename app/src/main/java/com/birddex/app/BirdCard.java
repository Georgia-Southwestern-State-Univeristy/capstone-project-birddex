package com.birddex.app;

import com.google.firebase.firestore.Exclude;

/**
 * BirdCard: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class BirdCard {
    private String id; // This will store the cardId
    private String userId;
    private String userBirdId;
    private int collectionSlot;
    private String rarity;

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public BirdCard() {
        // Default constructor required for calls to DataSnapshot.getValue(BirdCard.class)
    }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public BirdCard(String id, String userId, String userBirdId, int collectionSlot, String rarity) {
        this.id = id;
        this.userId = userId;
        this.userBirdId = userBirdId;
        this.collectionSlot = collectionSlot;
        this.rarity = rarity;
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
    public String getUserId() {
        return userId;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getUserBirdId() {
        return userBirdId;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setUserBirdId(String userBirdId) {
        this.userBirdId = userBirdId;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public int getCollectionSlot() {
        return collectionSlot;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setCollectionSlot(int collectionSlot) {
        this.collectionSlot = collectionSlot;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getRarity() {
        return rarity;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setRarity(String rarity) {
        this.rarity = rarity;
    }
}
