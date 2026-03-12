package com.birddex.app;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentId;
import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.ServerTimestamp;
import java.util.Map;

/**
 * ForumComment: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class ForumComment {
    @DocumentId
    private String commentId; // Renamed from id to avoid conflict with 'id' field in Firestore documents
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

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public ForumComment() {
        // Required for Firestore
        this.likedBy = new java.util.HashMap<>();
    }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public ForumComment(String threadId, String userId, String username, String userProfilePictureUrl, String text) {
        this.threadId = threadId;
        this.userId = userId;
        this.username = username;
        this.userProfilePictureUrl = userProfilePictureUrl;
        this.text = text;
        this.likeCount = 0;
        this.likedBy = new java.util.HashMap<>();
        this.edited = false;
        this.likeNotificationSent = false;
    }

    // Getters and Setters
    @Exclude // Prevents Firestore from writing an 'id' field while allowing code to use getId()
    public String getId() { return commentId; }
    public void setId(String id) { this.commentId = id; }

    public String getCommentId() { return commentId; }
    public void setCommentId(String commentId) { this.commentId = commentId; }

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
