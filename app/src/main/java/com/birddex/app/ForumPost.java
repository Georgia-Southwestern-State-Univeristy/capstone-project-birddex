package com.birddex.app;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentId;
import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.ServerTimestamp;
import java.util.HashMap;
import java.util.Map;

/**
 * ForumPost: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class ForumPost {
    @DocumentId
    private String postId; // Renamed from id to avoid conflict with 'id' field in Firestore documents
    private String userId;
    private String username;
    private String userProfilePictureUrl;
    private String message;
    private String birdImageUrl;
    @ServerTimestamp
    private Timestamp timestamp;
    private int likeCount;
    private int commentCount;
    private int viewCount;
    private Map<String, Boolean> likedBy;
    private Map<String, Object> viewedBy; // Changed to Object to handle Timestamps
    private boolean edited;
    private Timestamp lastEditedAt;

    // New fields for Map and Bird Status
    private Double latitude;
    private Double longitude;
    private boolean showLocation;
    private boolean hunted;
    private boolean spotted;

    private boolean notificationSent;
    private boolean likeNotificationSent;
    private Timestamp lastViewedAt;
    private Timestamp heatmapExpiresAt;

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public ForumPost() {
        // Required for Firestore
        this.likedBy = new HashMap<>();
        this.viewedBy = new HashMap<>();
    }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public ForumPost(String userId, String username, String userProfilePictureUrl, String message, String birdImageUrl) {
        this.userId = userId;
        this.username = username;
        this.userProfilePictureUrl = userProfilePictureUrl;
        this.message = message;
        this.birdImageUrl = birdImageUrl;
        this.likeCount = 0;
        this.commentCount = 0;
        this.viewCount = 0;
        this.likedBy = new HashMap<>();
        this.viewedBy = new HashMap<>();
        this.edited = false;
        this.showLocation = false;
        this.hunted = false;
        this.spotted = false;
        this.notificationSent = false;
        this.likeNotificationSent = false;
    }

    // Getters and Setters
    @Exclude // Prevents Firestore from writing an 'id' field while allowing code to use getId()
    public String getId() { return postId; }
    public void setId(String id) { this.postId = id; }

    public String getPostId() { return postId; }
    public void setPostId(String postId) { this.postId = postId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getUserProfilePictureUrl() { return userProfilePictureUrl; }
    public void setUserProfilePictureUrl(String userProfilePictureUrl) { this.userProfilePictureUrl = userProfilePictureUrl; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getBirdImageUrl() { return birdImageUrl; }
    public void setBirdImageUrl(String birdImageUrl) { this.birdImageUrl = birdImageUrl; }
    public Timestamp getTimestamp() { return timestamp; }
    public void setTimestamp(Timestamp timestamp) { this.timestamp = timestamp; }
    public int getLikeCount() { return likeCount; }
    public void setLikeCount(int likeCount) { this.likeCount = likeCount; }
    public int getCommentCount() { return commentCount; }
    public void setCommentCount(int commentCount) { this.commentCount = commentCount; }
    public int getViewCount() { return viewCount; }
    public void setViewCount(int viewCount) { this.viewCount = viewCount; }
    public Map<String, Boolean> getLikedBy() { return likedBy; }
    public void setLikedBy(Map<String, Boolean> likedBy) { this.likedBy = likedBy; }
    public Map<String, Object> getViewedBy() { return viewedBy; }
    public void setViewedBy(Map<String, Object> viewedBy) { this.viewedBy = viewedBy; }
    public boolean isEdited() { return edited; }
    public void setEdited(boolean edited) { this.edited = edited; }
    public Timestamp getLastEditedAt() { return lastEditedAt; }
    public void setLastEditedAt(Timestamp lastEditedAt) { this.lastEditedAt = lastEditedAt; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public boolean isShowLocation() { return showLocation; }
    public void setShowLocation(boolean showLocation) { this.showLocation = showLocation; }
    public boolean isHunted() { return hunted; }
    public void setHunted(boolean hunted) { this.hunted = hunted; }
    public boolean isSpotted() { return spotted; }
    public void setSpotted(boolean spotted) { this.spotted = spotted; }

    public boolean isNotificationSent() { return notificationSent; }
    public void setNotificationSent(boolean notificationSent) { this.notificationSent = notificationSent; }
    public boolean isLikeNotificationSent() { return likeNotificationSent; }
    public void setLikeNotificationSent(boolean likeNotificationSent) { this.likeNotificationSent = likeNotificationSent; }
    public Timestamp getLastViewedAt() { return lastViewedAt; }
    public void setLastViewedAt(Timestamp lastViewedAt) { this.lastViewedAt = lastViewedAt; }
    public Timestamp getHeatmapExpiresAt() { return heatmapExpiresAt; }
    public void setHeatmapExpiresAt(Timestamp heatmapExpiresAt) { this.heatmapExpiresAt = heatmapExpiresAt; }
}
