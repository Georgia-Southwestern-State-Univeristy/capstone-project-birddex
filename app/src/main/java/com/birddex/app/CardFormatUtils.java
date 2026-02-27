package com.birddex.app;

import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class CardFormatUtils {

    private static final Map<String, String> STATE_ABBREVIATIONS = new HashMap<>();

    static {
        STATE_ABBREVIATIONS.put("alabama", "AL");
        STATE_ABBREVIATIONS.put("alaska", "AK");
        STATE_ABBREVIATIONS.put("arizona", "AZ");
        STATE_ABBREVIATIONS.put("arkansas", "AR");
        STATE_ABBREVIATIONS.put("california", "CA");
        STATE_ABBREVIATIONS.put("colorado", "CO");
        STATE_ABBREVIATIONS.put("connecticut", "CT");
        STATE_ABBREVIATIONS.put("delaware", "DE");
        STATE_ABBREVIATIONS.put("florida", "FL");
        STATE_ABBREVIATIONS.put("georgia", "GA");
        STATE_ABBREVIATIONS.put("hawaii", "HI");
        STATE_ABBREVIATIONS.put("idaho", "ID");
        STATE_ABBREVIATIONS.put("illinois", "IL");
        STATE_ABBREVIATIONS.put("indiana", "IN");
        STATE_ABBREVIATIONS.put("iowa", "IA");
        STATE_ABBREVIATIONS.put("kansas", "KS");
        STATE_ABBREVIATIONS.put("kentucky", "KY");
        STATE_ABBREVIATIONS.put("louisiana", "LA");
        STATE_ABBREVIATIONS.put("maine", "ME");
        STATE_ABBREVIATIONS.put("maryland", "MD");
        STATE_ABBREVIATIONS.put("massachusetts", "MA");
        STATE_ABBREVIATIONS.put("michigan", "MI");
        STATE_ABBREVIATIONS.put("minnesota", "MN");
        STATE_ABBREVIATIONS.put("mississippi", "MS");
        STATE_ABBREVIATIONS.put("missouri", "MO");
        STATE_ABBREVIATIONS.put("montana", "MT");
        STATE_ABBREVIATIONS.put("nebraska", "NE");
        STATE_ABBREVIATIONS.put("nevada", "NV");
        STATE_ABBREVIATIONS.put("new hampshire", "NH");
        STATE_ABBREVIATIONS.put("new jersey", "NJ");
        STATE_ABBREVIATIONS.put("new mexico", "NM");
        STATE_ABBREVIATIONS.put("new york", "NY");
        STATE_ABBREVIATIONS.put("north carolina", "NC");
        STATE_ABBREVIATIONS.put("north dakota", "ND");
        STATE_ABBREVIATIONS.put("ohio", "OH");
        STATE_ABBREVIATIONS.put("oklahoma", "OK");
        STATE_ABBREVIATIONS.put("oregon", "OR");
        STATE_ABBREVIATIONS.put("pennsylvania", "PA");
        STATE_ABBREVIATIONS.put("rhode island", "RI");
        STATE_ABBREVIATIONS.put("south carolina", "SC");
        STATE_ABBREVIATIONS.put("south dakota", "SD");
        STATE_ABBREVIATIONS.put("tennessee", "TN");
        STATE_ABBREVIATIONS.put("texas", "TX");
        STATE_ABBREVIATIONS.put("utah", "UT");
        STATE_ABBREVIATIONS.put("vermont", "VT");
        STATE_ABBREVIATIONS.put("virginia", "VA");
        STATE_ABBREVIATIONS.put("washington", "WA");
        STATE_ABBREVIATIONS.put("west virginia", "WV");
        STATE_ABBREVIATIONS.put("wisconsin", "WI");
        STATE_ABBREVIATIONS.put("wyoming", "WY");
        STATE_ABBREVIATIONS.put("district of columbia", "DC");
    }

    private CardFormatUtils() {}

    public static String formatLocation(@Nullable String state, @Nullable String locality) {
        String cleanLocality = clean(locality);
        String stateAbbrev = abbreviateState(state);

        if (!cleanLocality.isEmpty() && !stateAbbrev.isEmpty()) {
            return "Location: " + cleanLocality + ", " + stateAbbrev;
        }

        if (!cleanLocality.isEmpty()) {
            return "Location: " + cleanLocality;
        }

        if (!stateAbbrev.isEmpty()) {
            return "Location: " + stateAbbrev;
        }

        return "Location: --";
    }

    public static String formatCaughtDate(@Nullable Date date) {
        if (date == null) return "Date caught: --";
        return "Date caught: " + new SimpleDateFormat("MMM d, yyyy", Locale.US).format(date);
    }

    public static String abbreviateState(@Nullable String state) {
        String cleanState = clean(state);
        if (cleanState.isEmpty()) return "";

        if (cleanState.length() == 2) {
            return cleanState.toUpperCase(Locale.US);
        }

        String mapped = STATE_ABBREVIATIONS.get(cleanState.toLowerCase(Locale.US));
        if (mapped != null) return mapped;

        return cleanState;
    }

    private static String clean(@Nullable String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ");
    }
}