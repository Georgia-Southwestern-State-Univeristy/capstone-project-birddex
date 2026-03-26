package com.birddex.app;

import android.Manifest;
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
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private Handler timeoutHandler;
    private Runnable timeoutRunnable;
    private static final long IDENTIFICATION_TIMEOUT_MS = 45000;
    private final AtomicBoolean identificationCompleted = new AtomicBoolean(false);
    private final AtomicBoolean identificationStarted = new AtomicBoolean(false);
    private final String requestId = UUID.randomUUID().toString();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarHelper.applyStandardNavBar(this);
        setContentView(R.layout.activity_identifying);

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
                    if (isFinishing() || isDestroyed()) return;

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
        if (isFinishing() || isDestroyed()) return;

        Log.d(TAG, "onLocationReceived: Lat=" + location.getLatitude() + ", Lng=" + location.getLongitude() + ", Locality=" + localityName);
        startIdentificationFlow(localImageUri, location.getLatitude(), location.getLongitude(), localityName, state, country);
        locationHelper.stopLocationUpdates();
    }

    public void onLocationReceived(Location location, @Nullable String localityName) {
        onLocationReceived(location, localityName, null, null);
    }

    @Override
    public void onLocationError(String errorMessage) {
        if (isFinishing() || isDestroyed()) return;

        Log.e(TAG, "onLocationError: " + errorMessage);
        Toast.makeText(this, "Location error: " + errorMessage + ". Proceeding without location.", Toast.LENGTH_LONG).show();
        startIdentificationFlow(localImageUri, null, null, null, null, null);
        locationHelper.stopLocationUpdates();
    }

    @Override
    public void onNetworkAvailable() {
        Log.d(TAG, "onNetworkAvailable: Connection restored");
    }

    @Override
    public void onNetworkLost() {
        Log.w(TAG, "onNetworkLost: Connection lost during process");
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
        if (identificationCompleted.get()) {
            Log.d(TAG, "startIdentificationFlow: Already completed, ignoring");
            return;
        }
        if (!identificationStarted.compareAndSet(false, true)) {
            Log.d(TAG, "startIdentificationFlow: Already started, ignoring duplicate call");
            return;
        }

        if (!networkMonitor.isConnected()) {
            Log.e(TAG, "startIdentificationFlow: No internet connection");
            finishActivityWithToast("No internet connection.");
            return;
        }

        uploadImageToIdentificationStorage(imageUri, latitude, longitude, localityName, state, country);
    }

    private void uploadImageToIdentificationStorage(Uri imageUri,
                                                    @Nullable Double latitude,
                                                    @Nullable Double longitude,
                                                    @Nullable String localityName,
                                                    @Nullable String state,
                                                    @Nullable String country) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finishActivityWithToast("User not logged in.");
            return;
        }

        String fileName = "identificationImages/" + user.getUid() + "/" + UUID.randomUUID().toString() + ".jpg";
        StorageReference storageRef = FirebaseStorage.getInstance().getReference().child(fileName);

        Log.d(TAG, "uploadImageToIdentificationStorage: Uploading to " + fileName);
        storageRef.putFile(imageUri)
                .addOnSuccessListener(taskSnapshot -> {
                    if (identificationCompleted.get() || isFinishing() || isDestroyed()) return;
                    storageRef.getDownloadUrl().addOnSuccessListener(downloadUri -> {
                        if (identificationCompleted.get() || isFinishing() || isDestroyed()) return;
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

    private void identifyBirdWithUrl(String downloadUrl,
                                     @Nullable Double latitude,
                                     @Nullable Double longitude,
                                     @Nullable String localityName,
                                     @Nullable String state,
                                     @Nullable String country) {
        Log.d(TAG, "identifyBirdWithUrl: Encoding image for AI analysis...");
        String base64Image = encodeImage(localImageUri);
        if (base64Image == null) {
            finishActivityWithToast("Failed to encode image for analysis.");
            return;
        }

        openAiApi.identifyBirdFromImage(base64Image, downloadUrl, latitude, longitude, localityName, requestId, new OpenAiApi.OpenAiCallback() {
            @Override
            public void onSuccess(OpenAiApi.IdentificationResult result) {
                if (identificationCompleted.get() || isFinishing() || isDestroyed()) return;
                Log.d(TAG, "identifyBird onSuccess: verified=" + result.isVerified + ", gore=" + result.isGore + ", inDatabase=" + result.isInDatabase);

                if (result.isGore) {
                    finishActivityWithToast(result.userMessage != null ? result.userMessage : "Please take a picture of a non-gore picture of a bird.");
                    return;
                }

                if (!result.isInDatabase) {
                    finishActivityWithToast(result.userMessage != null ? result.userMessage : "Sorry, this bird is not in our database just yet.");
                    return;
                }

                if (!result.isVerified || result.primaryBird == null) {
                    finishActivityWithToast("Identification failed. Please try again.");
                    return;
                }

                proceedToInfoActivity(result, downloadUrl, latitude, longitude, localityName, state, country);
            }

            @Override
            public void onFailure(Exception e, String message) {
                if (identificationCompleted.get() || isFinishing() || isDestroyed()) return;
                Log.e(TAG, "identifyBird onFailure: " + message, e);
                finishActivityWithToast("Identification failed: " + message);
            }
        });
    }

    private void proceedToInfoActivity(OpenAiApi.IdentificationResult result,
                                       @Nullable String downloadUrl,
                                       @Nullable Double latitude,
                                       @Nullable Double longitude,
                                       @Nullable String localityName,
                                       @Nullable String state,
                                       @Nullable String country) {
        if (!identificationCompleted.compareAndSet(false, true)) {
            return;
        }

        timeoutHandler.removeCallbacks(timeoutRunnable);

        Intent intent = new Intent(IdentifyingActivity.this, BirdInfoActivity.class);
        intent.putExtra("imageUri", localImageUri.toString());
        intent.putExtra("birdId", result.primaryBird.birdId);
        intent.putExtra("commonName", result.primaryBird.commonName);
        intent.putExtra("scientificName", result.primaryBird.scientificName);
        intent.putExtra("species", result.primaryBird.species);
        intent.putExtra("family", result.primaryBird.family);
        intent.putExtra("imageUrl", downloadUrl);
        intent.putExtra("identificationLogId", result.identificationLogId);
        intent.putExtra("identificationId", result.identificationId);
        intent.putExtra("selectionSource", "initial_result");
        intent.putParcelableArrayListExtra("modelAlternatives", toCandidateBundles(result.modelAlternatives));

        boolean awardPoints = getIntent().getBooleanExtra("awardPoints", true);
        intent.putExtra("awardPoints", awardPoints);

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

    private ArrayList<Bundle> toCandidateBundles(ArrayList<OpenAiApi.BirdChoice> candidates) {
        ArrayList<Bundle> bundles = new ArrayList<>();
        if (candidates == null) {
            return bundles;
        }

        for (OpenAiApi.BirdChoice candidate : candidates) {
            if (candidate == null || candidate.birdId == null || candidate.birdId.trim().isEmpty()) {
                continue;
            }
            Bundle bundle = new Bundle();
            bundle.putString("candidateBirdId", candidate.birdId);
            bundle.putString("candidateCommonName", candidate.commonName);
            bundle.putString("candidateScientificName", candidate.scientificName);
            bundle.putString("candidateSpecies", candidate.species);
            bundle.putString("candidateFamily", candidate.family);
            bundle.putString("candidateSource", candidate.source);
            bundles.add(bundle);
        }
        return bundles;
    }

    private String encodeImage(Uri imageUri) {
        try {
            Bitmap bitmap = (Build.VERSION.SDK_INT >= 28)
                    ? ImageDecoder.decodeBitmap(ImageDecoder.createSource(this.getContentResolver(), imageUri))
                    : MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);

            int maxWidth = 1024;
            int maxHeight = 1024;
            float ratio = Math.min((float) maxWidth / bitmap.getWidth(), (float) maxHeight / bitmap.getHeight());
            if (ratio < 1.0f) {
                bitmap = Bitmap.createScaledBitmap(bitmap,
                        Math.round(ratio * bitmap.getWidth()),
                        Math.round(ratio * bitmap.getHeight()),
                        true);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
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

    @Override
    protected void onStop() {
        super.onStop();
        if (locationHelper != null) locationHelper.stopLocationUpdates();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (locationHelper != null) locationHelper.shutdown();
        if (timeoutHandler != null) timeoutHandler.removeCallbacks(timeoutRunnable);
    }
}
