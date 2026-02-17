package com.example.birddex;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * HomeActivity serves as the main navigation hub of the application.
 * It uses a BottomNavigationView to switch between different fragments.
 */
public class HomeActivity extends AppCompatActivity {

    private BottomNavigationView bottomNav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        // Initialize the bottom navigation bar.
        bottomNav = findViewById(R.id.bottomNav);

        // Default start fragment is the Forum (middle item).
        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_forum);
            switchFragment(new ForumFragment());
        }

        // Set up the listener for navigation item selection.
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();

            // Check which item was selected and switch to the corresponding fragment.
            if (id == R.id.nav_camera) {
                startActivity(new Intent(HomeActivity.this, ImageUploadActivity.class));
                return true;
            } else if (id == R.id.nav_search_collection) {
                switchFragment(new SearchCollectionFragment());
                return true;
            } else if (id == R.id.nav_forum) {
                switchFragment(new ForumFragment());
                return true;
            } else if (id == R.id.nav_nearby) {
                switchFragment(new NearbyFragment());
                return true;
            } else if (id == R.id.nav_profile) {
                switchFragment(new ProfileFragment());
                return true;
            }

            return false;
        });
    }

    /**
     * Replaces the current fragment in the container with the specified fragment.
     * @param fragment The new fragment to display.
     */
    private void switchFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit();
    }
}
