package com.birddex.app;

import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.HttpsCallableResult;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EbirdApi helper that fetches processed regional bird data and individual bird details from Cloud Functions.
 */
/**
 * EbirdApi: Interface/model contract used to keep different parts of the app communicating with a shared shape.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class EbirdApi {
    private static final String TAG = "EbirdApi";

    public interface EbirdCoreBirdListCallback {
        void onSuccess(List<JSONObject> birds);
        void onFailure(Exception e);
    }

    public interface BirdDetailsCallback {
        void onSuccess(JSONObject birdDetails);
        void onFailure(Exception e);
    }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public EbirdApi() {
        // Constructor
    }

    /**
     * Fetches the core list of Georgia birds from the Cloud Function.
     * This returns basic bird information, not detailed facts.
     */
    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     */
    public void fetchCoreGeorgiaBirdList(EbirdCoreBirdListCallback callback) {
        FirebaseFunctions.getInstance()
                .getHttpsCallable("getGeorgiaBirds")
                .call()
                .addOnSuccessListener(result -> {
                    try {
                        Map<String, Object> resMap = (Map<String, Object>) result.getData();
                        List<Map<String, Object>> birdsData = (List<Map<String, Object>>) resMap.get("birds");
                        
                        List<JSONObject> birdObjects = new ArrayList<>();
                        if (birdsData != null) {
                            for (Map<String, Object> map : birdsData) {
                                birdObjects.add(new JSONObject(map));
                            }
                        }
                        callback.onSuccess(birdObjects);
                    } catch (Exception e) {
                        callback.onFailure(e);
                    }
                })
                .addOnFailureListener(callback::onFailure);
    }

    /**
     * Fetches detailed information and facts for a single bird from the Cloud Function.
     * @param birdId The ID of the bird to fetch details for.
     * @param callback The callback to be notified upon success or failure.
     */
    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     */
    public void fetchBirdDetailsAndFacts(String birdId, BirdDetailsCallback callback) {
        Map<String, Object> data = new HashMap<>();
        data.put("birdId", birdId);

        FirebaseFunctions.getInstance()
                .getHttpsCallable("getBirdDetailsAndFacts")
                .call(data)
                .addOnSuccessListener(result -> {
                    try {
                        Map<String, Object> birdDetailsMap = (Map<String, Object>) result.getData();
                        if (birdDetailsMap != null) {
                            callback.onSuccess(new JSONObject(birdDetailsMap));
                        } else {
                            callback.onFailure(new Exception("No bird details returned."));
                        }
                    } catch (Exception e) {
                        callback.onFailure(e);
                    }
                })
                .addOnFailureListener(callback::onFailure);
    }
}
