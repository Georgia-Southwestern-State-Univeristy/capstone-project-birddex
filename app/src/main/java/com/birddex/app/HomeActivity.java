package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
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
    private static final long GEORGIA_SYNC_CHECK_TTL_MS = 24L * 60L * 60L * 1000L;
    private static final int GEORGIA_BIRD_DATA_REFRESH_VERSION = 1;

    private BottomNavigationView bottomNav;
    private View bottomNavContainer;
    private View bottomNavSystemInset;
    private TextView welcomeMessageTv;

    // Tracks the last "real" tab (anything except camera)
    private int lastNonCameraTabId = R.id.nav_forum;

    private EbirdApi ebirdApi; // New: EbirdApi instance
    private BirdCacheManager birdCacheManager;
    private FirebaseManager firebaseManager;
    private List<JSONObject> allGeorgiaBirds; // New: To hold the core bird list
    private NetworkMonitor networkMonitor; // New: NetworkMonitor instance
    private boolean isFetchingBirds = false; // Flag to prevent redundant fetches
    private boolean isNavigating = false;
    private boolean welcomeCheckedThisResume = false;

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
        bottomNavContainer = findViewById(R.id.bottomNavContainer);
        bottomNavSystemInset = findViewById(R.id.bottomNavSystemInset);
        welcomeMessageTv = findViewById(R.id.welcomeMessage);

        // Apply bottom system-bar handling so gesture mode keeps the brown look,
        // while 3-button mode shows a black area under the system buttons.
        applyBottomNavInsets();

        // Initialize EbirdApi and the bird list
        ebirdApi = new EbirdApi(this);
        birdCacheManager = new BirdCacheManager(this);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        firebaseManager = new FirebaseManager(this);
        allGeorgiaBirds = new ArrayList<>();

        // Initialize NetworkMonitor
        networkMonitor = new NetworkMonitor(this, this);

        // Load the cached Georgia bird list immediately, then refresh only when needed.
        loadCachedGeorgiaBirdListImmediately();
        fetchCoreGeorgiaBirdList();
        maybeCheckBirdDataSync(false);

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
     * Bottom nav padding. System status/navigation bar colors and the top status inset strip are
     * applied globally after the content view is set (see {@link BirdDexAppCheck}).
     *
     * This expects activity_home.xml to contain:
     * - @id/bottomNavContainer
     * - @id/bottomNavSystemInset
     * <p>
     * System navigation inset is applied on {@link #bottomNav} in {@link #onStart()} with minimal
     * base padding so the fragment and bar stay as close as the system allows.
     */
    private void applyBottomNavInsets() {
        if (bottomNav == null) return;

        // Larger tap targets; system nav inset is applied in onStart() on bottomNav.
        bottomNav.setItemPaddingTop(dp(10));
        bottomNav.setItemPaddingBottom(dp(4));

        // Window navigation bar colors and status-bar strip are applied globally (BirdDexAppCheck).

        if (bottomNavSystemInset != null) {
            bottomNavSystemInset.setVisibility(View.GONE);
            ViewGroup.LayoutParams params = bottomNavSystemInset.getLayoutParams();
            params.height = 0;
            bottomNavSystemInset.setLayoutParams(params);
        }

    }

    /**
     * Minimal padding above system gestures/buttons: apply inset on the bar itself so there is no
     * extra band between fragment content and the icons beyond this single inset.
     */
    @Override
    protected void onStart() {
        super.onStart();
        if (bottomNav == null) {
            return;
        }
        final int padTop = dp(6);
        final int padBottom = dp(3);
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav, (v, insets) -> {
            int sys = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(0, padTop, 0, padBottom + sys);
            return insets;
        });
        ViewCompat.requestApplyInsets(bottomNav);
    }

    /**
     * Helper for converting dp values to pixels.
     */
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void checkWelcomeMessage() {
        FirebaseUser currentUser = firebaseManager.getCurrentUser();
        if (currentUser == null) return;

        firebaseManager.getUserProfile(currentUser.getUid(), task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                User user = task.getResult().toObject(User.class);
                if (user != null) {
                    String username = user.getUsername() != null ? user.getUsername() : "";
                    if (!user.isHasLoggedInBefore()) {
                        // First time login
                        showWelcomeAnimation("Welcome to your nest " + username);
                        firebaseManager.updateUserActiveStatus(currentUser.getUid(), true, new Date());
                    } else {
                        // Subsequent login
                        Date lastActive = user.getLastActiveAt();
                        if (lastActive != null) {
                            long diffInMs = new Date().getTime() - lastActive.getTime();
                            long diffInHours = diffInMs / (1000 * 60 * 60);
                            if (diffInHours >= 2) {
                                showWelcomeAnimation("Welcome back to your nest " + username);
                                firebaseManager.updateUserActiveStatus(currentUser.getUid(), true, new Date());
                            }
                        } else {
                            // Fallback if lastActiveAt is null for some reason
                            firebaseManager.updateUserActiveStatus(currentUser.getUid(), true, new Date());
                        }
                    }
                }
            }
        });
    }

    private void showWelcomeAnimation(String message) {
        if (welcomeMessageTv == null) return;

        welcomeMessageTv.setText(message);
        welcomeMessageTv.setVisibility(View.VISIBLE);

        Animation fadeInOut = AnimationUtils.loadAnimation(this, R.anim.fade_in_out);
        fadeInOut.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {}

            @Override
            public void onAnimationEnd(Animation animation) {
                welcomeMessageTv.setVisibility(View.GONE);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {}
        });

        welcomeMessageTv.startAnimation(fadeInOut);
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

        BirdDexApiWarmupHelper.maybeWarmup(this, "app_open");

        if (!welcomeCheckedThisResume) {
            checkWelcomeMessage();
            welcomeCheckedThisResume = true;
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
        welcomeCheckedThisResume = false;

        // Update last active time when user leaves
        FirebaseUser currentUser = firebaseManager.getCurrentUser();
        if (currentUser != null) {
            firebaseManager.updateUserActiveStatus(currentUser.getUid(), true, new Date());
        }
    }

    /**
     * Fetches the core list of Georgia birds in the background.
     * This data can then be used by other fragments as needed.
     */
    private void loadCachedGeorgiaBirdListImmediately() {
        if (birdCacheManager == null) return;

        List<JSONObject> cachedBirds = birdCacheManager.getCachedCoreGeorgiaBirds();
        if (!cachedBirds.isEmpty()) {
            allGeorgiaBirds.clear();
            allGeorgiaBirds.addAll(cachedBirds);
            Log.d(TAG, "Loaded " + allGeorgiaBirds.size() + " cached Georgia birds immediately on app launch.");
        }
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void fetchCoreGeorgiaBirdList() {
        if (isFetchingBirds) return;

        boolean hasCachedList = birdCacheManager != null && !birdCacheManager.getCachedCoreGeorgiaBirds().isEmpty();
        if (!networkMonitor.isConnected() && !hasCachedList) {
            Log.w(TAG, "Attempted to fetchCoreGeorgiaBirdList but no network and no cached Georgia list in HomeActivity.");
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
                Log.d(TAG, "Loaded " + allGeorgiaBirds.size() + " core Georgia birds.");
            }

            @Override
            public void onFailure(Exception e) {
                isFetchingBirds = false;
                Log.e(TAG, "Failed to fetch core bird list in HomeActivity: " + e.getMessage(), e);
                if (allGeorgiaBirds.isEmpty()) {
                    Toast.makeText(HomeActivity.this, "Failed to load core bird data in background.", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /**
     * Main logic block for this part of the feature.
     */
    private boolean shouldCheckBirdDataSync(boolean forceRefresh) {
        if (birdCacheManager == null) return true;
        return birdCacheManager.shouldCheckGeorgiaBirdSync(
                forceRefresh,
                GEORGIA_SYNC_CHECK_TTL_MS,
                GEORGIA_BIRD_DATA_REFRESH_VERSION
        );
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void maybeCheckBirdDataSync(boolean forceRefresh) {
        if (!forceRefresh && !shouldCheckBirdDataSync(false)) {
            Log.d(TAG, "Skipping Georgia bird sync check on launch. Cache and sync-check state are still fresh.");
            return;
        }

        if (!networkMonitor.isConnected()) {
            Log.w(TAG, "Skipping Georgia bird sync check because the device is offline.");
            return;
        }

        checkBirdDataSync();
    }

    /**
     * Main logic block for this part of the feature.
     */
    public void refreshGeorgiaBirdDataManually() {
        fetchCoreGeorgiaBirdList();
        maybeCheckBirdDataSync(true);
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
                Toast.makeText(this, "Internet connection restored. Retrying bird list fetch.", Toast.LENGTH_SHORT).show();
                fetchCoreGeorgiaBirdList();
            }

            if (shouldCheckBirdDataSync(false)) {
                maybeCheckBirdDataSync(false);
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
                if (birdCacheManager != null) {
                    birdCacheManager.markGeorgiaSyncCheckNow();
                    birdCacheManager.setGeorgiaDataRefreshVersion(GEORGIA_BIRD_DATA_REFRESH_VERSION);
                }
            } else {
                Log.e(TAG, "Automatic bird sync failed", task.getException());
            }
        });
    }
}