package com.birddex.app;

import com.google.firebase.firestore.Exclude;

public class BirdCard {
    private String id; // This will store the cardId
    private String userId;
    private String userBirdId;
    private int collectionSlot;
    private String rarity;

    public BirdCard() {
        // Default constructor required for calls to DataSnapshot.getValue(BirdCard.class)
    }

    public BirdCard(String id, String userId, String userBirdId, int collectionSlot, String rarity) {
        this.id = id;
        this.userId = userId;
        this.userBirdId = userBirdId;
        this.collectionSlot = collectionSlot;
        this.rarity = rarity;
    }

    @Exclude
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserBirdId() {
        return userBirdId;
    }

    public void setUserBirdId(String userBirdId) {
        this.userBirdId = userBirdId;
    }

    public int getCollectionSlot() {
        return collectionSlot;
    }

    public void setCollectionSlot(int collectionSlot) {
        this.collectionSlot = collectionSlot;
    }

    public String getRarity() {
        return rarity;
    }

    public void setRarity(String rarity) {
        this.rarity = rarity;
    }
}
