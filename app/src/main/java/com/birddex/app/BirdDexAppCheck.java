package com.birddex.app;

import android.app.Application;

import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;

public class BirdDexAppCheck extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        FirebaseApp.initializeApp(this);
        
        FirebaseAppCheck firebaseAppCheck = FirebaseAppCheck.getInstance();

        if (BuildConfig.DEBUG) {
            // Use the Debug provider for development
            firebaseAppCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance());
        } else {
            // Use Play Integrity for release builds
            firebaseAppCheck.installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance());
        }
    }
}
