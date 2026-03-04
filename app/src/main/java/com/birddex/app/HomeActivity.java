package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * HomeActivity serves as the main navigation hub of the application.
 * It keeps the main tabs alive, preloads them on launch, and only opens
 * camera as a separate activity.
 */
public class HomeActivity extends AppCompatActivity implements NetworkMonitor.NetworkStatusListener {

    public static final String EXTRA_SHOW_STARTUP_LOADING = "show_startup_loading";

    private static final String TAG = "HomeActivity";
    private static final String TAG_FORUM = "tab_forum";
    private static final String TAG_COLLECTION = "tab_collection";
    private static final String TAG_NEARBY = "tab_nearby";
    private static final String TAG_PROFILE = "tab_profile";
    private static final String STATE_LAST_TAB_ID = "state_last_tab_id";
    private static final long STARTUP_LOADING_MIN_MS = 2200L;

    private final Handler startupHandler = new Handler(Looper.getMainLooper());
    private final String[] loadingMessages = {
            "Preparing your flock...",
            "Loading your collection...",
            "Checking nearby sightings...",
            "Warming up the forum...",
            "Getting your profile ready..."
    };

    private BottomNavigationView bottomNav;
    private View startupLoadingOverlay;
    private TextView tvStartupLoadingText;

    private Runnable startupTextCycler;
    private int loadingMessageIndex = 0;

    // Tracks the last real tab (anything except camera)
    private int lastNonCameraTabId = R.id.nav_forum;

    private ForumFragment forumFragment;
    private SearchCollectionFragment searchCollectionFragment;
    private NearbyFragment nearbyFragment;
    private ProfileFragment profileFragment;
    private Fragment activeFragment;

    private EbirdApi ebirdApi;
    private final List<JSONObject> allGeorgiaBirds = new ArrayList<>();
    private NetworkMonitor networkMonitor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        bottomNav = findViewById(R.id.bottomNav);
        startupLoadingOverlay = findViewById(R.id.startupLoadingOverlay);
        tvStartupLoadingText = findViewById(R.id.tvStartupLoadingText);

        ebirdApi = new EbirdApi();
        networkMonitor = new NetworkMonitor(this, this);

        fetchCoreGeorgiaBirdList();
        initializeFragments(savedInstanceState, getIntent());
        setupBottomNavigation();

        if (savedInstanceState == null) {
            bottomNav.getMenu().findItem(lastNonCameraTabId).setChecked(true);

            if (shouldShowStartupLoading(getIntent())) {
                showStartupLoading();
                startupHandler.postDelayed(this::hideStartupLoading, STARTUP_LOADING_MIN_MS);
            } else {
                hideStartupLoadingImmediate();
            }
        } else {
            lastNonCameraTabId = savedInstanceState.getInt(
                    STATE_LAST_TAB_ID,
                    resolveSelectedTabFromActiveFragment()
            );
            bottomNav.getMenu().findItem(lastNonCameraTabId).setChecked(true);
            hideStartupLoadingImmediate();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        networkMonitor.register();

        if (bottomNav != null && bottomNav.getSelectedItemId() != lastNonCameraTabId) {
            bottomNav.setSelectedItemId(lastNonCameraTabId);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        networkMonitor.unregister();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStartupTextCycling();
        startupHandler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_LAST_TAB_ID, lastNonCameraTabId);
    }

    private void setupBottomNavigation() {
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_camera) {
                startActivity(new Intent(HomeActivity.this, ImageUploadActivity.class));

                // restore highlight to previous real tab
                bottomNav.post(() -> bottomNav.setSelectedItemId(lastNonCameraTabId));
                return false;
            }

            Fragment targetFragment = getFragmentForTab(id);
            if (targetFragment == null) {
                return false;
            }

            lastNonCameraTabId = id;
            switchToFragment(targetFragment);
            return true;
        });
    }

    private void initializeFragments(Bundle savedInstanceState, Intent intent) {
        FragmentManager fm = getSupportFragmentManager();

        if (savedInstanceState == null) {
            String targetUserId = intent != null ? intent.getStringExtra("target_user_id") : null;

            forumFragment = new ForumFragment();
            searchCollectionFragment = new SearchCollectionFragment();
            nearbyFragment = new NearbyFragment();
            profileFragment = (targetUserId != null && !targetUserId.trim().isEmpty())
                    ? ProfileFragment.newInstance(targetUserId)
                    : new ProfileFragment();

            FragmentTransaction transaction = fm.beginTransaction();
            transaction.setReorderingAllowed(true);
            transaction.add(R.id.fragmentContainer, forumFragment, TAG_FORUM);
            transaction.add(R.id.fragmentContainer, searchCollectionFragment, TAG_COLLECTION).hide(searchCollectionFragment);
            transaction.add(R.id.fragmentContainer, nearbyFragment, TAG_NEARBY).hide(nearbyFragment);
            transaction.add(R.id.fragmentContainer, profileFragment, TAG_PROFILE).hide(profileFragment);
            transaction.commitNow();

            activeFragment = forumFragment;
            lastNonCameraTabId = R.id.nav_forum;

            if (targetUserId != null && !targetUserId.trim().isEmpty()) {
                lastNonCameraTabId = R.id.nav_profile;
                switchToFragment(profileFragment);
            }
        } else {
            forumFragment = (ForumFragment) fm.findFragmentByTag(TAG_FORUM);
            searchCollectionFragment = (SearchCollectionFragment) fm.findFragmentByTag(TAG_COLLECTION);
            nearbyFragment = (NearbyFragment) fm.findFragmentByTag(TAG_NEARBY);
            profileFragment = (ProfileFragment) fm.findFragmentByTag(TAG_PROFILE);
            activeFragment = findVisibleFragment();

            if (activeFragment == null) {
                activeFragment = forumFragment;
            }

            handleIntent(intent);
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;

        String targetUserId = intent.getStringExtra("target_user_id");
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            return;
        }

        replaceProfileFragment(targetUserId);
        lastNonCameraTabId = R.id.nav_profile;
        bottomNav.getMenu().findItem(R.id.nav_profile).setChecked(true);
        switchToFragment(profileFragment);
    }

    private void replaceProfileFragment(String targetUserId) {
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction transaction = fm.beginTransaction();
        transaction.setReorderingAllowed(true);

        if (profileFragment != null && profileFragment.isAdded()) {
            transaction.remove(profileFragment);
        }

        profileFragment = ProfileFragment.newInstance(targetUserId);
        transaction.add(R.id.fragmentContainer, profileFragment, TAG_PROFILE).hide(profileFragment);
        transaction.commitNow();
    }

    private Fragment findVisibleFragment() {
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment != null && fragment.isAdded() && !fragment.isHidden()) {
                return fragment;
            }
        }
        return null;
    }

    private Fragment getFragmentForTab(int tabId) {
        if (tabId == R.id.nav_forum) return forumFragment;
        if (tabId == R.id.nav_search_collection) return searchCollectionFragment;
        if (tabId == R.id.nav_nearby) return nearbyFragment;
        if (tabId == R.id.nav_profile) return profileFragment;
        return null;
    }

    private void switchToFragment(Fragment targetFragment) {
        if (targetFragment == null || targetFragment == activeFragment) {
            return;
        }

        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.setReorderingAllowed(true);

        if (activeFragment != null && activeFragment.isAdded()) {
            transaction.hide(activeFragment);
        }

        transaction.show(targetFragment);
        transaction.commit();
        activeFragment = targetFragment;
    }

    private int resolveSelectedTabFromActiveFragment() {
        if (activeFragment == profileFragment) return R.id.nav_profile;
        if (activeFragment == nearbyFragment) return R.id.nav_nearby;
        if (activeFragment == searchCollectionFragment) return R.id.nav_search_collection;
        return R.id.nav_forum;
    }

    private boolean shouldShowStartupLoading(Intent intent) {
        return intent != null && intent.getBooleanExtra(EXTRA_SHOW_STARTUP_LOADING, false);
    }

    private void showStartupLoading() {
        if (startupLoadingOverlay == null) return;
        startupLoadingOverlay.setVisibility(View.VISIBLE);
        startStartupTextCycling();
    }

    private void hideStartupLoading() {
        hideStartupLoadingImmediate();

        Intent cleanedIntent = new Intent(getIntent());
        cleanedIntent.removeExtra(EXTRA_SHOW_STARTUP_LOADING);
        setIntent(cleanedIntent);
    }

    private void hideStartupLoadingImmediate() {
        stopStartupTextCycling();
        if (startupLoadingOverlay != null) {
            startupLoadingOverlay.setVisibility(View.GONE);
        }
    }

    private void startStartupTextCycling() {
        if (tvStartupLoadingText == null) return;

        stopStartupTextCycling();
        startupTextCycler = new Runnable() {
            @Override
            public void run() {
                if (tvStartupLoadingText == null) return;
                tvStartupLoadingText.setText(loadingMessages[loadingMessageIndex]);
                loadingMessageIndex = (loadingMessageIndex + 1) % loadingMessages.length;
                startupHandler.postDelayed(this, 1200L);
            }
        };
        startupHandler.post(startupTextCycler);
    }

    private void stopStartupTextCycling() {
        if (startupTextCycler != null) {
            startupHandler.removeCallbacks(startupTextCycler);
        }
    }

    /**
     * Fetches the core list of Georgia birds in the background.
     */
    private void fetchCoreGeorgiaBirdList() {
        if (!networkMonitor.isConnected()) {
            Log.w(TAG, "Attempted to fetchCoreGeorgiaBirdList but no network in HomeActivity.");
            Toast.makeText(this, "No internet to fetch bird list.", Toast.LENGTH_SHORT).show();
            return;
        }

        ebirdApi.fetchCoreGeorgiaBirdList(new EbirdApi.EbirdCoreBirdListCallback() {
            @Override
            public void onSuccess(List<JSONObject> birds) {
                allGeorgiaBirds.clear();
                allGeorgiaBirds.addAll(birds);
                Log.d(TAG, "Loaded " + allGeorgiaBirds.size() + " core Georgia birds from Cloud cache.");
            }

            @Override
            public void onFailure(Exception e) {
                Log.e(TAG, "Failed to fetch core bird list in HomeActivity: " + e.getMessage(), e);
                Toast.makeText(HomeActivity.this, "Failed to load core bird data in background.", Toast.LENGTH_LONG).show();
            }
        });
    }

    public List<JSONObject> getAllGeorgiaBirds() {
        return allGeorgiaBirds;
    }

    @Override
    public void onNetworkAvailable() {
        Log.d(TAG, "Network became available in HomeActivity.");
        if (allGeorgiaBirds.isEmpty()) {
            Toast.makeText(this, "Internet connected. Loading bird list...", Toast.LENGTH_SHORT).show();
            fetchCoreGeorgiaBirdList();
        }
    }

    @Override
    public void onNetworkLost() {
        Log.e(TAG, "Network lost in HomeActivity.");
        Toast.makeText(this, "Internet connection lost.", Toast.LENGTH_LONG).show();
    }
}