package com.birddex.app;

import com.google.firebase.firestore.Exclude;
import java.util.Date;

/**
 * CollectionSlot: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class CollectionSlot {
    private String id;
    private String userBirdId;
    private String birdId;
    private Date timestamp;
    private String imageUrl;
    private String rarity = CardRarityHelper.COMMON;
    private int slotIndex;
    private boolean isFavorite;

    private String commonName;
    private String scientificName;

    private String state;
    private String locality;

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public CollectionSlot() {
        this.rarity = CardRarityHelper.COMMON;
    }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public CollectionSlot(String id, String userBirdId, Date timestamp, String imageUrl, String rarity, int slotIndex) {
        this.id = id;
        this.userBirdId = userBirdId;
        this.timestamp = timestamp;
        this.imageUrl = imageUrl;
        this.rarity = CardRarityHelper.normalizeRarity(rarity);
        this.slotIndex = slotIndex;
        this.isFavorite = false;
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
    public Date getTimestamp() {
        return timestamp;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getImageUrl() {
        return imageUrl;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getRarity() {
        return CardRarityHelper.normalizeRarity(rarity);
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setRarity(String rarity) {
        this.rarity = CardRarityHelper.normalizeRarity(rarity);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public int getSlotIndex() {
        return slotIndex;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setSlotIndex(int slotIndex) {
        this.slotIndex = slotIndex;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public boolean isFavorite() {
        return isFavorite;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setFavorite(boolean favorite) {
        isFavorite = favorite;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getCommonName() {
        return commonName;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setCommonName(String commonName) {
        this.commonName = commonName;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getScientificName() {
        return scientificName;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setScientificName(String scientificName) {
        this.scientificName = scientificName;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getState() {
        return state;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setState(String state) {
        this.state = state;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getLocality() {
        return locality;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setLocality(String locality) {
        this.locality = locality;
    }
}