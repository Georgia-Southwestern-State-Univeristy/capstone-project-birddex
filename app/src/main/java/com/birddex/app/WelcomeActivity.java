package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.TimeUnit;

/**
 * WelcomeActivity serves as the entry point of the app.
 * It handles automatic login redirection and transitions quickly to the next activity.
 * Data loading is now decoupled from this initial screen for better performance.
 */
public class WelcomeActivity extends AppCompatActivity implements NetworkMonitor.NetworkStatusListener {

    private static final String TAG = "WelcomeActivity";
    private FirebaseManager firebaseManager;
    private NetworkMonitor networkMonitor;
    private boolean isTransitioned = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        firebaseManager = new FirebaseManager(this);
        networkMonitor = new NetworkMonitor(this, this);

        // Immediately proceed to the next activity after handling authentication.
        // Data loading is now handled in subsequent activities/fragments.
        proceedToNextActivity();
    }

    @Override
    protected void onResume() {
        super.onResume();
        networkMonitor.register(); // Register network monitor
    }

    @Override
    protected void onPause() {
        super.onPause();
        networkMonitor.unregister(); // Unregister network monitor
    }

    private synchronized void proceedToNextActivity() {
        if (isTransitioned) {
            return;
        }
        isTransitioned = true;

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();

        if (currentUser != null) {
            long lastSignInTimestamp = currentUser.getMetadata().getLastSignInTimestamp();
            long thirtyDaysInMillis = TimeUnit.DAYS.toMillis(30);
            long currentTime = System.currentTimeMillis();

            if ((currentTime - lastSignInTimestamp) > thirtyDaysInMillis) {
                FirebaseAuth.getInstance().signOut();
                showWelcomeScreenLayout();
            } else {
                startActivity(new Intent(WelcomeActivity.this, HomeActivity.class));
                finish();
            }
        } else {
            showWelcomeScreenLayout();
        }
    }

    private void showWelcomeScreenLayout() {
        Button btnSignup = findViewById(R.id.btnSignup);
        TextView tvAlready = findViewById(R.id.tvAlready);

        btnSignup.setOnClickListener(v -> {
            startActivity(new Intent(WelcomeActivity.this, SignUpActivity.class));
        });

        tvAlready.setOnClickListener(v -> {
            startActivity(new Intent(WelcomeActivity.this, LoginActivity.class));
        });
    }

    // --- NetworkMonitor Callbacks ---
    @Override
    public void onNetworkAvailable() {
        Log.d(TAG, "Network became available in WelcomeActivity.");
        // No immediate action needed here, subsequent activities will handle data loading.
    }

    @Override
    public void onNetworkLost() {
        Log.e(TAG, "Network lost during WelcomeActivity startup.");
        synchronized (this) {
            if (!isTransitioned) {
                Toast.makeText(this, "Internet connection lost. Please reconnect and restart the app.", Toast.LENGTH_LONG).show();
                finish(); // Exit the app as core data might not load
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // No handlers/runnables to remove here anymore.
    }
}