package com.birddex.app;

import android.content.Context;
import android.util.Log;

import com.google.firebase.functions.FirebaseFunctions;

import org.json.JSONObject;

import java.util.ArrayList;
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

    private final BirdCacheManager cacheManager;

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
        this.cacheManager = null;
    }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     * This method also reads or writes local device preferences so some state survives app
     * restarts.
     */
    public EbirdApi(Context context) {
        this.cacheManager = context != null ? new BirdCacheManager(context.getApplicationContext()) : null;
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     */
    public void fetchCoreGeorgiaBirdList(EbirdCoreBirdListCallback callback) {
        fetchCoreGeorgiaBirdList(false, callback);
    }

    /**
     * Fetches the core list of Georgia birds from the Cloud Function.
     * This returns basic bird information, not detailed facts.
     */
    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     */
    public void fetchCoreGeorgiaBirdList(boolean forceRefresh, EbirdCoreBirdListCallback callback) {
        if (!forceRefresh && cacheManager != null && cacheManager.hasFreshCoreGeorgiaBirdList(BirdCacheManager.CORE_BIRD_LIST_CACHE_TTL_MS)) {
            List<JSONObject> cachedBirds = cacheManager.getCachedCoreGeorgiaBirds();
            if (!cachedBirds.isEmpty()) {
                Log.d(TAG, "Returning cached Georgia bird list (" + cachedBirds.size() + " birds).");
                callback.onSuccess(cachedBirds);
                return;
            }
        }

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

                        if (cacheManager != null && !birdObjects.isEmpty()) {
                            cacheManager.saveCoreGeorgiaBirds(birdObjects);
                        }
                        callback.onSuccess(birdObjects);
                    } catch (Exception e) {
                        callback.onFailure(e);
                    }
                })
                .addOnFailureListener(e -> {
                    if (cacheManager != null) {
                        List<JSONObject> cachedBirds = cacheManager.getCachedCoreGeorgiaBirds();
                        if (!cachedBirds.isEmpty()) {
                            Log.w(TAG, "getGeorgiaBirds failed. Falling back to cached list.", e);
                            callback.onSuccess(cachedBirds);
                            return;
                        }
                    }
                    callback.onFailure(e);
                });
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
        fetchBirdDetailsAndFacts(birdId, false, callback);
    }

    /**
     * Fetches detailed information and facts for a single bird from the Cloud Function.
     * @param birdId The ID of the bird to fetch details for.
     * @param forceRefresh True if the caller wants to bypass the app-side cache.
     * @param callback The callback to be notified upon success or failure.
     */
    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     */
    public void fetchBirdDetailsAndFacts(String birdId, boolean forceRefresh, BirdDetailsCallback callback) {
        if (birdId == null || birdId.trim().isEmpty()) {
            callback.onFailure(new IllegalArgumentException("birdId is required."));
            return;
        }

        if (!forceRefresh && cacheManager != null && cacheManager.hasFreshBirdDetails(birdId, BirdCacheManager.BIRD_DETAILS_CACHE_TTL_MS)) {
            JSONObject cached = cacheManager.getCachedBirdDetails(birdId);
            if (cached != null) {
                Log.d(TAG, "Returning cached bird details for " + birdId);
                callback.onSuccess(cached);
                return;
            }
        }

        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("birdId", birdId);

        FirebaseFunctions.getInstance()
                .getHttpsCallable("getBirdDetailsAndFacts")
                .call(data)
                .addOnSuccessListener(result -> {
                    try {
                        Map<String, Object> birdDetailsMap = (Map<String, Object>) result.getData();
                        if (birdDetailsMap != null) {
                            JSONObject details = new JSONObject(birdDetailsMap);
                            if (cacheManager != null) {
                                cacheManager.saveBirdDetails(birdId, details);
                            }
                            callback.onSuccess(details);
                        } else {
                            callback.onFailure(new Exception("No bird details returned."));
                        }
                    } catch (Exception e) {
                        callback.onFailure(e);
                    }
                })
                .addOnFailureListener(e -> {
                    if (cacheManager != null) {
                        JSONObject cached = cacheManager.getCachedBirdDetails(birdId);
                        if (cached != null) {
                            Log.w(TAG, "getBirdDetailsAndFacts failed. Falling back to cached details for " + birdId, e);
                            callback.onSuccess(cached);
                            return;
                        }
                    }
                    callback.onFailure(e);
                });
    }
}
