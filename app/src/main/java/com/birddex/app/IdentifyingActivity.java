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
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date; // Import Date for timestamp
import java.util.HashMap; // Import HashMap for maps
import java.util.Map;   // Import Map for maps
import java.util.UUID;

/**
 * IdentifyingActivity orchestrates the bird identification process.
 * Order: Get Location -> Identify (Cloud) -> Verify (Cloud) -> Upload (Storage) ONLY if verified.
 * Now includes user location for identification logging.
 */
public class IdentifyingActivity extends AppCompatActivity implements LocationHelper.LocationListener {

    private static final String TAG = "IdentifyingActivity";
    public static final String EXTRA_VERIFIED_BIRD_ID = "verifiedBirdId"; // New extra for verified bird ID
    private Uri localImageUri;
    private OpenAiApi openAiApi;
    private LocationHelper locationHelper;
    private ActivityResultLauncher<String[]> locationPermissionLauncher;
    private FirebaseManager firebaseManager; // Add FirebaseManager instance

    private Location currentLocation;
    private String currentLocalityName;
    private String currentState; // New field for state
    private String currentCountry; // New field for country

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_identifying);

        ImageView identifyingImageView = findViewById(R.id.identifyingImageView);
        String uriStr = getIntent().getStringExtra("imageUri");
        if (uriStr == null) {
            finish();
            return;
        }

        localImageUri = Uri.parse(uriStr);
        identifyingImageView.setImageURI(localImageUri);

        openAiApi = new OpenAiApi();
        firebaseManager = new FirebaseManager(); // Initialize FirebaseManager

        // Initialize LocationHelper
        locationHelper = new LocationHelper(this, this); // 'this' activity implements LocationListener

        // Initialize permission launcher
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
                        Toast.makeText(this, "Location permissions denied. Cannot log identification location.", Toast.LENGTH_LONG).show();
                        // Proceed with identification without location
                        startIdentification(localImageUri, null, null, null, null, null);
                    }
                });

        // Request permissions and then get location
        requestLocationPermissions();
    }

    private void requestLocationPermissions() {
        boolean fineLocationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseLocationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (fineLocationGranted || coarseLocationGranted) {
            Log.d(TAG, "Location permissions already granted, getting last known location.");
            locationHelper.getLastKnownLocation(); // Permissions are already granted, get location
        } else {
            Log.d(TAG, "Requesting location permissions.");
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    // Implementation of LocationHelper.LocationListener
    @Override
    public void onLocationReceived(Location location, @Nullable String localityName, @Nullable String state, @Nullable String country) {
        this.currentLocation = location;
        this.currentLocalityName = localityName;
        this.currentState = state; // Store state
        this.currentCountry = country; // Store country
        Log.d(TAG, "Location received: " + localityName + ", " + state + ", " + country + " (" + location.getLatitude() + ", " + location.getLongitude() + ")");
        // Once location is received, start the identification process
        startIdentification(localImageUri, location.getLatitude(), location.getLongitude(), localityName, state, country);
        locationHelper.stopLocationUpdates(); // Stop updates after getting one valid location
    }

    @Override
    public void onLocationError(String errorMessage) {
        Log.e(TAG, "Location error: " + errorMessage);
        Toast.makeText(this, "Location error: " + errorMessage + ". Cannot log identification location.", Toast.LENGTH_LONG).show();
        // Proceed with identification without location
        startIdentification(localImageUri, null, null, null, null, null);
        locationHelper.stopLocationUpdates(); // Ensure updates are stopped
    }

    private void startIdentification(Uri imageUri, @Nullable Double latitude, @Nullable Double longitude, @Nullable String localityName, @Nullable String state, @Nullable String country) {
        String base64Image = encodeImage(imageUri);
        if (base64Image == null) {
            Toast.makeText(this, "Failed to process image.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        openAiApi.identifyBirdFromImage(base64Image, latitude, longitude, localityName, new OpenAiApi.OpenAiCallback() {
            @Override
            public void onSuccess(String response, boolean isVerified) {
                if (!isVerified) {
                    Toast.makeText(IdentifyingActivity.this, "Bird not recognized in Georgia regional data. Image not saved.", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }

                // 2. ONLY if verified, upload the image to Firebase Storage
                uploadVerifiedImage(response, latitude, longitude, localityName, state, country);
            }

            @Override
            public void onFailure(Exception e, String message) {
                Log.e(TAG, "Identification failed: " + message, e);
                Toast.makeText(IdentifyingActivity.this, "Identification failed. Check internet.", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private void uploadVerifiedImage(String identificationResult, @Nullable Double latitude, @Nullable Double longitude, @Nullable String localityName, @Nullable String state, @Nullable String country) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String userId = user.getUid();
        String fileName = "images/" + userId + "/" + UUID.randomUUID().toString() + ".jpg";
        StorageReference storageRef = FirebaseStorage.getInstance().getReference().child(fileName);

        storageRef.putFile(localImageUri)
                .addOnSuccessListener(taskSnapshot -> {
                    storageRef.getDownloadUrl().addOnSuccessListener(downloadUri -> {
                        // Parse identification result here to get bird details
                        String[] lines = identificationResult.split("\\r?\\n");
                        String birdId = "Unknown";
                        String commonName = "Unknown";
                        String scientificName = "Unknown";
                        String species = "Unknown";
                        String family = "Unknown";

                        for (String line : lines) {
                            String trimmedLine = line.trim();
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

                        // All data is now ready to be passed to BirdInfoActivity
                        proceedToInfoActivity(birdId, commonName, scientificName, species, family, downloadUri.toString(), latitude, longitude, localityName, state, country);
                    });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Storage upload failed", e);
                    Toast.makeText(IdentifyingActivity.this, "Image upload failed.", Toast.LENGTH_SHORT).show();
                    finish();
                });
    }

    private void proceedToInfoActivity(String birdId, String commonName, String scientificName, String species, String family, String downloadUrl, @Nullable Double latitude, @Nullable Double longitude, @Nullable String localityName, @Nullable String state, @Nullable String country) {
        Intent intent = new Intent(IdentifyingActivity.this, BirdInfoActivity.class);
        intent.putExtra("imageUri", localImageUri.toString());
        intent.putExtra("birdId", birdId);
        intent.putExtra("commonName", commonName);
        intent.putExtra("scientificName", scientificName);
        intent.putExtra("species", species);
        intent.putExtra("family", family);
        intent.putExtra("imageUrl", downloadUrl);
        // Pass location data to the next activity
        if (latitude != null && longitude != null) {
            intent.putExtra("latitude", latitude);
            intent.putExtra("longitude", longitude);
            intent.putExtra("localityName", localityName);
            intent.putExtra("state", state);
            intent.putExtra("country", country);
        }
        startActivity(intent);
        finish();
    }

    private String encodeImage(Uri imageUri) {
        try {
            Bitmap bitmap;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(this.getContentResolver(), imageUri));
            } else {
                bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
            }
            int maxWidth = 512;
            int maxHeight = 512;
            float ratio = Math.min((float) maxWidth / bitmap.getWidth(), (float) maxHeight / bitmap.getHeight());
            if (ratio < 1.0f) {
                bitmap = Bitmap.createScaledBitmap(bitmap, Math.round(ratio * bitmap.getWidth()), Math.round(ratio * bitmap.getHeight()), true);
            }
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, byteArrayOutputStream);
            return Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP);
        } catch (IOException e) {
            Log.e(TAG, "Error encoding image", e);
            return null;
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
    }
}