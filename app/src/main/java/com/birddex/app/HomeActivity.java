package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * HomeActivity serves as the main navigation hub of the application.
 * It uses a BottomNavigationView to switch between different fragments
 * and pre-loads the core Georgia bird list in the background.
 * 
 * Race Condition fixes:
 *  - Added isNavigating guard for camera launch.
 */
/**
 * HomeActivity: Main signed-in container that swaps bottom-nav screens and kicks off app-wide warm-up work.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class HomeActivity extends AppCompatActivity implements NetworkMonitor.NetworkStatusListener {

    private static final String TAG = "HomeActivity";
    private BottomNavigationView bottomNav;

    // Tracks the last "real" tab (anything except camera)
    private int lastNonCameraTabId = R.id.nav_forum;

    private EbirdApi ebirdApi; // New: EbirdApi instance
    private FirebaseManager firebaseManager;
    private List<JSONObject> allGeorgiaBirds; // New: To hold the core bird list
    private NetworkMonitor networkMonitor; // New: NetworkMonitor instance
    private boolean isFetchingBirds = false; // Flag to prevent redundant fetches
    private boolean isNavigating = false;

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        // Initialize the bottom navigation bar.
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        bottomNav = findViewById(R.id.bottomNav);

        // Initialize EbirdApi and the bird list
        ebirdApi = new EbirdApi();
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        firebaseManager = new FirebaseManager(this);
        allGeorgiaBirds = new ArrayList<>();

        // Initialize NetworkMonitor
        networkMonitor = new NetworkMonitor(this, this);

        // Load the core Georgia bird list in the background
        fetchCoreGeorgiaBirdList();
        checkBirdDataSync();

        // Check for deep links or specific navigation requests
        handleIntent(getIntent());

        // Default start fragment if not already set by handleIntent
        if (getSupportFragmentManager().findFragmentById(R.id.fragmentContainer) == null) {
            lastNonCameraTabId = R.id.nav_forum;
            bottomNav.setSelectedItemId(R.id.nav_forum);
            switchFragment(new ForumFragment());
        }

        // Set up the listener for navigation item selection.
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();

            // Check which item was selected and switch to the corresponding fragment.
            if (id == R.id.nav_camera) {
                if (isNavigating) return false;
                isNavigating = true;
                
                // Move into the next screen and pass the identifiers/data that screen needs.
                startActivity(new Intent(HomeActivity.this, ImageUploadActivity.class));

                // Restore highlight to the last non-camera tab (so camera doesn't stay selected)
                bottomNav.post(() -> bottomNav.setSelectedItemId(lastNonCameraTabId));

                // returning false prevents nav_camera from being "checked"
                return false;
            } else if (id == R.id.nav_search_collection) {
                lastNonCameraTabId = id;
                switchFragment(new SearchCollectionFragment());
                return true;
            } else if (id == R.id.nav_forum) {
                lastNonCameraTabId = id;
                switchFragment(new ForumFragment());
                return true;
            } else if (id == R.id.nav_nearby) {
                lastNonCameraTabId = id;
                switchFragment(new NearbyFragment());
                return true;
            } else if (id == R.id.nav_profile) {
                lastNonCameraTabId = id;
                switchFragment(new ProfileFragment());
                return true;
            }

            return false;
        });
    }

    /**
     * Handles a new Intent delivered to an existing Activity instance.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /**
     * Central handler that reacts to an event/input and decides what the next app action should
     * be.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    private void handleIntent(Intent intent) {
        if (intent != null && intent.hasExtra("target_user_id")) {
            String targetUserId = intent.getStringExtra("target_user_id");
            lastNonCameraTabId = R.id.nav_profile;
            bottomNav.setSelectedItemId(R.id.nav_profile);
            switchFragment(ProfileFragment.newInstance(targetUserId));
        }
    }

    /**
     * Runs when the screen returns to the foreground, so it often refreshes UI state or restarts
     * listeners.
     */
    @Override
    protected void onResume() {
        super.onResume();
        // Register NetworkMonitor
        networkMonitor.register();
        isNavigating = false;

        // Ensure the selected tab matches the fragment the user was last actually on
        if (bottomNav != null && bottomNav.getSelectedItemId() != lastNonCameraTabId) {
            bottomNav.setSelectedItemId(lastNonCameraTabId);
        }
    }

    /**
     * Runs when the screen is leaving the foreground, so it is used to pause work or save
     * transient state.
     */
    @Override
    protected void onPause() {
        super.onPause();
        // Unregister NetworkMonitor
        networkMonitor.unregister();
    }

    /**
     * Fetches the core list of Georgia birds in the background.
     * This data can then be used by other fragments as needed.
     */
    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void fetchCoreGeorgiaBirdList() {
        if (isFetchingBirds) return;

        if (!networkMonitor.isConnected()) {
            Log.w(TAG, "Attempted to fetchCoreGeorgiaBirdList but no network in HomeActivity.");
            // Give the user immediate feedback about the result of this action.
            Toast.makeText(this, "No internet to fetch bird list.", Toast.LENGTH_SHORT).show();
            return;
        }

        isFetchingBirds = true;
        ebirdApi.fetchCoreGeorgiaBirdList(new EbirdApi.EbirdCoreBirdListCallback() {
            @Override
            public void onSuccess(List<JSONObject> birds) {
                isFetchingBirds = false;
                allGeorgiaBirds.clear();
                allGeorgiaBirds.addAll(birds);
                Log.d(TAG, "Loaded " + allGeorgiaBirds.size() + " core Georgia birds from Cloud cache.");
            }

            @Override
            public void onFailure(Exception e) {
                isFetchingBirds = false;
                Log.e(TAG, "Failed to fetch core bird list in HomeActivity: " + e.getMessage(), e);
                Toast.makeText(HomeActivity.this, "Failed to load core bird data in background.", Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Replaces the current fragment in the container with the specified fragment.
     * FIX: Uses commitAllowingStateLoss() to prevent IllegalStateException if 
     * the fragment is replaced while the activity is in the background (e.g. via network callback).
     * @param fragment The new fragment to display.
     */
    /**
     * Main logic block for this part of the feature.
     */
    private void switchFragment(Fragment fragment) {
        if (isFinishing() || isDestroyed()) return;

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commitAllowingStateLoss();
    }

    // You can add a method here to provide the allGeorgiaBirds list to fragments if they need it
    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public List<JSONObject> getAllGeorgiaBirds() {
        return allGeorgiaBirds;
    }

    // --- NetworkMonitor Callbacks ---
    // NetworkMonitor callbacks can arrive on a background thread (ConnectivityManager fires
    // onAvailable/onLost on its own executor).  Any UI interaction must be posted to the main thread.
    /**
     * Main logic block for this part of the feature.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    @Override
    public void onNetworkAvailable() {
        Log.d(TAG, "Network became available in HomeActivity.");
        runOnUiThread(() -> {
            if (allGeorgiaBirds.isEmpty()) {
                // Give the user immediate feedback about the result of this action.
                Toast.makeText(this, "Internet connection restored. Retrying bird list fetch.", Toast.LENGTH_SHORT).show();
                fetchCoreGeorgiaBirdList();
            }
        });
    }

    /**
     * Main logic block for this part of the feature.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    @Override
    public void onNetworkLost() {
        Log.e(TAG, "Network lost in HomeActivity.");
        runOnUiThread(() ->
                // Give the user immediate feedback about the result of this action.
                Toast.makeText(this, "Internet connection lost. Bird data may be incomplete.", Toast.LENGTH_LONG).show()
        );
    }
    /** * Triggers the Cloud Function to ensure the Firestore bird database
     * is synced with eBird. The function uses a 72-hour cache.
     */
    /**
     * Main logic block for this part of the feature.
     */
    private void checkBirdDataSync() {
        firebaseManager.syncGeorgiaBirdList(task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "Bird data sync check complete.");
            } else {
                Log.e(TAG, "Automatic bird sync failed", task.getException());
            }
        });
    }
}
