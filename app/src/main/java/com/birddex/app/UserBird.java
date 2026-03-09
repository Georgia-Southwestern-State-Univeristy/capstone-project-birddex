package com.birddex.app;

import com.google.firebase.firestore.Exclude;
import java.util.Date;

/**
 * UserBird: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class UserBird {
    private String id;
    private String userId;
    private String birdSpeciesId; // Corresponds to birdId from identification
    private String imageUrl;
    private String locationId; // Replaced latitude, longitude, localityName with a single locationId
    private Date timeSpotted;
    private String birdFactsId; // Added for linking to bird facts
    private String hunterFactsId; // Added for linking to hunter facts
    private boolean isDuplicate; // New field
    private int pointsEarned; // New field
    private int imageCount; // Track number of associated images

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public UserBird() {
        // Required for Firestore deserialization
    }

    // Existing constructor updated to initialize new fields
    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public UserBird(String id, String userId, String birdSpeciesId, String imageUrl, String locationId, Date timeSpotted, String birdFactsId, String hunterFactsId) {
        this.id = id;
        this.userId = userId;
        this.birdSpeciesId = birdSpeciesId;
        this.imageUrl = imageUrl;
        this.locationId = locationId;
        this.timeSpotted = timeSpotted;
        this.birdFactsId = birdFactsId;
        this.hunterFactsId = hunterFactsId;
        this.isDuplicate = false; 
        this.pointsEarned = 0;    
        this.imageCount = 1; // Default to 1
    }

    // Full constructor including all fields
    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public UserBird(String id, String userId, String birdSpeciesId, String imageUrl, String locationId, Date timeSpotted, String birdFactsId, String hunterFactsId, boolean isDuplicate, int pointsEarned, int imageCount) {
        this.id = id;
        this.userId = userId;
        this.birdSpeciesId = birdSpeciesId;
        this.imageUrl = imageUrl;
        this.locationId = locationId;
        this.timeSpotted = timeSpotted;
        this.birdFactsId = birdFactsId;
        this.hunterFactsId = hunterFactsId;
        this.isDuplicate = isDuplicate;
        this.pointsEarned = pointsEarned;
        this.imageCount = imageCount;
    }

    // Getters and setters (required for Firestore)
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getBirdSpeciesId() { return birdSpeciesId; }
    public void setBirdSpeciesId(String birdSpeciesId) { this.birdSpeciesId = birdSpeciesId; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public String getLocationId() { return locationId; }
    public void setLocationId(String locationId) { this.locationId = locationId; }

    public Date getTimeSpotted() { return timeSpotted; }
    public void setTimeSpotted(Date timeSpotted) { this.timeSpotted = timeSpotted; }

    public String getBirdFactsId() { return birdFactsId; }
    public void setBirdFactsId(String birdFactsId) { this.birdFactsId = birdFactsId; }

    public String getHunterFactsId() { return hunterFactsId; }
    public void setHunterFactsId(String hunterFactsId) { this.hunterFactsId = hunterFactsId; }

    public boolean getIsDuplicate() { return isDuplicate; }
    public void setIsDuplicate(boolean isDuplicate) { this.isDuplicate = isDuplicate; }

    public int getPointsEarned() { return pointsEarned; }
    public void setPointsEarned(int pointsEarned) { this.pointsEarned = pointsEarned; }

    public int getImageCount() { return imageCount; }
    public void setImageCount(int imageCount) { this.imageCount = imageCount; }
}
