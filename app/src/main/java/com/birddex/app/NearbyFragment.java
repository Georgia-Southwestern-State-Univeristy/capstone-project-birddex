package com.birddex.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.firestore.DocumentSnapshot;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NearbyFragment displays bird sightings based on the user's current location.
 * It filters sightings by radius (50km) and recency (72 hours).
 */
public class NearbyFragment extends Fragment {

    private static final String TAG = "NearbyFragment";

    private TextView txtLocation;
    private RecyclerView rvNearby;
    private NearbyAdapter adapter;
    private ImageButton btnRefresh;
    private ImageButton btnSearch;
    private ImageButton btnMap;
    private NearbyPreloadManager nearbyPreloadManager;
    private FusedLocationProviderClient fusedLocationClient;
    private ActivityResultLauncher<String[]> locationPermissionLauncher;

    private LocationRequest locationRequest;
    private LocationCallback locationCallback;
    private Location currentLocation;
    private Location lastFetchLocation;
    private String lastPlaceShown = null;

    private FirebaseManager firebaseManager;
    private EbirdApi ebirdApi;

    private boolean isUpdating = false;
    private boolean isSearchDataLoading = false;
    private ExecutorService geoExecutor;
    private int fetchCount = 0;

    private final List<Bird> firestoreBirds = new ArrayList<>();
    private final List<Bird> ebirdBirds = new ArrayList<>();
    private final List<Bird> searchableBirds = new ArrayList<>();

    private static final long LOCATION_UPDATE_INTERVAL_MS = 3 * 60 * 1000;
    private static final double SEARCH_RADIUS_METERS = 50000;
    private static final long SIGHTING_RECENCY_MS = 72L * 60 * 60 * 1000;
    private static final float MIN_DISTANCE_FOR_FETCH = 1000f;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_nearby, container, false);

        txtLocation = v.findViewById(R.id.txtLocation);
        rvNearby = v.findViewById(R.id.rvNearby);
        btnRefresh = v.findViewById(R.id.btnRefresh);
        btnSearch = v.findViewById(R.id.btnSearch);
        btnMap = v.findViewById(R.id.btnMap);

        rvNearby.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new NearbyAdapter(new ArrayList<>());
        rvNearby.setAdapter(adapter);

        firebaseManager = new FirebaseManager(requireContext());
        ebirdApi = new EbirdApi();
        nearbyPreloadManager = NearbyPreloadManager.getInstance();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());

        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL_MS)
                .setMinUpdateIntervalMillis(LOCATION_UPDATE_INTERVAL_MS / 2)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (locationResult.getLocations().isEmpty()) return;

                Location location = locationResult.getLastLocation();
                if (location == null) return;

                Log.d(TAG, "Location update received: " + location.getLatitude() + ", " + location.getLongitude());
                handleNewLocation(location, false);
            }
        };

        locationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    if (Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION)) ||
                            Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION))) {
                        startLocationUpdates();
                    } else {
                        txtLocation.setText("Location: Permission denied");
                    }
                });

        btnRefresh.setOnClickListener(view -> {
            Log.d(TAG, "Manual refresh clicked");
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .addOnSuccessListener(location -> {
                            if (location != null) {
                                handleNewLocation(location, true);
                            } else {
                                fetchAllNearbyData();
                            }
                        });
            } else {
                requestLocationOrLoad();
            }
        });

        btnSearch.setOnClickListener(view -> openBirdSearchDialog());
        btnMap.setOnClickListener(view -> openHeatmapScreen());

        primeSearchableBirds();
        applyPreloadedState();
        primeSearchableBirds();

        return v;
    }

    @Override
    public void onResume() {
        super.onResume();

        if (geoExecutor == null || geoExecutor.isShutdown()) {
            geoExecutor = Executors.newSingleThreadExecutor();
        }

        applyPreloadedState();
        primeSearchableBirds();

        if (isHidden()) {
            return;
        }

        if (currentLocation == null) {
            requestLocationOrLoad();
        } else {
            startLocationUpdates();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopLocationUpdates();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);

        if (hidden) {
            stopLocationUpdates();
            return;
        }

        if (geoExecutor == null || geoExecutor.isShutdown()) {
            geoExecutor = Executors.newSingleThreadExecutor();
        }

        applyPreloadedState();
        primeSearchableBirds();

        if (currentLocation == null) {
            requestLocationOrLoad();
        } else {
            startLocationUpdates();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (geoExecutor != null) geoExecutor.shutdownNow();
    }

    private void applyPreloadedState() {
        if (nearbyPreloadManager == null) {
            return;
        }

        List<Bird> cachedNearbyBirds = nearbyPreloadManager.getCachedNearbyBirds();
        if (!cachedNearbyBirds.isEmpty()) {
            adapter.updateList(cachedNearbyBirds);
        }

        List<Bird> cachedSearchBirds = nearbyPreloadManager.getCachedSearchableBirds();
        if (!cachedSearchBirds.isEmpty() && searchableBirds.isEmpty()) {
            searchableBirds.clear();
            searchableBirds.addAll(cachedSearchBirds);
        }

        String cachedPlace = nearbyPreloadManager.getCachedPlace();
        if (!isBlank(cachedPlace)) {
            txtLocation.setText("Location: " + cachedPlace);
            lastPlaceShown = cachedPlace;
        }

        Location cachedLocation = nearbyPreloadManager.getCachedLocation();
        if (cachedLocation != null) {
            currentLocation = cachedLocation;
            lastFetchLocation = cachedLocation;
        }
    }

    private void handleNewLocation(Location location, boolean forceDataFetch) {
        currentLocation = location;

        boolean shouldFetch = forceDataFetch || lastFetchLocation == null;
        if (!shouldFetch && lastFetchLocation != null) {
            float distance = location.distanceTo(lastFetchLocation);
            if (distance > MIN_DISTANCE_FOR_FETCH) {
                Log.d(TAG, "Significant movement detected (" + distance + "m).");
                shouldFetch = true;
            }
        }

        if (shouldFetch) {
            lastFetchLocation = location;
            fetchAllNearbyData();
        }

        if (geoExecutor != null && !geoExecutor.isShutdown()) {
            geoExecutor.execute(() -> {
                String place = getCityStateFromLocation(location);
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        txtLocation.setText("Location: " + place);
                        lastPlaceShown = place;
                    });
                }
            });
        }
    }

    private void requestLocationOrLoad() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            handleNewLocation(location, false);
                        } else {
                            fusedLocationClient.getLastLocation().addOnSuccessListener(lastLoc -> {
                                if (lastLoc != null) handleNewLocation(lastLoc, false);
                            });
                        }
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
        if (isUpdating) return;

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
            isUpdating = true;
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException: ", se);
        }
    }

    private void stopLocationUpdates() {
        if (!isUpdating) return;
        fusedLocationClient.removeLocationUpdates(locationCallback);
        isUpdating = false;
    }

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

    private synchronized void fetchAllNearbyData() {
        if (currentLocation == null) return;

        fetchCount = 0;
        Log.d(TAG, "Starting dual-fetch from Firestore and eBird...");


        firestoreBirds.clear();
        ebirdBirds.clear();

        firebaseManager.getAllBirds(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                for (DocumentSnapshot doc : task.getResult().getDocuments()) {
                    Bird bird = doc.toObject(Bird.class);

                    if (bird != null && isBlank(bird.getId())) {
                        bird.setId(doc.getId());
                    }

                    if (bird != null) {
                        cacheSearchBird(bird);
                    }

                    if (isValidSighting(bird)) {
                        firestoreBirds.add(bird);
                    }
                }
            }
            checkIfAllFetched();
        });

        ebirdApi.fetchCoreGeorgiaBirdList(new EbirdApi.EbirdCoreBirdListCallback() {
            @Override
            public void onSuccess(List<JSONObject> birdsJson) {
                for (JSONObject json : birdsJson) {
                    Bird bird = parseBirdJson(json);
                    if (isValidSighting(bird)) ebirdBirds.add(bird);
                }
                checkIfAllFetched();
            }

            @Override
            public void onFailure(Exception e) {
                checkIfAllFetched();
            }
        });
    }

    private synchronized void checkIfAllFetched() {
        fetchCount++;
        if (fetchCount >= 2) {
            processFinalList();
        }
    }

    private boolean isValidSighting(Bird bird) {
        if (bird == null ||
                bird.getLastSeenLatitudeGeorgia() == null ||
                bird.getLastSeenLongitudeGeorgia() == null ||
                bird.getLastSeenTimestampGeorgia() == null ||
                currentLocation == null) {
            return false;
        }

        long cutoff = System.currentTimeMillis() - SIGHTING_RECENCY_MS;
        if (bird.getLastSeenTimestampGeorgia() < cutoff) return false;

        float[] results = new float[1];
        Location.distanceBetween(
                currentLocation.getLatitude(),
                currentLocation.getLongitude(),
                bird.getLastSeenLatitudeGeorgia(),
                bird.getLastSeenLongitudeGeorgia(),
                results
        );

        return results[0] <= SEARCH_RADIUS_METERS;
    }

    private Bird parseBirdJson(JSONObject json) {
        Bird b = new Bird();
        b.setId(json.optString("id"));
        b.setCommonName(json.optString("commonName"));
        b.setScientificName(json.optString("scientificName"));
        b.setLastSeenLatitudeGeorgia(json.optDouble("lastSeenLatitudeGeorgia"));
        b.setLastSeenLongitudeGeorgia(json.optDouble("lastSeenLongitudeGeorgia"));
        b.setLastSeenTimestampGeorgia(json.optLong("lastSeenTimestampGeorgia"));
        return b;
    }

    private void processFinalList() {
        List<Bird> combined = new ArrayList<>(firestoreBirds);

        for (Bird eb : ebirdBirds) {
            boolean isDup = false;
            for (Bird b : combined) {
                if (b.getId() != null && b.getId().equals(eb.getId())) {
                    isDup = true;
                    break;
                }
            }
            if (!isDup) combined.add(eb);
        }

        Collections.sort(combined, (b1, b2) -> {
            Long t1 = b1.getLastSeenTimestampGeorgia();
            Long t2 = b2.getLastSeenTimestampGeorgia();
            if (t1 == null && t2 == null) return 0;
            if (t1 == null) return 1;
            if (t2 == null) return -1;
            return t2.compareTo(t1);
        });

        if (nearbyPreloadManager != null) {
            nearbyPreloadManager.updateNearbyCache(currentLocation, lastPlaceShown, combined);
            nearbyPreloadManager.updateSearchableBirds(searchableBirds);
        }

        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() -> adapter.updateList(combined));
        }
    }

    private void primeSearchableBirds() {
        if (nearbyPreloadManager != null) {
            List<Bird> cachedBirds = nearbyPreloadManager.getCachedSearchableBirds();
            if (!cachedBirds.isEmpty()) {
                searchableBirds.clear();
                searchableBirds.addAll(cachedBirds);
                return;
            }
        }

        if (isSearchDataLoading || !searchableBirds.isEmpty()) return;

        isSearchDataLoading = true;

        firebaseManager.getAllBirds(task -> {
            isSearchDataLoading = false;

            if (!task.isSuccessful() || task.getResult() == null) {
                return;
            }

            List<Bird> loadedBirds = new ArrayList<>();

            for (DocumentSnapshot doc : task.getResult().getDocuments()) {
                Bird bird = doc.toObject(Bird.class);
                if (bird == null) continue;

                if (isBlank(bird.getId())) {
                    bird.setId(doc.getId());
                }

                loadedBirds.add(bird);
            }

            Collections.sort(loadedBirds, (b1, b2) ->
                    safeName(b1.getCommonName()).compareToIgnoreCase(safeName(b2.getCommonName())));

            searchableBirds.clear();
            searchableBirds.addAll(loadedBirds);

            if (nearbyPreloadManager != null) {
                nearbyPreloadManager.updateSearchableBirds(loadedBirds);
            }
        });
    }

    private void cacheSearchBird(Bird bird) {
        if (bird == null || isBlank(bird.getId())) return;

        for (Bird existing : searchableBirds) {
            if (bird.getId().equals(existing.getId())) {
                return;
            }
        }

        searchableBirds.add(bird);
    }

    private void openBirdSearchDialog() {
        if (!isAdded()) return;

        if (searchableBirds.isEmpty()) {
            primeSearchableBirds();
            Toast.makeText(requireContext(), "Bird search is still loading. Tap search again.", Toast.LENGTH_SHORT).show();
            return;
        }

        List<SearchBirdItem> items = new ArrayList<>();
        for (Bird bird : searchableBirds) {
            if (isBlank(bird.getId())) continue;

            items.add(new SearchBirdItem(
                    bird.getId(),
                    buildBirdSearchLabel(bird),
                    safeName(bird.getCommonName()),
                    safeName(bird.getScientificName())
            ));
        }

        if (items.isEmpty()) {
            Toast.makeText(requireContext(), "No searchable birds found.", Toast.LENGTH_SHORT).show();
            return;
        }

        Collections.sort(items, (a, b) -> a.label.compareToIgnoreCase(b.label));

        AutoCompleteTextView input = new AutoCompleteTextView(requireContext());
        input.setHint("Search birds");
        input.setThreshold(1);
        input.setSingleLine(true);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setTextColor(ContextCompat.getColor(requireContext(), R.color.on_page));
        input.setHintTextColor(ContextCompat.getColor(requireContext(), R.color.on_page_variant));
        input.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.bg_input));
        input.setDropDownBackgroundDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.bg_white_button));
        input.setPadding(36, 28, 36, 28);

        ArrayAdapter<SearchBirdItem> searchAdapter = new ArrayAdapter<>(
                requireContext(),
                R.layout.item_bird_search_suggestion,
                R.id.tvSuggestionText,
                items
        );
        input.setAdapter(searchAdapter);

        int padding = (int) (16 * requireContext().getResources().getDisplayMetrics().density);

        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(
                input,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("Search Birds")
                .setMessage("Type a common name or scientific name.")
                .setView(container)
                .setPositiveButton("Open", null)
                .setNegativeButton("Cancel", null)
                .create();

        input.setOnItemClickListener((parent, view, position, id) -> {
            SearchBirdItem selected = (SearchBirdItem) parent.getItemAtPosition(position);
            dialog.dismiss();
            openBirdWikiPage(selected.birdId);
        });

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.on_page)
            );
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.on_page_variant)
            );

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String query = input.getText() != null ? input.getText().toString() : "";
                SearchBirdItem match = findBirdMatch(query, items);

                if (match == null) {
                    input.setError("Bird not found");
                    return;
                }

                dialog.dismiss();
                openBirdWikiPage(match.birdId);
            });
        });

        dialog.show();
        input.requestFocus();
    }

    private SearchBirdItem findBirdMatch(String query, List<SearchBirdItem> items) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        if (normalized.isEmpty()) return null;

        for (SearchBirdItem item : items) {
            if (item.commonName.equals(normalized)
                    || item.scientificName.equals(normalized)
                    || item.label.equalsIgnoreCase(query.trim())) {
                return item;
            }
        }

        for (SearchBirdItem item : items) {
            if (item.commonName.contains(normalized) || item.scientificName.contains(normalized)) {
                return item;
            }
        }

        return null;
    }

    private void openBirdWikiPage(String birdId) {
        if (!isAdded() || isBlank(birdId)) {
            Toast.makeText(requireContext(), "No bird info available.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(requireContext(), BirdWikiActivity.class);
        intent.putExtra(BirdWikiActivity.EXTRA_BIRD_ID, birdId);
        startActivity(intent);
    }

    private void openHeatmapScreen() {
        if (!isAdded()) return;

        Intent intent = new Intent(requireContext(), NearbyHeatmapActivity.class);

        if (currentLocation != null) {
            intent.putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LAT, currentLocation.getLatitude());
            intent.putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LNG, currentLocation.getLongitude());
        }

        startActivity(intent);
    }

    private String buildBirdSearchLabel(Bird bird) {
        String commonName = safeName(bird.getCommonName());
        String scientificName = safeName(bird.getScientificName());

        if (!scientificName.isEmpty()) {
            return commonName + " (" + scientificName + ")";
        }

        return commonName;
    }

    private String safeName(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class SearchBirdItem {
        final String birdId;
        final String label;
        final String commonName;
        final String scientificName;

        SearchBirdItem(String birdId, String label, String commonName, String scientificName) {
            this.birdId = birdId;
            this.label = label;
            this.commonName = commonName == null ? "" : commonName.trim().toLowerCase(Locale.getDefault());
            this.scientificName = scientificName == null ? "" : scientificName.trim().toLowerCase(Locale.getDefault());
        }

        @NonNull
        @Override
        public String toString() {
            return label;
        }
    }
}