package com.example.birddex;

import android.util.Log;

import com.google.firebase.functions.FirebaseFunctions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * NuthatchApi is a helper class for interacting with the Nuthatch API via Firebase Cloud Functions.
 * It searches for bird images by name using a secure server-side function.
 */
public class NuthatchApi {

    private static final String TAG = "NuthatchApi";

    /**
     * Callback interface for handling the result of a Nuthatch API search.
     */
    public interface SearchResultHandler {
        void onImageFound(String imageUrl);
        void onImageNotFound();
    }

    public NuthatchApi() {
        // No longer needs Volley RequestQueue
    }

    /**
     * Searches for a bird by its name using the 'searchBirdImage' Cloud Function.
     * @param searchTerm The name of the bird to search for.
     * @param handler The callback to handle the result.
     */
    public void searchNuthatchByName(String searchTerm, SearchResultHandler handler) {
        Map<String, Object> data = new HashMap<>();
        data.put("searchTerm", searchTerm);

        FirebaseFunctions.getInstance()
                .getHttpsCallable("searchBirdImage")
                .call(data)
                .addOnSuccessListener(result -> {
                    try {
                        // The Cloud Function returns the raw data from Nuthatch API
                        Map<String, Object> resMap = (Map<String, Object>) result.getData();
                        Object dataObj = resMap.get("data");
                        
                        if (dataObj == null) {
                            handler.onImageNotFound();
                            return;
                        }

                        // Convert the result to JSONObject for easier parsing
                        JSONObject jsonObject = new JSONObject(dataObj.toString());
                        if (jsonObject.has("entities")) {
                            JSONArray entities = jsonObject.getJSONArray("entities");
                            if (entities.length() > 0) {
                                JSONObject entity = entities.getJSONObject(0);
                                if (entity.has("images")) {
                                    JSONArray images = entity.getJSONArray("images");
                                    if (images.length() > 0) {
                                        handler.onImageFound(images.getString(0));
                                        return;
                                    }
                                }
                            }
                        }
                        handler.onImageNotFound();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse Nuthatch Cloud Function response", e);
                        handler.onImageNotFound();
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Nuthatch Cloud Function call failed", e);
                    handler.onImageNotFound();
                });
    }
}
