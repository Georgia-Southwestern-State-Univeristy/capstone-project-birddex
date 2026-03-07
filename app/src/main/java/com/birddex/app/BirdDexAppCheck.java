package com.birddex.app;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.ListenerRegistration;

public class BirdDexAppCheck extends Application {
    private static final String TAG = "BirdDexAppCheck";
    private ListenerRegistration sessionListener;
    private FirebaseManager firebaseManager;
    private SessionManager sessionManager;
    private Activity currentActivity;

    @Override
    public void onCreate() {
        super.onCreate();
        FirebaseApp.initializeApp(this);
        
        FirebaseAppCheck firebaseAppCheck = FirebaseAppCheck.getInstance();

        if (BuildConfig.DEBUG) {
            firebaseAppCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance());
        } else {
            firebaseAppCheck.installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance());
        }

        firebaseManager = new FirebaseManager(this);
        sessionManager = new SessionManager(this);

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {}

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                currentActivity = activity;
                // Only start listening if user is logged in and we are not on login/welcome screens
                if (!(activity instanceof LoginActivity || activity instanceof WelcomeActivity || 
                      activity instanceof SignUpActivity || activity instanceof SplashActivity || 
                      activity instanceof LoadingActivity)) {
                    startSessionListener();
                }
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                currentActivity = activity;
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
                if (currentActivity == activity) {
                    currentActivity = null;
                }
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                if (!(activity instanceof LoginActivity || activity instanceof WelcomeActivity || 
                      activity instanceof SignUpActivity || activity instanceof SplashActivity || 
                      activity instanceof LoadingActivity)) {
                    stopSessionListener();
                }
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {}
        });
    }

    private void startSessionListener() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null && sessionListener == null) {
            sessionListener = firebaseManager.listenToSessionId(user.getUid(), (snapshot, e) -> {
                if (e != null) {
                    Log.e(TAG, "Session listener failed", e);
                    return;
                }

                if (snapshot != null && snapshot.exists()) {
                    String remoteSessionId = snapshot.getString("currentSessionId");
                    String localSessionId = sessionManager.getSessionId(user.getUid());

                    if (remoteSessionId != null && localSessionId != null && !remoteSessionId.equals(localSessionId)) {
                        handleOtherDeviceLogin(user.getUid());
                    }
                }
            });
        }
    }

    private void stopSessionListener() {
        if (sessionListener != null) {
            sessionListener.remove();
            sessionListener = null;
        }
    }

    private void handleOtherDeviceLogin(String uid) {
        stopSessionListener();
        
        if (currentActivity != null && !currentActivity.isFinishing()) {
            currentActivity.runOnUiThread(() -> {
                new AlertDialog.Builder(currentActivity)
                        .setTitle("Logged Out")
                        .setMessage("Someone else has signed into this account. You have been signed out.")
                        .setPositiveButton("OK", (dialog, which) -> {
                            logoutAndRedirect(uid);
                        })
                        .setCancelable(false)
                        .show();
            });
        } else {
            // Fallback if no activity is visible
            logoutAndRedirect(uid);
        }
    }

    private void logoutAndRedirect(String uid) {
        FirebaseAuth.getInstance().signOut();
        sessionManager.clearSession(uid);
        
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }
}
