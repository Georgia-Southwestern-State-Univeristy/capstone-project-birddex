package com.birddex.app;

import androidx.lifecycle.ViewModel;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ViewModel for CreatePostActivity to survive configuration changes.
 * Prevents duplicate posts on rotation by persisting the pendingPostId,
 * submission state, and uploaded image URL.
 */
/**
 * CreatePostViewModel: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class CreatePostViewModel extends ViewModel {
    private String pendingPostId;
    private String uploadedImageUrl; // FIX #3: Persist URL to prevent orphaning on rotation
    
    public final AtomicBoolean isPostInProgress = new AtomicBoolean(false);
    public final AtomicBoolean isPostFinished   = new AtomicBoolean(false);

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getPendingPostId() {
        return pendingPostId;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setPendingPostId(String id) {
        this.pendingPostId = id;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public String getUploadedImageUrl() {
        return uploadedImageUrl;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setUploadedImageUrl(String url) {
        this.uploadedImageUrl = url;
    }
}
