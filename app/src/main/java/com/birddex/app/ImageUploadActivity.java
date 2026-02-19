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
public class ImageUploadActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
