package com.example.birddex;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class HomeActivity extends AppCompatActivity {

    private BottomNavigationView bottomNav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        bottomNav = findViewById(R.id.bottomNav);

        // Default = Forum (middle)
        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_forum);
            switchFragment(new ForumFragment());
        }

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_camera) {
                getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragmentContainer, new CameraFragment())
                        .commit();
                return true;
            }

            if (id == R.id.nav_search_collection) {
                switchFragment(new SearchCollectionFragment());
                return true;
            } else if (id == R.id.nav_camera) {
                switchFragment(new CameraFragment());
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

    private void switchFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit();
    }
}
