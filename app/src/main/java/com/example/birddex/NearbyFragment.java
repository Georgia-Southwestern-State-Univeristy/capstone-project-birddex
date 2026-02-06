package com.example.birddex;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class NearbyFragment extends Fragment {

    private TextView txtLocation;
    private LinearLayout birdsContainer;

    private FusedLocationProviderClient fusedLocationClient;
    private ActivityResultLauncher<String[]> locationPermissionLauncher;

    private LocationRequest locationRequest;
    private LocationCallback locationCallback;

    private boolean isUpdating = false;
    private String lastPlaceShown = null;


    private final ExecutorService geoExecutor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_nearby, container, false);

        txtLocation = v.findViewById(R.id.txtLocation);
        birdsContainer = v.findViewById(R.id.birdsContainer);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());
        // Request frequent updates (tune these as you like)
        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L) // 3s desired
                .setMinUpdateIntervalMillis(1500L)  // fastest
                .setMinUpdateDistanceMeters(10f)    // only update if moved ~10m
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location == null) return;

                // Show coordinates immediately (fast UI feedback)
                requireActivity().runOnUiThread(() -> {
                    if (lastPlaceShown == null) {
                        txtLocation.setText("Location: Getting address...");
                    }
                });

                // Reverse geocode off the main thread, then update UI
                geoExecutor.execute(() -> {
                    String place = getCityStateFromLocation(location);

                    // Avoid spamming UI if place didn't change
                    if (place != null && place.equals(lastPlaceShown)) return;
                    lastPlaceShown = place;

                    requireActivity().runOnUiThread(() -> {
                        txtLocation.setText("Location: " + place);

                        String state = extractState(place);
                        showBirds(makePlaceholderBirdsForState(state));
                    });
                });
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
                        showBirds(makeFallbackBirds());
                    }
                });

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

    private void loadLocationAndBirds() {
        startLocationUpdates();
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
            showBirds(makeFallbackBirds());
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
            txtLocation.setText("Location: Permission denied");
            showBirds(makeFallbackBirds());
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
        // If user already granted permission earlier, start updates when fragment becomes visible
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



    private String getCityStateFromLocation(Location location) {
        Geocoder geocoder = new Geocoder(requireContext(), Locale.getDefault());

        try {
            List<Address> results = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
            if (results != null && !results.isEmpty()) {
                Address a = results.get(0);

                String city = a.getLocality();
                if (city == null) city = a.getSubAdminArea(); // fallback

                String state = a.getAdminArea(); // e.g., "Georgia"
                if (city != null && state != null) return city + ", " + state;

                if (state != null) return state;
            }
        } catch (IOException ignored) {}

        // If geocoder fails, at least show coordinates
        return String.format(Locale.US, "%.4f, %.4f", location.getLatitude(), location.getLongitude());
    }

    // If place is "Albany, Georgia" -> returns "Georgia"
    // If place is coordinates -> returns null
    private String extractState(String place) {
        if (place == null) return null;
        if (place.contains(",")) {
            String[] parts = place.split(",");
            if (parts.length >= 2) return parts[1].trim();
        }
        // If it's just "Georgia" return it
        if (!place.contains(" ")) return place;
        return place; // might still be a state name
    }

    private void showBirds(List<String> birds) {
        birdsContainer.removeAllViews();

        for (String bird : birds) {
            TextView tv = new TextView(requireContext());
            tv.setText("• " + bird);
            tv.setTextSize(16f);
            tv.setPadding(0, 8, 0, 8);
            birdsContainer.addView(tv);
        }
    }

    private List<String> makePlaceholderBirdsForState(String stateName) {
        // Placeholder lists (you’ll replace later with API results)
        // We still *actually used* the user's location to pick the list.

        ArrayList<String> birds = new ArrayList<>();

        if (stateName == null) {
            return makeFallbackBirds();
        }

        String s = stateName.toLowerCase(Locale.US);

        if (s.contains("georgia") || s.contains("florida") || s.contains("alabama") ||
                s.contains("south carolina") || s.contains("north carolina") || s.contains("tennessee")) {
            birds.add("Northern Cardinal");
            birds.add("Carolina Wren");
            birds.add("Blue Jay");
            birds.add("Mourning Dove");
            birds.add("Red-bellied Woodpecker");
            birds.add("American Robin");
        } else if (s.contains("california")) {
            birds.add("California Scrub-Jay");
            birds.add("Anna’s Hummingbird");
            birds.add("Western Bluebird");
            birds.add("Acorn Woodpecker");
            birds.add("Mourning Dove");
        } else if (s.contains("texas")) {
            birds.add("Scissor-tailed Flycatcher");
            birds.add("Northern Mockingbird");
            birds.add("Great-tailed Grackle");
            birds.add("Red-winged Blackbird");
        } else if (s.contains("new york") || s.contains("massachusetts") || s.contains("pennsylvania")) {
            birds.add("Black-capped Chickadee");
            birds.add("American Crow");
            birds.add("Downy Woodpecker");
            birds.add("Blue Jay");
            birds.add("Northern Cardinal");
        } else {
            // Generic US-ish placeholders
            birds.add("Northern Cardinal");
            birds.add("American Robin");
            birds.add("Blue Jay");
            birds.add("Mourning Dove");
            birds.add("House Sparrow");
        }

        return birds;
    }

    private List<String> makeFallbackBirds() {
        ArrayList<String> birds = new ArrayList<>();
        birds.add("Northern Cardinal");
        birds.add("American Robin");
        birds.add("Mourning Dove");
        birds.add("Blue Jay");
        birds.add("House Sparrow");
        return birds;
    }
}
