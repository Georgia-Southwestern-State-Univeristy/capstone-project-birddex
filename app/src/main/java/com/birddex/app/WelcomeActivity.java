package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.functions.HttpsCallableResult; // Added import

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * WelcomeActivity serves as the entry point of the app.
 * It handles automatic login redirection and triggers the regional bird data cache check.
 */
public class WelcomeActivity extends AppCompatActivity {

    private static final String TAG = "WelcomeActivity";
    private FirebaseManager firebaseManager; // Added FirebaseManager instance

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        firebaseManager = new FirebaseManager(this); // Initialize FirebaseManager

        // Pre-fetch/Verify Georgia bird list cache in the background.
        // This ensures data is ready and fresh (within 72h) when needed.
        warmUpBirdCache();

        // Also trigger the eBird API sightings fetch for map data
        triggerEbirdApiSightingsFetch();

        // Retrieve the current Firebase user if they are already authenticated.
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        
        if (currentUser != null) {
            // Check session limit.
            long lastSignInTimestamp = currentUser.getMetadata().getLastSignInTimestamp();
            long thirtyDaysInMillis = TimeUnit.DAYS.toMillis(30);
            long currentTime = System.currentTimeMillis();

            if ((currentTime - lastSignInTimestamp) > thirtyDaysInMillis) {
                FirebaseAuth.getInstance().signOut();
                showWelcomeScreen();
            } else {
                startActivity(new Intent(WelcomeActivity.this, HomeActivity.class));
                finish();
            }
            return;
        }

        showWelcomeScreen();
    }

    /**
     * Triggers the Cloud Function to check and update the regional bird list.
     * This runs silently in the background when the app starts.
     */
    private void warmUpBirdCache() {
        Log.d(TAG, "Starting bird cache warm-up...");
        EbirdApi ebirdApi = new EbirdApi();
        ebirdApi.fetchGeorgiaBirdList(new EbirdApi.EbirdCallback() {
            @Override
            public void onSuccess(List<org.json.JSONObject> birds) {
                Log.i(TAG, "Bird cache is warm. Loaded " + birds.size() + " birds.");
            }

            @Override
            public void onFailure(Exception e) {
                Log.e(TAG, "Bird cache warm-up failed: " + e.getMessage());
            }
        });
    }

    /**
     * Triggers the Cloud Function to fetch and store recent eBird API sightings
     * for map population. This runs silently in the background on app warmup.
     */
    private void triggerEbirdApiSightingsFetch() {
        Log.d(TAG, "Starting eBird API sightings fetch for map data...");
        firebaseManager.triggerEbirdDataFetch(task -> {
            if (task.isSuccessful()) {
                HttpsCallableResult result = task.getResult();
                // You can log the result message from the Cloud Function if needed
                Log.i(TAG, "eBird API sightings fetch completed: " + result.getData());
            } else {
                Log.e(TAG, "eBird API sightings fetch failed: ", task.getException());
            }
        });
    }

    private void showWelcomeScreen() {
        setContentView(R.layout.activity_main);

        Button btnSignup = findViewById(R.id.btnSignup);
        TextView tvAlready = findViewById(R.id.tvAlready);

        btnSignup.setOnClickListener(v -> {
            startActivity(new Intent(WelcomeActivity.this, SignUpActivity.class));
        });

        tvAlready.setOnClickListener(v -> {
            startActivity(new Intent(WelcomeActivity.this, LoginActivity.class));
        });
    }
}
