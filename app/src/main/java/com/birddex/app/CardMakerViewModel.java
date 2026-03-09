package com.birddex.app;

import androidx.lifecycle.ViewModel;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Survives configuration changes (screen rotation) so isSaveInProgress and
 * isSaveFinished are never reset mid-save.
 */
public class CardMakerViewModel extends ViewModel {
    public final AtomicBoolean isSaveInProgress = new AtomicBoolean(false);
    public final AtomicBoolean isSaveFinished   = new AtomicBoolean(false);
}
