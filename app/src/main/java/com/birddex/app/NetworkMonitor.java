package com.birddex.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

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

    private void updateInitialNetworkStatus() {
        if (connectivityManager == null) {
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

    public boolean isConnected() {
        return isConnected.get();
    }

    public void register() {
        if (connectivityManager != null) {
            NetworkRequest networkRequest = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
            Log.d(TAG, "Network monitor registered.");
        }
    }

    public void unregister() {
        if (connectivityManager != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            Log.d(TAG, "Network monitor unregistered.");
        }
    }
}
