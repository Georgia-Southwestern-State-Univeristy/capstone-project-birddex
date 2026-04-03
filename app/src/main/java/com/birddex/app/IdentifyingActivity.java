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
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.functions.FirebaseFunctionsException;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * IdentifyingActivity orchestrates the bird identification process.
 */
/**
 * IdentifyingActivity: Result screen shown after bird identification; displays the match and lets the user continue with save/collection flows.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
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
    private final AtomicBoolean identificationCompleted = new AtomicBoolean(false);
    // Guards startIdentificationFlow so only one call proceeds even if location callback
    // and permission result both fire near-simultaneously.
    private final AtomicBoolean identificationStarted = new AtomicBoolean(false);
    private final String requestId = UUID.randomUUID().toString(); // Persistent ID for this specific attempt
    private final ArrayList<String> pendingFeedbackNotes = new ArrayList<>();
    @Nullable private String currentIdentificationLogId;
    @Nullable private String currentIdentificationId;
    private StorageReference uploadedIdentificationStorageRef;
    private String uploadedIdentificationDownloadUrl;
    private boolean identificationImageShouldBeKept = false;

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarHelper.applyStandardNavBar(this);
        setContentView(R.layout.activity_identifying);

        Log.d(TAG, "onCreate: Activity started");

        // Bind or inflate the UI pieces this method needs before it can update the screen.
        ImageView identifyingImageView = findViewById(R.id.identifyingImageView);
        String uriStr = getIntent().getStringExtra("imageUri");
        if (uriStr == null) {
            Log.e(TAG, "onCreate: No image URI provided in intent");
            finishActivityWithToast("No image provided for identification.");
            return;
        }

        localImageUri = Uri.parse(uriStr);
        identifyingImageView.setImageURI(localImageUri);

        // Burst frames are no longer needed here because the guard report was already computed
        // before this screen and passed in the intent.
        clearBurstFrameCacheAsync();

        openAiApi = new OpenAiApi();
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        firebaseManager = new FirebaseManager(this);

        locationHelper = new LocationHelper(this, this);
        networkMonitor = new NetworkMonitor(this, this);

        timeoutHandler = new Handler(Looper.getMainLooper());
        timeoutRunnable = () -> {
            if (identificationCompleted.compareAndSet(false, true)) {
                Log.e(TAG, "Identification process timed out after " + IDENTIFICATION_TIMEOUT_MS + "ms");
                deleteUploadedIdentificationImageIfUnused();
                deleteLocalTempImageIfNeeded(localImageUri);
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
                        // Give the user immediate feedback about the result of this action.
                        Toast.makeText(this, "Location permissions denied. Proceeding without location.", Toast.LENGTH_LONG).show();
                        startIdentificationFlow(localImageUri, null, null, null, null, null);
                    }
                });

        requestLocationPermissions();
    }

    /**
     * Runs when the screen returns to the foreground, so it often refreshes UI state or restarts
     * listeners.
     */
    @Override
    protected void onResume() {
        super.onResume();
        networkMonitor.register();
    }

    /**
     * Runs when the screen is leaving the foreground, so it is used to pause work or save
     * transient state.
     */
    @Override
    protected void onPause() {
        super.onPause();
        networkMonitor.unregister();
    }

    /**
     * Main logic block for this part of the feature.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
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

    /**
     * Main logic block for this part of the feature.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    @Override
    public void onLocationReceived(Location location, @Nullable String localityName, @Nullable String state, @Nullable String country) {
        if (isFinishing() || isDestroyed()) return;

        Log.d(TAG, "onLocationReceived: Lat=" + location.getLatitude() + ", Lng=" + location.getLongitude() + ", Locality=" + localityName);
        this.currentLocation = location;
        this.currentLocalityName = localityName;
        this.currentState = state;
        this.currentCountry = country;
        startIdentificationFlow(localImageUri, location.getLatitude(), location.getLongitude(), localityName, state, country);
        locationHelper.stopLocationUpdates();
    }

    /**
     * Main logic block for this part of the feature.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public void onLocationReceived(Location location, @Nullable String localityName) {
        onLocationReceived(location, localityName, null, null);
    }

    /**
     * Main logic block for this part of the feature.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    @Override
    public void onLocationError(String errorMessage) {
        if (isFinishing() || isDestroyed()) return;

        Log.e(TAG, "onLocationError: " + errorMessage);
        // Give the user immediate feedback about the result of this action.
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

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void startIdentificationFlow(Uri imageUri, @Nullable Double latitude, @Nullable Double longitude, @Nullable String localityName, @Nullable String state, @Nullable String country) {
        // Kick off an asynchronous one-time read; the callbacks below decide how the UI should react.
        if (identificationCompleted.get()) {
            Log.d(TAG, "startIdentificationFlow: Already completed, ignoring");
            return;
        }
        // Use compareAndSet so that if the location callback and the permission callback both
        // invoke this method at the same time, only the first one proceeds.
        if (!identificationStarted.compareAndSet(false, true)) {
            Log.d(TAG, "startIdentificationFlow: Already started, ignoring duplicate call");
            return;
        }

        if (!networkMonitor.isConnected()) {
            Log.e(TAG, "startIdentificationFlow: No internet connection");
            finishActivityWithToast("No internet connection.");
            return;
        }

        Log.d(TAG, "startIdentificationFlow: uploading identification image");
        uploadImageToIdentificationStorage(imageUri, latitude, longitude, localityName, state, country);
    }

    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void uploadImageToIdentificationStorage(Uri imageUri, @Nullable Double latitude, @Nullable Double longitude, @Nullable String localityName, @Nullable String state, @Nullable String country) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finishActivityWithToast("User not logged in.");
            return;
        }

        // Reverted: Folder changed to identificationImages
        String fileName = "identificationImages/" + user.getUid() + "/" + UUID.randomUUID().toString() + ".jpg";
        StorageReference storageRef = FirebaseStorage.getInstance().getReference().child(fileName);
        uploadedIdentificationStorageRef = storageRef;
        uploadedIdentificationDownloadUrl = null;
        identificationImageShouldBeKept = false;

        Log.d(TAG, "uploadImageToIdentificationStorage: Uploading to " + fileName);
        storageRef.putFile(imageUri)
                .addOnSuccessListener(taskSnapshot -> {
                    // Kick off an asynchronous one-time read; the callbacks below decide how the UI should react.
                    if (identificationCompleted.get() || isFinishing() || isDestroyed()) return;
                    storageRef.getDownloadUrl().addOnSuccessListener(downloadUri -> {
                        if (identificationCompleted.get() || isFinishing() || isDestroyed()) return;
                        uploadedIdentificationDownloadUrl = downloadUri.toString();
                        Log.d(TAG, "Image uploaded. Download URL: " + downloadUri);
                        identifyBirdWithUrl(downloadUri.toString(), latitude, longitude, localityName, state, country);
                    }).addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to get download URL", e);
                        deleteUploadedIdentificationImageIfUnused();
                        deleteLocalTempImageIfNeeded(localImageUri);
                        finishActivityWithToast("Failed to process identification image link.");
                    });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Upload failed", e);
                    deleteLocalTempImageIfNeeded(localImageUri);
                    finishActivityWithToast("Image upload for identification failed.");
                });
    }

    /**
     * Main logic block for this part of the feature.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void identifyBirdWithUrl(String downloadUrl, @Nullable Double latitude, @Nullable Double longitude, @Nullable String localityName, @Nullable String state, @Nullable String country) {
        Log.d(TAG, "identifyBirdWithUrl: Encoding image for AI analysis...");
        String base64Image = encodeImage(localImageUri); // Still need base64 for the Vision API call
        if (base64Image == null) {
            deleteUploadedIdentificationImageIfUnused();
            deleteLocalTempImageIfNeeded(localImageUri);
            finishActivityWithToast("Failed to encode image for analysis.");
            return;
        }

        boolean awardPointsRequested = getIntent().getBooleanExtra("awardPoints", true);
        CaptureGuardHelper.GuardReport captureGuardReport = CaptureGuardHelper.readReportFromIntent(getIntent(), awardPointsRequested);

        // We pass the base64 for analysis AND the storage URL for logging AND requestId for idempotency
        openAiApi.identifyBirdFromImage(base64Image, downloadUrl, latitude, longitude, localityName, requestId, captureGuardReport, new OpenAiApi.IdentifyBirdCallback() {
            @Override
            public void onSuccess(OpenAiApi.IdentifyBirdResult result) {
                if (identificationCompleted.get() || isFinishing() || isDestroyed()) return;
                currentIdentificationLogId = result.identificationLogId;
                currentIdentificationId = result.identificationId;
                flushPendingFeedbackIfPossible();

                Log.d(TAG, "identifyBird onSuccess: isVerified=" + result.isVerified
                        + ", isGore=" + result.isGore
                        + ", isInDatabase=" + result.isInDatabase
                        + ", reasonCode=" + result.reasonCode);

                if (result.isGore) {
                    deleteUploadedIdentificationImageIfUnused();
                    deleteLocalTempImageIfNeeded(localImageUri);
                    finishActivityWithToast("Please take a picture of a non-gore picture of a bird.");
                    return;
                }

                if (!result.isInDatabase || "NOT_IN_DATABASE".equals(result.reasonCode)) {
                    deleteUploadedIdentificationImageIfUnused();
                    deleteLocalTempImageIfNeeded(localImageUri);
                    finishActivityWithToast("Sorry, this bird is not in our database just yet.");
                    return;
                }

                if (!result.isVerified || result.primaryBird == null || result.primaryBird.birdId == null || result.primaryBird.birdId.trim().isEmpty()) {
                    deleteUploadedIdentificationImageIfUnused();
                    deleteLocalTempImageIfNeeded(localImageUri);
                    finishActivityWithToast(result.userMessage != null && !result.userMessage.trim().isEmpty()
                            ? result.userMessage
                            : "Identification could not be verified.");
                    return;
                }

                proceedToInfoActivity(result, downloadUrl, latitude, longitude, localityName, state, country);
            }

            @Override
            public void onFailure(Exception e, String message) {
                if (identificationCompleted.get() || isFinishing() || isDestroyed()) return;
                Log.e(TAG, "identifyBird onFailure: " + message, e);
                deleteUploadedIdentificationImageIfUnused();
                deleteLocalTempImageIfNeeded(localImageUri);
                finishActivityWithToast("Identification failed: " + message);
            }
        });
    }

    /**
     * Main logic block for this part of the feature.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    private void flushPendingFeedbackIfPossible() {
        if (currentIdentificationLogId == null || currentIdentificationLogId.trim().isEmpty() || pendingFeedbackNotes.isEmpty()) {
            return;
        }
        for (String pendingFeedback : new ArrayList<>(pendingFeedbackNotes)) {
            if (pendingFeedback == null || pendingFeedback.trim().isEmpty()) {
                continue;
            }
            openAiApi.submitIdentificationFeedback(
                    currentIdentificationLogId,
                    currentIdentificationId,
                    "identifying_loading",
                    pendingFeedback,
                    new OpenAiApi.FeedbackSyncCallback() {
                        @Override
                        public void onSuccess(String userMessage) {
                            if (isFinishing() || isDestroyed()) return;
                            String finalMessage = (userMessage != null && !userMessage.trim().isEmpty())
                                    ? userMessage
                                    : getString(R.string.submit_feedback_pending_sent);
                            Toast.makeText(IdentifyingActivity.this, finalMessage, Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onFailure(Exception e, String message) {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(IdentifyingActivity.this, message != null && !message.trim().isEmpty()
                                    ? message
                                    : getString(R.string.submit_feedback_pending_failed), Toast.LENGTH_LONG).show();
                        }
                    }
            );
        }
        pendingFeedbackNotes.clear();
    }

    private void proceedToInfoActivity(OpenAiApi.IdentifyBirdResult result, @Nullable String downloadUrl, @Nullable Double latitude, @Nullable Double longitude, @Nullable String localityName, @Nullable String state, @Nullable String country) {
        if (identificationCompleted.compareAndSet(false, true)) {
            timeoutHandler.removeCallbacks(timeoutRunnable);

            identificationImageShouldBeKept = true;
            OpenAiApi.BirdChoice primary = result.primaryBird;
            Intent intent = new Intent(IdentifyingActivity.this, BirdInfoActivity.class);
            intent.putExtra("imageUri", localImageUri.toString());
            intent.putExtra("birdId", primary.birdId);
            intent.putExtra("commonName", primary.commonName);
            intent.putExtra("scientificName", primary.scientificName);
            intent.putExtra("species", primary.species);
            intent.putExtra("family", primary.family);
            intent.putExtra("imageUrl", downloadUrl);
            intent.putExtra("identificationLogId", result.identificationLogId);
            intent.putExtra("identificationId", result.identificationId);
            intent.putExtra("selectionSource", primary.source != null ? primary.source : "initial_result");
            intent.putParcelableArrayListExtra("modelAlternatives", toCandidateBundles(result.modelAlternatives));
            intent.putExtra("notMyBirdAllowed", result.notMyBirdAllowed);
            intent.putExtra("notMyBirdBlockMessage", result.notMyBirdBlockMessage);
            if (result.modelTop1Confidence != null) intent.putExtra("modelTop1Confidence", result.modelTop1Confidence);
            if (result.modelTop2Confidence != null) intent.putExtra("modelTop2Confidence", result.modelTop2Confidence);
            if (result.modelConfidenceMargin != null) intent.putExtra("modelConfidenceMargin", result.modelConfidenceMargin);

            boolean awardPoints = getIntent().getBooleanExtra("awardPoints", true) && result.allowPointAward;
            intent.putExtra("awardPoints", awardPoints);
            if (result.pointAwardBlockReason != null) intent.putExtra("pointAwardBlockReason", result.pointAwardBlockReason);
            if (result.pointAwardUserMessage != null) intent.putExtra("pointAwardUserMessage", result.pointAwardUserMessage);

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

    /**
     * Main logic block for this part of the feature.
     * Bitmap/rendering work happens here, so this block is shaping the final card/image output
     * rather than just text data.
     */

    private void deleteUploadedIdentificationImageIfUnused() {
        if (identificationImageShouldBeKept) {
            return;
        }

        StorageReference ref = uploadedIdentificationStorageRef;
        if (ref == null) {
            return;
        }

        uploadedIdentificationStorageRef = null;
        uploadedIdentificationDownloadUrl = null;

        ref.delete()
                .addOnSuccessListener(unused -> Log.d(TAG, "Unused identification image deleted from Storage."))
                .addOnFailureListener(e -> Log.w(TAG, "Failed to delete unused identification image.", e));
    }

    private void deleteLocalTempImageIfNeeded(@Nullable Uri uri) {
        if (uri == null || uri.getPath() == null) {
            return;
        }

        try {
            File file = new File(uri.getPath());
            String cacheRoot = getCacheDir().getAbsolutePath();
            String filePath = file.getAbsolutePath();

            if (!filePath.startsWith(cacheRoot)) {
                return;
            }
            if (!file.exists()) {
                return;
            }

            if (file.delete()) {
                Log.d(TAG, "Deleted local temp identification image: " + filePath);
            } else {
                Log.w(TAG, "Failed to delete local temp identification image: " + filePath);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to delete local temp identification image.", e);
        }
    }

    private void clearBurstFrameCacheAsync() {
        new Thread(() -> {
            try {
                File dir = new File(getCacheDir(), "camera_burst_frames");
                if (!dir.exists()) return;

                File[] files = dir.listFiles();
                if (files == null) return;

                for (File file : files) {
                    if (file != null && file.exists() && !file.delete()) {
                        Log.w(TAG, "Failed to delete burst temp file: " + file.getAbsolutePath());
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to clean burst frame cache.", e);
            }
        }).start();
    }

    private String encodeImage(Uri imageUri) {
        try {
            Bitmap bitmap = (Build.VERSION.SDK_INT >= 28) ? ImageDecoder.decodeBitmap(ImageDecoder.createSource(this.getContentResolver(), imageUri)) : MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
            int maxWidth = 1024, maxHeight = 1024;
            float ratio = Math.min((float) maxWidth / bitmap.getWidth(), (float) maxHeight / bitmap.getHeight());
            if (ratio < 1.0f) bitmap = Bitmap.createScaledBitmap(bitmap, Math.round(ratio * bitmap.getWidth()), Math.round(ratio * bitmap.getHeight()), true);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (IOException e) {
            Log.e(TAG, "encodeImage: Error", e);
            return null;
        }
    }

    /**
     * Main logic block for this part of the feature.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void finishActivityWithToast(String message) {
        if (identificationCompleted.compareAndSet(false, true)) {
            Log.d(TAG, "finishActivityWithToast: " + message);
            if (timeoutHandler != null) timeoutHandler.removeCallbacks(timeoutRunnable);
            // Give the user immediate feedback about the result of this action.
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override protected void onStop() { super.onStop(); if (locationHelper != null) locationHelper.stopLocationUpdates(); }
    @Override protected void onDestroy() { super.onDestroy(); if (locationHelper != null) locationHelper.shutdown(); if (timeoutHandler != null) timeoutHandler.removeCallbacks(timeoutRunnable); }
}