package com.birddex.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.birddex.app.databinding.ActivityCreatePostBinding;
import com.bumptech.glide.Glide;
import com.canhub.cropper.CropImageContract;
import com.canhub.cropper.CropImageContractOptions;
import com.canhub.cropper.CropImageOptions;
import com.canhub.cropper.CropImageView;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.UUID;

public class CreatePostActivity extends AppCompatActivity {

    private static final String TAG = "CreatePostActivity";
    private static final int REQUEST_READ_EXTERNAL_STORAGE = 101;
    private static final int REQUEST_LOCATION_PERMISSION = 102;

    private ActivityCreatePostBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseStorage storage;
    private FirebaseManager firebaseManager;
    private Uri selectedImageUri;
    private String currentUsername;
    private String currentUserProfilePicUrl;

    private FusedLocationProviderClient fusedLocationClient;
    private Double currentLatitude = null;
    private Double currentLongitude = null;

    private ActivityResultLauncher<Intent> pickImageLauncher;
    private ActivityResultLauncher<CropImageContractOptions> cropImageLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCreatePostBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        storage = FirebaseStorage.getInstance();
        firebaseManager = new FirebaseManager(this);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        setupUI();
        loadUserInfo();
        setupImageLaunchers();
        
        // Pre-fetch location if switch is on or when activity starts
        if (hasLocationPermission()) {
            fetchLocation();
        }
    }

    private void setupUI() {
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        
        binding.btnSelectImage.setOnClickListener(v -> checkPermissionsAndPickImage());
        
        binding.fabRemoveImage.setOnClickListener(v -> {
            selectedImageUri = null;
            binding.ivBirdImagePreview.setVisibility(View.GONE);
            binding.fabRemoveImage.setVisibility(View.GONE);
            binding.tvImagePlaceholder.setVisibility(View.VISIBLE);
        });

        binding.swShowLocation.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !hasLocationPermission()) {
                requestLocationPermission();
            } else if (isChecked) {
                fetchLocation();
            }
        });

        binding.btnPost.setOnClickListener(v -> attemptPost());
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
    }

    private void fetchLocation() {
        try {
            fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
                if (location != null) {
                    currentLatitude = location.getLatitude();
                    currentLongitude = location.getLongitude();
                    Log.d(TAG, "Location fetched: " + currentLatitude + ", " + currentLongitude);
                }
            });
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission not granted", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fetchLocation();
            } else {
                binding.swShowLocation.setChecked(false);
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void loadUserInfo() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) {
            firebaseManager.getUserProfile(user.getUid(), task -> {
                if (isFinishing() || isDestroyed()) return;
                if (task.isSuccessful() && task.getResult() != null) {
                    currentUsername = task.getResult().getString("username");
                    currentUserProfilePicUrl = task.getResult().getString("profilePictureUrl");
                    
                    binding.tvCreatorUsername.setText(currentUsername);
                    Glide.with(this)
                            .load(currentUserProfilePicUrl)
                            .placeholder(R.drawable.ic_profile)
                            .into(binding.ivCreatorProfilePicture);
                }
            });
        }
    }

    private void setupImageLaunchers() {
        pickImageLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) startImageCropper(uri);
                    }
                });

        cropImageLauncher = registerForActivityResult(new CropImageContract(), result -> {
            if (isFinishing() || isDestroyed()) return;
            if (result.isSuccessful()) {
                selectedImageUri = result.getUriContent();
                if (selectedImageUri != null) {
                    binding.ivBirdImagePreview.setVisibility(View.VISIBLE);
                    binding.fabRemoveImage.setVisibility(View.VISIBLE);
                    binding.tvImagePlaceholder.setVisibility(View.GONE);
                    Glide.with(this).load(selectedImageUri).into(binding.ivBirdImagePreview);
                }
            }
        });
    }

    private void checkPermissionsAndPickImage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQUEST_READ_EXTERNAL_STORAGE);
            } else {
                pickImage();
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_READ_EXTERNAL_STORAGE);
            } else {
                pickImage();
            }
        }
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        pickImageLauncher.launch(intent);
    }

    private void startImageCropper(Uri uri) {
        CropImageOptions options = new CropImageOptions();
        options.cropShape = CropImageView.CropShape.RECTANGLE;
        options.fixAspectRatio = true;
        options.aspectRatioX = 4;
        options.aspectRatioY = 3;
        cropImageLauncher.launch(new CropImageContractOptions(uri, options));
    }

    private void attemptPost() {
        String message = binding.etPostMessage.getText().toString().trim();
        if (message.isEmpty() && selectedImageUri == null) {
            Toast.makeText(this, "Please add a message or an image", Toast.LENGTH_SHORT).show();
            return;
        }

        if (ContentFilter.containsInappropriateContent(message)) {
            Toast.makeText(this, "Your message contains inappropriate language and cannot be posted.", Toast.LENGTH_LONG).show();
            return;
        }

        binding.btnPost.setEnabled(false);
        if (selectedImageUri != null) {
            uploadImageAndPost(message);
        } else {
            createFirestorePost(message, null);
        }
    }

    private void uploadImageAndPost(String message) {
        String imageId = UUID.randomUUID().toString();
        StorageReference ref = storage.getReference().child("forum_post_images/" + imageId + ".jpg");

        ref.putFile(selectedImageUri)
                .addOnSuccessListener(taskSnapshot -> ref.getDownloadUrl().addOnSuccessListener(uri -> {
                    if (isFinishing() || isDestroyed()) return;
                    createFirestorePost(message, uri.toString());
                }))
                .addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;
                    Log.e(TAG, "Image upload failed: " + e.getMessage());
                    binding.btnPost.setEnabled(true);
                    Toast.makeText(this, "Image upload failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void createFirestorePost(String message, String imageUrl) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        DocumentReference newPostRef = db.collection("forumThreads").document();
        ForumPost post = new ForumPost(user.getUid(), currentUsername, currentUserProfilePicUrl, message, imageUrl);
        post.setId(newPostRef.getId());
        
        // Add location and status data
        post.setSpotted(binding.cbSpotted.isChecked());
        post.setHunted(binding.cbHunted.isChecked());
        post.setShowLocation(binding.swShowLocation.isChecked());
        
        if (post.isShowLocation()) {
            post.setLatitude(currentLatitude);
            post.setLongitude(currentLongitude);
        }

        firebaseManager.addForumPost(post, task -> {
            if (isFinishing() || isDestroyed()) return;
            if (task.isSuccessful()) {
                Toast.makeText(this, "Post shared!", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                binding.btnPost.setEnabled(true);
                Toast.makeText(this, "Failed to share post", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
