package com.birddex.app;

import com.google.firebase.firestore.PropertyName;

/**
 * Bird: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class Bird {
    private String id; 
    private String commonName;
    private String scientificName;
    private String family;
    private String species;
    private boolean isEndangered;
    private boolean canHunt;
    private String lastSeenLocationIdGeorgia; 
    private Long lastSeenTimestampGeorgia; 
    private Double lastSeenLatitudeGeorgia;
    private Double lastSeenLongitudeGeorgia;

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public Bird() {
        // Default constructor required for calls to DataSnapshot.getValue(Bird.class)
    }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public Bird(String id, String commonName, String scientificName, String family, String species, boolean isEndangered, boolean canHunt, String lastSeenLocationIdGeorgia, Long lastSeenTimestampGeorgia, Double lastSeenLatitudeGeorgia, Double lastSeenLongitudeGeorgia) {
        this.id = id;
        this.commonName = commonName;
        this.scientificName = scientificName;
        this.family = family;
        this.species = species;
        this.isEndangered = isEndangered;
        this.canHunt = canHunt;
        this.lastSeenLocationIdGeorgia = lastSeenLocationIdGeorgia;
        this.lastSeenTimestampGeorgia = lastSeenTimestampGeorgia;
        this.lastSeenLatitudeGeorgia = lastSeenLatitudeGeorgia;
        this.lastSeenLongitudeGeorgia = lastSeenLongitudeGeorgia;
    }

    // Use PropertyName to ensure Firestore maps the document ID to this field if desired,
    // or just to avoid the "No setter" warning if 'id' exists in the document data.
    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    @PropertyName("id")
    public String getId() {
        return id;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    @PropertyName("id")
    public void setId(String id) {
        this.id = id;
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
    public String getFamily() {
        return family;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setFamily(String family) {
        this.family = family;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getSpecies() {
        return species;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setSpecies(String species) {
        this.species = species;
    }

    // Changed to standard getter/setter for boolean fields to fix Firestore warnings
    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    @PropertyName("isEndangered")
    public boolean isEndangered() {
        return isEndangered;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    @PropertyName("isEndangered")
    public void setEndangered(boolean endangered) {
        isEndangered = endangered;
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

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public String getLastSeenLocationIdGeorgia() {
        return lastSeenLocationIdGeorgia;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public void setLastSeenLocationIdGeorgia(String lastSeenLocationIdGeorgia) {
        this.lastSeenLocationIdGeorgia = lastSeenLocationIdGeorgia;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public Long getLastSeenTimestampGeorgia() {
        return lastSeenTimestampGeorgia;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setLastSeenTimestampGeorgia(Long lastSeenTimestampGeorgia) {
        this.lastSeenTimestampGeorgia = lastSeenTimestampGeorgia;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public Double getLastSeenLatitudeGeorgia() {
        return lastSeenLatitudeGeorgia;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setLastSeenLatitudeGeorgia(Double lastSeenLatitudeGeorgia) {
        this.lastSeenLatitudeGeorgia = lastSeenLatitudeGeorgia;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public Double getLastSeenLongitudeGeorgia() {
        return lastSeenLongitudeGeorgia;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setLastSeenLongitudeGeorgia(Double lastSeenLongitudeGeorgia) {
        this.lastSeenLongitudeGeorgia = lastSeenLongitudeGeorgia;
    }
}
