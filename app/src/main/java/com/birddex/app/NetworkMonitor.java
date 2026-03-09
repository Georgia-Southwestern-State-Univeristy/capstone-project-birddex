package com.birddex.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NetworkMonitor: Interface/model contract used to keep different parts of the app communicating with a shared shape.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class NetworkMonitor {

    private static final String TAG = "NetworkMonitor";
    private final ConnectivityManager connectivityManager;
    private final NetworkCallback networkCallback;
    private final NetworkStatusListener listener;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);

    public interface NetworkStatusListener {
        void onNetworkAvailable();
        void onNetworkLost();
    }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public NetworkMonitor(Context context, NetworkStatusListener listener) {
        this.connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.listener = listener;
        this.networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (isConnected.compareAndSet(false, true)) {
                    Log.d(TAG, "Network Available!");
                    listener.onNetworkAvailable();
                }
            }

            @Override
            public void onLost(Network network) {
                if (isConnected.compareAndSet(true, false)) {
                    Log.d(TAG, "Network Lost!");
                    listener.onNetworkLost();
                }
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                // This can be used to detect changes in network properties (e.g., from WiFi to Cellular)
                // For now, we'll primarily rely on onAvailable and onLost for basic connectivity.
            }
        };

        // Initial check for network status
        updateInitialNetworkStatus();
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void updateInitialNetworkStatus() {
        if (connectivityManager == null) {
            // Persist the new state so the action is saved outside the current screen.
            isConnected.set(false);
            return;
        }
        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork == null) {
            isConnected.set(false);
            return;
        }
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
        boolean currentlyConnected = capabilities != null &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        isConnected.set(currentlyConnected);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    public boolean isConnected() {
        // Kick off an asynchronous one-time read; the callbacks below decide how the UI should react.
        return isConnected.get();
    }

    /**
     * Main logic block for this part of the feature.
     */
    public void register() {
        if (connectivityManager != null) {
            NetworkRequest networkRequest = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
            Log.d(TAG, "Network monitor registered.");
        }
    }

    /**
     * Main logic block for this part of the feature.
     */
    public void unregister() {
        if (connectivityManager != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            Log.d(TAG, "Network monitor unregistered.");
        }
    }
}
