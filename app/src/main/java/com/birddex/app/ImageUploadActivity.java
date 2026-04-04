package com.birddex.app;

import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

/**
 * ImageUploadActivity is a container activity for the CameraFragment.
 * It dynamically creates a FrameLayout as its content view and hosts the camera interface.
 */
/**
 * ImageUploadActivity: Activity class for one BirdDex screen. It owns screen setup, user actions, and navigation for this part of the app.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class ImageUploadActivity extends AppCompatActivity {

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BirdDexApiWarmupHelper.maybeWarmup(this, "camera_entry");

        // Dynamically create a layout container for the fragment.
        FrameLayout frameLayout = new FrameLayout(this);
        frameLayout.setId(View.generateViewId());
        setContentView(frameLayout);

        // If the activity is starting for the first time, load the CameraFragment.
        if (savedInstanceState == null) {
            CameraFragment cameraFragment = new CameraFragment();
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            transaction.replace(frameLayout.getId(), cameraFragment);
            transaction.commit();
        }
    }
}
