package com.birddex.app;

import androidx.lifecycle.ViewModel;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ViewModel for CreatePostActivity to survive configuration changes.
 * Prevents duplicate posts on rotation by persisting the pendingPostId,
 * submission state, and uploaded image URL.
 */
public class CreatePostViewModel extends ViewModel {
    private String pendingPostId;
    private String uploadedImageUrl; // FIX #3: Persist URL to prevent orphaning on rotation
    
    public final AtomicBoolean isPostInProgress = new AtomicBoolean(false);
    public final AtomicBoolean isPostFinished   = new AtomicBoolean(false);

    public String getPendingPostId() {
        return pendingPostId;
    }

    public void setPendingPostId(String id) {
        this.pendingPostId = id;
    }

    public String getUploadedImageUrl() {
        return uploadedImageUrl;
    }

    public void setUploadedImageUrl(String url) {
        this.uploadedImageUrl = url;
    }
}
