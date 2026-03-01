package com.birddex.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class NearbyPreloadManager {

    private static final String TAG = "NearbyPreloadManager";
    private static final double SEARCH_RADIUS_METERS = 50000;
    private static final long SIGHTING_RECENCY_MS = 72L * 60 * 60 * 1000;

    private static NearbyPreloadManager instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Runnable> pendingCallbacks = new ArrayList<>();
    private final List<Bird> cachedNearbyBirds = new ArrayList<>();
    private final List<Bird> cachedSearchableBirds = new ArrayList<>();

    private boolean preloadStarted = false;
    private boolean preloadFinished = false;
    private Location cachedLocation;
    private String cachedPlace = "Nearby";

    private NearbyPreloadManager() {
    }

    public static synchronized NearbyPreloadManager getInstance() {
        if (instance == null) {
            instance = new NearbyPreloadManager();
        }
        return instance;
    }

    public void preload(Context context, Runnable callback) {
        Context appContext = context.getApplicationContext();

        synchronized (this) {
            if (callback != null) {
                if (preloadFinished) {
                    postToMain(callback);
                    return;
                }
                pendingCallbacks.add(callback);
            }

            if (preloadStarted) {
                return;
            }

            preloadStarted = true;
        }

        preloadSearchableBirds(appContext);

        if (!hasLocationPermission(appContext)) {
            Log.d(TAG, "Skipping nearby preload because location permission is not granted yet.");
            finishPreload();
            return;
        }

        FusedLocationProviderClient fusedLocationClient =
                LocationServices.getFusedLocationProviderClient(appContext);

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        preloadForLocation(appContext, location);
                    } else {
                        fusedLocationClient.getLastLocation()
                                .addOnSuccessListener(lastLocation -> {
                                    if (lastLocation != null) {
                                        preloadForLocation(appContext, lastLocation);
                                    } else {
                                        finishPreload();
                                    }
                                })
                                .addOnFailureListener(e -> {
                                    Log.e(TAG, "Failed to get last location for preload.", e);
                                    finishPreload();
                                });
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to get current location for preload.", e);
                    finishPreload();
                });
    }

    public synchronized List<Bird> getCachedNearbyBirds() {
        return new ArrayList<>(cachedNearbyBirds);
    }

    public synchronized List<Bird> getCachedSearchableBirds() {
        return new ArrayList<>(cachedSearchableBirds);
    }

    public synchronized String getCachedPlace() {
        return cachedPlace;
    }

    public synchronized Location getCachedLocation() {
        return cachedLocation == null ? null : new Location(cachedLocation);
    }

    public synchronized void updateNearbyCache(Location location, String place, List<Bird> birds) {
        if (location != null) {
            cachedLocation = new Location(location);
        }

        cachedPlace = (place == null || place.trim().isEmpty()) ? "Nearby" : place;

        cachedNearbyBirds.clear();
        if (birds != null) {
            cachedNearbyBirds.addAll(birds);
        }

        preloadFinished = true;
        preloadStarted = true;
    }

    public synchronized void updateSearchableBirds(List<Bird> birds) {
        cachedSearchableBirds.clear();
        if (birds != null) {
            cachedSearchableBirds.addAll(birds);
        }

        Collections.sort(cachedSearchableBirds, (b1, b2) ->
                safeName(b1.getCommonName()).compareToIgnoreCase(safeName(b2.getCommonName())));
    }

    private void preloadForLocation(Context appContext, Location location) {
        AtomicInteger partsDone = new AtomicInteger(0);
        AtomicReference<String> placeRef = new AtomicReference<>("Nearby");
        List<Bird> firestoreNearby = Collections.synchronizedList(new ArrayList<>());
        List<Bird> ebirdNearby = Collections.synchronizedList(new ArrayList<>());

        Runnable onPartDone = () -> {
            if (partsDone.incrementAndGet() >= 3) {
                List<Bird> combined = combineBirds(firestoreNearby, ebirdNearby);
                updateNearbyCache(location, placeRef.get(), combined);
                finishPreload();
            }
        };

        executor.execute(() -> {
            placeRef.set(getCityStateFromLocation(appContext, location));
            onPartDone.run();
        });

        FirebaseManager firebaseManager = new FirebaseManager(appContext);
        firebaseManager.getAllBirds(task -> {
            try {
                List<Bird> allBirds = new ArrayList<>();

                if (task.isSuccessful() && task.getResult() != null) {
                    for (DocumentSnapshot doc : task.getResult().getDocuments()) {
                        Bird bird = doc.toObject(Bird.class);
                        if (bird == null) continue;

                        if (isBlank(bird.getId())) {
                            bird.setId(doc.getId());
                        }

                        allBirds.add(bird);

                        if (isValidSighting(bird, location)) {
                            firestoreNearby.add(bird);
                        }
                    }
                }

                updateSearchableBirds(allBirds);
            } finally {
                onPartDone.run();
            }
        });

        EbirdApi ebirdApi = new EbirdApi();
        ebirdApi.fetchCoreGeorgiaBirdList(new EbirdApi.EbirdCoreBirdListCallback() {
            @Override
            public void onSuccess(List<JSONObject> birdsJson) {
                try {
                    for (JSONObject json : birdsJson) {
                        Bird bird = parseBirdJson(json);
                        if (isValidSighting(bird, location)) {
                            ebirdNearby.add(bird);
                        }
                    }
                } finally {
                    onPartDone.run();
                }
            }

            @Override
            public void onFailure(Exception e) {
                Log.e(TAG, "eBird preload failed.", e);
                onPartDone.run();
            }
        });
    }

    private synchronized void finishPreload() {
        preloadFinished = true;
        preloadStarted = true;

        List<Runnable> callbacks = new ArrayList<>(pendingCallbacks);
        pendingCallbacks.clear();

        for (Runnable callback : callbacks) {
            postToMain(callback);
        }
    }

    private void preloadSearchableBirds(Context appContext) {
        if (!getCachedSearchableBirds().isEmpty()) {
            return;
        }

        FirebaseManager firebaseManager = new FirebaseManager(appContext);
        firebaseManager.getAllBirds(task -> {
            if (!task.isSuccessful() || task.getResult() == null) {
                return;
            }

            List<Bird> allBirds = new ArrayList<>();
            for (DocumentSnapshot doc : task.getResult().getDocuments()) {
                Bird bird = doc.toObject(Bird.class);
                if (bird == null) continue;

                if (isBlank(bird.getId())) {
                    bird.setId(doc.getId());
                }

                allBirds.add(bird);
            }

            updateSearchableBirds(allBirds);
        });
    }

    private boolean hasLocationPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private List<Bird> combineBirds(List<Bird> firestoreBirds, List<Bird> ebirdBirds) {
        List<Bird> combined = new ArrayList<>(firestoreBirds);

        for (Bird ebirdBird : ebirdBirds) {
            boolean duplicate = false;
            for (Bird existingBird : combined) {
                if (existingBird.getId() != null && existingBird.getId().equals(ebirdBird.getId())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                combined.add(ebirdBird);
            }
        }

        Collections.sort(combined, (b1, b2) -> {
            Long t1 = b1.getLastSeenTimestampGeorgia();
            Long t2 = b2.getLastSeenTimestampGeorgia();
            if (t1 == null && t2 == null) return 0;
            if (t1 == null) return 1;
            if (t2 == null) return -1;
            return t2.compareTo(t1);
        });

        return combined;
    }

    private boolean isValidSighting(Bird bird, Location location) {
        if (bird == null
                || bird.getLastSeenLatitudeGeorgia() == null
                || bird.getLastSeenLongitudeGeorgia() == null
                || bird.getLastSeenTimestampGeorgia() == null
                || location == null) {
            return false;
        }

        long cutoff = System.currentTimeMillis() - SIGHTING_RECENCY_MS;
        if (bird.getLastSeenTimestampGeorgia() < cutoff) {
            return false;
        }

        float[] results = new float[1];
        Location.distanceBetween(
                location.getLatitude(),
                location.getLongitude(),
                bird.getLastSeenLatitudeGeorgia(),
                bird.getLastSeenLongitudeGeorgia(),
                results
        );

        return results[0] <= SEARCH_RADIUS_METERS;
    }

    private Bird parseBirdJson(JSONObject json) {
        Bird bird = new Bird();
        bird.setId(json.optString("id"));
        bird.setCommonName(json.optString("commonName"));
        bird.setScientificName(json.optString("scientificName"));
        bird.setLastSeenLatitudeGeorgia(json.optDouble("lastSeenLatitudeGeorgia"));
        bird.setLastSeenLongitudeGeorgia(json.optDouble("lastSeenLongitudeGeorgia"));
        bird.setLastSeenTimestampGeorgia(json.optLong("lastSeenTimestampGeorgia"));
        return bird;
    }

    private String getCityStateFromLocation(Context context, Location location) {
        Geocoder geocoder = new Geocoder(context, Locale.getDefault());
        try {
            List<Address> results = geocoder.getFromLocation(
                    location.getLatitude(),
                    location.getLongitude(),
                    1
            );

            if (results != null && !results.isEmpty()) {
                Address address = results.get(0);
                String city = address.getLocality() != null
                        ? address.getLocality()
                        : address.getSubAdminArea();
                String state = address.getAdminArea();

                if (city != null && state != null) return city + ", " + state;
                if (state != null) return state;
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to geocode preload location.", e);
        }

        return "Nearby";
    }

    private void postToMain(Runnable runnable) {
        mainHandler.post(runnable);
    }

    private static String safeName(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}