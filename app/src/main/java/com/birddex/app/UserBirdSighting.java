package com.birddex.app;

import java.util.Date;
import java.util.Map;

/**
 * UserBirdSighting: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class UserBirdSighting {
    private String id;
    private Map<String, Object> user_sighting;
    private Location location; // Denormalized location data
    private String birdId; // Denormalized bird ID
    private String commonName; // Denormalized common name
    private String howMany; // How many birds were seen
    private Date timestamp; // Sighting timestamp

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public UserBirdSighting() {
        // Required for Firestore deserialization
    }

    // Constructor for creating a new user bird sighting
    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public UserBirdSighting(String id, Map<String, Object> user_sighting, Location location, String birdId, String commonName, String howMany, Date timestamp) {
        this.id = id;
        this.user_sighting = user_sighting;
        this.location = location;
        this.birdId = birdId;
        this.commonName = commonName;
        this.howMany = howMany;
        this.timestamp = timestamp;
    }

    // Getters and setters
    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
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
    public Map<String, Object> getUser_sighting() {
        return user_sighting;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setUser_sighting(Map<String, Object> user_sighting) {
        this.user_sighting = user_sighting;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public Location getLocation() {
        return location;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public void setLocation(Location location) {
        this.location = location;
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
    public String getHowMany() {
        return howMany;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setHowMany(String howMany) {
        this.howMany = howMany;
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
}