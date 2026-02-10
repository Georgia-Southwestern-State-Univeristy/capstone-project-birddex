package com.example.birddex;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * BirdVerifier is a utility class used to cross-reference identification results 
 * from OpenAI with a validated regional bird list from eBird.
 * This helps ensure the identified bird is known to inhabit the user's current region.
 */
public class BirdVerifier {

    private static final String TAG = "BirdVerifier";

    /**
     * Callback interface for handling the result of a bird verification check.
     */
    public interface BirdVerifierCallback {
        void onResult(boolean isVerified, String commonName, String scientificName);
    }

    /**
     * Verifies if a given bird (by common and scientific name) exists in the provided eBird taxonomy list.
     * @param commonName The common name of the bird (e.g., from OpenAI).
     * @param scientificName The scientific name of the bird.
     * @param georgiaBirds A list of JSONObjects representing birds found in Georgia.
     * @param callback The callback to handle the verification result.
     */
    public void verifyBirdWithEbird(String commonName, String scientificName, List<JSONObject> georgiaBirds, BirdVerifierCallback callback) {
        Log.d(TAG, "Verifying bird...");
        Log.d(TAG, "OpenAI Common Name: '" + commonName.trim() + "'");
        Log.d(TAG, "OpenAI Scientific Name: '" + scientificName.trim() + "'");
        Log.d(TAG, "Number of Georgia birds in list: " + georgiaBirds.size());

        boolean isGeorgiaBird = false;
        try {
            // Iterate through the regional bird list to find an exact (case-insensitive) match.
            for (JSONObject bird : georgiaBirds) {
                String ebirdComName = bird.getString("comName");
                String ebirdSciName = bird.getString("sciName");
                
                // Compare the identified names with the eBird records.
                if (ebirdComName.equalsIgnoreCase(commonName.trim()) &&
                        ebirdSciName.equalsIgnoreCase(scientificName.trim())) {
                    isGeorgiaBird = true;
                    Log.i(TAG, "SUCCESS: Match found for " + commonName + " in eBird data.");
                    break;
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error verifying bird with eBird data.", e);
        }
        
        // Return the result via the callback.
        callback.onResult(isGeorgiaBird, commonName, scientificName);
    }
}