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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.firestore.DocumentSnapshot;

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
    private LinearLayout birdsContainer;

    private FusedLocationProviderClient fusedLocationClient;
    private ActivityResultLauncher<String[]> locationPermissionLauncher;

    private LocationRequest locationRequest;
    private LocationCallback locationCallback;
    private Location currentLocation;
    private String currentLocalityName;

    private FirebaseManager firebaseManager;

    private boolean isUpdating = false;
    private String lastPlaceShown = null;

    private ExecutorService geoExecutor; // Initialize in onCreateView

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_nearby, container, false);

        txtLocation = v.findViewById(R.id.txtLocation);
        birdsContainer = v.findViewById(R.id.birdsContainer);

        firebaseManager = new FirebaseManager(requireContext());

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());
        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateIntervalMillis(1500L)
                .setMinUpdateDistanceMeters(10f)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location == null) {
                    Log.w(TAG, "Location result is null.");
                    return;
                }

                currentLocation = location;
                
                requireActivity().runOnUiThread(() -> {
                    if (lastPlaceShown == null) {
                        txtLocation.setText("Location: Getting address...");
                    }
                });

                if (geoExecutor != null && !geoExecutor.isShutdown() && !geoExecutor.isTerminated()) {
                    geoExecutor.execute(() -> {
                        String place = getCityStateFromLocation(location);
                        currentLocalityName = place;

                        if (place != null && place.equals(lastPlaceShown) && areLocationsSimilar(currentLocation, location)) {
                            return;
                        }
                        lastPlaceShown = place;

                        requireActivity().runOnUiThread(() -> {
                            txtLocation.setText("Location: " + place);
                            fetchAndShowNearbyBirds();
                        });
                    });
                } else {
                    Log.d(TAG, "geoExecutor is shut down or terminated, not submitting new task.");
                }
            }
        };

        locationPermissionLauncher =
                registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    boolean fine = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION));
                    boolean coarse = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));

                    if (fine || coarse) {
                        startLocationUpdates();
                    } else {
                        txtLocation.setText("Location: Permission denied");
                        showBirds(new ArrayList<>());
                        Toast.makeText(requireContext(), "Location permission denied. Cannot show nearby birds.", Toast.LENGTH_LONG).show();
                    }
                });

        geoExecutor = Executors.newSingleThreadExecutor(); // Initialize here
        requestLocationOrLoad();

        return v;
    }

    private void requestLocationOrLoad() {
        boolean fineGranted = ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        boolean coarseGranted = ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (fineGranted || coarseGranted) {
            startLocationUpdates();
        } else {
            txtLocation.setText("Location: Requesting permission...");
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    private void startLocationUpdates() {
        boolean fineGranted = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;

        boolean coarseGranted = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;

        if (!(fineGranted || coarseGranted)) {
            txtLocation.setText("Location: Permission denied");
            showBirds(new ArrayList<>());
            return;
        }

        if (isUpdating) return;

        try {
            txtLocation.setText("Location: Listening for updates...");
            fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
            );
            isUpdating = true;
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException when starting location updates: ", se);
            txtLocation.setText("Location: Permission denied");
            showBirds(new ArrayList<>());
            Toast.makeText(requireContext(), "Location permission denied. Cannot show nearby birds.", Toast.LENGTH_LONG).show();
        }
    }

    private void stopLocationUpdates() {
        if (!isUpdating) return;
        fusedLocationClient.removeLocationUpdates(locationCallback);
        isUpdating = false;
    }

    @Override
    public void onStart() {
        super.onStart();
        boolean fineGranted = ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (fineGranted || coarseGranted) {
            startLocationUpdates();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        stopLocationUpdates();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (geoExecutor != null && !geoExecutor.isShutdown()) {
            geoExecutor.shutdownNow(); // Interrupt any ongoing tasks and prevent new ones
            try {
                // Wait a while for tasks to terminate
                if (!geoExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                    Log.e(TAG, "Executor did not terminate in time.");
                }
            } catch (InterruptedException ie) {
                // (Re-)Cancel if current thread also interrupted
                java.lang.Thread.currentThread().interrupt();
            }
        }
    }

    private String getCityStateFromLocation(Location location) {
        Geocoder geocoder = new Geocoder(requireContext(), Locale.getDefault());
        try {
            List<Address> results = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
            if (results != null && !results.isEmpty()) {
                Address a = results.get(0);
                String city = a.getLocality();
                if (city == null) city = a.getSubAdminArea();
                String state = a.getAdminArea();
                if (city != null && state != null) return city + ", " + state;
                if (state != null) return state;
            }
        } catch (IOException e) {
            Log.e(TAG, "Geocoder failed: " + e.getMessage());
        }
        return String.format(Locale.US, "%.4f, %.4f", location.getLatitude(), location.getLongitude());
    }

    private void fetchAndShowNearbyBirds() {
        if (currentLocation == null) {
            Log.w(TAG, "Current location is null, cannot fetch nearby birds.");
            showBirds(new ArrayList<>());
            return;
        }

        firebaseManager.getAllBirds(task -> {
            if (task.isSuccessful()) {
                List<Bird> nearbyBirds = new ArrayList<>();
                for (DocumentSnapshot document : task.getResult().getDocuments()) {
                    Bird bird = document.toObject(Bird.class);
                    // Ensure bird is not null and has valid last seen location data
                    if (bird != null &&
                        bird.getLastSeenLatitudeGeorgia() != null &&
                        bird.getLastSeenLongitudeGeorgia() != null &&
                        currentLocation != null // Defensive check, though already checked at start of method
                    ) {
                        double distance = calculateDistance(
                                currentLocation.getLatitude(), currentLocation.getLongitude(),
                                bird.getLastSeenLatitudeGeorgia(), bird.getLastSeenLongitudeGeorgia()
                        );
                        // Filter birds within a certain range (e.g., 100,000 meters = 100 km)
                        if (distance <= 100000) {
                            nearbyBirds.add(bird);
                        }
                    }
                }
                // Sort by distance before showing
                Collections.sort(nearbyBirds, (b1, b2) -> {
                    // Ensure locations are not null for comparison
                    if (b1.getLastSeenLatitudeGeorgia() == null || b1.getLastSeenLongitudeGeorgia() == null) return 1; // Put birds with no location at the end
                    if (b2.getLastSeenLatitudeGeorgia() == null || b2.getLastSeenLongitudeGeorgia() == null) return -1; // Put birds with no location at the end

                    double dist1 = calculateDistance(currentLocation.getLatitude(), currentLocation.getLongitude(), b1.getLastSeenLatitudeGeorgia(), b1.getLastSeenLongitudeGeorgia());
                    double dist2 = calculateDistance(currentLocation.getLatitude(), currentLocation.getLongitude(), b2.getLastSeenLatitudeGeorgia(), b2.getLastSeenLongitudeGeorgia());
                    return Double.compare(dist1, dist2);
                });

                showBirds(nearbyBirds);
            } else {
                Log.e(TAG, "Error getting birds from Firestore: ", task.getException());
                Toast.makeText(requireContext(), "Failed to load bird data.", Toast.LENGTH_SHORT).show();
                showBirds(new ArrayList<>());
            }
        });
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        float[] results = new float[1];
        Location.distanceBetween(lat1, lon1, lat2, lon2, results);
        return results[0];
    }

    private boolean areLocationsSimilar(Location loc1, Location loc2) {
        if (loc1 == null || loc2 == null) return false;
        float[] distResult = new float[1];
        Location.distanceBetween(loc1.getLatitude(), loc1.getLongitude(), loc2.getLatitude(), loc2.getLongitude(), distResult);
        return distResult[0] < 5; // Consider locations similar if less than 5 meters apart
    }

    private void showBirds(List<Bird> birds) {
        if (!isAdded()) {
            return;
        }
        birdsContainer.removeAllViews();

        if (birds.isEmpty()) {
            TextView tv = new TextView(requireContext());
            tv.setText("No nearby birds found with recent sightings.");
            tv.setTextSize(16f);
            tv.setPadding(0, 8, 0, 8);
            birdsContainer.addView(tv);
            return;
        }

        for (Bird bird : birds) {
            TextView tv = new TextView(requireContext());
            // Ensure lastSeenLatitudeGeorgia and lastSeenLongitudeGeorgia are not null for distance calculation
            if (bird.getLastSeenLatitudeGeorgia() != null && bird.getLastSeenLongitudeGeorgia() != null && currentLocation != null) {
                double distance = calculateDistance(
                        currentLocation.getLatitude(), currentLocation.getLongitude(),
                        bird.getLastSeenLatitudeGeorgia(), bird.getLastSeenLongitudeGeorgia()
                );
                String distanceStr;
                if (distance < 1000) {
                    distanceStr = String.format(Locale.US, "%.0f meters away", distance);
                } else {
                    distanceStr = String.format(Locale.US, "%.1f km away", distance / 1000);
                }
                tv.setText("• " + bird.getCommonName() + " (" + distanceStr + ")");
            } else {
                // If last seen location is unknown, just show the common name
                tv.setText("• " + bird.getCommonName() + " (Location unknown)");
            }
            tv.setTextSize(16f);
            tv.setPadding(0, 8, 0, 8);
            birdsContainer.addView(tv);
        }
    }
}