package com.birddex.app;

import androidx.lifecycle.ViewModel;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Survives configuration changes (screen rotation) so isSaveInProgress and
 * isSaveFinished are never reset mid-save.
 */
/**
 * CardMakerViewModel: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class CardMakerViewModel extends ViewModel {
    public final AtomicBoolean isSaveInProgress = new AtomicBoolean(false);
    public final AtomicBoolean isSaveFinished   = new AtomicBoolean(false);
    public String saveOperationId = null;
    public String pendingUploadPath = null;
}
