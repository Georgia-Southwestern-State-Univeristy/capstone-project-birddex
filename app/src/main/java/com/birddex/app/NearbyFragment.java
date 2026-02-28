package com.birddex.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
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
    private LoadingDialog loadingDialog;

    private FusedLocationProviderClient fusedLocationClient;
    private ActivityResultLauncher<String[]> locationPermissionLauncher;

    private LocationRequest locationRequest;
    private LocationCallback locationCallback;
    private Location currentLocation;
    private String lastPlaceShown = null;

    private FirebaseManager firebaseManager;
    private EbirdApi ebirdApi;

    private boolean isUpdating = false;
    private ExecutorService geoExecutor;

    private final List<Bird> firestoreBirds = new ArrayList<>();
    private final List<Bird> ebirdBirds = new ArrayList<>();

    // Configuration
    private static final long LOCATION_UPDATE_INTERVAL_MS = 3 * 60 * 1000; // 3 Minutes
    private static final double SEARCH_RADIUS_METERS = 50000; // 50 km radius
    private static final long SIGHTING_RECENCY_MS = 72L * 60 * 60 * 1000; // 72 Hours

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_nearby, container, false);

        txtLocation = v.findViewById(R.id.txtLocation);
        rvNearby = v.findViewById(R.id.rvNearby);
        btnRefresh = v.findViewById(R.id.btnRefresh);
        
        loadingDialog = new LoadingDialog(requireContext());

        rvNearby.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new NearbyAdapter(new ArrayList<>());
        rvNearby.setAdapter(adapter);

        firebaseManager = new FirebaseManager(requireContext());
        ebirdApi = new EbirdApi();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());
        
        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL_MS)
                .setMinUpdateIntervalMillis(LOCATION_UPDATE_INTERVAL_MS / 2)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location == null) return;
                handleNewLocation(location);
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
            if (currentLocation != null) fetchAllNearbyData();
            else startLocationUpdates();
        });

        geoExecutor = Executors.newSingleThreadExecutor();
        return v;
    }

    @Override
    public void onStart() {
        super.onStart();
        requestLocationOrLoad();
    }

    private void handleNewLocation(Location location) {
        currentLocation = location;
        
        // Update the city/state name in the UI
        if (geoExecutor != null && !geoExecutor.isShutdown()) {
            geoExecutor.execute(() -> {
                String place = getCityStateFromLocation(location);
                if (lastPlaceShown == null || !place.equals(lastPlaceShown)) {
                    lastPlaceShown = place;
                    if (isAdded()) {
                        requireActivity().runOnUiThread(() -> {
                            txtLocation.setText("Location: " + place);
                            fetchAllNearbyData(); // Trigger data fetch now that we have a location
                        });
                    }
                }
            });
        }
    }

    private void requestLocationOrLoad() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // 1. Try to get the last known location immediately for instant display
            fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
                if (location != null) {
                    handleNewLocation(location);
                }
            });
            // 2. Start active polling
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
        } catch (SecurityException ignored) {}
    }

    private void stopLocationUpdates() {
        if (!isUpdating) return;
        fusedLocationClient.removeLocationUpdates(locationCallback);
        isUpdating = false;
    }

    @Override
    public void onStop() {
        super.onStop();
        stopLocationUpdates();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (geoExecutor != null) geoExecutor.shutdownNow();
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

    private void fetchAllNearbyData() {
        if (currentLocation == null) return;

        if (isAdded()) loadingDialog.show();
        firestoreBirds.clear();
        ebirdBirds.clear();

        firebaseManager.getAllBirds(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                for (DocumentSnapshot document : task.getResult().getDocuments()) {
                    Bird bird = document.toObject(Bird.class);
                    if (isValidSighting(bird)) firestoreBirds.add(bird);
                }
            }
            processFinalList();
        });

        ebirdApi.fetchCoreGeorgiaBirdList(new EbirdApi.EbirdCoreBirdListCallback() {
            @Override
            public void onSuccess(List<JSONObject> birdsJson) {
                for (JSONObject json : birdsJson) {
                    Bird bird = parseBirdJson(json);
                    if (isValidSighting(bird)) ebirdBirds.add(bird);
                }
                processFinalList();
            }
            @Override public void onFailure(Exception e) { processFinalList(); }
        });
    }

    private boolean isValidSighting(Bird bird) {
        if (bird == null || bird.getLastSeenLatitudeGeorgia() == null || 
            bird.getLastSeenLongitudeGeorgia() == null || bird.getLastSeenTimestampGeorgia() == null) {
            return false;
        }
        long cutoff = System.currentTimeMillis() - SIGHTING_RECENCY_MS;
        if (bird.getLastSeenTimestampGeorgia() < cutoff) return false;

        float[] results = new float[1];
        Location.distanceBetween(currentLocation.getLatitude(), currentLocation.getLongitude(),
                bird.getLastSeenLatitudeGeorgia(), bird.getLastSeenLongitudeGeorgia(), results);
        
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
                    isDup = true; break;
                }
            }
            if (!isDup) combined.add(eb);
        }

        Collections.sort(combined, (b1, b2) -> b2.getLastSeenTimestampGeorgia().compareTo(b1.getLastSeenTimestampGeorgia()));

        if (isAdded()) {
            requireActivity().runOnUiThread(() -> {
                adapter.updateList(combined);
                if (loadingDialog.isShowing()) loadingDialog.dismiss();
            });
        }
    }
}