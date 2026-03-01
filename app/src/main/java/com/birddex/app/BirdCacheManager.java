package com.birddex.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class BirdCacheManager {
    private static final String TAG = "BirdCacheManager";
    private static final String PREF_NAME = "BirdDexCache";
    private static final String KEY_NEARBY_BIRDS = "nearby_birds_json";
    private static final String KEY_NEARBY_TIMESTAMP = "nearby_birds_timestamp";

    private final SharedPreferences prefs;

    public BirdCacheManager(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void saveNearbyBirds(List<Bird> birds) {
        JSONArray array = new JSONArray();
        for (Bird bird : birds) {
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
        prefs.edit()
                .putString(KEY_NEARBY_BIRDS, array.toString())
                .putLong(KEY_NEARBY_TIMESTAMP, System.currentTimeMillis())
                .apply();
    }

    public List<Bird> getCachedNearbyBirds() {
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
                b.setLastSeenLatitudeGeorgia(obj.optDouble("lastSeenLatitudeGeorgia"));
                b.setLastSeenLongitudeGeorgia(obj.optDouble("lastSeenLongitudeGeorgia"));
                b.setLastSeenTimestampGeorgia(obj.optLong("lastSeenTimestampGeorgia"));
                birds.add(b);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing cached birds", e);
        }
        return birds;
    }

    public long getCacheAge() {
        return System.currentTimeMillis() - prefs.getLong(KEY_NEARBY_TIMESTAMP, 0);
    }
}
