package com.birddex.app;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.functions.FirebaseFunctions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAiApi is a helper class for Cloud Function calls related to BirdDex identification.
 */
public class OpenAiApi {

    private static final String TAG = "OpenAiApi";

    public static class BirdChoice {
        @Nullable public String birdId;
        @Nullable public String commonName;
        @Nullable public String scientificName;
        @Nullable public String species;
        @Nullable public String family;
        @Nullable public String source;
    }

    public static class IdentifyBirdResult {
        @Nullable public String resultText;
        public boolean isVerified;
        public boolean isGore;
        public boolean isInDatabase = true;
        @Nullable public String reasonCode;
        @Nullable public String identificationLogId;
        @Nullable public String identificationId;
        @Nullable public BirdChoice primaryBird;
        public ArrayList<BirdChoice> modelAlternatives = new ArrayList<>();
    }

    public interface IdentifyBirdCallback {
        void onSuccess(IdentifyBirdResult result);
        void onFailure(Exception e, String message);
    }

    public interface BirdChoicesCallback {
        void onSuccess(ArrayList<BirdChoice> candidates, @Nullable String userMessage, boolean isGore);
        void onFailure(Exception e, String message);
    }

    public void identifyBirdFromImage(String base64Image,
                                      String imageUrl,
                                      @Nullable Double latitude,
                                      @Nullable Double longitude,
                                      @Nullable String localityName,
                                      String requestId,
                                      IdentifyBirdCallback callback) {
        Map<String, Object> data = new HashMap<>();
        data.put("image", base64Image);
        data.put("imageUrl", imageUrl);
        data.put("requestId", requestId);
        if (latitude != null) data.put("latitude", latitude);
        if (longitude != null) data.put("longitude", longitude);
        if (localityName != null) data.put("localityName", localityName);

        FirebaseFunctions.getInstance()
                .getHttpsCallable("identifyBird")
                .call(data)
                .addOnSuccessListener(result -> {
                    try {
                        Map<String, Object> resMap = castMap(result.getData());
                        IdentifyBirdResult parsed = new IdentifyBirdResult();
                        parsed.resultText = getString(resMap, "result");
                        parsed.isVerified = Boolean.TRUE.equals(resMap.get("isVerified"));
                        parsed.isGore = Boolean.TRUE.equals(resMap.get("isGore"));
                        parsed.isInDatabase = !resMap.containsKey("isInDatabase") || Boolean.TRUE.equals(resMap.get("isInDatabase"));
                        parsed.reasonCode = getString(resMap, "reasonCode");
                        parsed.identificationLogId = getString(resMap, "identificationLogId");
                        parsed.identificationId = getString(resMap, "identificationId");
                        parsed.primaryBird = parseBirdChoice(resMap.get("primaryBird"));
                        parsed.modelAlternatives = parseBirdChoices(resMap.get("modelAlternatives"));
                        callback.onSuccess(parsed);
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing identifyBird response", e);
                        callback.onFailure(e, "Failed to parse server response.");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "identifyBird Cloud Function call failed", e);
                    callback.onFailure(new Exception(e), "API Error. Check Logcat for details.");
                });
    }

    public void requestOpenAiReviewCandidates(String base64Image,
                                              String imageUrl,
                                              String identificationLogId,
                                              String requestId,
                                              BirdChoicesCallback callback) {
        Map<String, Object> data = new HashMap<>();
        data.put("image", base64Image);
        data.put("imageUrl", imageUrl);
        data.put("identificationLogId", identificationLogId);
        data.put("requestId", requestId);

        FirebaseFunctions.getInstance()
                .getHttpsCallable("reviewBirdAlternatives")
                .call(data)
                .addOnSuccessListener(result -> {
                    try {
                        Map<String, Object> resMap = castMap(result.getData());
                        boolean isGore = Boolean.TRUE.equals(resMap.get("isGore"));
                        String userMessage = getString(resMap, "userMessage");
                        ArrayList<BirdChoice> candidates = parseBirdChoices(resMap.get("openAiAlternatives"));
                        callback.onSuccess(candidates, userMessage, isGore);
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing reviewBirdAlternatives response", e);
                        callback.onFailure(e, "Failed to parse AI review response.");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "reviewBirdAlternatives failed", e);
                    callback.onFailure(new Exception(e), "AI review failed.");
                });
    }

    public void syncIdentificationFeedback(@Nullable String identificationLogId,
                                           @Nullable String identificationId,
                                           @NonNull String action,
                                           @Nullable String selectedBirdId,
                                           @Nullable String selectionSource,
                                           @Nullable String note) {
        if (identificationLogId == null || identificationLogId.trim().isEmpty()) {
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("identificationLogId", identificationLogId);
        if (identificationId != null) data.put("identificationId", identificationId);
        data.put("action", action);
        if (selectedBirdId != null) data.put("selectedBirdId", selectedBirdId);
        if (selectionSource != null) data.put("selectionSource", selectionSource);
        if (note != null) data.put("note", note);

        FirebaseFunctions.getInstance()
                .getHttpsCallable("syncIdentificationFeedback")
                .call(data)
                .addOnFailureListener(e -> Log.e(TAG, "syncIdentificationFeedback failed", e));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : new HashMap<>();
    }

    @Nullable
    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String ? (String) value : null;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private BirdChoice parseBirdChoice(Object raw) {
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        BirdChoice choice = new BirdChoice();
        choice.birdId = getString(map, "birdId");
        choice.commonName = getString(map, "commonName");
        choice.scientificName = getString(map, "scientificName");
        choice.species = getString(map, "species");
        choice.family = getString(map, "family");
        choice.source = getString(map, "source");
        return choice;
    }

    @SuppressWarnings("unchecked")
    private ArrayList<BirdChoice> parseBirdChoices(Object raw) {
        ArrayList<BirdChoice> results = new ArrayList<>();
        if (!(raw instanceof List)) {
            return results;
        }
        for (Object item : (List<Object>) raw) {
            BirdChoice choice = parseBirdChoice(item);
            if (choice != null && choice.birdId != null && !choice.birdId.trim().isEmpty()) {
                results.add(choice);
            }
        }
        return results;
    }
}
