package com.birddex.app;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.ServerTimestamp;

public class Report {
    private String id;
    private String targetType; // "post", "comment", or "user"
    private String targetId;   // ID of the post, comment, or user being reported
    private String reporterId;
    private String reason;
    @ServerTimestamp
    private Timestamp timestamp;

    public Report() {
        // Required for Firestore
    }

    public Report(String targetType, String targetId, String reporterId, String reason) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.reporterId = reporterId;
        this.reason = reason;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getReporterId() { return reporterId; }
    public void setReporterId(String reporterId) { this.reporterId = reporterId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Timestamp getTimestamp() { return timestamp; }
    public void setTimestamp(Timestamp timestamp) { this.timestamp = timestamp; }
}
