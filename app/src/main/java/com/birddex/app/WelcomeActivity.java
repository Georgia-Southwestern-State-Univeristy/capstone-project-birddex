package com.birddex.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.concurrent.TimeUnit;

/**
 * WelcomeActivity serves as the entry point of the app.
 * It handles automatic login redirection and transitions quickly to the next activity.
 * Data loading is now decoupled from this initial screen for better performance.
 */
public class WelcomeActivity extends AppCompatActivity implements NetworkMonitor.NetworkStatusListener {

    private static final String TAG = "WelcomeActivity";
    private FirebaseAuth mAuth;
    private FirebaseManager firebaseManager;
    private NetworkMonitor networkMonitor;
    private boolean isTransitioned = false;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Log.d(TAG, "Notification permission granted");
                } else {
                    Log.d(TAG, "Notification permission denied");
                }
                proceedToNextActivity();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mAuth = FirebaseAuth.getInstance();
        firebaseManager = new FirebaseManager(this);
        networkMonitor = new NetworkMonitor(this, this);

        // Immediately handle notifications and proceed to authentication
        askNotificationPermission();
        setupFCM();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED) {
                proceedToNextActivity();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            proceedToNextActivity();
        }
    }

    private void setupFCM() {
        FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        Log.w(TAG, "Fetching FCM registration token failed", task.getException());
                        return;
                    }

                    String token = task.getResult();
                    Log.d(TAG, "FCM Token: " + token);
                    updateUserToken(token);
                });
    }

    private void updateUserToken(String token) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) {
            FirebaseFirestore.getInstance().collection("users").document(user.getUid())
                    .update("fcmToken", token);
        }
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

        FirebaseUser currentUser = mAuth.getCurrentUser();

        if (currentUser != null) {
            long lastSignInTimestamp = currentUser.getMetadata().getLastSignInTimestamp();
            long thirtyDaysInMillis = TimeUnit.DAYS.toMillis(30);
            long currentTime = System.currentTimeMillis();

            if ((currentTime - lastSignInTimestamp) > thirtyDaysInMillis) {
                mAuth.signOut();
                showWelcomeScreenLayout();
            } else {
                Intent intent = new Intent(WelcomeActivity.this, LoadingActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            }
        } else {
            showWelcomeScreenLayout();
        }
    }

    private void showWelcomeScreenLayout() {
        Button btnSignup = findViewById(R.id.btnSignup);
        TextView tvAlready = findViewById(R.id.tvAlready);

        if (btnSignup != null) {
            btnSignup.setOnClickListener(v -> {
                startActivity(new Intent(WelcomeActivity.this, SignUpActivity.class));
            });
        }

        if (tvAlready != null) {
            tvAlready.setOnClickListener(v -> {
                startActivity(new Intent(WelcomeActivity.this, LoginActivity.class));
            });
        }
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
