package com.birddex.app;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;

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

    public LoadingDialog(@NonNull Context context) {
        super(context);
    }

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

        tvLoadingText = findViewById(R.id.tvLoadingText);
        startTextCycling();
    }

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

    @Override
    public void dismiss() {
        if (handler != null && textCycler != null) {
            handler.removeCallbacks(textCycler);
        }
        super.dismiss();
    }
}
