package com.example.birddex;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * IdentifyingActivity orchestrates the bird identification process.
 * It uploads the user's cropped image to Firebase Storage and then sends it to OpenAI's GPT-4o model
 * for species identification. The results are then displayed to the user.
 */
public class IdentifyingActivity extends AppCompatActivity {

    private static final String TAG = "IdentifyingActivity";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_identifying);

        // Display the image being identified.
        ImageView identifyingImageView = findViewById(R.id.identifyingImageView);
        String uriStr = getIntent().getStringExtra("imageUri");
        if (uriStr == null) {
            finish();
            return;
        }

        Uri imageUri = Uri.parse(uriStr);
        identifyingImageView.setImageURI(imageUri);

        // Initiate the identification workflow: Upload to Storage -> Call OpenAI API.
        uploadImageAndIdentify(imageUri);
    }

    /**
     * Uploads the image to Firebase Storage under the user's unique path.
     * Once the upload is successful, it retrieves the download URL and proceeds to call the identification API.
     */
    private void uploadImageAndIdentify(Uri imageUri) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.e(TAG, "User not logged in, skipping upload");
            callOpenAI(imageUri, null); 
            return;
        }

        String userId = user.getUid();
        String fileName = "images/" + userId + "/" + UUID.randomUUID().toString() + ".jpg";
        StorageReference storageRef = FirebaseStorage.getInstance().getReference().child(fileName);

        storageRef.putFile(imageUri)
                .addOnSuccessListener(taskSnapshot -> {
                    Log.d(TAG, "Image uploaded successfully: " + fileName);
                    storageRef.getDownloadUrl().addOnSuccessListener(uri -> {
                        callOpenAI(imageUri, uri.toString());
                    });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Image upload failed", e);
                    callOpenAI(imageUri, null);
                });
    }

    /**
     * Encodes the image to Base64 and sends it to the OpenAI API (GPT-4o) for identification.
     * Parses the structured text response from the model.
     */
    private void callOpenAI(Uri imageUri, String downloadUrl) {
        new Thread(() -> {
            String base64Image = encodeImage(imageUri);
            if (base64Image == null) {
                Log.e(TAG, "Failed to encode image");
                return;
            }

            try {
                JSONObject jsonBody = new JSONObject();
                jsonBody.put("model", "gpt-4o");

                JSONArray messages = new JSONArray();
                JSONObject message = new JSONObject();
                message.put("role", "user");

                JSONArray content = new JSONArray();

                JSONObject textPart = new JSONObject();
                textPart.put("type", "text");
                textPart.put("text", "Identify the bird in this image. Provide the response in this exact format:\nCommon Name: [name]\nScientific Name: [name]\nSpecies: [name]\nFamily: [name]");
                content.put(textPart);

                JSONObject imagePart = new JSONObject();
                imagePart.put("type", "image_url");
                JSONObject imageUrl = new JSONObject();
                imageUrl.put("url", "data:image/jpeg;base64," + base64Image);
                imagePart.put("image_url", imageUrl);
                content.put(imagePart);

                message.put("content", content);
                messages.put(message);
                jsonBody.put("messages", messages);
                jsonBody.put("max_tokens", 300);

                OkHttpClient client = new OkHttpClient();
                RequestBody body = RequestBody.create(jsonBody.toString(), MediaType.get("application/json; charset=utf-8"));
                Request request = new Request.Builder()
                        .url("https://api.openai.com/v1/chat/completions")
                        .addHeader("Authorization", "Bearer " + BuildConfig.OPENAI_API_KEY)
                        .post(body)
                        .build();

                client.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e(TAG, "OpenAI API call failed", e);
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        String responseBody = response.body() != null ? response.body().string() : "";
                        if (!response.isSuccessful()) {
                            Log.e(TAG, "OpenAI API call failed with code: " + response.code());
                            return;
                        }

                        try {
                            JSONObject jsonObject = new JSONObject(responseBody);
                            String contentStr = jsonObject.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

                            String[] lines = contentStr.split("\n");
                            String commonName = "Unknown";
                            String scientificName = "Unknown";
                            String species = "Unknown";
                            String family = "Unknown";

                            for (String line : lines) {
                                if (line.startsWith("Common Name: ")) commonName = line.replace("Common Name: ", "").trim();
                                else if (line.startsWith("Scientific Name: ")) scientificName = line.replace("Scientific Name: ", "").trim();
                                else if (line.startsWith("Species: ")) species = line.replace("Species: ", "").trim();
                                else if (line.startsWith("Family: ")) family = line.replace("Family: ", "").trim();
                            }

                            // Pass everything to BirdInfoActivity. 
                            // Identification is NOT saved to Firestore yet; 
                            // the user must click "Store in collection" in BirdInfoActivity.
                            Intent intent = new Intent(IdentifyingActivity.this, BirdInfoActivity.class);
                            intent.putExtra("imageUri", imageUri.toString());
                            intent.putExtra("commonName", commonName);
                            intent.putExtra("scientificName", scientificName);
                            intent.putExtra("species", species);
                            intent.putExtra("family", family);
                            intent.putExtra("imageUrl", downloadUrl);
                            startActivity(intent);
                            finish();
                        } catch (JSONException e) {
                            Log.e(TAG, "Failed to parse OpenAI response", e);
                        }
                    }
                });
            } catch (JSONException e) {
                Log.e(TAG, "Failed to build JSON request", e);
            }
        }).start();
    }

    private String encodeImage(Uri imageUri) {
        try {
            Bitmap bitmap;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(this.getContentResolver(), imageUri));
            } else {
                bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
            }
            int maxWidth = 1024;
            int maxHeight = 1024;
            float ratio = Math.min((float) maxWidth / bitmap.getWidth(), (float) maxHeight / bitmap.getHeight());
            if (ratio < 1.0f) {
                int width = Math.round(ratio * bitmap.getWidth());
                int height = Math.round(ratio * bitmap.getHeight());
                bitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
            }
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream);
            byte[] byteArray = byteArrayOutputStream.toByteArray();
            return Base64.encodeToString(byteArray, Base64.NO_WRAP);
        } catch (IOException e) {
            Log.e(TAG, "Error encoding image", e);
            return null;
        }
    }
}
