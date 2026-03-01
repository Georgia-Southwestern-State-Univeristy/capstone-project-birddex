package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * HomeActivity serves as the main navigation hub of the application.
 * This version pre-adds the main fragments and switches with show/hide
 * so tabs are not recreated every time.
 */
public class HomeActivity extends AppCompatActivity implements NetworkMonitor.NetworkStatusListener {

    private static final String TAG = "HomeActivity";

    private static final String TAG_FORUM = "tab_forum";
    private static final String TAG_COLLECTION = "tab_collection";
    private static final String TAG_NEARBY = "tab_nearby";
    private static final String TAG_PROFILE = "tab_profile";
    private static final String TAG_EXTERNAL_PROFILE = "external_profile";

    private BottomNavigationView bottomNav;

    // Tracks the last "real" tab (anything except camera)
    private int lastNonCameraTabId = R.id.nav_forum;
    private boolean suppressNavCallback = false;

    private EbirdApi ebirdApi;
    private List<JSONObject> allGeorgiaBirds;
    private NetworkMonitor networkMonitor;

    private ForumFragment forumFragment;
    private SearchCollectionFragment searchCollectionFragment;
    private NearbyFragment nearbyFragment;
    private ProfileFragment profileFragment;

    private Fragment externalProfileFragment;
    private Fragment currentFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        bottomNav = findViewById(R.id.bottomNav);

        ebirdApi = new EbirdApi();
        allGeorgiaBirds = new ArrayList<>();
        networkMonitor = new NetworkMonitor(this, this);

        initializeFragments(savedInstanceState);
        setupBottomNavigation();

        // Start background bird list load
        fetchCoreGeorgiaBirdList();

        // Handle incoming request to open a user profile
        handleIntent(getIntent());

        // Make sure the correct tab is highlighted on app open
        setBottomNavSelection(lastNonCameraTabId);
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
            setBottomNavSelection(lastNonCameraTabId);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        networkMonitor.unregister();
    }

    private void initializeFragments(Bundle savedInstanceState) {
        FragmentManager fm = getSupportFragmentManager();

        if (savedInstanceState == null) {
            forumFragment = new ForumFragment();
            searchCollectionFragment = new SearchCollectionFragment();
            nearbyFragment = new NearbyFragment();
            profileFragment = new ProfileFragment();

            // Pre-add all main fragments once.
            // Profile is created here too, so it can start loading BEFORE the user taps it.
            fm.beginTransaction()
                    .add(R.id.fragmentContainer, profileFragment, TAG_PROFILE)
                    .hide(profileFragment)
                    .add(R.id.fragmentContainer, nearbyFragment, TAG_NEARBY)
                    .hide(nearbyFragment)
                    .add(R.id.fragmentContainer, searchCollectionFragment, TAG_COLLECTION)
                    .hide(searchCollectionFragment)
                    .add(R.id.fragmentContainer, forumFragment, TAG_FORUM)
                    .commitNow();

            currentFragment = forumFragment;
            lastNonCameraTabId = R.id.nav_forum;
        } else {
            forumFragment = (ForumFragment) fm.findFragmentByTag(TAG_FORUM);
            searchCollectionFragment = (SearchCollectionFragment) fm.findFragmentByTag(TAG_COLLECTION);
            nearbyFragment = (NearbyFragment) fm.findFragmentByTag(TAG_NEARBY);
            profileFragment = (ProfileFragment) fm.findFragmentByTag(TAG_PROFILE);
            externalProfileFragment = fm.findFragmentByTag(TAG_EXTERNAL_PROFILE);

            if (forumFragment == null) forumFragment = new ForumFragment();
            if (searchCollectionFragment == null) searchCollectionFragment = new SearchCollectionFragment();
            if (nearbyFragment == null) nearbyFragment = new NearbyFragment();
            if (profileFragment == null) profileFragment = new ProfileFragment();

            currentFragment = findVisibleFragment();

            if (currentFragment == null) {
                currentFragment = forumFragment;
            }

            lastNonCameraTabId = resolveNavIdForFragment(currentFragment);
        }
    }

    private void setupBottomNavigation() {
        bottomNav.setOnItemSelectedListener(item -> {
            if (suppressNavCallback) {
                return true;
            }

            int id = item.getItemId();

            if (id == R.id.nav_camera) {
                startActivity(new Intent(HomeActivity.this, ImageUploadActivity.class));

                // Keep highlight on the last real tab
                bottomNav.post(() -> setBottomNavSelection(lastNonCameraTabId));
                return false;
            } else if (id == R.id.nav_search_collection) {
                switchToMainFragment(searchCollectionFragment, R.id.nav_search_collection);
                return true;
            } else if (id == R.id.nav_forum) {
                switchToMainFragment(forumFragment, R.id.nav_forum);
                return true;
            } else if (id == R.id.nav_nearby) {
                switchToMainFragment(nearbyFragment, R.id.nav_nearby);
                return true;
            } else if (id == R.id.nav_profile) {
                switchToMainFragment(profileFragment, R.id.nav_profile);
                return true;
            }

            return false;
        });
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !intent.hasExtra("target_user_id")) {
            return;
        }

        String targetUserId = intent.getStringExtra("target_user_id");
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            intent.removeExtra("target_user_id");
            return;
        }

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        String currentUserId = currentUser != null ? currentUser.getUid() : null;

        if (currentUserId != null && currentUserId.equals(targetUserId)) {
            switchToMainFragment(profileFragment, R.id.nav_profile);
            setBottomNavSelection(R.id.nav_profile);
        } else {
            openExternalProfile(targetUserId);
            setBottomNavSelection(R.id.nav_profile);
        }

        intent.removeExtra("target_user_id");
    }

    private void switchToMainFragment(Fragment target, int navId) {
        if (target == null) return;

        FragmentTransaction transaction = getSupportFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true);

        // Remove temporary external profile if it exists
        if (externalProfileFragment != null && externalProfileFragment.isAdded()) {
            transaction.remove(externalProfileFragment);
            if (currentFragment == externalProfileFragment) {
                currentFragment = null;
            }
            externalProfileFragment = null;
        }

        if (currentFragment != null && currentFragment != target && currentFragment.isAdded()) {
            transaction.hide(currentFragment);
        }

        if (target.isAdded()) {
            transaction.show(target);
        } else {
            transaction.add(R.id.fragmentContainer, target, getTagForMainFragment(target));
        }

        transaction.commit();

        currentFragment = target;
        lastNonCameraTabId = navId;
    }

    private void openExternalProfile(String userId) {
        FragmentTransaction transaction = getSupportFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true);

        if (externalProfileFragment != null && externalProfileFragment.isAdded()) {
            transaction.remove(externalProfileFragment);
        }

        if (currentFragment != null && currentFragment.isAdded()) {
            transaction.hide(currentFragment);
        }

        externalProfileFragment = ProfileFragment.newInstance(userId);
        transaction.add(R.id.fragmentContainer, externalProfileFragment, TAG_EXTERNAL_PROFILE);
        transaction.commit();

        currentFragment = externalProfileFragment;
        lastNonCameraTabId = R.id.nav_profile;
    }

    private Fragment findVisibleFragment() {
        List<Fragment> fragments = getSupportFragmentManager().getFragments();
        for (Fragment fragment : fragments) {
            if (fragment != null && fragment.isVisible()) {
                return fragment;
            }
        }
        return null;
    }

    private int resolveNavIdForFragment(Fragment fragment) {
        if (fragment == searchCollectionFragment) return R.id.nav_search_collection;
        if (fragment == nearbyFragment) return R.id.nav_nearby;
        if (fragment == profileFragment || fragment == externalProfileFragment) return R.id.nav_profile;
        return R.id.nav_forum;
    }

    private String getTagForMainFragment(Fragment fragment) {
        if (fragment == forumFragment) return TAG_FORUM;
        if (fragment == searchCollectionFragment) return TAG_COLLECTION;
        if (fragment == nearbyFragment) return TAG_NEARBY;
        if (fragment == profileFragment) return TAG_PROFILE;
        return fragment.getClass().getSimpleName();
    }

    private void setBottomNavSelection(int itemId) {
        if (bottomNav == null) return;

        suppressNavCallback = true;
        bottomNav.setSelectedItemId(itemId);
        suppressNavCallback = false;
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
            Toast.makeText(this, "Internet connection restored. Retrying bird list fetch.", Toast.LENGTH_SHORT).show();
            fetchCoreGeorgiaBirdList();
        }
    }

    @Override
    public void onNetworkLost() {
        Log.e(TAG, "Network lost in HomeActivity.");
        Toast.makeText(this, "Internet connection lost. Bird data may be incomplete.", Toast.LENGTH_LONG).show();
    }
}