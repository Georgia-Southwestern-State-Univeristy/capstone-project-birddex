package com.example.birddex;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * EbirdApi is a helper class for interacting with the eBird API.
 * It fetches a list of bird species for a specific region (currently hardcoded to Georgia, US).
 */
public class EbirdApi {

    private static final String TAG = "EbirdApi";
    // URL for the full eBird taxonomy reference.
    private static final String EBIRD_API_URL = "https://api.ebird.org/v2/ref/taxonomy/ebird?fmt=json";

    private final OkHttpClient client;

    /**
     * Callback interface for handling results from the eBird API calls.
     */
    public interface EbirdCallback {
        void onSuccess(List<JSONObject> birds);
        void onFailure(Exception e);
    }

    public EbirdApi(OkHttpClient client) {
        this.client = client;
    }

    /**
     * Fetches the list of bird species codes for Georgia (US-GA) and then resolves their full details.
     * @param ebirdApiKey Your eBird API key.
     * @param callback The callback to handle the response.
     */
    public void fetchGeorgiaBirdList(String ebirdApiKey, EbirdCallback callback) {
        Request request = new Request.Builder()
                .url("https://api.ebird.org/v2/product/spplist/US-GA")
                .header("X-eBirdApiToken", ebirdApiKey)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to fetch Georgia bird species codes", e);
                callback.onFailure(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    IOException e = new IOException("Failed to fetch Georgia bird species codes: " + response);
                    Log.e(TAG, e.getMessage());
                    callback.onFailure(e);
                    return;
                }
                try {
                    if (response.body() == null) {
                        IOException e = new IOException("Response body is null");
                        Log.e(TAG, e.getMessage());
                        callback.onFailure(e);
                        return;
                    }
                    // Once species codes are fetched, get their full taxonomic details.
                    JSONArray speciesCodes = new JSONArray(response.body().string());
                    fetchBirdNamesFromCodes(speciesCodes, ebirdApiKey, callback);
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to parse Georgia species codes JSON", e);
                    callback.onFailure(e);
                }
            }
        });
    }

    /**
     * Fetches the entire eBird taxonomy and filters it to find birds matching the given species codes.
     * @param speciesCodes A JSONArray of bird species codes to look for.
     * @param ebirdApiKey Your eBird API key.
     * @param callback The callback to handle the response.
     */
    private void fetchBirdNamesFromCodes(JSONArray speciesCodes, String ebirdApiKey, EbirdCallback callback) {
        Request request = new Request.Builder()
                .url(EBIRD_API_URL)
                .header("X-eBirdApiToken", ebirdApiKey)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to fetch eBird taxonomy", e);
                callback.onFailure(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    IOException e = new IOException("Failed to fetch eBird taxonomy: " + response);
                    Log.e(TAG, e.getMessage());
                    callback.onFailure(e);
                    return;
                }
                try {
                     if (response.body() == null) {
                        IOException e = new IOException("Response body is null");
                        Log.e(TAG, e.getMessage());
                        callback.onFailure(e);
                        return;
                    }
                    JSONArray taxonomy = new JSONArray(response.body().string());
                    List<String> codes = new ArrayList<>();
                    for (int i = 0; i < speciesCodes.length(); i++) {
                        codes.add(speciesCodes.getString(i));
                    }
                    
                    // Filter the full taxonomy to get only the birds found in Georgia.
                    List<JSONObject> georgiaBirds = new ArrayList<>();
                    for (int i = 0; i < taxonomy.length(); i++) {
                        JSONObject bird = taxonomy.getJSONObject(i);
                        if (codes.contains(bird.getString("speciesCode"))) {
                            georgiaBirds.add(bird);
                        }
                    }
                    Log.i(TAG, "Successfully loaded " + georgiaBirds.size() + " Georgia bird details.");
                    callback.onSuccess(georgiaBirds);
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to parse eBird taxonomy JSON", e);
                    callback.onFailure(e);
                }
            }
        });
    }
}