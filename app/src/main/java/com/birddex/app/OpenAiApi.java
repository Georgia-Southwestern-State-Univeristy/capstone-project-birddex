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
        public boolean isSupportedInDatabase = true;
        public boolean locationPlausible = true;
        @Nullable public Double locationPlausibilityScore;
        @Nullable public Double distanceMilesFromUser;
        @Nullable public Double daysSinceLastSeenGeorgia;
    }

    public static class IdentifyBirdResult {
        @Nullable public String resultText;
        public boolean isVerified;
        public boolean isGore;
        public boolean isInDatabase = true;
        @Nullable public String reasonCode;
        @Nullable public String userMessage;
        @Nullable public String identificationLogId;
        @Nullable public String identificationId;
        @Nullable public BirdChoice primaryBird;
        public ArrayList<BirdChoice> modelAlternatives = new ArrayList<>();
        public boolean notMyBirdAllowed = true;
        @Nullable public String notMyBirdBlockMessage;
        @Nullable public Double modelTop1Confidence;
        @Nullable public Double modelTop2Confidence;
        @Nullable public Double modelConfidenceMargin;
        public boolean allowPointAward = true;
        @Nullable public String pointAwardBlockReason;
        @Nullable public String pointAwardUserMessage;
    }

    public interface IdentifyBirdCallback {
        void onSuccess(IdentifyBirdResult result);
        void onFailure(Exception e, String message);
    }

    public interface BirdChoicesCallback {
        void onSuccess(ArrayList<BirdChoice> candidates, @Nullable String userMessage, boolean isGore);
        void onFailure(Exception e, String message);
    }

    public interface FeedbackSyncCallback {
        void onSuccess(@Nullable String userMessage);
        void onFailure(@Nullable Exception e, @NonNull String message);
    }

    public void identifyBirdFromImage(String base64Image,
                                      String imageUrl,
                                      @Nullable Double latitude,
                                      @Nullable Double longitude,
                                      @Nullable String localityName,
                                      String requestId,
                                      @Nullable CaptureGuardHelper.GuardReport captureGuardReport,
                                      IdentifyBirdCallback callback) {
        Map<String, Object> data = new HashMap<>();
        data.put("image", base64Image);
        data.put("imageUrl", imageUrl);
        data.put("requestId", requestId);

        CaptureGuardHelper.GuardReport safeReport = captureGuardReport != null
                ? captureGuardReport
                : CaptureGuardHelper.buildFallbackReport(CaptureGuardHelper.CAPTURE_SOURCE_UNKNOWN, 0);

        data.put("captureSource", safeReport.captureSource);
        Map<String, Object> captureGuard = new HashMap<>();
        captureGuard.put("analyzerVersion", safeReport.analyzerVersion);
        captureGuard.put("suspicionScore", safeReport.suspicionScore);
        captureGuard.put("suspicious", safeReport.suspicious);
        captureGuard.put("burstFrameCount", safeReport.burstFrameCount);
        captureGuard.put("burstSpanMs", safeReport.burstSpanMs);
        captureGuard.put("selectedFrameIndex", safeReport.selectedFrameIndex);
        captureGuard.put("frameSimilarity", safeReport.frameSimilarity);
        captureGuard.put("aliasingScore", safeReport.aliasingScore);
        captureGuard.put("screenArtifactScore", safeReport.screenArtifactScore);
        captureGuard.put("borderScore", safeReport.borderScore);
        captureGuard.put("glareScore", safeReport.glareScore);
        captureGuard.put("selectedFrameSharpness", safeReport.selectedFrameSharpness);
        captureGuard.put("metadataScore", safeReport.metadataScore);
        captureGuard.put("metadataSuspicious", safeReport.metadataSuspicious);
        captureGuard.put("editedSoftwareTagPresent", safeReport.editedSoftwareTagPresent);
        captureGuard.put("cameraMakeModelMissing", safeReport.cameraMakeModelMissing);
        captureGuard.put("dateTimeOriginalMissing", safeReport.dateTimeOriginalMissing);
        captureGuard.put("reasons", new ArrayList<>(safeReport.reasons));
        data.put("captureGuard", captureGuard);

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
                        parsed.userMessage = getString(resMap, "userMessage");
                        parsed.identificationLogId = getString(resMap, "identificationLogId");
                        parsed.identificationId = getString(resMap, "identificationId");
                        parsed.primaryBird = parseBirdChoice(resMap.get("primaryBird"));
                        parsed.modelAlternatives = parseBirdChoices(resMap.get("modelAlternatives"));
                        parsed.notMyBirdAllowed = !resMap.containsKey("notMyBirdAllowed") || Boolean.TRUE.equals(resMap.get("notMyBirdAllowed"));
                        parsed.notMyBirdBlockMessage = getString(resMap, "notMyBirdBlockMessage");
                        parsed.modelTop1Confidence = getDouble(resMap, "modelTop1Confidence");
                        parsed.modelTop2Confidence = getDouble(resMap, "modelTop2Confidence");
                        parsed.modelConfidenceMargin = getDouble(resMap, "modelConfidenceMargin");
                        parsed.allowPointAward = !resMap.containsKey("allowPointAward") || Boolean.TRUE.equals(resMap.get("allowPointAward"));
                        parsed.pointAwardBlockReason = getString(resMap, "pointAwardBlockReason");
                        parsed.pointAwardUserMessage = getString(resMap, "pointAwardUserMessage");
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

    public void submitIdentificationFeedback(@Nullable String identificationLogId,
                                             @Nullable String identificationId,
                                             @NonNull String stage,
                                             @NonNull String note) {
        submitIdentificationFeedback(identificationLogId, identificationId, stage, note, null);
    }

    public void submitIdentificationFeedback(@Nullable String identificationLogId,
                                             @Nullable String identificationId,
                                             @NonNull String stage,
                                             @NonNull String note,
                                             @Nullable FeedbackSyncCallback callback) {
        syncIdentificationFeedback(
                identificationLogId,
                identificationId,
                "submit_feedback",
                null,
                stage,
                note,
                null,
                null,
                null,
                null,
                callback
        );
    }

    public void syncIdentificationFeedback(@Nullable String identificationLogId,
                                           @Nullable String identificationId,
                                           @NonNull String action,
                                           @Nullable String selectedBirdId,
                                           @Nullable String selectionSource,
                                           @Nullable String note) {
        syncIdentificationFeedback(
                identificationLogId,
                identificationId,
                action,
                selectedBirdId,
                selectionSource,
                note,
                null,
                null,
                null,
                null,
                null
        );
    }

    public void syncIdentificationFeedback(@Nullable String identificationLogId,
                                           @Nullable String identificationId,
                                           @NonNull String action,
                                           @Nullable String selectedBirdId,
                                           @Nullable String selectionSource,
                                           @Nullable String note,
                                           @Nullable String selectedCommonName,
                                           @Nullable String selectedScientificName,
                                           @Nullable String selectedSpecies,
                                           @Nullable String selectedFamily) {
        syncIdentificationFeedback(
                identificationLogId,
                identificationId,
                action,
                selectedBirdId,
                selectionSource,
                note,
                selectedCommonName,
                selectedScientificName,
                selectedSpecies,
                selectedFamily,
                null
        );
    }

    public void syncIdentificationFeedback(@Nullable String identificationLogId,
                                           @Nullable String identificationId,
                                           @NonNull String action,
                                           @Nullable String selectedBirdId,
                                           @Nullable String selectionSource,
                                           @Nullable String note,
                                           @Nullable String selectedCommonName,
                                           @Nullable String selectedScientificName,
                                           @Nullable String selectedSpecies,
                                           @Nullable String selectedFamily,
                                           @Nullable FeedbackSyncCallback callback) {
        if (identificationLogId == null || identificationLogId.trim().isEmpty()) {
            if (callback != null) {
                callback.onFailure(null, "Identification log was not ready yet.");
            }
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("identificationLogId", identificationLogId);
        if (identificationId != null) data.put("identificationId", identificationId);
        data.put("action", action);
        if (selectedBirdId != null) data.put("selectedBirdId", selectedBirdId);
        if (selectionSource != null) data.put("selectionSource", selectionSource);
        if (note != null) data.put("note", note);
        if (selectedCommonName != null) data.put("selectedCommonName", selectedCommonName);
        if (selectedScientificName != null) data.put("selectedScientificName", selectedScientificName);
        if (selectedSpecies != null) data.put("selectedSpecies", selectedSpecies);
        if (selectedFamily != null) data.put("selectedFamily", selectedFamily);

        FirebaseFunctions.getInstance()
                .getHttpsCallable("syncIdentificationFeedback")
                .call(data)
                .addOnSuccessListener(result -> {
                    Map<String, Object> resMap = castMap(result.getData());
                    String userMessage = getString(resMap, "userMessage");
                    if (callback != null) {
                        callback.onSuccess(userMessage);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "syncIdentificationFeedback failed", e);
                    String message = getFeedbackErrorMessage(e);
                    if (callback != null) {
                        callback.onFailure(new Exception(e), message);
                    }
                });
    }

    @NonNull
    private String getFeedbackErrorMessage(@NonNull Exception exception) {
        if (exception instanceof com.google.firebase.functions.FirebaseFunctionsException) {
            String message = exception.getMessage();
            if (message != null && !message.trim().isEmpty()) {
                int colonIndex = message.indexOf(": ");
                if (colonIndex >= 0 && colonIndex + 2 < message.length()) {
                    return message.substring(colonIndex + 2).trim();
                }
                return message.trim();
            }
        }
        String message = exception.getMessage();
        return (message != null && !message.trim().isEmpty())
                ? message.trim()
                : "Could not submit feedback right now.";
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
    private Double getDouble(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : null;
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
        choice.isSupportedInDatabase = !map.containsKey("isSupportedInDatabase") || Boolean.TRUE.equals(map.get("isSupportedInDatabase"));
        choice.locationPlausible = !map.containsKey("locationPlausible") || Boolean.TRUE.equals(map.get("locationPlausible"));
        choice.locationPlausibilityScore = getDouble(map, "locationPlausibilityScore");
        choice.distanceMilesFromUser = getDouble(map, "distanceMilesFromUser");
        choice.daysSinceLastSeenGeorgia = getDouble(map, "daysSinceLastSeenGeorgia");
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
            if (choice != null && hasDisplayableBirdData(choice)) {
                results.add(choice);
            }
        }
        return results;
    }

    private boolean hasDisplayableBirdData(@Nullable BirdChoice choice) {
        if (choice == null) return false;
        return hasText(choice.birdId)
                || hasText(choice.commonName)
                || hasText(choice.scientificName);
    }

    private boolean hasText(@Nullable String value) {
        return value != null && !value.trim().isEmpty();
    }
}
