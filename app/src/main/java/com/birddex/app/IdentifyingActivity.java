package com.birddex.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.functions.FirebaseFunctionsException;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Date; // Added import for Date

/**
 * IdentifyingActivity orchestrates the bird identification process.
 * Order: Get Location -> Identify (Cloud) -> Verify (Cloud) -> Upload (Storage) ONLY if verified.
 * Now includes user location for identification logging.
 */
public class IdentifyingActivity extends AppCompatActivity implements LocationHelper.LocationListener, NetworkMonitor.NetworkStatusListener {

    private static final String TAG = "IdentifyingActivity";
    public static final String EXTRA_VERIFIED_BIRD_ID = "verifiedBirdId"; // kept

    private Uri localImageUri;
    private OpenAiApi openAiApi;
    private LocationHelper locationHelper;
    private NetworkMonitor networkMonitor;
    private ActivityResultLauncher<String[]> locationPermissionLauncher;

    private FirebaseManager firebaseManager;

    private Location currentLocation;
    private String currentLocalityName;
    private String currentState;
    private String currentCountry;

    private Handler timeoutHandler;
    private Runnable timeoutRunnable;
    private static final long IDENTIFICATION_TIMEOUT_MS = 30000; // 30 seconds for the entire process
    private AtomicBoolean identificationCompleted = new AtomicBoolean(false);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_identifying);

        ImageView identifyingImageView = findViewById(R.id.identifyingImageView);
        String uriStr = getIntent().getStringExtra("imageUri");
        if (uriStr == null) {
            finishActivityWithToast("No image provided for identification.");
            return;
        }

        localImageUri = Uri.parse(uriStr);
        identifyingImageView.setImageURI(localImageUri);

        openAiApi = new OpenAiApi();
        firebaseManager = new FirebaseManager(this);

        locationHelper = new LocationHelper(this, this);
        networkMonitor = new NetworkMonitor(this, this);

        timeoutHandler = new Handler(Looper.getMainLooper());
        timeoutRunnable = () -> {
            if (identificationCompleted.compareAndSet(false, true)) {
                Log.e(TAG, "Identification process timed out.");
                finishActivityWithToast("Identification timed out. Please try again.");
            }
        };
        timeoutHandler.postDelayed(timeoutRunnable, IDENTIFICATION_TIMEOUT_MS);

        locationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean fineLocationGranted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION));
                    boolean coarseLocationGranted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));

                    if (fineLocationGranted || coarseLocationGranted) {
                        Log.d(TAG, "Location permissions granted, getting last known location.");
                        locationHelper.getLastKnownLocation();
                    } else {
                        Log.e(TAG, "Location permissions denied. Cannot log identification location.");
                        Toast.makeText(this,
                                "Location permissions denied. Identification will proceed without location.",
                                Toast.LENGTH_LONG).show();

                        startIdentificationFlow(localImageUri, null, null, null, null, null);
                    }
                });

        requestLocationPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        networkMonitor.register();
    }

    @Override
    protected void onPause() {
        super.onPause();
        networkMonitor.unregister();
    }

    private void requestLocationPermissions() {
        boolean fineLocationGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseLocationGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (fineLocationGranted || coarseLocationGranted) {
            Log.d(TAG, "Location permissions already granted, getting last known location.");
            locationHelper.getLastKnownLocation();
        } else {
            Log.d(TAG, "Requesting location permissions.");
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    @Override
    public void onLocationReceived(Location location,
                                   @Nullable String localityName,
                                   @Nullable String state,
                                   @Nullable String country) {
        this.currentLocation = location;
        this.currentLocalityName = localityName;
        this.currentState = state;
        this.currentCountry = country;

        Log.d(TAG, "Location received: " + localityName + ", " + state + ", " + country
                + " (" + location.getLatitude() + ", " + location.getLongitude() + ")");

        startIdentificationFlow(localImageUri,
                location.getLatitude(),
                location.getLongitude(),
                localityName,
                state,
                country);

        locationHelper.stopLocationUpdates();
    }

    public void onLocationReceived(Location location, @Nullable String localityName) {
        this.currentLocation = location;
        this.currentLocalityName = localityName;
        this.currentState = null;
        this.currentCountry = null;

        Log.d(TAG, "Location received: " + localityName
                + " (" + location.getLatitude() + ", " + location.getLongitude() + ")");

        startIdentificationFlow(localImageUri,
                location.getLatitude(),
                location.getLongitude(),
                localityName,
                null,
                null);

        locationHelper.stopLocationUpdates();
    }

    @Override
    public void onLocationError(String errorMessage) {
        Log.e(TAG, "Location error: " + errorMessage);
        Toast.makeText(this,
                "Location error: " + errorMessage + ". Identification will proceed without location.",
                Toast.LENGTH_LONG).show();

        startIdentificationFlow(localImageUri, null, null, null, null, null);

        locationHelper.stopLocationUpdates();
    }

    @Override
    public void onNetworkAvailable() {
        Log.d(TAG, "Network became available.");
        if (!identificationCompleted.get() && localImageUri != null) {
            Toast.makeText(this, "Internet connection restored.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onNetworkLost() {
        Log.e(TAG, "Network lost during identification.");
        if (!identificationCompleted.get()) {
            finishActivityWithToast("Internet connection lost. Please reconnect and try again.");
        }
    }

    private void startIdentificationFlow(Uri imageUri,
                                         @Nullable Double latitude,
                                         @Nullable Double longitude,
                                         @Nullable String localityName,
                                         @Nullable String state,
                                         @Nullable String country) {

        if (identificationCompleted.get()) return; // Pre-check to prevent starting if already completed/timed out

        // Initial network check via NetworkMonitor
        if (!networkMonitor.isConnected()) {
            Log.e(TAG, "No internet connection for identification at startup.");
            finishActivityWithToast("No internet connection. Please connect and try again.");
            return;
        }

        String base64Image = encodeImage(imageUri);
        if (base64Image == null) {
            finishActivityWithToast("Failed to process image for identification.");
            return;
        }

        // First, check OpenAI request limits
        firebaseManager.getOpenAiRequestsRemaining(new FirebaseManager.OpenAiRequestLimitListener() {
            @Override
            public void onCheckComplete(boolean hasRequestsRemaining, int remaining, Date expirationDate) { // Modified signature
                if (identificationCompleted.get()) return; // Already timed out or completed

                if (hasRequestsRemaining) {
                    Log.d(TAG, "OpenAI requests remaining: " + remaining + ". Proceeding with identification.");
                    // Proceed with OpenAI API call
                    openAiApi.identifyBirdFromImage(base64Image, latitude, longitude, localityName,
                            new OpenAiApi.OpenAiCallback() {
                                @Override
                                public void onSuccess(String response, boolean isVerified) {
                                    if (identificationCompleted.get()) return; // Already timed out or completed

                                    if (!isVerified) {
                                        finishActivityWithToast("Bird not recognized in Georgia regional data. Image not saved.");
                                        return;
                                    }

                                    // ONLY if verified, upload image to Firebase Storage
                                    uploadVerifiedImage(response, latitude, longitude, localityName, state, country);
                                }

                                @Override
                                public void onFailure(Exception e, String message) {
                                    if (identificationCompleted.get()) return; // Already timed out or completed

                                    Log.e(TAG, "Identification failed: " + message, e);

                                    String displayMessage = "Identification failed. Please try again.";
                                    if (e instanceof FirebaseFunctionsException) {
                                        FirebaseFunctionsException ffe = (FirebaseFunctionsException) e;
                                        if (ffe.getCode() == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED) {
                                            displayMessage = "You have reached your daily limit for AI bird identification requests.";
                                        } else if (ffe.getCode() == FirebaseFunctionsException.Code.UNAVAILABLE ||
                                                ffe.getCode() == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ||
                                                ffe.getCode() == FirebaseFunctionsException.Code.INTERNAL)
                                        {
                                            displayMessage = "Server error or timeout during identification. Please try again.";
                                        } else if (ffe.getCode() == FirebaseFunctionsException.Code.NOT_FOUND ||
                                                   ffe.getCode() == FirebaseFunctionsException.Code.INVALID_ARGUMENT) {
                                            displayMessage = "There was an issue with the identification service. Please try again.";
                                        }
                                    } else if (e instanceof IOException) {
                                        displayMessage = "Network error during identification. Check your internet connection.";
                                    } else if (!networkMonitor.isConnected()) {
                                        displayMessage = "Network connection lost during identification. Check your internet.";
                                    }

                                    finishActivityWithToast(displayMessage);
                                }
                            });
                } else {
                    // OpenAI request limit exceeded
                    finishActivityWithToast("You have reached your daily limit of " + remaining + " AI bird identification requests.");
                }
            }

            @Override
            public void onFailure(String errorMessage) {
                if (identificationCompleted.get()) return; // Already timed out or completed
                Log.e(TAG, "Failed to check OpenAI request limits: " + errorMessage);
                finishActivityWithToast("Failed to check AI limits. Please try again.");
            }
        });
    }

    private void uploadVerifiedImage(String identificationResult,
                                     @Nullable Double latitude,
                                     @Nullable Double longitude,
                                     @Nullable String localityName,
                                     @Nullable String state,
                                     @Nullable String country) {
        if (identificationCompleted.get()) return; // Already timed out or completed

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finishActivityWithToast("User not logged in. Please log in again.");
            return;
        }

        String userId = user.getUid();
        // Changed the upload path from "images/" to "user_images/" to match storage rules
        String fileName = "user_images/" + userId + "/" + UUID.randomUUID().toString() + ".jpg";
        StorageReference storageRef = FirebaseStorage.getInstance().getReference().child(fileName);

        storageRef.putFile(localImageUri)
                .addOnSuccessListener(taskSnapshot -> {
                    if (identificationCompleted.get()) return; // Already timed out or completed
                    storageRef.getDownloadUrl()
                        .addOnSuccessListener(downloadUri -> {
                            if (identificationCompleted.get()) return; // Already timed out or completed
                            if (downloadUri == null) {
                                Log.e(TAG, "Download URI is null.");
                                Toast.makeText(this, "Upload succeeded but URL missing.", Toast.LENGTH_SHORT).show();
                                proceedToInfoActivity(identificationResult, null,
                                        latitude, longitude, localityName, state, country);
                                return;
                            }
                            proceedToInfoActivity(identificationResult, downloadUri.toString(),
                                    latitude, longitude, localityName, state, country);
                        })
                        .addOnFailureListener(e -> {
                            if (identificationCompleted.get()) return; // Already timed out or completed
                            Log.e(TAG, "Failed to get download URL", e);
                            String displayMessage = "Image upload failed: Could not get download link.";
                            if (!networkMonitor.isConnected()) {
                                displayMessage = "Network connection lost during upload. Check your internet.";
                            }
                            finishActivityWithToast(displayMessage);
                        });
                })
                .addOnFailureListener(e -> {
                    if (identificationCompleted.get()) return; // Already timed out or completed
                    Log.e(TAG, "Storage upload failed", e);
                    String displayMessage = "Image upload failed. Check internet.";
                    if (!networkMonitor.isConnected()) {
                        displayMessage = "Network connection lost during upload. Check your internet.";
                    }
                    finishActivityWithToast(displayMessage);
                });
    }

    /**
     * Combined result parsing + intent extras.
     */
    private void proceedToInfoActivity(String contentStr,
                                       @Nullable String downloadUrl,
                                       @Nullable Double latitude,
                                       @Nullable Double longitude,
                                       @Nullable String localityName,
                                       @Nullable String state,
                                       @Nullable String country) {
        if (identificationCompleted.compareAndSet(false, true)) { // Mark as completed and prevent multiple calls
            timeoutHandler.removeCallbacks(timeoutRunnable); // Cancel the timeout
            Log.d(TAG, "Content String received: " + contentStr);

            String[] lines = contentStr.split("\r?\n");
            String birdId = "Unknown";
            String commonName = "Unknown";
            String scientificName = "Unknown";
            String species = "Unknown";
            String family = "Unknown";

            for (String line : lines) {
                String trimmedLine = line.trim();
                Log.d(TAG, "Parsing line: " + trimmedLine);

                if (trimmedLine.startsWith("ID: ")) {
                    birdId = trimmedLine.substring("ID: ".length()).trim();
                } else if (trimmedLine.startsWith("Common Name: ")) {
                    commonName = trimmedLine.substring("Common Name: ".length()).trim();
                } else if (trimmedLine.startsWith("Scientific Name: ")) {
                    scientificName = trimmedLine.substring("Scientific Name: ".length()).trim();
                } else if (trimmedLine.startsWith("Species: ")) {
                    species = trimmedLine.substring("Species: ".length()).trim();
                } else if (trimmedLine.startsWith("Family: ")) {
                    family = trimmedLine.substring("Family: ".length()).trim();
                }
            }

            Log.d(TAG, "Extracted Bird ID: " + birdId);

            Intent intent = new Intent(IdentifyingActivity.this, BirdInfoActivity.class);
            intent.putExtra("imageUri", localImageUri.toString());
            intent.putExtra("birdId", birdId);
            intent.putExtra("commonName", commonName);
            intent.putExtra("scientificName", scientificName);
            intent.putExtra("species", species);
            intent.putExtra("family", family);
            intent.putExtra("imageUrl", downloadUrl);

            // Pass location data forward (superset)
            if (latitude != null && longitude != null) {
                intent.putExtra("latitude", latitude);
                intent.putExtra("longitude", longitude);
                intent.putExtra("localityName", localityName);
                intent.putExtra("state", state);
                intent.putExtra("country", country);
            } else if (currentLocation != null) {
                intent.putExtra("latitude", currentLocation.getLatitude());
                intent.putExtra("longitude", currentLocation.getLongitude());
                intent.putExtra("localityName", currentLocalityName);
                intent.putExtra("state", currentState);
                intent.putExtra("country", currentCountry);
            }

            startActivity(intent);
            finish();
        }
    }

    private String encodeImage(Uri imageUri) {
        try {
            Bitmap bitmap;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                bitmap = ImageDecoder.decodeBitmap(
                        ImageDecoder.createSource(this.getContentResolver(), imageUri));
            } else {
                bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
            }

            int maxWidth = 512;
            int maxHeight = 512;
            float ratio = Math.min((float) maxWidth / bitmap.getWidth(),
                    (float) maxHeight / bitmap.getHeight());

            if (ratio < 1.0f) {
                bitmap = Bitmap.createScaledBitmap(bitmap,
                        Math.round(ratio * bitmap.getWidth()),
                        Math.round(ratio * bitmap.getHeight()),
                        true);
            }

            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, byteArrayOutputStream);
            return Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP);

        } catch (IOException e) {
            Log.e(TAG, "Error encoding image", e);
            return null;
        }
    }

    private void finishActivityWithToast(String message) {
        if (identificationCompleted.compareAndSet(false, true)) {
            if (timeoutHandler != null && timeoutRunnable != null) {
                timeoutHandler.removeCallbacks(timeoutRunnable);
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (locationHelper != null) {
            locationHelper.stopLocationUpdates();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (locationHelper != null) {
            locationHelper.shutdown();
        }
        if (timeoutHandler != null && timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
        }
    }
}