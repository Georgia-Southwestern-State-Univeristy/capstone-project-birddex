package com.birddex.app;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * CardRarityHelper centralizes rarity names, upgrade costs, and layout selection so the same
 * rules are used everywhere in the app and backend-facing UI code.
 */
public final class CardRarityHelper {

    public static final String COMMON = "common";
    public static final String UNCOMMON = "uncommon";
    public static final String RARE = "rare";
    public static final String EPIC = "epic";
    public static final String LEGENDARY = "legendary";
    public static final String MYTHIC = "mythic";

    public static final List<String> ORDER = Arrays.asList(
            COMMON,
            UNCOMMON,
            RARE,
            EPIC,
            LEGENDARY,
            MYTHIC
    );

    private CardRarityHelper() {
        // Utility class
    }

    @NonNull
    public static String normalizeRarity(@Nullable String rarity) {
        if (rarity == null) return COMMON;

        String normalized = rarity.trim().toLowerCase(Locale.US);
        if (ORDER.contains(normalized)) {
            return normalized;
        }

        return COMMON;
    }

    public static boolean isValidRarity(@Nullable String rarity) {
        return ORDER.contains(normalizeRarity(rarity));
    }

    public static int getRarityIndex(@Nullable String rarity) {
        return ORDER.indexOf(normalizeRarity(rarity));
    }

    public static int getLayoutResId(@Nullable String rarity) {
        switch (normalizeRarity(rarity)) {
            case UNCOMMON:
                return R.layout.view_bird_card_uncommon;
            case RARE:
                return R.layout.view_bird_card_rare;
            case EPIC:
                return R.layout.view_bird_card_epic;
            case LEGENDARY:
                return R.layout.view_bird_card_legendary;
            case MYTHIC:
                return R.layout.view_bird_card_mythic;
            case COMMON:
            default:
                return R.layout.view_bird_card_common;
        }
    }


    public static int getUpgradeCost(@Nullable String fromRarity, @Nullable String toRarity) {
        int fromIndex = getRarityIndex(fromRarity);
        int toIndex = getRarityIndex(toRarity);

        if (fromIndex < 0 || toIndex < 0 || toIndex <= fromIndex) {
            return -1;
        }

        int total = 0;
        for (int i = fromIndex + 1; i <= toIndex; i++) {
            total += getStepCostForIndex(i);
        }
        return total;
    }

    /**
     * Calculates the refund for reverting to a lower rarity.
     * Refund is 75% of the total points spent between the target and current rarity.
     */
    public static int getDowngradeRefund(@Nullable String currentRarity, @Nullable String targetRarity) {
        int currentIndex = getRarityIndex(currentRarity);
        int targetIndex = getRarityIndex(targetRarity);

        if (currentIndex < 0 || targetIndex < 0 || targetIndex >= currentIndex) {
            return -1;
        }

        int totalCost = 0;
        for (int i = targetIndex + 1; i <= currentIndex; i++) {
            totalCost += getStepCostForIndex(i);
        }

        return (int) Math.floor(totalCost * 0.75);
    }

    @NonNull
    public static String getNextRarity(@Nullable String rarity) {
        int index = getRarityIndex(rarity);
        if (index < 0 || index >= ORDER.size() - 1) {
            return normalizeRarity(rarity);
        }
        return ORDER.get(index + 1);
    }

    private static int getStepCostForIndex(int rarityIndex) {
        // Updated to use increments of 5 as requested
        switch (rarityIndex) {
            case 1:
                return 10; // common -> uncommon
            case 2:
                return 20; // uncommon -> rare
            case 3:
                return 30; // rare -> epic
            case 4:
                return 50; // epic -> legendary
            case 5:
                return 100; // legendary -> mythic
            default:
                return 0;
        }
    }
}