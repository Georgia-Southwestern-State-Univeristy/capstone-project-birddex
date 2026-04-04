package com.birddex.app;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

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

/**
 * BirdDexAppCheck: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class BirdDexAppCheck extends Application {
    private static final String TAG = "BirdDexAppCheck";
    private ListenerRegistration sessionListener;
    private FirebaseManager firebaseManager;
    private SessionManager sessionManager;
    private Activity currentActivity;

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        FirebaseApp.initializeApp(this);
        
        FirebaseAppCheck firebaseAppCheck = FirebaseAppCheck.getInstance();

        if (BuildConfig.DEBUG) {
            // Read hardcoded debug token from BuildConfig (populated from local.properties)
            // to prevent it from changing on re-installs.
            // Remember to add this token to the Firebase Console App Check debug tokens list.
            if (BuildConfig.APP_CHECK_DEBUG_TOKEN != null && !BuildConfig.APP_CHECK_DEBUG_TOKEN.isEmpty()) {
                System.setProperty("firebase.appcheck.debug.token", BuildConfig.APP_CHECK_DEBUG_TOKEN);
            }
            firebaseAppCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance());
        } else {
            firebaseAppCheck.installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance());
        }

        // Set up or query the Firebase layer that supplies/stores this feature's data.
        firebaseManager = new FirebaseManager(this);
        sessionManager = new SessionManager(this);

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {}

            /**
             * Runs after {@code onCreate} and after the content view exists, so status-bar insets
             * apply consistently on every BirdDex screen (including activities that used to call
             * {@link SystemBarHelper#applyStandardNavBar(Activity)} before {@code setContentView}).
             * <p>
             * The work is posted to the next frame so we do not reparent the content view while
             * splash/entry animations are running (which would cancel them and block navigation).
             */
            @Override
            public void onActivityPostCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
                final View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
                if (decor == null) {
                    return;
                }
                decor.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    applyBirdDexSystemBarsIfNeeded(activity);
                });
            }

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

    /**
     * One place for edge-to-edge status/navigation handling so all app activities match.
     * Skips embedded library activities (e.g. CanHub cropper) that expect their own window layout.
     */
    private static void applyBirdDexSystemBarsIfNeeded(@NonNull Activity activity) {
        String name = activity.getClass().getName();
        if (!name.startsWith("com.birddex.app.")) {
            return;
        }
        if (activity instanceof SplashActivity) {
            SystemBarHelper.applySplashWindowBars(activity);
        } else {
            SystemBarHelper.applyStandardNavBar(activity);
        }
    }

    /**
     * Main logic block for this part of the feature.
     */
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

                    if (remoteSessionId == null || remoteSessionId.trim().isEmpty()) return;
                    if (localSessionId == null || localSessionId.trim().isEmpty()) return;

                    if (!remoteSessionId.equals(localSessionId)) {
                        handleOtherDeviceLogin(user.getUid());
                    }
                }
            });
        }
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void stopSessionListener() {
        if (sessionListener != null) {
            sessionListener.remove();
            sessionListener = null;
        }
    }

    /**
     * Central handler that reacts to an event/input and decides what the next app action should
     * be.
     */
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

    /**
     * Main logic block for this part of the feature.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    private void logoutAndRedirect(String uid) {
        FirebaseAuth.getInstance().signOut();
        sessionManager.clearSession(uid);
        
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        // Move into the next screen and pass the identifiers/data that screen needs.
        startActivity(intent);
    }
}
