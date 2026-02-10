package com.example.birddex;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.example.birddex.BuildConfig;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import okhttp3.OkHttpClient;

/**
 * ImageResultActivity handles the identification of a bird from a locally selected image.
 * It fetches a regional bird list, calls OpenAI for identification, and verifies the result.
 */
public class ImageResultActivity extends AppCompatActivity {

    private static final String TAG = "ImageResultActivity";

    private ImageView birdImageView;
    private Button identifyButton;
    private TextView commonNameTextView;
    private TextView scientificNameTextView;
    private TextView familyTextView;
    private Uri imageUri;
    private OkHttpClient client = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_result);

        // Initialize UI components
        birdImageView = findViewById(R.id.birdImageView);
        identifyButton = findViewById(R.id.identifyButton);
        commonNameTextView = findViewById(R.id.commonNameTextView);
        scientificNameTextView = findViewById(R.id.scientificNameTextView);
        familyTextView = findViewById(R.id.familyTextView);

        // Retrieve the image URI passed via intent
        String imageUriString = getIntent().getStringExtra("imageUri");
        if (imageUriString != null) {
            imageUri = Uri.parse(imageUriString);
            // Load the image into the preview ImageView using Glide
            Glide.with(this).load(imageUri).into(birdImageView);
        } else {
            Toast.makeText(this, "Image URI not found.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Set up the identification trigger
        identifyButton.setOnClickListener(v -> {
            identifyButton.setEnabled(false);
            identifyButton.setText("Identifying...");
            identifyBird();
        });
    }

    /**
     * Orchestrates the bird identification process:
     * 1. Converts the image to Base64.
     * 2. Fetches the regional (Georgia) bird list from eBird.
     * 3. Sends the image to OpenAI for identification.
     * 4. Verifies if the identified bird is in the regional list.
     */
    private void identifyBird() {
        try {
            // Convert the image URI to a Bitmap and then to a Base64 string
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
            byte[] imageBytes = outputStream.toByteArray();
            String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

            // Fetch the regional bird list first to ensure verification data is ready
            EbirdApi ebirdApi = new EbirdApi(client);
            ebirdApi.fetchGeorgiaBirdList(BuildConfig.EBIRD_API_KEY, new EbirdApi.EbirdCallback() {
                @Override
                public void onSuccess(List<JSONObject> birds) {
                    // Once the list is fetched, call OpenAI for identification
                    OpenAiApi openAiApi = new OpenAiApi(client);
                    openAiApi.identifyBirdFromImage(base64Image, BuildConfig.OPENAI_API_KEY, new OpenAiApi.OpenAiCallback() {
                        @Override
                        public void onSuccess(String response) {
                            // Extract values from OpenAI's structured text response
                            String commonName = extractValue(response, "Common Name:");
                            String scientificName = extractValue(response, "Scientific Name:");
                            String family = extractValue(response, "Family:");

                            // Verify the AI result against the eBird regional data
                            BirdVerifier birdVerifier = new BirdVerifier();
                            birdVerifier.verifyBirdWithEbird(commonName, scientificName, birds, (isVerified, verifiedCommonName, verifiedScientificName) -> {
                                if (isVerified) {
                                    // Update the UI on the main thread if verified
                                    runOnUiThread(() -> {
                                        commonNameTextView.setText("Common Name: " + verifiedCommonName);
                                        scientificNameTextView.setText("Scientific Name: " + verifiedScientificName);
                                        familyTextView.setText("Family: " + family);
                                        identifyButton.setVisibility(View.GONE);
                                    });
                                } else {
                                    // Handle cases where the bird isn't in the expected region
                                    runOnUiThread(() -> {
                                        Toast.makeText(ImageResultActivity.this, "Bird not found in Georgia bird list.", Toast.LENGTH_LONG).show();
                                        identifyButton.setEnabled(true);
                                        identifyButton.setText("Identify");
                                    });
                                }
                            });
                        }

                        @Override
                        public void onFailure(Exception e, String message) {
                            runOnUiThread(() -> {
                                Toast.makeText(ImageResultActivity.this, message, Toast.LENGTH_LONG).show();
                                identifyButton.setEnabled(true);
                                identifyButton.setText("Identify");
                            });
                        }
                    });
                }

                @Override
                public void onFailure(Exception e) {
                    runOnUiThread(() -> {
                        Toast.makeText(ImageResultActivity.this, "Failed to fetch Georgia bird list.", Toast.LENGTH_LONG).show();
                        identifyButton.setEnabled(true);
                        identifyButton.setText("Identify");
                    });
                }
            });
        } catch (IOException e) {
            Log.e(TAG, "Error converting image to base64", e);
            Toast.makeText(this, "Error converting image to base64.", Toast.LENGTH_SHORT).show();
            identifyButton.setEnabled(true);
            identifyButton.setText("Identify");
        }
    }

    /**
     * Utility method to extract specific info from OpenAI's formatted string response.
     */
    private String extractValue(String text, String key) {
        int start = text.indexOf(key);
        if (start == -1) {
            return "";
        }
        int end = text.indexOf("\n", start);
        if (end == -1) {
            end = text.length();
        }
        return text.substring(start + key.length(), end).trim();
    }
}
