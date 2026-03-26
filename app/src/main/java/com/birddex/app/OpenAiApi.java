package com.birddex.app;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.firebase.functions.FirebaseFunctions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAiApi is a helper class for BirdDex identification cloud-function calls.
 */
public class OpenAiApi {

    private static final String TAG = "OpenAiApi";

    public static final class BirdChoice {
        public final String birdId;
        public final String commonName;
        public final String scientificName;
        public final String species;
        public final String family;
        public final String source;

        public BirdChoice(@Nullable String birdId,
                          @Nullable String commonName,
                          @Nullable String scientificName,
                          @Nullable String species,
                          @Nullable String family,
                          @Nullable String source) {
            this.birdId = birdId;
            this.commonName = commonName;
            this.scientificName = scientificName;
            this.species = species;
            this.family = family;
            this.source = source;
        }
    }

    public static final class IdentificationResult {
        public final boolean isVerified;
        public final boolean isGore;
        public final boolean isInDatabase;
        public final String userMessage;
        public final String rawResultText;
        public final String imageUrl;
        public final String identificationLogId;
        public final String identificationId;
        public final BirdChoice primaryBird;
        public final ArrayList<BirdChoice> modelAlternatives;
        public final ArrayList<BirdChoice> openAiAlternatives;

        public IdentificationResult(boolean isVerified,
                                    boolean isGore,
                                    boolean isInDatabase,
                                    @Nullable String userMessage,
                                    @Nullable String rawResultText,
                                    @Nullable String imageUrl,
                                    @Nullable String identificationLogId,
                                    @Nullable String identificationId,
                                    @Nullable BirdChoice primaryBird,
                                    ArrayList<BirdChoice> modelAlternatives,
                                    ArrayList<BirdChoice> openAiAlternatives) {
            this.isVerified = isVerified;
            this.isGore = isGore;
            this.isInDatabase = isInDatabase;
            this.userMessage = userMessage;
            this.rawResultText = rawResultText;
            this.imageUrl = imageUrl;
            this.identificationLogId = identificationLogId;
            this.identificationId = identificationId;
            this.primaryBird = primaryBird;
            this.modelAlternatives = modelAlternatives != null ? modelAlternatives : new ArrayList<>();
            this.openAiAlternatives = openAiAlternatives != null ? openAiAlternatives : new ArrayList<>();
        }
    }

    public interface OpenAiCallback {
        void onSuccess(IdentificationResult result);
        void onFailure(Exception e, String message);
    }

    public interface BirdChoicesCallback {
        void onSuccess(ArrayList<BirdChoice> candidates, @Nullable String userMessage, boolean isGore);
        void onFailure(Exception e, String message);
    }

    public interface StatusCallback {
        void onSuccess();
        void onFailure(Exception e, String message);
    }

    public OpenAiApi() {
    }

    public void identifyBirdFromImage(String base64Image,
                                      String imageUrl,
                                      @Nullable Double latitude,
                                      @Nullable Double longitude,
                                      @Nullable String localityName,
                                      String requestId,
                                      OpenAiCallback callback) {
        Map<String, Object> data = new HashMap<>();
        data.put("image", base64Image);
        data.put("imageUrl", imageUrl);
        data.put("requestId", requestId);

        if (latitude != null) {
            data.put("latitude", latitude);
        }
        if (longitude != null) {
            data.put("longitude", longitude);
        }
        if (localityName != null) {
            data.put("localityName", localityName);
        }

        FirebaseFunctions.getInstance()
                .getHttpsCallable("identifyBird")
                .call(data)
                .addOnSuccessListener(result -> {
                    try {
                        IdentificationResult parsed = parseIdentificationResult(result.getData());
                        callback.onSuccess(parsed);
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing identifyBird response", e);
                        callback.onFailure(e, "Failed to parse server response.");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "identifyBird cloud function call failed", e);
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
                        Map<String, Object> resMap = asMap(result.getData());
                        boolean isGore = getBoolean(resMap, "isGore");
                        String userMessage = getString(resMap, "userMessage");
                        ArrayList<BirdChoice> candidates = parseBirdChoices(resMap.get("openAiAlternatives"));
                        callback.onSuccess(candidates, userMessage, isGore);
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing reviewBirdAlternatives response", e);
                        callback.onFailure(e, "Failed to parse server response.");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "reviewBirdAlternatives cloud function call failed", e);
                    callback.onFailure(new Exception(e), "API Error. Check Logcat for details.");
                });
    }

    public void syncIdentificationFeedback(@Nullable String identificationLogId,
                                           @Nullable String identificationId,
                                           String action,
                                           @Nullable String selectedBirdId,
                                           @Nullable String selectedSource,
                                           @Nullable StatusCallback callback) {
        if (identificationLogId == null || identificationLogId.trim().isEmpty()) {
            if (callback != null) {
                callback.onSuccess();
            }
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("identificationLogId", identificationLogId);
        data.put("action", action);
        if (identificationId != null && !identificationId.trim().isEmpty()) {
            data.put("identificationId", identificationId);
        }
        if (selectedBirdId != null && !selectedBirdId.trim().isEmpty()) {
            data.put("selectedBirdId", selectedBirdId);
        }
        if (selectedSource != null && !selectedSource.trim().isEmpty()) {
            data.put("selectedSource", selectedSource);
        }

        FirebaseFunctions.getInstance()
                .getHttpsCallable("syncIdentificationFeedback")
                .call(data)
                .addOnSuccessListener(result -> {
                    if (callback != null) {
                        callback.onSuccess();
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "syncIdentificationFeedback cloud function call failed", e);
                    if (callback != null) {
                        callback.onFailure(new Exception(e), "Failed to sync identification feedback.");
                    }
                });
    }

    private IdentificationResult parseIdentificationResult(Object data) {
        Map<String, Object> resMap = asMap(data);
        return new IdentificationResult(
                getBoolean(resMap, "isVerified"),
                getBoolean(resMap, "isGore"),
                getBoolean(resMap, "isInDatabase"),
                getString(resMap, "userMessage"),
                getString(resMap, "result"),
                getString(resMap, "imageUrl"),
                getString(resMap, "identificationLogId"),
                getString(resMap, "identificationId"),
                parseBirdChoice(resMap.get("primaryBird")),
                parseBirdChoices(resMap.get("modelAlternatives")),
                parseBirdChoices(resMap.get("openAiAlternatives"))
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new HashMap<>();
    }

    private ArrayList<BirdChoice> parseBirdChoices(@Nullable Object value) {
        ArrayList<BirdChoice> choices = new ArrayList<>();
        if (!(value instanceof List)) {
            return choices;
        }

        for (Object item : (List<?>) value) {
            BirdChoice choice = parseBirdChoice(item);
            if (choice != null) {
                choices.add(choice);
            }
        }
        return choices;
    }

    private BirdChoice parseBirdChoice(@Nullable Object value) {
        if (!(value instanceof Map)) {
            return null;
        }

        Map<String, Object> map = asMap(value);
        String birdId = getString(map, "birdId");
        String commonName = getString(map, "commonName");
        String scientificName = getString(map, "scientificName");
        String species = getString(map, "species");
        String family = getString(map, "family");
        String source = getString(map, "source");

        if ((birdId == null || birdId.trim().isEmpty()) && (commonName == null || commonName.trim().isEmpty())) {
            return null;
        }

        return new BirdChoice(birdId, commonName, scientificName, species, family, source);
    }

    private boolean getBoolean(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Boolean && (Boolean) value;
    }

    @Nullable
    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
