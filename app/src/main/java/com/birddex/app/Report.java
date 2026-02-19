package com.birddex.app;

import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.ServerTimestamp;

import java.util.Date;

public class Report {
    private String id; // This will store the reportId
    private String threadId;
    private String postId;
    private String reporterId;
    private String reason;
    private Date createdAt;

    public Report() {
        // Default constructor required for calls to DataSnapshot.getValue(Report.class)
    }

    public Report(String id, String threadId, String postId, String reporterId, String reason, Date createdAt) {
        this.id = id;
        this.threadId = threadId;
        this.postId = postId;
        this.reporterId = reporterId;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    @Exclude
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getThreadId() {
        return threadId;
    }

    public void setThreadId(String threadId) {
        this.threadId = threadId;
    }

    public String getPostId() {
        return postId;
    }

    public void setPostId(String postId) {
        this.postId = postId;
    }

    public String getReporterId() {
        return reporterId;
    }

    public void setReporterId(String reporterId) {
        this.reporterId = reporterId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    @ServerTimestamp
    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }
}
