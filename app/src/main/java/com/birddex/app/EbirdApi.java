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

    public EbirdApi() {
        // Constructor
    }

    /**
     * Fetches the core list of Georgia birds from the Cloud Function.
     * This returns basic bird information, not detailed facts.
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
