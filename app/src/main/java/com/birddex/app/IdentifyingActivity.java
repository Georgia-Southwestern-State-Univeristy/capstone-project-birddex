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
import java.util.Date;

/**
 * IdentifyingActivity orchestrates the bird identification process.
 */
public class IdentifyingActivity extends AppCompatActivity implements LocationHelper.LocationListener, NetworkMonitor.NetworkStatusListener {

    private static final String TAG = "IdentifyingActivity";
    public static final String EXTRA_VERIFIED_BIRD_ID = "verifiedBirdId";

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
    private static final long IDENTIFICATION_TIMEOUT_MS = 45000; // Increased timeout for upload + AI
    private AtomicBoolean identificationCompleted = new AtomicBoolean(false);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_identifying);

        Log.d(TAG, "onCreate: Activity started");

        ImageView identifyingImageView = findViewById(R.id.identifyingImageView);
        String uriStr = getIntent().getStringExtra("imageUri");
        if (uriStr == null) {
            Log.e(TAG, "onCreate: No image URI provided in intent");
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
                Log.e(TAG, "Identification process timed out after " + IDENTIFICATION_TIMEOUT_MS + "ms");
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
                        Log.d(TAG, "Location permissions granted, requesting location...");
                        locationHelper.getLastKnownLocation();
                    } else {
                        Log.w(TAG, "Location permissions denied, proceeding without location");
                        Toast.makeText(this, "Location permissions denied. Proceeding without location.", Toast.LENGTH_LONG).show();
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
        boolean fineLocationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseLocationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (fineLocationGranted || coarseLocationGranted) {
            Log.d(TAG, "requestLocationPermissions: Permissions already granted");
            locationHelper.getLastKnownLocation();
        } else {
            Log.d(TAG, "requestLocationPermissions: Requesting permissions");
            locationPermissionLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
        }
    }

    @Override
    public void onLocationReceived(Location location, @Nullable String localityName, @Nullable String state, @Nullable String country) {
        Log.d(TAG, "onLocationReceived: Lat=" + location.getLatitude() + ", Lng=" + location.getLongitude() + ", Locality=" + localityName);
        this.currentLocation = location;
        this.currentLocalityName = localityName;
        this.currentState = state;
        this.currentCountry = country;
        startIdentificationFlow(localImageUri, location.getLatitude(), location.getLongitude(), localityName, state, country);
        locationHelper.stopLocationUpdates();
    }

    public void onLocationReceived(Location location, @Nullable String localityName) {
        onLocationReceived(location, localityName, null, null);
    }

    @Override
    public void onLocationError(String errorMessage) {
        Log.e(TAG, "onLocationError: " + errorMessage);
        Toast.makeText(this, "Location error: " + errorMessage + ". Proceeding without location.", Toast.LENGTH_LONG).show();
        startIdentificationFlow(localImageUri, null, null, null, null, null);
        locationHelper.stopLocationUpdates();
    }

    @Override public void onNetworkAvailable() {
        Log.d(TAG, "onNetworkAvailable: Connection restored");
    }
    @Override public void onNetworkLost() {
        Log.w(TAG, "onNetworkLost: Connection lost during process");
        if (!identificationCompleted.get()) {
            finishActivityWithToast("Internet connection lost. Please reconnect and try again.");
        }
    }

    private void startIdentificationFlow(Uri imageUri, @Nullable Double latitude, @Nullable Double longitude, @Nullable String localityName, @Nullable String state, @Nullable String country) {
        if (identificationCompleted.get()) {
            Log.d(TAG, "startIdentificationFlow: Already completed, ignoring");
            return;
        }

        if (!networkMonitor.isConnected()) {
            Log.e(TAG, "startIdentificationFlow: No internet connection");
            finishActivityWithToast("No internet connection.");
            return;
        }

        Log.d(TAG, "startIdentificationFlow: checking limits");

        firebaseManager.getOpenAiRequestsRemaining(new FirebaseManager.OpenAiRequestLimitListener() {
            @Override
            public void onCheckComplete(boolean hasRequestsRemaining, int remaining, Date expirationDate) {
                if (identificationCompleted.get()) return;

                if (hasRequestsRemaining) {
                    Log.d(TAG, "onCheckComplete: " + remaining + " requests remaining. Uploading image to identificationImages first...");
                    // Reverted: Upload to identificationImages FIRST, then identify
                    uploadImageToIdentificationStorage(imageUri, latitude, longitude, localityName, state, country);
                } else {
                    Log.w(TAG, "onCheckComplete: Daily limit reached");
                    finishActivityWithToast("Daily limit reached for AI requests.");
                }
            }

            @Override
            public void onFailure(String errorMessage) {
                if (identificationCompleted.get()) return;
                Log.e(TAG, "Failed to check OpenAI request limits: " + errorMessage);
                finishActivityWithToast("Failed to check AI limits.");
            }
        });
    }

    private void uploadImageToIdentificationStorage(Uri imageUri, @Nullable Double latitude, @Nullable Double longitude, @Nullable String localityName, @Nullable String state, @Nullable String country) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finishActivityWithToast("User not logged in.");
            return;
        }

        // Reverted: Folder changed to identificationImages
        String fileName = "identificationImages/" + user.getUid() + "/" + UUID.randomUUID().toString() + ".jpg";
        StorageReference storageRef = FirebaseStorage.getInstance().getReference().child(fileName);

        Log.d(TAG, "uploadImageToIdentificationStorage: Uploading to " + fileName);
        storageRef.putFile(imageUri)
                .addOnSuccessListener(taskSnapshot -> {
                    if (identificationCompleted.get()) return;
                    storageRef.getDownloadUrl().addOnSuccessListener(downloadUri -> {
                        if (identificationCompleted.get()) return;
                        Log.d(TAG, "Image uploaded. Download URL: " + downloadUri);
                        identifyBirdWithUrl(downloadUri.toString(), latitude, longitude, localityName, state, country);
                    }).addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to get download URL", e);
                        finishActivityWithToast("Failed to process identification image link.");
                    });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Upload failed", e);
                    finishActivityWithToast("Image upload for identification failed.");
                });
    }

    private void identifyBirdWithUrl(String downloadUrl, @Nullable Double latitude, @Nullable Double longitude, @Nullable String localityName, @Nullable String state, @Nullable String country) {
        Log.d(TAG, "identifyBirdWithUrl: Encoding image for AI analysis...");
        String base64Image = encodeImage(localImageUri); // Still need base64 for the Vision API call
        if (base64Image == null) {
            finishActivityWithToast("Failed to encode image for analysis.");
            return;
        }

        // We pass the base64 for analysis AND the storage URL for logging
        openAiApi.identifyBirdFromImage(base64Image, downloadUrl, latitude, longitude, localityName, new OpenAiApi.OpenAiCallback() {
            @Override
            public void onSuccess(String response, boolean isVerified, boolean isGore) {
                if (identificationCompleted.get()) return;
                Log.d(TAG, "OpenAI onSuccess: isVerified=" + isVerified + ", isGore=" + isGore);

                if (isGore) {
                    finishActivityWithToast("Please take a picture of a non-gore picture of a bird.");
                    return;
                }

                if (!isVerified) {
                    finishActivityWithToast("Bird not recognized in Georgia regional data.");
                    return;
                }
                proceedToInfoActivity(response, downloadUrl, latitude, longitude, localityName, state, country);
            }

            @Override
            public void onFailure(Exception e, String message) {
                if (identificationCompleted.get()) return;
                Log.e(TAG, "OpenAI onFailure: " + message, e);
                finishActivityWithToast("Identification failed: " + message);
            }
        });
    }

    private void proceedToInfoActivity(String contentStr, @Nullable String downloadUrl, @Nullable Double latitude, @Nullable Double longitude, @Nullable String localityName, @Nullable String state, @Nullable String country) {
        if (identificationCompleted.compareAndSet(false, true)) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
            
            Log.d(TAG, "proceedToInfoActivity: Parsing results...");
            String birdId = "Unknown", commonName = "Unknown", scientificName = "Unknown", species = "Unknown", family = "Unknown";
            String[] lines = contentStr.split("\r?\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("ID: ")) birdId = trimmed.substring(4).trim();
                else if (trimmed.startsWith("Common Name: ")) commonName = trimmed.substring(13).trim();
                else if (trimmed.startsWith("Scientific Name: ")) scientificName = trimmed.substring(17).trim();
                else if (trimmed.startsWith("Species: ")) species = trimmed.substring(9).trim();
                else if (trimmed.startsWith("Family: ")) family = trimmed.substring(8).trim();
            }

            Intent intent = new Intent(IdentifyingActivity.this, BirdInfoActivity.class);
            intent.putExtra("imageUri", localImageUri.toString());
            intent.putExtra("birdId", birdId);
            intent.putExtra("commonName", commonName);
            intent.putExtra("scientificName", scientificName);
            intent.putExtra("species", species);
            intent.putExtra("family", family);
            intent.putExtra("imageUrl", downloadUrl);

            if (latitude != null) {
                intent.putExtra("latitude", latitude);
                intent.putExtra("longitude", longitude);
                intent.putExtra("localityName", localityName);
                intent.putExtra("state", state);
                intent.putExtra("country", country);
            }

            startActivity(intent);
            finish();
        }
    }

    private String encodeImage(Uri imageUri) {
        try {
            Bitmap bitmap = (Build.VERSION.SDK_INT >= 28) ? ImageDecoder.decodeBitmap(ImageDecoder.createSource(this.getContentResolver(), imageUri)) : MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
            int maxWidth = 512, maxHeight = 512;
            float ratio = Math.min((float) maxWidth / bitmap.getWidth(), (float) maxHeight / bitmap.getHeight());
            if (ratio < 1.0f) bitmap = Bitmap.createScaledBitmap(bitmap, Math.round(ratio * bitmap.getWidth()), Math.round(ratio * bitmap.getHeight()), true);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos);
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (IOException e) { 
            Log.e(TAG, "encodeImage: Error", e);
            return null; 
        }
    }

    private void finishActivityWithToast(String message) {
        if (identificationCompleted.compareAndSet(false, true)) {
            Log.d(TAG, "finishActivityWithToast: " + message);
            if (timeoutHandler != null) timeoutHandler.removeCallbacks(timeoutRunnable);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override protected void onStop() { super.onStop(); if (locationHelper != null) locationHelper.stopLocationUpdates(); }
    @Override protected void onDestroy() { super.onDestroy(); if (locationHelper != null) locationHelper.shutdown(); if (timeoutHandler != null) timeoutHandler.removeCallbacks(timeoutRunnable); }
}
