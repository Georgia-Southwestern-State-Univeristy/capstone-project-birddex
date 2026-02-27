package com.birddex.app;

import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CardUpgradeManager {

    private static final String TAG = "CardUpgradeManager";
    private final FirebaseFirestore db;

    // Define ordered rarity levels
    private static final List<String> RARITY_LEVELS = Arrays.asList(
            "common",
            "uncommon",
            "rare",
            "epic",
            "legendary"
    );

    // Define point costs for upgrading TO a specific rarity from the previous one
    // e.g., to upgrade from 'common' to 'uncommon', check cost for 'uncommon'
    private static final Map<String, Integer> RARITY_POINT_COSTS = new HashMap<>();

    static {
        RARITY_POINT_COSTS.put("uncommon", 10);
        RARITY_POINT_COSTS.put("rare", 50);
        RARITY_POINT_COSTS.put("epic", 200);
        RARITY_POINT_COSTS.put("legendary", 1000);
    }

    public interface UpgradeCallback {
        void onSuccess(String newRarity, int pointsDeducted, int newTotalPoints);
        void onFailure(String errorMessage);
        void onNotEnoughPoints(String currentRarity, int requiredPoints, int availablePoints);
        void onMaxRarityReached(String currentRarity);
    }

    public CardUpgradeManager(FirebaseFirestore db) {
        this.db = db;
    }

    public void upgradeCard(String userId, String collectionSlotId, UpgradeCallback callback) {
        if (userId == null || collectionSlotId == null) {
            callback.onFailure("User ID or Collection Slot ID cannot be null.");
            return;
        }

        final DocumentReference userRef = db.collection("users").document(userId);
        final DocumentReference collectionSlotRef = userRef.collection("collectionSlot").document(collectionSlotId);

        db.runTransaction(transaction -> {
            DocumentSnapshot userDoc = transaction.get(userRef);
            if (!userDoc.exists()) {
                throw new IllegalStateException("User document not found."); // Changed to IllegalStateException
            }
            long currentUserPoints = userDoc.getLong("totalPoints") != null ? userDoc.getLong("totalPoints") : 0;

            DocumentSnapshot cardDoc = transaction.get(collectionSlotRef);
            if (!cardDoc.exists()) {
                throw new IllegalStateException("Collection slot document not found."); // Changed to IllegalStateException
            }
            String currentRarity = cardDoc.getString("rarity");
            if (currentRarity == null || currentRarity.trim().isEmpty()) {
                currentRarity = "common"; // Default to common if not explicitly set
            }

            int currentRarityIndex = RARITY_LEVELS.indexOf(currentRarity.toLowerCase());
            if (currentRarityIndex == -1) {
                throw new IllegalStateException("Unrecognized current rarity: " + currentRarity); // Changed to IllegalStateException
            }

            if (currentRarityIndex == RARITY_LEVELS.size() - 1) {
                // Already at legendary rarity (max)
                return new UpgradeResult(false, currentRarity, 0, (int) currentUserPoints, "MAX_RARITY_REACHED");
            }

            String nextRarity = RARITY_LEVELS.get(currentRarityIndex + 1);
            Integer upgradeCost = RARITY_POINT_COSTS.get(nextRarity);

            if (upgradeCost == null) {
                throw new IllegalStateException("No upgrade cost defined for rarity: " + nextRarity); // Changed to IllegalStateException
            }

            if (currentUserPoints < upgradeCost) {
                // Not enough points
                return new UpgradeResult(false, currentRarity, upgradeCost, (int) currentUserPoints, "NOT_ENOUGH_POINTS");
            }

            // Perform the upgrade
            transaction.update(collectionSlotRef, "rarity", nextRarity);
            transaction.update(userRef, "totalPoints", FieldValue.increment(-upgradeCost));

            Log.d(TAG, "Card upgraded to " + nextRarity + " for user " + userId + ". Points deducted: " + upgradeCost);
            return new UpgradeResult(true, nextRarity, upgradeCost, (int) (currentUserPoints - upgradeCost), null);

        }).addOnSuccessListener(result -> {
            if (result.isSuccess) {
                callback.onSuccess(result.newRarity, result.pointsDeducted, result.newTotalPoints);
            } else if ("MAX_RARITY_REACHED".equals(result.reason)) {
                callback.onMaxRarityReached(result.currentRarity);
            } else if ("NOT_ENOUGH_POINTS".equals(result.reason)) {
                // Note: result.newRarity in this case is actually currentRarity
                callback.onNotEnoughPoints(result.currentRarity, result.pointsDeducted, result.newTotalPoints); 
            }
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Card upgrade transaction failed: " + e.getMessage(), e);
            callback.onFailure("Failed to upgrade card: " + e.getMessage());
        });
    }

    // Helper class for transaction result
    private static class UpgradeResult {
        boolean isSuccess;
        String newRarity; // Or currentRarity if max/not enough points
        int pointsDeducted;
        int newTotalPoints; // Or currentTotalPoints if not enough points
        String reason; // For specific failure types
        String currentRarity; // For max/not enough points callback

        UpgradeResult(boolean isSuccess, String newRarity, int pointsDeducted, int newTotalPoints, String reason) {
            this.isSuccess = isSuccess;
            this.newRarity = newRarity; // This will be the next rarity on success, or current on failure reasons
            this.pointsDeducted = pointsDeducted; // This will be the cost if not enough, or actual deducted on success
            this.newTotalPoints = newTotalPoints;
            this.reason = reason;
            this.currentRarity = newRarity; // For non-success cases, newRarity is essentially currentRarity
        }
    }
}
