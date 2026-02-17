package com.example.birddex;

import android.util.Log;

import com.google.firebase.functions.FirebaseFunctions;

import java.util.HashMap;
import java.util.Map;

/**
 * OpenAiApi is a helper class for interacting with OpenAI's Chat Completions API via Cloud Functions.
 * It sends a Base64 encoded image to a secure server-side function to identify bird species.
 */
public class OpenAiApi {

    private static final String TAG = "OpenAiApi";

    /**
     * Callback interface for handling results from the OpenAI API call.
     */
    public interface OpenAiCallback {
        void onSuccess(String response, boolean isVerified);
        void onFailure(Exception e, String message);
    }

    public OpenAiApi() {
        // No longer needs OkHttpClient as it uses FirebaseFunctions
    }

    /**
     * Sends a Base64 encoded image to the OpenAI GPT-4o model via Cloud Functions for bird identification.
     * @param base64Image The image data encoded as a Base64 string.
     * @param callback The callback to handle the response.
     */
    public void identifyBirdFromImage(String base64Image, OpenAiCallback callback) {
        Map<String, Object> data = new HashMap<>();
        data.put("image", base64Image);

        FirebaseFunctions.getInstance()
                .getHttpsCallable("identifyBird")
                .call(data)
                .addOnSuccessListener(result -> {
                    try {
                        Map<String, Object> resMap = (Map<String, Object>) result.getData();
                        String content = (String) resMap.get("result");
                        boolean isVerified = (boolean) resMap.get("isVerified");
                        if (content != null) {
                            Log.i(TAG, "[SUCCESS] Received content from cloud function");
                            callback.onSuccess(content, isVerified);
                        } else {
                            callback.onFailure(new Exception("Null response"), "Cloud function returned empty result.");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing Cloud Function response", e);
                        callback.onFailure(e, "Failed to parse server response.");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Cloud Function call failed", e);
                    callback.onFailure(new Exception(e), "API Error. Check Logcat for details.");
                });
    }
}
