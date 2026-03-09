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
public class WelcomeActivity extends AppCompatActivity implements NetworkMonitor.NetworkStatusListener {

    private static final String TAG = "WelcomeActivity";
    private FirebaseAuth mAuth;
    private NetworkMonitor networkMonitor;
    private boolean isTransitioned = false;
    private boolean isNavigating = false;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                proceedToNextActivity();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mAuth = FirebaseAuth.getInstance();
        networkMonitor = new NetworkMonitor(this, this);

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
                    if (task.isSuccessful() && task.getResult() != null) {
                        updateUserToken(task.getResult());
                    }
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
        networkMonitor.register();
        isNavigating = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        networkMonitor.unregister();
    }

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

        if (btnSignup != null) {
            btnSignup.setOnClickListener(v -> {
                if (isNavigating) return;
                isNavigating = true;
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
