package com.birddex.app;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.ServerTimestamp;
import java.util.Map;

public class ForumComment {
    private String id;
    private String threadId;
    private String userId;
    private String username;
    private String userProfilePictureUrl;
    private String text;
    @ServerTimestamp
    private Timestamp timestamp;
    private int likeCount;
    private Map<String, Boolean> likedBy;
    private String parentCommentId; 
    private String parentUsername; 
    private boolean edited;
    private Timestamp lastEditedAt;
    private boolean likeNotificationSent;

    public ForumComment() {
        // Required for Firestore
    }

    public ForumComment(String threadId, String userId, String username, String userProfilePictureUrl, String text) {
        this.threadId = threadId;
        this.userId = userId;
        this.username = username;
        this.userProfilePictureUrl = userProfilePictureUrl;
        this.text = text;
        this.likeCount = 0;
        this.edited = false;
        this.likeNotificationSent = false;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getUserProfilePictureUrl() { return userProfilePictureUrl; }
    public void setUserProfilePictureUrl(String userProfilePictureUrl) { this.userProfilePictureUrl = userProfilePictureUrl; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Timestamp getTimestamp() { return timestamp; }
    public void setTimestamp(Timestamp timestamp) { this.timestamp = timestamp; }
    public int getLikeCount() { return likeCount; }
    public void setLikeCount(int likeCount) { this.likeCount = likeCount; }
    public Map<String, Boolean> getLikedBy() { return likedBy; }
    public void setLikedBy(Map<String, Boolean> likedBy) { this.likedBy = likedBy; }
    public String getParentCommentId() { return parentCommentId; }
    public void setParentCommentId(String parentCommentId) { this.parentCommentId = parentCommentId; }
    public String getParentUsername() { return parentUsername; }
    public void setParentUsername(String parentUsername) { this.parentUsername = parentUsername; }
    public boolean isEdited() { return edited; }
    public void setEdited(boolean edited) { this.edited = edited; }
    public Timestamp getLastEditedAt() { return lastEditedAt; }
    public void setLastEditedAt(Timestamp lastEditedAt) { this.lastEditedAt = lastEditedAt; }
    public boolean isLikeNotificationSent() { return likeNotificationSent; }
    public void setLikeNotificationSent(boolean likeNotificationSent) { this.likeNotificationSent = likeNotificationSent; }
}
