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
 * Fixes: Added isNavigating guard to prevent redundant activity launches.
 */
/**
 * WelcomeActivity: Activity class for one BirdDex screen. It owns screen setup, user actions, and navigation for this part of the app.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class WelcomeActivity extends AppCompatActivity implements NetworkMonitor.NetworkStatusListener {

    private static final String TAG = "WelcomeActivity";
    private FirebaseAuth mAuth;
    private NetworkMonitor networkMonitor;
    private boolean isTransitioned = false;
    private boolean isNavigating = false;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            /**
             * Main logic block for this part of the feature.
             */
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                proceedToNextActivity();
            });

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mAuth = FirebaseAuth.getInstance();
        networkMonitor = new NetworkMonitor(this, this);

        askNotificationPermission();
        setupFCM();
    }

    /**
     * Main logic block for this part of the feature.
     */
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

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    private void setupFCM() {
        FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        updateUserToken(task.getResult());
                    }
                });
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void updateUserToken(String token) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) {
            // Set up or query the Firebase layer that supplies/stores this feature's data.
            FirebaseFirestore.getInstance().collection("users").document(user.getUid())
                    // Persist the new state so the action is saved outside the current screen.
                    .update("fcmToken", token);
        }
    }

    /**
     * Runs when the screen returns to the foreground, so it often refreshes UI state or restarts
     * listeners.
     */
    @Override
    protected void onResume() {
        super.onResume();
        networkMonitor.register();
        isNavigating = false;
    }

    /**
     * Runs when the screen is leaving the foreground, so it is used to pause work or save
     * transient state.
     */
    @Override
    protected void onPause() {
        super.onPause();
        networkMonitor.unregister();
    }

    /**
     * Main logic block for this part of the feature.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    private synchronized void proceedToNextActivity() {
        if (isTransitioned) return;
        isTransitioned = true;

        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            long lastSignIn = currentUser.getMetadata().getLastSignInTimestamp();
            if ((System.currentTimeMillis() - lastSignIn) > TimeUnit.DAYS.toMillis(30)) {
                mAuth.signOut();
                showWelcomeScreenLayout();
            } else {
                // Move into the next screen and pass the identifiers/data that screen needs.
                startActivity(new Intent(WelcomeActivity.this, HomeActivity.class));
                finish();
            }
        } else {
            showWelcomeScreenLayout();
        }
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    private void showWelcomeScreenLayout() {
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        Button btnSignup = findViewById(R.id.btnSignup);
        TextView tvAlready = findViewById(R.id.tvAlready);

        if (btnSignup != null) {
            // Attach the user interaction that should run when this control is tapped.
            btnSignup.setOnClickListener(v -> {
                if (isNavigating) return;
                isNavigating = true;
                // Move into the next screen and pass the identifiers/data that screen needs.
                startActivity(new Intent(WelcomeActivity.this, SignUpActivity.class));
            });
        }

        if (tvAlready != null) {
            tvAlready.setOnClickListener(v -> {
                if (isNavigating) return;
                isNavigating = true;
                startActivity(new Intent(WelcomeActivity.this, LoginActivity.class));
            });
        }
    }

    @Override public void onNetworkAvailable() {}
    @Override public void onNetworkLost() {
        if (!isTransitioned) {
            Toast.makeText(this, "Connection lost.", Toast.LENGTH_LONG).show();
            finish();
        }
    }
}
