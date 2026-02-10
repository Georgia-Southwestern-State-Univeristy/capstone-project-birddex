package com.example.birddex;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * OpenAiApi is a helper class for interacting with OpenAI's Chat Completions API.
 * It sends a Base64 encoded image to the GPT-4o model to identify the bird species.
 */
public class OpenAiApi {

    private static final String TAG = "OpenAiApi";
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";

    private final OkHttpClient client;

    /**
     * Callback interface for handling results from the OpenAI API call.
     */
    public interface OpenAiCallback {
        void onSuccess(String response);
        void onFailure(Exception e, String message);
    }

    public OpenAiApi(OkHttpClient client) {
        this.client = client;
    }

    /**
     * Sends a Base64 encoded image to the OpenAI GPT-4o model for bird identification.
     * @param base64Image The image data encoded as a Base64 string.
     * @param apiKey Your OpenAI API key.
     * @param callback The callback to handle the response.
     */
    public void identifyBirdFromImage(String base64Image, String apiKey, OpenAiCallback callback) {
        try {
            // Construct the JSON request body for the OpenAI API.
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", "gpt-4o");
            requestBody.put("max_tokens", 100);

            JSONArray messages = new JSONArray();
            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");

            JSONArray content = new JSONArray();
            
            // Set the prompt for bird identification.
            JSONObject textPart = new JSONObject();
            textPart.put("type", "text");
            textPart.put("text", "Identify the bird in this image. Respond with only: Common Name: [name], Scientific Name: [name], Family: [name]");

            // Include the image data in the request.
            JSONObject imageUrlObject = new JSONObject();
            imageUrlObject.put("url", "data:image/jpeg;base64," + base64Image);
            JSONObject imagePart = new JSONObject();
            imagePart.put("type", "image_url");
            imagePart.put("image_url", imageUrlObject);

            content.put(textPart);
            content.put(imagePart);
            userMessage.put("content", content);
            messages.put(userMessage);
            requestBody.put("messages", messages);

            // Create the network request using OkHttp.
            RequestBody body = RequestBody.create(requestBody.toString(), MediaType.get("application/json"));
            Request request = new Request.Builder()
                    .url(OPENAI_API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .post(body)
                    .build();

            // Execute the asynchronous call.
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "OkHttp call failed", e);
                    callback.onFailure(e, "Network Error. Check Logcat.");
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    final String responseBody = response.body().string();
                    if (!response.isSuccessful()) {
                        final int statusCode = response.code();
                        String errorMessage = "--- OPENAI API ERROR ---\nStatus: " + statusCode + "\nBody: " + responseBody;
                        Log.e(TAG, errorMessage);
                        callback.onFailure(new IOException(errorMessage), "API Error: " + statusCode + ". Check Logcat for details.");
                        return;
                    }

                    try {
                        // Parse the JSON response to extract the model's textual output.
                        JSONObject jsonObject = new JSONObject(responseBody);
                        String responseContent = jsonObject.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                        Log.i(TAG, "[SUCCESS] Parsed content: " + responseContent);
                        callback.onSuccess(responseContent);

                    } catch (JSONException e) {
                        Log.e(TAG, "[FAIL] JSON parsing error.", e);
                        callback.onFailure(e, "Failed to parse server response.");
                    }
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build JSON request programmatically", e);
            callback.onFailure(e, "A fatal error occurred while building the request.");
        }
    }
}