package com.birddex.app;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;

/**
 * LoadingDialog: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class LoadingDialog extends Dialog {

    private TextView tvLoadingText;
    private final String[] loadingMessages = {
            "Finding nearby birds from Ebird...",
            "Setting up your nest...",
            "Cleaning the binoculars...",
            "Synchronizing BirdDex...",
            "Consulting the field guides..."
    };
    private int currentMessageIndex = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable textCycler;

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public LoadingDialog(@NonNull Context context) {
        super(context);
    }

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_loading);
        setCancelable(false);

        if (getWindow() != null) {
            getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        }

        // Bind or inflate the UI pieces this method needs before it can update the screen.
        tvLoadingText = findViewById(R.id.tvLoadingText);
        startTextCycling();
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void startTextCycling() {
        textCycler = new Runnable() {
            @Override
            public void run() {
                if (tvLoadingText != null) {
                    tvLoadingText.setText(loadingMessages[currentMessageIndex]);
                    currentMessageIndex = (currentMessageIndex + 1) % loadingMessages.length;
                    handler.postDelayed(this, 1500);
                }
            }
        };
        handler.post(textCycler);
    }

    /**
     * Main logic block for this part of the feature.
     */
    @Override
    public void dismiss() {
        if (handler != null && textCycler != null) {
            handler.removeCallbacks(textCycler);
        }
        super.dismiss();
    }
}
