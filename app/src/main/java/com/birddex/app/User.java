package com.birddex.app;

import com.google.firebase.firestore.DocumentId;
import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.ServerTimestamp;

import java.util.Date;

/**
 * User: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class User {
    @DocumentId
    private String userId; // Renamed from id to avoid conflict with 'id' field in Firestore documents
    private String email;
    private String username;
    private String bio; // Added bio field
    private Date createdAt;
    private String defaultLocationId; // Renamed from locationId
    private int totalBirds; // New field
    private int duplicateBirds; // New field
    private int totalPoints; // New field
    private String profilePictureUrl; // Added profile picture URL
    private int pfpChangesToday; // New field for PFP change limit
    private Date pfpCooldownResetTimestamp; // Renamed for rolling 24-hour cooldown
    private int openAiRequestsRemaining; // New field for OpenAI request limit
    private Date openAiCooldownResetTimestamp; // Renamed for rolling 24-hour cooldown
    private int followerCount; // New field for followers
    private int followingCount; // New field for following
    private boolean hasLoggedInBefore; // New field for welcome message logic
    private Date lastActiveAt; // New field for welcome message logic
    private boolean isStaff; // New field for moderator access

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public User() {
        // Default constructor required for calls to DataSnapshot.getValue(User.class)
    }

    // Constructor updated to reflect the renamed field and new fields
    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public User(String id, String email, String username, Date createdAt, String defaultLocationId) {
        this.userId = id;
        this.email = email;
        this.username = username;
        this.bio = null; // Initialize bio
        this.createdAt = createdAt;
        this.defaultLocationId = defaultLocationId;
        this.totalBirds = 0;       // Initialize new fields
        this.duplicateBirds = 0;   // Initialize new fields
        this.totalPoints = 0;      // Initialize new fields
        this.profilePictureUrl = null; // Initialize
        this.pfpChangesToday = 5; // Default value
        this.pfpCooldownResetTimestamp = null; // Will be set by Cloud Function
        this.openAiRequestsRemaining = 100; // Default value
        this.openAiCooldownResetTimestamp = null; // Will be set by Cloud Function
        this.followerCount = 0;
        this.followingCount = 0;
        this.hasLoggedInBefore = false;
        this.lastActiveAt = null;
        this.isStaff = false;
    }

    // Full constructor including all fields for completeness
    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public User(String id, String email, String username, String bio, Date createdAt, String defaultLocationId, int totalBirds, int duplicateBirds, int totalPoints, String profilePictureUrl, int pfpChangesToday, Date pfpCooldownResetTimestamp, int openAiRequestsRemaining, Date openAiCooldownResetTimestamp, int followerCount, int followingCount) {
        this.userId = id;
        this.email = email;
        this.username = username;
        this.bio = bio; // Initialize bio
        this.createdAt = createdAt;
        this.defaultLocationId = defaultLocationId;
        this.totalBirds = totalBirds;
        this.duplicateBirds = duplicateBirds;
        this.totalPoints = totalPoints;
        this.profilePictureUrl = profilePictureUrl;
        this.pfpChangesToday = pfpChangesToday;
        this.pfpCooldownResetTimestamp = pfpCooldownResetTimestamp;
        this.openAiRequestsRemaining = openAiRequestsRemaining;
        this.openAiCooldownResetTimestamp = openAiCooldownResetTimestamp;
        this.followerCount = followerCount;
        this.followingCount = followingCount;
        this.hasLoggedInBefore = false;
        this.lastActiveAt = null;
        this.isStaff = false;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    @Exclude
    public String getId() {
        return userId;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    @Exclude
    public void setId(String id) {
        this.userId = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getEmail() {
        return email;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getUsername() {
        return username;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getBio() {
        return bio;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setBio(String bio) {
        this.bio = bio;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    @ServerTimestamp
    public Date getCreatedAt() {
        return createdAt;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public String getDefaultLocationId() {
        return defaultLocationId;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public void setDefaultLocationId(String defaultLocationId) {
        this.defaultLocationId = defaultLocationId;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public int getTotalBirds() {
        return totalBirds;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setTotalBirds(int totalBirds) {
        this.totalBirds = totalBirds;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public int getDuplicateBirds() {
        return duplicateBirds;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setDuplicateBirds(int duplicateBirds) {
        this.duplicateBirds = duplicateBirds;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public int getTotalPoints() {
        return totalPoints;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setTotalPoints(int totalPoints) {
        this.totalPoints = totalPoints;
    }

    public String getProfilePictureUrl() { return profilePictureUrl; }

    public void setProfilePictureUrl(String profilePictureUrl) { this.profilePictureUrl = profilePictureUrl; }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public int getPfpChangesToday() {
        return pfpChangesToday;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setPfpChangesToday(int pfpChangesToday) {
        this.pfpChangesToday = pfpChangesToday;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public Date getPfpCooldownResetTimestamp() {
        return pfpCooldownResetTimestamp;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setPfpCooldownResetTimestamp(Date pfpCooldownResetTimestamp) {
        this.pfpCooldownResetTimestamp = pfpCooldownResetTimestamp;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public int getOpenAiRequestsRemaining() {
        return openAiRequestsRemaining;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setOpenAiRequestsRemaining(int openAiRequestsRemaining) {
        this.openAiRequestsRemaining = openAiRequestsRemaining;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public Date getOpenAiCooldownResetTimestamp() {
        return openAiCooldownResetTimestamp;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setOpenAiCooldownResetTimestamp(Date openAiCooldownResetTimestamp) {
        this.openAiCooldownResetTimestamp = openAiCooldownResetTimestamp;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public int getFollowerCount() {
        return followerCount;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setFollowerCount(int followerCount) {
        this.followerCount = followerCount;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public int getFollowingCount() {
        return followingCount;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setFollowingCount(int followingCount) {
        this.followingCount = followingCount;
    }

    public boolean isHasLoggedInBefore() {
        return hasLoggedInBefore;
    }

    public void setHasLoggedInBefore(boolean hasLoggedInBefore) {
        this.hasLoggedInBefore = hasLoggedInBefore;
    }

    public Date getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Date lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public boolean isStaff() {
        return isStaff;
    }

    public void setStaff(boolean staff) {
        isStaff = staff;
    }
}
