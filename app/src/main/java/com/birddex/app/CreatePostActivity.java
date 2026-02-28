package com.birddex.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.birddex.app.databinding.ActivityCreatePostBinding;
import com.bumptech.glide.Glide;
import com.canhub.cropper.CropImageContract;
import com.canhub.cropper.CropImageContractOptions;
import com.canhub.cropper.CropImageOptions;
import com.canhub.cropper.CropImageView;
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

    private ActivityCreatePostBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseStorage storage;
    private FirebaseManager firebaseManager;
    private Uri selectedImageUri;
    private String currentUsername;
    private String currentUserProfilePicUrl;

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

        setupUI();
        loadUserInfo();
        setupImageLaunchers();
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

        binding.btnPost.setOnClickListener(v -> attemptPost());
    }

    private void loadUserInfo() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) {
            firebaseManager.getUserProfile(user.getUid(), task -> {
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
                    createFirestorePost(message, uri.toString());
                }))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Image upload failed: " + e.getMessage());
                    binding.btnPost.setEnabled(true);
                    Toast.makeText(this, "Image upload failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void createFirestorePost(String message, String imageUrl) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            Log.e(TAG, "createFirestorePost: User is null");
            return;
        }

        Log.d(TAG, "Attempting to create Firestore post. User: " + user.getUid());

        // Generate a new ID for the post
        DocumentReference newPostRef = db.collection("forumThreads").document();
        ForumPost post = new ForumPost(user.getUid(), currentUsername, currentUserProfilePicUrl, message, imageUrl);
        post.setId(newPostRef.getId());

        firebaseManager.addForumPost(post, task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "Post saved successfully to Firestore: " + post.getId());
                Toast.makeText(this, "Post shared!", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                Exception e = task.getException();
                Log.e(TAG, "Failed to share post to Firestore: " + (e != null ? e.getMessage() : "Unknown error"), e);
                binding.btnPost.setEnabled(true);
                Toast.makeText(this, "Failed to share post: " + (e != null ? e.getMessage() : "Unknown error"), Toast.LENGTH_SHORT).show();
            }
        });
    }
}
