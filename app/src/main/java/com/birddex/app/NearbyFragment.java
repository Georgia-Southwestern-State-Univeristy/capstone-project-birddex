package com.birddex.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Window;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NearbyFragment displays bird sightings based on the user's current location.
 */
/**
 * NearbyFragment: Nearby birds screen that uses location plus bird data to build the local sightings list.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class NearbyFragment extends Fragment {

    private static final String TAG = "NearbyFragment";

    private TextView txtLocation;
    private RecyclerView rvNearby;
    private NearbyAdapter adapter;
    private ImageButton btnRefresh, btnSearch, btnMap;
    private ProgressBar pbLoading;
    private TextView tvNoBirds;

    private FusedLocationProviderClient fusedLocationClient;
    private ActivityResultLauncher<String[]> locationPermissionLauncher;

    private LocationRequest locationRequest;
    private LocationCallback locationCallback;
    private Location currentLocation, lastFetchLocation;
    private FirebaseManager firebaseManager;
    private FirebaseFirestore db;
    private EbirdApi ebirdApi;
    private BirdCacheManager cacheManager;
    private Location lastSavedTrackedBirdLocation;
    private long lastSavedTrackedBirdLocationAt = 0L;

    private boolean isUpdating = false;
    private boolean isSearchDataLoading = false;
    private ExecutorService geoExecutor;

    private int fetchGeneration = 0;
    private final AtomicInteger fetchCount = new AtomicInteger(0);
    private final List<Bird> latestFirestoreResults = Collections.synchronizedList(new ArrayList<>());
    private final List<Bird> latestEbirdResults = Collections.synchronizedList(new ArrayList<>());
    private final List<Bird> searchableBirds = new CopyOnWriteArrayList<>();

    private boolean isNavigating = false;

    // NearbyFragment search state preservation
    private boolean reopenBirdSearchOnResume = false;
    private boolean isRestoringBirdSearchState = false;

    private BottomSheetDialog birdSearchDialog;
    private EditText birdSearchEditText;
    private RecyclerView birdSearchRecyclerView;
    private TextView birdSearchEmptyText;
    private NearbyBirdSearchAdapter birdSearchAdapter;
    private LinearLayoutManager birdSearchLayoutManager;

    private String savedBirdSearchQuery = "";
    private int savedBirdSearchScrollPosition = 0;
    private int savedBirdSearchScrollOffset = 0;

    private static final long LOCATION_UPDATE_INTERVAL_MS = 3 * 60 * 1000;
    private static final double SEARCH_RADIUS_METERS = 50000;
    private static final long SIGHTING_RECENCY_MS = 72L * 60 * 60 * 1000;

    private static final float MIN_DISTANCE_FOR_FETCH = 1000f;
    private static final int MAX_USER_SIGHTINGS = 500;
    private static final float TRACKED_BIRD_LOCATION_SAVE_MIN_DISTANCE_METERS = 1609.34f; // ~1 mile
    private static final long TRACKED_BIRD_LOCATION_SAVE_MIN_INTERVAL_MS = 30L * 60L * 1000L; // 30 minutes
    private static final long NEARBY_LOCAL_CACHE_MAX_AGE_MS = BirdCacheManager.NEARBY_CACHE_TTL_MS;

    /**
     * Android calls this to inflate the Fragment's XML and return the root view that will be shown
     * on screen.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        View v = inflater.inflate(R.layout.fragment_nearby, container, false);
        txtLocation = v.findViewById(R.id.txtLocation);
        rvNearby = v.findViewById(R.id.rvNearby);
        btnRefresh = v.findViewById(R.id.btnRefresh);
        btnSearch = v.findViewById(R.id.btnSearch);
        btnMap = v.findViewById(R.id.btnMap);
        pbLoading = v.findViewById(R.id.pbLoading);
        tvNoBirds = v.findViewById(R.id.tvNoBirds);

        rvNearby.setLayoutManager(new LinearLayoutManager(requireContext()));
        // Hook the data source to the list/grid adapter so model objects can render as UI rows/cards.
        adapter = new NearbyAdapter(new ArrayList<>());
        rvNearby.setAdapter(adapter);

        // Set up or query the Firebase layer that supplies/stores this feature's data.
        firebaseManager = new FirebaseManager(requireContext());
        db = FirebaseFirestore.getInstance();
        ebirdApi = new EbirdApi(requireContext().getApplicationContext());
        cacheManager = new BirdCacheManager(requireContext());
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());

        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL_MS)
                .setMinUpdateIntervalMillis(LOCATION_UPDATE_INTERVAL_MS / 2)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult lr) {
                Location l = lr.getLastLocation();
                if (l != null) handleNewLocation(l, false);
            }
        };

        locationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                res -> {
                    if (Boolean.TRUE.equals(res.get(Manifest.permission.ACCESS_FINE_LOCATION))
                            || Boolean.TRUE.equals(res.get(Manifest.permission.ACCESS_COARSE_LOCATION))) {
                        startLocationUpdates();
                    }
                }
        );

        // Attach the user interaction that should run when this control is tapped.
        btnRefresh.setOnClickListener(view -> requestLocationOrLoad(true));
        btnSearch.setOnClickListener(view -> openBirdSearchDialog());
        btnMap.setOnClickListener(view -> openHeatmapScreen());

        loadCachedData();
        return v;
    }

    /**
     * Runs when the screen returns to the foreground, so it often refreshes UI state or restarts
     * listeners.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    @Override
    public void onResume() {
        super.onResume();
        isNavigating = false;
        if (adapter != null) adapter.setNavigating(false);
        if (geoExecutor == null || geoExecutor.isShutdown()) geoExecutor = Executors.newSingleThreadExecutor();
        requestLocationOrLoad(false);
        primeSearchableBirds();

        if (reopenBirdSearchOnResume) {
            reopenBirdSearchOnResume = false;
            rvNearby.post(this::openBirdSearchDialog);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopLocationUpdates();
        saveBirdSearchUiState();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (geoExecutor != null) geoExecutor.shutdownNow();
        cleanupBirdSearchDialogRefs();
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     */
    private void loadCachedData() {
        List<Bird> cached = cacheManager.getCachedNearbyBirds();
        if (!cached.isEmpty() && cacheManager.hasFreshNearbyBirds(NEARBY_LOCAL_CACHE_MAX_AGE_MS)) {
            adapter.updateList(cached);
            rvNearby.setVisibility(View.VISIBLE);
            tvNoBirds.setVisibility(View.GONE);
            pbLoading.setVisibility(View.GONE);
        }
    }

    /**
     * Central handler that reacts to an event/input and decides what the next app action should
     * be.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private void handleNewLocation(Location l, boolean force) {
        currentLocation = l;
        maybeSaveTrackedBirdNotificationLocation(l);

        if (!force && cacheManager.hasFreshNearbyBirdsForLocation(
                l.getLatitude(),
                l.getLongitude(),
                NEARBY_LOCAL_CACHE_MAX_AGE_MS,
                MIN_DISTANCE_FOR_FETCH
        )) {
            Log.d(TAG, "Using fresh nearby cache for current location.");
            lastFetchLocation = l;
            List<Bird> cached = cacheManager.getCachedNearbyBirds();
            if (!cached.isEmpty()) {
                adapter.updateList(cached);
                rvNearby.setVisibility(View.VISIBLE);
                tvNoBirds.setVisibility(View.GONE);
                pbLoading.setVisibility(View.GONE);
            }
        } else if (force || lastFetchLocation == null || l.distanceTo(lastFetchLocation) > MIN_DISTANCE_FOR_FETCH) {
            Log.d(TAG, "Fetching new data for location: " + l.getLatitude() + ", " + l.getLongitude());
            lastFetchLocation = l;
            fetchAllNearbyData(force);
        }

        if (geoExecutor != null && !geoExecutor.isShutdown()) {
            geoExecutor.execute(() -> {
                String p = getCityStateFromLocation(l);
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        if (isAdded()) txtLocation.setText("Location: " + p);
                    });
                }
            });
        }
    }

    private void maybeSaveTrackedBirdNotificationLocation(Location location) {
        if (location == null || firebaseManager == null || firebaseManager.getCurrentUser() == null) {
            return;
        }

        // Skip obviously poor fixes if accuracy is available and very bad.
        if (location.hasAccuracy() && location.getAccuracy() > 200f) {
            return;
        }

        long now = System.currentTimeMillis();

        boolean shouldSaveBecauseNeverSaved =
                lastSavedTrackedBirdLocation == null || lastSavedTrackedBirdLocationAt <= 0L;

        boolean shouldSaveBecauseOld =
                !shouldSaveBecauseNeverSaved &&
                        (now - lastSavedTrackedBirdLocationAt) >= TRACKED_BIRD_LOCATION_SAVE_MIN_INTERVAL_MS;

        boolean shouldSaveBecauseMoved = false;
        if (!shouldSaveBecauseNeverSaved && lastSavedTrackedBirdLocation != null) {
            shouldSaveBecauseMoved =
                    location.distanceTo(lastSavedTrackedBirdLocation) >= TRACKED_BIRD_LOCATION_SAVE_MIN_DISTANCE_METERS;
        }

        if (!shouldSaveBecauseNeverSaved && !shouldSaveBecauseOld && !shouldSaveBecauseMoved) {
            return;
        }

        String uid = firebaseManager.getCurrentUser().getUid();
        if (uid == null || uid.trim().isEmpty()) {
            return;
        }

        Map<String, Object> updates = new java.util.HashMap<>();
        updates.put("lastKnownLatitude", location.getLatitude());
        updates.put("lastKnownLongitude", location.getLongitude());
        updates.put("lastKnownLocationUpdatedAt", new Date());

        db.collection("users")
                .document(uid)
                .update(updates)
                .addOnSuccessListener(unused -> {
                    lastSavedTrackedBirdLocation = new Location(location);
                    lastSavedTrackedBirdLocationAt = now;
                    Log.d(TAG, "Saved tracked-bird notification location for user.");
                })
                .addOnFailureListener(e ->
                        Log.e(TAG, "Failed to save tracked-bird notification location.", e)
                );
    }

    /**
     * Main logic block for this part of the feature.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private void requestLocationOrLoad(boolean force) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(l -> {
                        if (l != null) handleNewLocation(l, force);
                    });
            startLocationUpdates();
        } else {
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    private void startLocationUpdates() {
        if (!isUpdating) {
            try {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
                isUpdating = true;
            } catch (SecurityException ignored) {
            }
        }
    }

    private void stopLocationUpdates() {
        if (isUpdating) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            isUpdating = false;
        }
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private String getCityStateFromLocation(Location location) {
        Geocoder geocoder = new Geocoder(requireContext(), Locale.getDefault());
        try {
            List<Address> results = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
            if (results != null && !results.isEmpty()) {
                Address a = results.get(0);
                String city = a.getLocality() != null ? a.getLocality() : a.getSubAdminArea();
                String state = a.getAdminArea();
                if (city != null && state != null) return city + ", " + state;
                return state != null ? state : "Nearby";
            }
        } catch (IOException e) {
            Log.e(TAG, "Geocoder failed", e);
        }
        return "Nearby";
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private synchronized void fetchAllNearbyData(boolean forceRefresh) {
        if (currentLocation == null) return;
        final int myGen = ++fetchGeneration;
        // Persist the new state so the action is saved outside the current screen.
        fetchCount.set(0);
        latestFirestoreResults.clear();
        latestEbirdResults.clear();
        Log.d(TAG, "Starting dual-fetch (gen=" + myGen + ", force=" + forceRefresh + ")");
        if (isAdded()) {
            requireActivity().runOnUiThread(() -> {
                if (isAdded()) {
                    pbLoading.setVisibility(View.VISIBLE);
                    rvNearby.setVisibility(View.GONE);
                    tvNoBirds.setVisibility(View.GONE);
                }
            });
        }
        loadUserSightingsNearby(myGen);
        loadEbirdNearby(myGen, forceRefresh);
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    private void loadUserSightingsNearby(final int gen) {
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("userBirdSightings")
                .limit(MAX_USER_SIGHTINGS)
                .get(Source.CACHE)
                .addOnSuccessListener(querySnapshot -> {
                    if (gen != fetchGeneration) return;
                    if (querySnapshot != null && !querySnapshot.isEmpty()) {
                        for (DocumentSnapshot d : querySnapshot.getDocuments()) {
                            Bird b = mapUserSightingToBird(d);
                            if (isValidSighting(b)) {
                                latestFirestoreResults.add(b);
                                cacheSearchBird(b);
                            }
                        }
                    }
                    fetchUserSightingsFromServer(gen);
                })
                .addOnFailureListener(e -> {
                    if (gen != fetchGeneration) return;
                    fetchUserSightingsFromServer(gen);
                });
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    private void fetchUserSightingsFromServer(final int gen) {
        db.collection("userBirdSightings")
                .limit(MAX_USER_SIGHTINGS)
                .get(Source.SERVER)
                .addOnSuccessListener(querySnapshot -> {
                    if (gen != fetchGeneration) return;
                    latestFirestoreResults.clear();
                    for (DocumentSnapshot d : querySnapshot.getDocuments()) {
                        Bird b = mapUserSightingToBird(d);
                        if (isValidSighting(b)) {
                            latestFirestoreResults.add(b);
                            cacheSearchBird(b);
                        }
                    }
                    checkIfAllFetched(gen);
                })
                .addOnFailureListener(e -> {
                    if (gen == fetchGeneration) checkIfAllFetched(gen);
                });
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     */
    private void loadEbirdNearby(final int gen, boolean forceRefresh) {
        ebirdApi.fetchCoreGeorgiaBirdList(forceRefresh, new EbirdApi.EbirdCoreBirdListCallback() {
            @Override
            public void onSuccess(List<JSONObject> birdsJson) {
                if (gen != fetchGeneration) return;
                latestEbirdResults.clear();
                for (JSONObject j : birdsJson) {
                    Bird b = parseBirdJson(j);
                    if (isValidSighting(b)) latestEbirdResults.add(b);
                }
                checkIfAllFetched(gen);
            }

            @Override
            public void onFailure(Exception e) {
                if (gen == fetchGeneration) checkIfAllFetched(gen);
            }
        });
    }

    private void checkIfAllFetched(int gen) {
        if (gen == fetchGeneration && fetchCount.incrementAndGet() >= 2) processFinalList();
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void processFinalList() {
        List<Bird> combined = new ArrayList<>(latestFirestoreResults);
        combined.addAll(latestEbirdResults);
        combined.sort((b1, b2) -> {
            Long t1 = b1.getLastSeenTimestampGeorgia(), t2 = b2.getLastSeenTimestampGeorgia();
            if (t1 == null && t2 == null) return 0;
            if (t1 == null) return 1;
            if (t2 == null) return -1;
            return t2.compareTo(t1);
        });

        cacheManager.saveNearbyBirds(
                combined,
                currentLocation != null ? currentLocation.getLatitude() : null,
                currentLocation != null ? currentLocation.getLongitude() : null
        );

        if (isAdded()) {
            requireActivity().runOnUiThread(() -> {
                if (isAdded()) {
                    pbLoading.setVisibility(View.GONE);
                    if (combined.isEmpty()) {
                        rvNearby.setVisibility(View.GONE);
                        tvNoBirds.setVisibility(View.VISIBLE);
                    } else {
                        adapter.updateList(combined);
                        tvNoBirds.setVisibility(View.GONE);
                        rvNearby.setVisibility(View.VISIBLE);
                    }
                }
            });
        }
    }

    /**
     * Main logic block for this part of the feature.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private Bird mapUserSightingToBird(DocumentSnapshot d) {
        Bird b = new Bird();
        String bid = getStringValue(d, "birdId");
        b.setId(bid != null ? bid : d.getId());
        b.setCommonName(getStringValue(d, "commonName"));
        b.setScientificName(getStringValue(d, "scientificName"));
        b.setLastSeenLatitudeGeorgia(getDoubleValue(d, "location.latitude", "latitude", "lat", "lastSeenLatitudeGeorgia"));
        b.setLastSeenLongitudeGeorgia(getDoubleValue(d, "location.longitude", "longitude", "lng", "lastSeenLongitudeGeorgia"));
        b.setLastSeenTimestampGeorgia(getTimeMillisValue(d, "timestamp", "observationDate", "lastSeenTimestampGeorgia"));

        // Anti-cheat: Mark suspicious sightings
        Boolean susp = d.getBoolean("suspicious");
        b.setSuspicious(susp != null && susp);

        return b;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private boolean isValidSighting(Bird b) {
        if (b == null || b.getLastSeenLatitudeGeorgia() == null || b.getLastSeenTimestampGeorgia() == null || currentLocation == null) {
            return false;
        }

        // Anti-cheat: Exclude suspicious sightings from the list
        if (b.isSuspicious()) return false;

        if (b.getLastSeenTimestampGeorgia() < System.currentTimeMillis() - SIGHTING_RECENCY_MS) return false;
        float[] res = new float[1];
        Location.distanceBetween(
                currentLocation.getLatitude(),
                currentLocation.getLongitude(),
                b.getLastSeenLatitudeGeorgia(),
                b.getLastSeenLongitudeGeorgia(),
                res
        );
        return res[0] <= SEARCH_RADIUS_METERS;
    }

    /**
     * Main logic block for this part of the feature.
     */
    private Bird parseBirdJson(JSONObject json) {
        Bird b = new Bird();
        b.setId(json.optString("id"));
        b.setCommonName(json.optString("commonName"));
        b.setScientificName(json.optString("scientificName"));
        if (!json.isNull("lastSeenLatitudeGeorgia")) b.setLastSeenLatitudeGeorgia(json.optDouble("lastSeenLatitudeGeorgia"));
        if (!json.isNull("lastSeenLongitudeGeorgia")) b.setLastSeenLongitudeGeorgia(json.optDouble("lastSeenLongitudeGeorgia"));
        if (!json.isNull("lastSeenTimestampGeorgia")) b.setLastSeenTimestampGeorgia(json.optLong("lastSeenTimestampGeorgia"));
        return b;
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void primeSearchableBirds() {
        if (isSearchDataLoading || !searchableBirds.isEmpty()) return;
        isSearchDataLoading = true;

        firebaseManager.getAllBirds(task -> {
            try {
                if (task.isSuccessful() && task.getResult() != null) {
                    List<Bird> loaded = new ArrayList<>();
                    for (DocumentSnapshot d : task.getResult().getDocuments()) {
                        Bird b = d.toObject(Bird.class);
                        if (b != null) {
                            if (isBlank(b.getId())) b.setId(d.getId());
                            loaded.add(b);
                        }
                    }

                    loaded.sort((b1, b2) -> b1.getCommonName().compareToIgnoreCase(b2.getCommonName()));
                    searchableBirds.clear();
                    searchableBirds.addAll(loaded);
                }
            } finally {
                isSearchDataLoading = false;
            }
        });
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void cacheSearchBird(Bird bird) {
        if (bird == null || isBlank(bird.getId())) return;
        synchronized (searchableBirds) {
            for (Bird b : searchableBirds) {
                if (bird.getId().equals(b.getId())) return;
            }
            searchableBirds.add(bird);
        }
    }

    /**
     * Moves the user to another screen or flow and passes along the required extras.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void openBirdSearchDialog() {
        if (!isAdded()) return;

        if (birdSearchDialog != null && birdSearchDialog.isShowing()) {
            return;
        }

        // Give the user immediate feedback about the result of this action.
        if (searchableBirds.isEmpty()) {
            primeSearchableBirds();
            MessagePopupHelper.show(requireContext(), "Loading birds...");
            return;
        }

        List<Bird> birds = buildNearbySearchBirdList();
        if (birds.isEmpty()) return;

        View content = LayoutInflater.from(requireContext()).inflate(R.layout.bottom_sheet_nearby_bird_search, null, false);
        birdSearchEditText = content.findViewById(R.id.etBirdSearch);
        birdSearchRecyclerView = content.findViewById(R.id.rvBirdSearch);
        birdSearchEmptyText = content.findViewById(R.id.tvBirdSearchEmpty);

        birdSearchDialog = new BottomSheetDialog(requireContext());
        birdSearchDialog.setContentView(content);

        birdSearchDialog.setOnShowListener(d -> {
            View bottomSheet = birdSearchDialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(android.R.color.transparent);
                BottomSheetBehavior.from(bottomSheet).setState(BottomSheetBehavior.STATE_EXPANDED);
            }

            Window window = birdSearchDialog.getWindow();
            if (window != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.setNavigationBarContrastEnforced(false);
                }

                WindowInsetsControllerCompat controller =
                        WindowCompat.getInsetsController(window, window.getDecorView());

                window.setNavigationBarColor(
                        ContextCompat.getColor(requireContext(), R.color.nav_brown)
                );

                if (controller != null) {
                    controller.setAppearanceLightNavigationBars(false);
                }
            }
        });

        birdSearchLayoutManager = new LinearLayoutManager(requireContext());
        birdSearchRecyclerView.setLayoutManager(birdSearchLayoutManager);

        birdSearchAdapter = new NearbyBirdSearchAdapter(birds, bird -> {
            saveBirdSearchUiState();
            reopenBirdSearchOnResume = true;
            birdSearchDialog.dismiss();
            openBirdWikiPage(bird.getId());
        });

        birdSearchRecyclerView.setAdapter(birdSearchAdapter);
        updateBirdSearchEmptyState();

        // Hide until the previous scroll position has been restored,
        // so the user does not see the list jump from top to saved position.
        birdSearchRecyclerView.setAlpha(0f);

        birdSearchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                savedBirdSearchQuery = s == null ? "" : s.toString();

                if (birdSearchAdapter != null) {
                    birdSearchAdapter.filter(savedBirdSearchQuery);
                }

                updateBirdSearchEmptyState();

                // Only reset scroll when the user is typing.
                // Do not reset while restoring prior search state.
                if (!isRestoringBirdSearchState) {
                    savedBirdSearchScrollPosition = 0;
                    savedBirdSearchScrollOffset = 0;
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        birdSearchDialog.setOnDismissListener(dialog -> {
            boolean reopeningAfterBirdWiki = reopenBirdSearchOnResume;
            cleanupBirdSearchDialogRefs();

            if (!reopeningAfterBirdWiki) {
                clearBirdSearchUiState();
            }
        });

        birdSearchDialog.show();
        restoreBirdSearchUiState();
    }

    private void updateBirdSearchEmptyState() {
        if (birdSearchEmptyText != null && birdSearchAdapter != null) {
            birdSearchEmptyText.setVisibility(birdSearchAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        }
    }

    private void saveBirdSearchUiState() {
        if (birdSearchEditText != null) {
            savedBirdSearchQuery = birdSearchEditText.getText() == null
                    ? ""
                    : birdSearchEditText.getText().toString();
        }

        if (birdSearchLayoutManager != null && birdSearchRecyclerView != null) {
            int firstVisible = birdSearchLayoutManager.findFirstVisibleItemPosition();
            if (firstVisible != RecyclerView.NO_POSITION) {
                View firstVisibleView = birdSearchLayoutManager.findViewByPosition(firstVisible);
                savedBirdSearchScrollPosition = firstVisible;
                savedBirdSearchScrollOffset = firstVisibleView == null
                        ? 0
                        : firstVisibleView.getTop() - birdSearchRecyclerView.getPaddingTop();
            }
        }
    }

    private void restoreBirdSearchUiState() {
        if (birdSearchEditText == null || birdSearchAdapter == null || birdSearchRecyclerView == null) {
            return;
        }

        isRestoringBirdSearchState = true;

        birdSearchEditText.setText(savedBirdSearchQuery);
        birdSearchEditText.setSelection(birdSearchEditText.getText().length());

        if (birdSearchAdapter != null) {
            birdSearchAdapter.filter(savedBirdSearchQuery);
        }

        updateBirdSearchEmptyState();

        final int restorePosition = savedBirdSearchScrollPosition;
        final int restoreOffset = savedBirdSearchScrollOffset;

        birdSearchRecyclerView.post(() -> {
            if (birdSearchLayoutManager != null && birdSearchAdapter != null) {
                int itemCount = birdSearchAdapter.getItemCount();
                if (itemCount > 0) {
                    int safePosition = Math.min(
                            Math.max(restorePosition, 0),
                            itemCount - 1
                    );
                    birdSearchLayoutManager.scrollToPositionWithOffset(safePosition, restoreOffset);
                }
            }

            // Run one more frame later so the layout finishes at the restored spot
            // before we reveal it to the user.
            birdSearchRecyclerView.post(() -> {
                if (birdSearchRecyclerView != null) {
                    birdSearchRecyclerView.setAlpha(1f);
                }
                isRestoringBirdSearchState = false;
            });
        });
    }

    private void clearBirdSearchUiState() {
        reopenBirdSearchOnResume = false;
        savedBirdSearchQuery = "";
        savedBirdSearchScrollPosition = 0;
        savedBirdSearchScrollOffset = 0;

        if (birdSearchRecyclerView != null) {
            birdSearchRecyclerView.setAlpha(1f);
        }
    }

    private void cleanupBirdSearchDialogRefs() {
        isRestoringBirdSearchState = false;

        if (birdSearchRecyclerView != null) {
            birdSearchRecyclerView.setAlpha(1f);
        }

        birdSearchDialog = null;
        birdSearchEditText = null;
        birdSearchRecyclerView = null;
        birdSearchEmptyText = null;
        birdSearchAdapter = null;
        birdSearchLayoutManager = null;
    }

    /**
     * Main logic block for this part of the feature.
     */
    private List<Bird> buildNearbySearchBirdList() {
        LinkedHashMap<String, Bird> deduped = new LinkedHashMap<>();
        for (Bird bird : searchableBirds) {
            if (bird == null || isBlank(bird.getId())) continue;
            String key = bird.getId().trim();
            Bird existing = deduped.get(key);
            if (existing == null) {
                deduped.put(key, bird);
            } else {
                if (isBlank(existing.getCommonName()) && !isBlank(bird.getCommonName())) {
                    existing.setCommonName(bird.getCommonName());
                }
                if (isBlank(existing.getScientificName()) && !isBlank(bird.getScientificName())) {
                    existing.setScientificName(bird.getScientificName());
                }
            }
        }

        List<Bird> birds = new ArrayList<>(deduped.values());
        birds.sort((left, right) -> safeBirdLabel(left).compareToIgnoreCase(safeBirdLabel(right)));
        return birds;
    }

    private String safeBirdLabel(Bird bird) {
        if (bird == null) return "";
        if (!isBlank(bird.getCommonName())) return bird.getCommonName().trim();
        if (!isBlank(bird.getScientificName())) return bird.getScientificName().trim();
        return "Unknown Bird";
    }

    /**
     * Moves the user to another screen or flow and passes along the required extras.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    private void openBirdWikiPage(String id) {
        if (!isAdded() || isBlank(id) || isNavigating) return;
        isNavigating = true;
        // Move into the next screen and pass the identifiers/data that screen needs.
        startActivity(new Intent(requireContext(), BirdWikiActivity.class)
                .putExtra(BirdWikiActivity.EXTRA_BIRD_ID, id));
    }

    /**
     * Moves the user to another screen or flow and passes along the required extras.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private void openHeatmapScreen() {
        if (!isAdded() || isNavigating) return;
        isNavigating = true;
        Intent i = new Intent(requireContext(), NearbyHeatmapActivity.class);
        if (currentLocation != null) {
            i.putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LAT, currentLocation.getLatitude());
            i.putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LNG, currentLocation.getLongitude());
        }
        // Move into the next screen and pass the identifiers/data that screen needs.
        startActivity(i);
    }

    private boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    private String getStringValue(DocumentSnapshot d, String... paths) {
        for (String p : paths) {
            Object v = getNestedValue(d, p);
            if (v != null && !String.valueOf(v).trim().isEmpty()) return String.valueOf(v).trim();
        }
        return null;
    }

    private Double getDoubleValue(DocumentSnapshot d, String... paths) {
        for (String p : paths) {
            Object v = getNestedValue(d, p);
            if (v instanceof Number) return ((Number) v).doubleValue();
        }
        return null;
    }

    private Long getTimeMillisValue(DocumentSnapshot d, String... paths) {
        for (String p : paths) {
            Object v = getNestedValue(d, p);
            if (v instanceof Timestamp) return ((Timestamp) v).toDate().getTime();
            if (v instanceof Date) return ((Date) v).getTime();
            if (v instanceof Number) return ((Number) v).longValue();
        }
        return null;
    }

    private Object getNestedValue(DocumentSnapshot d, String p) {
        if (p == null || p.isEmpty()) return null;
        if (!p.contains(".")) return d.get(p);
        String[] pts = p.split("\\.");
        Object cur = d.get(pts[0]);
        for (int i = 1; i < pts.length; i++) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<?, ?>) cur).get(pts[i]);
        }
        return cur;
    }
}