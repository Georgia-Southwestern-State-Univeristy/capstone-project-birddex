package com.birddex.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * MyFirebaseMessagingService: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "MyFirebaseMsgService";
    private static final String CHANNEL_ID = "forum_notifications";

    /**
     * Main logic block for this part of the feature.
     */
    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        Log.d(TAG, "From: " + remoteMessage.getFrom());

        if (remoteMessage.getData().size() > 0) {
            Log.d(TAG, "Message data payload: " + remoteMessage.getData());
            sendNotification(remoteMessage.getData());
        }
    }

    /**
     * Main logic block for this part of the feature.
     */
    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.d(TAG, "Refreshed token: " + token);
        updateUserToken(token);
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void updateUserToken(String token) {
        String userId = FirebaseAuth.getInstance().getUid();
        if (userId != null) {
            // Set up or query the Firebase layer that supplies/stores this feature's data.
            FirebaseFirestore.getInstance().collection("users").document(userId)
                    // Persist the new state so the action is saved outside the current screen.
                    .update("fcmToken", token);
        }
    }

    /**
     * Main logic block for this part of the feature.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    private void sendNotification(Map<String, String> data) {
        String title = data.get("title");
        String body = data.get("body");
        String postId = data.get("postId");
        String type = data.get("type");

        Intent intent;

        if ("tracked_bird".equals(type)) {
            String latRaw = data.get("latitude");
            String lngRaw = data.get("longitude");
            String sightingId = data.get("sightingId");
            String birdId = data.get("birdId");
            String commonName = data.get("commonName");

            if (latRaw != null && lngRaw != null) {
                intent = new Intent(this, NearbyHeatmapActivity.class);

                try {
                    intent.putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LAT, Double.parseDouble(latRaw));
                    intent.putExtra(NearbyHeatmapActivity.EXTRA_CENTER_LNG, Double.parseDouble(lngRaw));
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Tracked bird notification had invalid coordinates.", e);
                }

                intent.putExtra("extra_tracked_sighting_id", sightingId);
                intent.putExtra("extra_tracked_bird_id", birdId);
                intent.putExtra("extra_tracked_bird_name", commonName);
            } else {
                intent = new Intent(this, HomeActivity.class);
            }
        } else if (postId != null && !postId.trim().isEmpty()) {
            intent = new Intent(this, PostDetailActivity.class);
            intent.putExtra(PostDetailActivity.EXTRA_POST_ID, postId);
        } else {
            intent = new Intent(this, HomeActivity.class);
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                (int) System.currentTimeMillis(),
                intent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent);

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (notificationManager == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Forum Notifications",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            notificationManager.createNotificationChannel(channel);
        }

        notificationManager.notify((int) System.currentTimeMillis(), notificationBuilder.build());
    }
}