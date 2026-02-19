package com.birddex.app;

import com.google.firebase.functions.FirebaseFunctions;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * EbirdApi helper that fetches processed regional bird data from Cloud Functions.
 */
public class EbirdApi {
    private static final String TAG = "EbirdApi";

    public interface EbirdCallback {
        void onSuccess(List<JSONObject> birds);
        void onFailure(Exception e);
    }

    public EbirdApi() {
        // Constructor no longer requires OkHttpClient as it uses Firebase Functions.
    }

    public void fetchGeorgiaBirdList(EbirdCallback callback) {
        FirebaseFunctions.getInstance()
                .getHttpsCallable("getGeorgiaBirds")
                .call()
                .addOnSuccessListener(result -> {
                    try {
                        Map<String, Object> resMap = (Map<String, Object>) result.getData();
                        List<Map<String, Object>> birdsData = (List<Map<String, Object>>) resMap.get("birds");
                        
                        List<JSONObject> birdObjects = new ArrayList<>();
                        if (birdsData != null) {
                            for (Map<String, Object> map : birdsData) {
                                birdObjects.add(new JSONObject(map));
                            }
                        }
                        callback.onSuccess(birdObjects);
                    } catch (Exception e) {
                        callback.onFailure(e);
                    }
                })
                .addOnFailureListener(callback::onFailure);
    }
}


