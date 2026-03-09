package com.birddex.app;

import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.ServerTimestamp;

import java.util.Date;

/**
 * Identification: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class Identification {
    private String id; // This will store the identificationId
    private String commonName;
    private String scientificName;
    private String family;
    private String species;
    private String locationId;
    private boolean verified;
    private String imageUrl;
    private Date timestamp;

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public Identification() {
        // Default constructor required for calls to DataSnapshot.getValue(Identification.class)
    }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public Identification(String id, String commonName, String scientificName, String family, String species, String locationId, boolean verified, String imageUrl, Date timestamp) {
        this.id = id;
        this.commonName = commonName;
        this.scientificName = scientificName;
        this.family = family;
        this.species = species;
        this.locationId = locationId;
        this.verified = verified;
        this.imageUrl = imageUrl;
        this.timestamp = timestamp;
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

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public String getLocationId() {
        return locationId;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public void setLocationId(String locationId) {
        this.locationId = locationId;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public boolean isVerified() {
        return verified;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setVerified(boolean verified) {
        this.verified = verified;
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
    @ServerTimestamp
    public Date getTimestamp() {
        return timestamp;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }
}
