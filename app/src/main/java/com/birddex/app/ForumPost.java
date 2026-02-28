package com.birddex.app;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.ServerTimestamp;
import java.util.HashMap;
import java.util.Map;

public class ForumPost {
    private String id;
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

    public ForumPost() {
        // Required for Firestore
        this.likedBy = new HashMap<>();
    }

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
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
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
}
