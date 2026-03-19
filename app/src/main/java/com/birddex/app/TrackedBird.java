package com.birddex.app;

import com.google.firebase.firestore.DocumentId;
import com.google.firebase.firestore.ServerTimestamp;

import java.util.Date;

/**
 * TrackedBird: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class TrackedBird {

    @DocumentId
    private String documentId;
    private String birdId;
    private String commonName;
    private String scientificName;
    private Date trackedAt;
    private Date lastNotifiedAt;
    private String lastNotifiedSightingId;
    private String lastNotificationSource;

    public TrackedBird() {
        // Required for Firestore
    }

    public TrackedBird(String birdId, String commonName, String scientificName) {
        this.documentId = birdId;
        this.birdId = birdId;
        this.commonName = commonName;
        this.scientificName = scientificName;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getBirdId() {
        return birdId;
    }

    public void setBirdId(String birdId) {
        this.birdId = birdId;
    }

    public String getCommonName() {
        return commonName;
    }

    public void setCommonName(String commonName) {
        this.commonName = commonName;
    }

    public String getScientificName() {
        return scientificName;
    }

    public void setScientificName(String scientificName) {
        this.scientificName = scientificName;
    }

    @ServerTimestamp
    public Date getTrackedAt() {
        return trackedAt;
    }

    public void setTrackedAt(Date trackedAt) {
        this.trackedAt = trackedAt;
    }

    @ServerTimestamp
    public Date getLastNotifiedAt() {
        return lastNotifiedAt;
    }

    public void setLastNotifiedAt(Date lastNotifiedAt) {
        this.lastNotifiedAt = lastNotifiedAt;
    }

    public String getLastNotifiedSightingId() {
        return lastNotifiedSightingId;
    }

    public void setLastNotifiedSightingId(String lastNotifiedSightingId) {
        this.lastNotifiedSightingId = lastNotifiedSightingId;
    }

    public String getLastNotificationSource() {
        return lastNotificationSource;
    }

    public void setLastNotificationSource(String lastNotificationSource) {
        this.lastNotificationSource = lastNotificationSource;
    }
}
