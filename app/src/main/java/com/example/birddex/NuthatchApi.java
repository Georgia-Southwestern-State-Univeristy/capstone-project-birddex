package com.example.birddex;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.StringRequest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/**
 * NuthatchApi is a helper class for interacting with the Nuthatch API.
 * It searches for bird images by name and returns the URL of the first image found.
 */
public class NuthatchApi {

    private final RequestQueue requestQueue;

    /**
     * Callback interface for handling the result of a Nuthatch API search.
     */
    public interface SearchResultHandler {
        void onImageFound(String imageUrl);
        void onImageNotFound();
    }

    public NuthatchApi(RequestQueue requestQueue) {
        this.requestQueue = requestQueue;
    }

    /**
     * Searches the Nuthatch API for a bird by its name.
     * @param searchTerm The name of the bird to search for.
     * @param handler The callback to handle the result (image found or not found).
     */
    public void searchNuthatchByName(String searchTerm, SearchResultHandler handler) {
        String encodedSearchTerm;
        try {
            // URL-encode the search term to handle spaces and special characters.
            encodedSearchTerm = URLEncoder.encode(searchTerm, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            handler.onImageNotFound();
            return;
        }

        // Construct the API request URL.
        String url = "https://nuthatch.lastelm.software/v2/birds?name=" + encodedSearchTerm + "&hasImg=true";
        
        // Create the Volley StringRequest.
        StringRequest stringRequest = new StringRequest(Request.Method.GET, url, response -> {
            try {
                // Parse the JSON response to find the first image URL.
                JSONObject jsonObject = new JSONObject(response);
                if (jsonObject.has("entities")) {
                    JSONArray entities = jsonObject.getJSONArray("entities");
                    if (entities.length() > 0) {
                        JSONObject entity = entities.getJSONObject(0);
                        if (entity.has("images")) {
                            JSONArray images = entity.getJSONArray("images");
                            if (images.length() > 0) {
                                // If an image is found, pass the URL to the handler.
                                handler.onImageFound(images.getString(0));
                                return;
                            }
                        }
                    }
                }
                // If no image is found in the response, notify the handler.
                handler.onImageNotFound();
            } catch (JSONException e) {
                handler.onImageNotFound();
            }
        }, error -> handler.onImageNotFound()) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                // Add the required API key to the request headers.
                Map<String, String> headers = new HashMap<>();
                headers.put("api-key", BuildConfig.NUTHATCH_API_KEY);
                return headers;
            }
        };
        
        // Add the request to the Volley queue.
        requestQueue.add(stringRequest);
    }
}
