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
import java.util.concurrent.TimeUnit;

public class NearbyFragment extends Fragment {

    private static final String TAG = "NearbyFragment";
    private TextView txtLocation;
    private RecyclerView rvNearby;
    private NearbyAdapter adapter;

    private FusedLocationProviderClient fusedLocationClient;
    private ActivityResultLauncher<String[]> locationPermissionLauncher;
    private String currentLocalityName;
    private LocationRequest locationRequest;
    private LocationCallback locationCallback;
    private Location currentLocation;
    private Geocoder geocoder;

    private FirebaseManager firebaseManager;
    private EbirdApi ebirdApi;

    private boolean isUpdating = false;
    private String lastPlaceShown = null;
    private ExecutorService geoExecutor;

    private List<Bird> firestoreBirds = new ArrayList<>();
    private List<Bird> ebirdBirds = new ArrayList<>();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        firebaseManager = new FirebaseManager(requireContext());
        ebirdApi = new EbirdApi();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());
        geocoder = new Geocoder(requireContext(), Locale.getDefault());
        geoExecutor = Executors.newSingleThreadExecutor();


        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateIntervalMillis(1000L)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (!isUpdating) {
                    isUpdating = true;
                    for (Location location : locationResult.getLocations()) {
                        currentLocation = location;
                        updateLocationUI();
                        break; // We only care about the latest location
                    }
                    isUpdating = false;
                }
            }
        };

        locationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                permissions -> {
                    boolean fineGranted = permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);
                    boolean coarseGranted = permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false);
                    if (fineGranted || coarseGranted) {
                        startLocationUpdates();
                    } else {
                        txtLocation.setText("Location: Permission denied");
                        adapter.updateList(new ArrayList<>());
                        Toast.makeText(requireContext(), "Location permission denied.", Toast.LENGTH_LONG).show();
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_nearby, container, false);

        txtLocation = v.findViewById(R.id.txtLocation);
        rvNearby = v.findViewById(R.id.rvNearby);

        // Initialize RecyclerView with Vertical LayoutManager
        rvNearby.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new NearbyAdapter(new ArrayList<>());
        rvNearby.setAdapter(adapter);

        // Request location or load from last known location
        requestLocationOrLoad();

        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopLocationUpdates();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopLocationUpdates();
        if (geoExecutor != null && !geoExecutor.isShutdown()) {
            geoExecutor.shutdownNow();
            try {
                if (!geoExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                    Log.e(TAG, "Executor did not terminate in time.");
                }
            } catch (InterruptedException ie) {
                java.lang.Thread.currentThread().interrupt();
            }
        }
    }

    private void requestLocationOrLoad() {
        boolean fineGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (fineGranted || coarseGranted) {
            startLocationUpdates();
        } else {
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    private void startLocationUpdates() {
        try {
            fusedLocationClient.requestLocationUpdates(locationRequest,
                    locationCallback,
                    Looper.getMainLooper());
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException when starting location updates: ", se);
            txtLocation.setText("Location: Permission denied");
            adapter.updateList(new ArrayList<>());
            Toast.makeText(requireContext(), "Location permission denied.", Toast.LENGTH_LONG).show();
        }
    }

    private void stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback);
    }

    private void updateLocationUI() {
        if (currentLocation != null) {
            geoExecutor.execute(() -> {
                try {
                    List<Address> addresses = geocoder.getFromLocation(currentLocation.getLatitude(), currentLocation.getLongitude(), 1);
                    String place = "Unknown Location";
                    if (addresses != null && !addresses.isEmpty()) {
                        Address address = addresses.get(0);
                        if (address.getLocality() != null) {
                            place = address.getLocality();
                            currentLocalityName = place; // Store the locality name
                        } else if (address.getSubAdminArea() != null) {
                            place = address.getSubAdminArea();
                            currentLocalityName = place;
                        } else if (address.getAdminArea() != null) {
                            place = address.getAdminArea();
                            currentLocalityName = place;
                        }
                    }

                    String finalPlace = place;
                    requireActivity().runOnUiThread(() -> {
                        txtLocation.setText("Location: " + finalPlace);
                        fetchAllNearbyData();
                    });
                } catch (IOException e) {
                    Log.e(TAG, "Error getting locality name: " + e.getMessage());
                    requireActivity().runOnUiThread(() -> {
                        txtLocation.setText("Location: N/A");
                        fetchAllNearbyData(); // Still try to fetch data if location available
                    });
                }
            });
        } else {
            txtLocation.setText("Location: Not available");
            fetchAllNearbyData(); // Still try to fetch data even if location not available, will result in empty list
        }
    }

    private void fetchAllNearbyData() {
        if (currentLocation == null) {
            adapter.updateList(new ArrayList<>());
            return;
        }

        firestoreBirds.clear();
        ebirdBirds.clear();

        // 1. Fetch from Firestore
        firebaseManager.getAllBirds(task -> {
            if (task.isSuccessful()) {
                for (DocumentSnapshot document : task.getResult().getDocuments()) {
                    Bird bird = document.toObject(Bird.class);
                    if (bird != null && bird.getLastSeenLatitudeGeorgia() != null && bird.getLastSeenLongitudeGeorgia() != null) {
                        double distance = calculateDistance(currentLocation.getLatitude(), currentLocation.getLongitude(),
                                bird.getLastSeenLatitudeGeorgia(), bird.getLastSeenLongitudeGeorgia());
                        if (distance <= 100000) { // Within 100km
                            firestoreBirds.add(bird);
                        }
                    }
                }
            } else {
                Log.e(TAG, "Error getting birds from Firestore: ", task.getException());
            }
            combineAndSortLists();
        });

        // 2. Fetch from eBird API
        ebirdApi.fetchCoreGeorgiaBirdList(new EbirdApi.EbirdCoreBirdListCallback() {
            @Override
            public void onSuccess(List<JSONObject> birdsJson) {
                for (JSONObject json : birdsJson) {
                    try {
                        Bird bird = new Bird();
                        bird.setId(json.optString("id"));
                        bird.setCommonName(json.optString("commonName"));
                        bird.setScientificName(json.optString("scientificName"));
                        bird.setFamily(json.optString("family"));
                        bird.setSpecies(json.optString("species"));

                        // Using the current location for eBird API.
                        // Assuming eBird API returns lastSeenLatitudeGeorgia and lastSeenLongitudeGeorgia,
                        // which implies that these are properties that exist in your Bird model for eBird data too.
                        if (!json.isNull("lastSeenLatitudeGeorgia")) {
                            bird.setLastSeenLatitudeGeorgia(json.optDouble("lastSeenLatitudeGeorgia"));
                        }
                        if (!json.isNull("lastSeenLongitudeGeorgia")) {
                            bird.setLastSeenLongitudeGeorgia(json.optDouble("lastSeenLongitudeGeorgia"));
                        }
                        if (!json.isNull("lastSeenTimestampGeorgia")) {
                            bird.setLastSeenTimestampGeorgia(json.optLong("lastSeenTimestampGeorgia"));
                        }

                        if (bird.getLastSeenLatitudeGeorgia() != null && bird.getLastSeenLongitudeGeorgia() != null) {
                            double distance = calculateDistance(currentLocation.getLatitude(), currentLocation.getLongitude(),
                                    bird.getLastSeenLatitudeGeorgia(), bird.getLastSeenLongitudeGeorgia());
                            if (distance <= 100000) { // Within 100km
                                ebirdBirds.add(bird);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing bird JSON: " + e.getMessage());
                    }
                }
                combineAndSortLists();
            }

            @Override
            public void onFailure(Exception e) {
                Log.e(TAG, "Failed to fetch eBird data: " + e.getMessage());
                combineAndSortLists();
            }
        });
    }

    private void combineAndSortLists() {
        // Calculate timestamp for 3 calendar days ago (72 hours)
        long threeDaysAgo = System.currentTimeMillis() - (3L * 24 * 60 * 60 * 1000);
        List<Bird> combinedList = new ArrayList<>();

        // Filter and add firestoreBirds
        for (Bird fb : firestoreBirds) {
            // Check for null to avoid NullPointerExceptions
            if (fb.getLastSeenTimestampGeorgia() != null && fb.getLastSeenTimestampGeorgia() >= threeDaysAgo) {
                combinedList.add(fb);
            }
        }

        // Add eBird birds, avoiding duplicates if already in combinedList based on ID, and filtering by time
        for (Bird eb : ebirdBirds) {
            // Check for null to avoid NullPointerExceptions
            if (eb.getLastSeenTimestampGeorgia() != null && eb.getLastSeenTimestampGeorgia() >= threeDaysAgo) {
                boolean alreadyPresent = false;
                for (Bird b : combinedList) {
                    if (b.getId() != null && b.getId().equals(eb.getId())) {
                        alreadyPresent = true;
                        break;
                    }
                }
                if (!alreadyPresent) {
                    combinedList.add(eb);
                }
            }
        }

        // Sort by distance from current location
        Collections.sort(combinedList, (b1, b2) -> {
            if (currentLocation == null) return 0; // Cannot sort by distance if current location is null
            if (b1.getLastSeenLatitudeGeorgia() == null || b1.getLastSeenLongitudeGeorgia() == null) return 1; // Put birds with no location at the end
            if (b2.getLastSeenLatitudeGeorgia() == null || b2.getLastSeenLongitudeGeorgia() == null) return -1; // Put birds with no location at the end

            double dist1 = calculateDistance(currentLocation.getLatitude(), currentLocation.getLongitude(),
                    b1.getLastSeenLatitudeGeorgia(), b1.getLastSeenLongitudeGeorgia());
            double dist2 = calculateDistance(currentLocation.getLatitude(), currentLocation.getLongitude(),
                    b2.getLastSeenLatitudeGeorgia(), b2.getLastSeenLongitudeGeorgia());
            return Double.compare(dist1, dist2);
        });

        if (isAdded()) { // Check if the fragment is currently added to its activity
            requireActivity().runOnUiThread(() -> adapter.updateList(combinedList));
        }
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        float[] distResult = new float[1];
        Location.distanceBetween(lat1, lon1, lat2, lon2, distResult);
        return distResult[0]; // Returns distance in meters
    }
}