package com.birddex.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Helper class to manage location services, including permission checks,
 * requesting updates, and reverse geocoding.
 */
public class LocationHelper {

    private static final String TAG = "LocationHelper";
    private FusedLocationProviderClient fusedLocationClient;
    private LocationRequest locationRequest;
    private LocationCallback locationCallback;
    private LocationListener listener;
    private Context context;
    private Context applicationContext; // Added to hold application context

    private final ExecutorService geoExecutor = Executors.newSingleThreadExecutor();

    public interface LocationListener {
        void onLocationReceived(Location location, String localityName);
        void onLocationError(String errorMessage);
    }

    public LocationHelper(Context context, LocationListener listener) {
        this.context = context;
        this.applicationContext = context.getApplicationContext(); // Get application context
        this.listener = listener;
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(applicationContext); // Use applicationContext

        // Corrected LocationRequest initialization using setMaxUpdateDelayMillis
        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L) // 5s desired
                .setMinUpdateIntervalMillis(2500L) // fastest
                .setMaxUpdateDelayMillis(10000L) // wait up to 10s for updates (corrected method)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    listener.onLocationError("Location result is null.");
                    return;
                }
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    // Perform reverse geocoding on a background thread
                    geoExecutor.execute(() -> {
                        String locality = getLocalityFromLocation(location);
                        ((Activity) context).runOnUiThread(() -> {
                            listener.onLocationReceived(location, locality);
                        });
                    });
                } else {
                    listener.onLocationError("Last location is null.");
                }
            }
        };
    }

    // Check if location permissions are granted
    public boolean checkLocationPermissions() {
        boolean fineGranted = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return fineGranted || coarseGranted;
    }

    // Request location updates
    public void startLocationUpdates() {
        if (!checkLocationPermissions()) {
            listener.onLocationError("Location permissions not granted.");
            return;
        }

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
            Log.d(TAG, "Started location updates.");
        } catch (SecurityException e) {
            listener.onLocationError("Location permissions denied: " + e.getMessage());
            Log.e(TAG, "Error starting location updates", e);
        }
    }

    // Stop location updates
    public void stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "Stopped location updates successfully.");
                    }
                     else {
                        Log.e(TAG, "Failed to stop location updates.");
                    }
                });
    }

    // Get the last known location once
    public void getLastKnownLocation() {
        if (!checkLocationPermissions()) {
            listener.onLocationError("Location permissions not granted.");
            return;
        }

        try {
            fusedLocationClient.getLastLocation()
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            geoExecutor.execute(() -> {
                                String locality = getLocalityFromLocation(location);
                                ((Activity) context).runOnUiThread(() -> {
                                    listener.onLocationReceived(location, locality);
                                });
                            });
                        } else {
                            // Fallback to requesting updates if last known is null
                            Log.w(TAG, "Last known location is null, requesting updates.");
                            startLocationUpdates();
                        }
                    })
                    .addOnFailureListener(e -> {
                        listener.onLocationError("Failed to get last known location: " + e.getMessage());
                        Log.e(TAG, "Error getting last known location", e);
                        startLocationUpdates(); // Fallback to requesting updates
                    });
        } catch (SecurityException e) {
            listener.onLocationError("Location permissions denied: " + e.getMessage());
            Log.e(TAG, "SecurityException getting last known location", e);
        }
    }

    // Reverse geocoding helper
    private String getLocalityFromLocation(Location location) {
        Geocoder geocoder = new Geocoder(applicationContext, Locale.getDefault()); // Use applicationContext
        try {
            List<Address> results = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
            if (results != null && !results.isEmpty()) {
                Address address = results.get(0);
                String city = address.getLocality();
                String state = address.getAdminArea();
                if (city != null && state != null) {
                    return city + ", " + state;
                } else if (state != null) {
                    return state;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Geocoder failed: " + e.getMessage());
        }
        return String.format(Locale.US, "Lat: %.4f, Lng: %.4f", location.getLatitude(), location.getLongitude());
    }

    public void shutdown() {
        geoExecutor.shutdown();
        stopLocationUpdates(); // Ensure updates are stopped when helper is no longer needed
    }
}
