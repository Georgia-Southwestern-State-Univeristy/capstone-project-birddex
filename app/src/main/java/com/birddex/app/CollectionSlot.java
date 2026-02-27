package com.birddex.app;

import com.google.firebase.firestore.Exclude;
import java.util.Date;

public class CollectionSlot {
    private String id;
    private String userBirdId;
    private Date timestamp;
    private String rarity; // New field for card rarity (e.g., R1, R2, ...)
    private int slotIndex; // New field for explicit slot positioning

    private String commonName;
    private String scientificName;

    private String state;
    private String locality;

    public CollectionSlot() {
    }

    public CollectionSlot(String id, String userBirdId, Date timestamp, String rarity, int slotIndex) {
        this.id = id;
        this.userBirdId = userBirdId;
        this.timestamp = timestamp;
        this.rarity = rarity;
        this.slotIndex = slotIndex;
    }

    @Exclude
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserBirdId() {
        return userBirdId;
    }

    public void setUserBirdId(String userBirdId) {
        this.userBirdId = userBirdId;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    public String getRarity() {
        return rarity;
    }

    public void setRarity(String rarity) {
        this.rarity = rarity;
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    public void setSlotIndex(int slotIndex) {
        this.slotIndex = slotIndex;
    }

    public String getCommonName() {
        return commonName;
    }

    public void setCommonName(String commonName) {
        this.commonName = commonName;
    }

    public String getScientificName() {
        return scientificName;
    }

    public void setScientificName(String scientificName) {
        this.scientificName = scientificName;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getLocality() {
        return locality;
    }

    public void setLocality(String locality) {
        this.locality = locality;
    }
}