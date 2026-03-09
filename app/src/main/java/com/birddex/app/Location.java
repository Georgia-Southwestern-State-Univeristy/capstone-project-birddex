package com.birddex.app;

import com.google.firebase.firestore.Exclude;

import java.util.Map;

/**
 * Location: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class Location {
    private String id; // This will store the locationId
    private Map<String, Object> metadata; // Using Map for flexible metadata
    private double latitude;
    private double longitude;
    private String country;
    private String state;
    private String locality;

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public Location() {
        // Default constructor required for calls to DataSnapshot.getValue(Location.class)
    }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public Location(String id, Map<String, Object> metadata, double latitude, double longitude, String country, String state, String locality) {
        this.id = id;
        this.metadata = metadata;
        this.latitude = latitude;
        this.longitude = longitude;
        this.country = country;
        this.state = state;
        this.locality = locality;
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
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public double getLatitude() {
        return latitude;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public double getLongitude() {
        return longitude;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getCountry() {
        return country;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setCountry(String country) {
        this.country = country;
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
