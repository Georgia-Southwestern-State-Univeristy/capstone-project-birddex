package com.birddex.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BirdCacheManager: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class BirdCacheManager {
    private static final String TAG = "BirdCacheManager";
    private static final String PREF_NAME = "BirdDexCache";

    private static final String KEY_NEARBY_BIRDS = "nearby_birds_json";
    private static final String KEY_NEARBY_TIMESTAMP = "nearby_birds_timestamp";
    private static final String KEY_NEARBY_CENTER_LAT = "nearby_center_lat";
    private static final String KEY_NEARBY_CENTER_LNG = "nearby_center_lng";

    private static final String KEY_CORE_BIRDS = "core_georgia_birds_json";
    private static final String KEY_CORE_BIRDS_TIMESTAMP = "core_georgia_birds_timestamp";
    private static final String KEY_GEORGIA_SYNC_CHECK_TIMESTAMP = "georgia_sync_check_timestamp";
    private static final String KEY_GEORGIA_DATA_REFRESH_VERSION = "georgia_data_refresh_version";

    private static final String KEY_BIRD_DETAILS_PREFIX = "bird_details_json_";
    private static final String KEY_BIRD_DETAILS_TIMESTAMP_PREFIX = "bird_details_timestamp_";

    public static final long NEARBY_CACHE_TTL_MS = 10L * 60L * 1000L;
    public static final long CORE_BIRD_LIST_CACHE_TTL_MS = 12L * 60L * 60L * 1000L;
    public static final long BIRD_DETAILS_CACHE_TTL_MS = 12L * 60L * 60L * 1000L;

    private static List<Bird> inMemoryNearbyBirds = new ArrayList<>();
    private static long inMemoryNearbyTimestamp = 0L;
    private static Double inMemoryNearbyCenterLat = null;
    private static Double inMemoryNearbyCenterLng = null;

    private static List<JSONObject> inMemoryCoreGeorgiaBirds = new ArrayList<>();
    private static long inMemoryCoreGeorgiaBirdsTimestamp = 0L;

    private static final Map<String, JSONObject> inMemoryBirdDetails = Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, Long> inMemoryBirdDetailsTimestamps = Collections.synchronizedMap(new HashMap<>());

    private final SharedPreferences prefs;

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     * This method also reads or writes local device preferences so some state survives app
     * restarts.
     */
    public BirdCacheManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     */
    public synchronized void saveNearbyBirds(List<Bird> birds) {
        saveNearbyBirds(birds, null, null);
    }

    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     */
    public synchronized void saveNearbyBirds(List<Bird> birds, Double centerLat, Double centerLng) {
        JSONArray array = new JSONArray();
        List<Bird> safeCopy = new ArrayList<>();

        if (birds != null) {
            for (Bird bird : birds) {
                if (bird == null) continue;
                safeCopy.add(bird);
                try {
                    JSONObject json = new JSONObject();
                    json.put("id", bird.getId());
                    json.put("commonName", bird.getCommonName());
                    json.put("scientificName", bird.getScientificName());
                    json.put("lastSeenLatitudeGeorgia", bird.getLastSeenLatitudeGeorgia());
                    json.put("lastSeenLongitudeGeorgia", bird.getLastSeenLongitudeGeorgia());
                    json.put("lastSeenTimestampGeorgia", bird.getLastSeenTimestampGeorgia());
                    array.put(json);
                } catch (JSONException e) {
                    Log.e(TAG, "Error serializing bird for cache", e);
                }
            }
        }

        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_NEARBY_BIRDS, array.toString())
                .putLong(KEY_NEARBY_TIMESTAMP, now);

        if (centerLat != null && centerLng != null) {
            editor.putString(KEY_NEARBY_CENTER_LAT, String.valueOf(centerLat));
            editor.putString(KEY_NEARBY_CENTER_LNG, String.valueOf(centerLng));
            inMemoryNearbyCenterLat = centerLat;
            inMemoryNearbyCenterLng = centerLng;
        } else {
            editor.remove(KEY_NEARBY_CENTER_LAT);
            editor.remove(KEY_NEARBY_CENTER_LNG);
            inMemoryNearbyCenterLat = null;
            inMemoryNearbyCenterLng = null;
        }

        editor.apply();

        inMemoryNearbyBirds = safeCopy;
        inMemoryNearbyTimestamp = now;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public synchronized List<Bird> getCachedNearbyBirds() {
        if (inMemoryNearbyTimestamp > 0L && !inMemoryNearbyBirds.isEmpty()) {
            return new ArrayList<>(inMemoryNearbyBirds);
        }

        String json = prefs.getString(KEY_NEARBY_BIRDS, null);
        if (json == null) return new ArrayList<>();

        List<Bird> birds = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                Bird b = new Bird();
                b.setId(obj.optString("id"));
                b.setCommonName(obj.optString("commonName"));
                b.setScientificName(obj.optString("scientificName"));
                if (!obj.isNull("lastSeenLatitudeGeorgia")) b.setLastSeenLatitudeGeorgia(obj.optDouble("lastSeenLatitudeGeorgia"));
                if (!obj.isNull("lastSeenLongitudeGeorgia")) b.setLastSeenLongitudeGeorgia(obj.optDouble("lastSeenLongitudeGeorgia"));
                if (!obj.isNull("lastSeenTimestampGeorgia")) b.setLastSeenTimestampGeorgia(obj.optLong("lastSeenTimestampGeorgia"));
                birds.add(b);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing cached birds", e);
            return new ArrayList<>();
        }

        inMemoryNearbyBirds = new ArrayList<>(birds);
        inMemoryNearbyTimestamp = prefs.getLong(KEY_NEARBY_TIMESTAMP, 0L);
        inMemoryNearbyCenterLat = parseNullableDouble(prefs.getString(KEY_NEARBY_CENTER_LAT, null));
        inMemoryNearbyCenterLng = parseNullableDouble(prefs.getString(KEY_NEARBY_CENTER_LNG, null));
        return birds;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public synchronized long getCacheAge() {
        long ts = inMemoryNearbyTimestamp > 0L ? inMemoryNearbyTimestamp : prefs.getLong(KEY_NEARBY_TIMESTAMP, 0L);
        return ts <= 0L ? Long.MAX_VALUE : (System.currentTimeMillis() - ts);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public synchronized boolean hasFreshNearbyBirds(long maxAgeMs) {
        return !getCachedNearbyBirds().isEmpty() && getCacheAge() <= maxAgeMs;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public synchronized boolean hasFreshNearbyBirdsForLocation(double latitude, double longitude, long maxAgeMs, float maxDistanceMeters) {
        if (!hasFreshNearbyBirds(maxAgeMs)) return false;

        Double cachedLat = inMemoryNearbyCenterLat != null ? inMemoryNearbyCenterLat : parseNullableDouble(prefs.getString(KEY_NEARBY_CENTER_LAT, null));
        Double cachedLng = inMemoryNearbyCenterLng != null ? inMemoryNearbyCenterLng : parseNullableDouble(prefs.getString(KEY_NEARBY_CENTER_LNG, null));

        if (cachedLat == null || cachedLng == null) return false;

        float[] result = new float[1];
        Location.distanceBetween(latitude, longitude, cachedLat, cachedLng, result);
        return result[0] <= maxDistanceMeters;
    }

    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     */
    public synchronized void saveCoreGeorgiaBirds(List<JSONObject> birds) {
        JSONArray array = new JSONArray();
        List<JSONObject> safeCopy = new ArrayList<>();

        if (birds != null) {
            for (JSONObject bird : birds) {
                if (bird == null) continue;
                array.put(bird);
                safeCopy.add(bird);
            }
        }

        long now = System.currentTimeMillis();
        prefs.edit()
                .putString(KEY_CORE_BIRDS, array.toString())
                .putLong(KEY_CORE_BIRDS_TIMESTAMP, now)
                .apply();

        inMemoryCoreGeorgiaBirds = safeCopy;
        inMemoryCoreGeorgiaBirdsTimestamp = now;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public synchronized List<JSONObject> getCachedCoreGeorgiaBirds() {
        if (inMemoryCoreGeorgiaBirdsTimestamp > 0L && !inMemoryCoreGeorgiaBirds.isEmpty()) {
            return new ArrayList<>(inMemoryCoreGeorgiaBirds);
        }

        String json = prefs.getString(KEY_CORE_BIRDS, null);
        if (json == null) return new ArrayList<>();

        List<JSONObject> birds = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                birds.add(array.getJSONObject(i));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing cached Georgia birds", e);
            return new ArrayList<>();
        }

        inMemoryCoreGeorgiaBirds = new ArrayList<>(birds);
        inMemoryCoreGeorgiaBirdsTimestamp = prefs.getLong(KEY_CORE_BIRDS_TIMESTAMP, 0L);
        return birds;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public synchronized long getCoreGeorgiaBirdCacheAge() {
        long ts = inMemoryCoreGeorgiaBirdsTimestamp > 0L ? inMemoryCoreGeorgiaBirdsTimestamp : prefs.getLong(KEY_CORE_BIRDS_TIMESTAMP, 0L);
        return ts <= 0L ? Long.MAX_VALUE : (System.currentTimeMillis() - ts);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public synchronized boolean hasFreshCoreGeorgiaBirdList(long maxAgeMs) {
        return !getCachedCoreGeorgiaBirds().isEmpty() && getCoreGeorgiaBirdCacheAge() <= maxAgeMs;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public synchronized long getLastGeorgiaSyncCheckTimestamp() {
        return prefs.getLong(KEY_GEORGIA_SYNC_CHECK_TIMESTAMP, 0L);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public synchronized long getGeorgiaSyncCheckAge() {
        long ts = getLastGeorgiaSyncCheckTimestamp();
        return ts <= 0L ? Long.MAX_VALUE : (System.currentTimeMillis() - ts);
    }

    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     */
    public synchronized void markGeorgiaSyncCheckNow() {
        prefs.edit().putLong(KEY_GEORGIA_SYNC_CHECK_TIMESTAMP, System.currentTimeMillis()).apply();
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public synchronized int getGeorgiaDataRefreshVersion() {
        return prefs.getInt(KEY_GEORGIA_DATA_REFRESH_VERSION, 0);
    }

    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     */
    public synchronized void setGeorgiaDataRefreshVersion(int version) {
        prefs.edit().putInt(KEY_GEORGIA_DATA_REFRESH_VERSION, version).apply();
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public synchronized boolean shouldCheckGeorgiaBirdSync(boolean forceRefresh, long maxCheckAgeMs, int expectedRefreshVersion) {
        if (forceRefresh) return true;
        if (!hasFreshCoreGeorgiaBirdList(CORE_BIRD_LIST_CACHE_TTL_MS)) return true;
        if (getGeorgiaSyncCheckAge() > maxCheckAgeMs) return true;
        return getGeorgiaDataRefreshVersion() != expectedRefreshVersion;
    }

    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     */
    public synchronized void saveBirdDetails(String birdId, JSONObject details) {
        if (birdId == null || birdId.trim().isEmpty() || details == null) return;

        long now = System.currentTimeMillis();
        prefs.edit()
                .putString(KEY_BIRD_DETAILS_PREFIX + birdId, details.toString())
                .putLong(KEY_BIRD_DETAILS_TIMESTAMP_PREFIX + birdId, now)
                .apply();

        inMemoryBirdDetails.put(birdId, details);
        inMemoryBirdDetailsTimestamps.put(birdId, now);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public synchronized JSONObject getCachedBirdDetails(String birdId) {
        if (birdId == null || birdId.trim().isEmpty()) return null;

        JSONObject inMemory = inMemoryBirdDetails.get(birdId);
        if (inMemory != null) return inMemory;

        String raw = prefs.getString(KEY_BIRD_DETAILS_PREFIX + birdId, null);
        if (raw == null) return null;

        try {
            JSONObject jsonObject = new JSONObject(raw);
            inMemoryBirdDetails.put(birdId, jsonObject);
            inMemoryBirdDetailsTimestamps.put(birdId, prefs.getLong(KEY_BIRD_DETAILS_TIMESTAMP_PREFIX + birdId, 0L));
            return jsonObject;
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing cached bird details for " + birdId, e);
            return null;
        }
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public synchronized long getBirdDetailsCacheAge(String birdId) {
        if (birdId == null || birdId.trim().isEmpty()) return Long.MAX_VALUE;
        Long inMemoryTs = inMemoryBirdDetailsTimestamps.get(birdId);
        long ts = inMemoryTs != null ? inMemoryTs : prefs.getLong(KEY_BIRD_DETAILS_TIMESTAMP_PREFIX + birdId, 0L);
        return ts <= 0L ? Long.MAX_VALUE : (System.currentTimeMillis() - ts);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public synchronized boolean hasFreshBirdDetails(String birdId, long maxAgeMs) {
        return getCachedBirdDetails(birdId) != null && getBirdDetailsCacheAge(birdId) <= maxAgeMs;
    }

    private Double parseNullableDouble(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
