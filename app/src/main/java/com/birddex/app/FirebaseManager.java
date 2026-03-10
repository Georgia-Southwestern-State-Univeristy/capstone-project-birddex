package com.birddex.app;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;
import com.google.firebase.functions.HttpsCallableResult;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FirebaseManager: Central Firebase helper that hides Firestore, Storage, Auth, and Cloud Function details from the UI screens.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class FirebaseManager {

    private static final String TAG = "FirebaseManager";
    private final FirebaseAuth mAuth;
    private final FirebaseFirestore db;
    private final FirebaseFunctions mFunctions;
    private Context context;

    // -------------------------------------------------------------------------
    // Interfaces
    // -------------------------------------------------------------------------

    public interface AuthListener {
        void onSuccess(FirebaseUser user);
        void onFailure(String errorMessage);
        void onUsernameTaken();
        void onEmailTaken();
    }

    public interface UsernameAndEmailCheckListener {
        void onCheckComplete(boolean isUsernameAvailable, boolean isEmailAvailable);
        void onFailure(String errorMessage);
    }

    public interface PfpChangeLimitListener {
        void onSuccess(int pfpChangesToday, Date pfpCooldownResetTimestamp);
        void onFailure(String errorMessage);
        void onLimitExceeded();
    }

    public interface OpenAiRequestLimitListener {
        void onCheckComplete(boolean hasRequestsRemaining, int remaining, Date openAiCooldownResetTimestamp);
        void onFailure(String errorMessage);
    }

    public interface OpenAiModerationListener {
        void onSuccess(boolean isAppropriate, String moderationReason);
        void onFailure(String errorMessage);
    }

    public interface LeaderboardListener {
        void onDataLoaded(List<Map<String, Object>> leaderboard);
        void onError(String error);
    }

    /**
     * Callback for recordBirdSighting CF.
     * onCooldown() is called when the same species was already recorded within 24 h —
     * the bird is still saved to the collection, just not the heatmap.
     */
    public interface BirdSightingListener {
        void onRecorded();
        void onCooldown();
        void onFailure(String errorMessage);
    }

    /**
     * Callback for recordForumPost CF.
     * onLimitReached() is called when the user has already posted 3 times today.
     */
    public interface ForumPostLimitListener {
        void onAllowed(int remaining);
        void onLimitReached();
        void onFailure(String errorMessage);
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    public FirebaseManager(Context context) {
        this.context = context.getApplicationContext();
        mAuth = FirebaseAuth.getInstance();
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db = FirebaseFirestore.getInstance();
        mFunctions = FirebaseFunctions.getInstance();
    }

    // -------------------------------------------------------------------------
    // AUTH & USER PROFILE
    // -------------------------------------------------------------------------

    /**
     * FIX #25: Sequential client-side check followed by Auth creation followed by
     * Firestore initialization had a race condition where a username could be stolen.
     * Re-routed creation through a more robust sequence:
     * 1. Auth creation (sets the UID)
     * 2. Atomic initializeUser CF (handles username uniqueness via Firestore transaction)
     */
    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void createAccount(String username, String email, String password, AuthListener listener) {
        Log.d(TAG, "Attempting to create account for email: " + email);
        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(authTask -> {
                    if (authTask.isSuccessful()) {
                        FirebaseUser firebaseUser = mAuth.getCurrentUser();
                        if (firebaseUser != null) {
                            Log.d(TAG, "Auth user created: " + firebaseUser.getUid());
                            firebaseUser.sendEmailVerification()
                                    .addOnCompleteListener(eTask -> Log.d(TAG, "Verification sent: " + eTask.isSuccessful()));
                            initializeUser(username, email, task -> {
                                if (task.isSuccessful()) {
                                    Log.d(TAG, "User profile initialized atomically.");
                                    listener.onSuccess(firebaseUser);
                                } else {
                                    Exception e = task.getException();
                                    Log.e(TAG, "Failed to initialize user document.", e);
                                    if (e instanceof FirebaseFunctionsException) {
                                        FirebaseFunctionsException ffe = (FirebaseFunctionsException) e;
                                        if (ffe.getCode() == FirebaseFunctionsException.Code.ALREADY_EXISTS) {
                                            // Persist the new state so the action is saved outside the current screen.
                                            firebaseUser.delete();
                                            listener.onUsernameTaken();
                                            return;
                                        }
                                    }
                                    listener.onFailure("Profile setup failed. Please try again.");
                                }
                            });
                        }
                    } else {
                        String error = authTask.getException() != null ? authTask.getException().getMessage() : "Sign up failed.";
                        Log.e(TAG, "Firebase Auth failed: " + error);
                        listener.onFailure(error);
                    }
                });
    }

    /**
     * Initializes helpers, adapters, listeners, or default values used by the rest of this file.
     */
    public void initializeUser(String username, String email, OnCompleteListener<Boolean> listener) {
        Log.d(TAG, "Calling initializeUser Cloud Function for: " + username);
        Map<String, Object> data = new HashMap<>();
        data.put("username", username);
        data.put("email", email);
        mFunctions.getHttpsCallable("initializeUser").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "initializeUser CF success.");
                listener.onComplete(Tasks.forResult(true));
            } else {
                Log.e(TAG, "initializeUser CF failure.", task.getException());
                listener.onComplete(Tasks.forException(task.getException()));
            }
        });
    }

    /**
     * Main logic block for this part of the feature.
     */
    public void checkUsernameAndEmailAvailability(String username, String email, UsernameAndEmailCheckListener listener) {
        Log.d(TAG, "Checking availability for: " + username + " / " + email);
        Map<String, Object> data = new HashMap<>();
        data.put("username", username);
        data.put("email", email);
        mFunctions.getHttpsCallable("checkUsernameAndEmailAvailability").call(data)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        Map<String, Object> res = (Map<String, Object>) task.getResult().getData();
                        boolean uAvail = (Boolean) res.get("isUsernameAvailable");
                        boolean eAvail = (Boolean) res.get("isEmailAvailable");
                        Log.d(TAG, "Availability check - Username: " + uAvail + ", Email: " + eAvail);
                        listener.onCheckComplete(uAvail, eAvail);
                    } else {
                        String error = task.getException() != null ? task.getException().getMessage() : "Check failed.";
                        Log.e(TAG, "checkUsernameAndEmailAvailability failed: " + error);
                        listener.onFailure(error);
                    }
                });
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    public void getUserProfile(String userId, OnCompleteListener<DocumentSnapshot> listener) {
        Log.d(TAG, "Fetching user profile: " + userId);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("users").document(userId).get().addOnCompleteListener(task -> {
            if (task.isSuccessful()) Log.d(TAG, "Profile fetch success for: " + userId);
            else Log.e(TAG, "Profile fetch failed for: " + userId, task.getException());
            listener.onComplete(task);
        });
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public void getUsername(String userId, OnCompleteListener<String> listener) {
        Log.d(TAG, "Fetching username for: " + userId);
        getUserProfile(userId, task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                DocumentSnapshot document = task.getResult();
                if (document.exists()) {
                    String username = document.getString("username");
                    Log.d(TAG, "Username found: " + username);
                    if (username != null) listener.onComplete(Tasks.forResult(username));
                    else listener.onComplete(Tasks.forException(new IllegalStateException("Username field missing.")));
                } else {
                    Log.w(TAG, "User document does not exist for: " + userId);
                    listener.onComplete(Tasks.forException(new IllegalStateException("User not found.")));
                }
            } else {
                Log.e(TAG, "Failed to fetch profile for username lookup.", task.getException());
                listener.onComplete(Tasks.forException(task.getException()));
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
    public void updateUserProfile(User updatedUser, AuthListener listener) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) { Log.e(TAG, "Cannot update profile: No user authenticated."); return; }
        Log.d(TAG, "Updating profile for: " + currentUser.getUid());
        Map<String, Object> updates = new HashMap<>();
        if (updatedUser.getUsername() != null) updates.put("username", updatedUser.getUsername());
        if (updatedUser.getProfilePictureUrl() != null) updates.put("profilePictureUrl", updatedUser.getProfilePictureUrl());
        if (updatedUser.getBio() != null) updates.put("bio", updatedUser.getBio());
        if (updatedUser.getEmail() != null) updates.put("email", updatedUser.getEmail());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("users").document(currentUser.getUid()).update(updates).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "Profile updated successfully in Firestore.");
                listener.onSuccess(currentUser);
            } else {
                String error = task.getException() != null ? task.getException().getMessage() : "Update failed.";
                Log.e(TAG, "Profile update failed: " + error);
                listener.onFailure(error);
            }
        });
    }

    /**
     * FIX #13: Route profile updates (especially username) through the atomic
     * initializeUser Cloud Function to ensure username uniqueness.
     */
    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     */
    public void updateUserProfileAtomic(User updatedUser, AuthListener listener) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) { Log.e(TAG, "Cannot update profile: No user authenticated."); return; }
        Map<String, Object> data = new HashMap<>();
        data.put("username", updatedUser.getUsername());
        data.put("bio", updatedUser.getBio());
        data.put("profilePictureUrl", updatedUser.getProfilePictureUrl());
        mFunctions.getHttpsCallable("initializeUser").call(data)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "Profile updated atomically via initializeUser CF.");
                        listener.onSuccess(currentUser);
                    } else {
                        Exception e = task.getException();
                        if (e instanceof FirebaseFunctionsException) {
                            FirebaseFunctionsException ffe = (FirebaseFunctionsException) e;
                            if (ffe.getCode() == FirebaseFunctionsException.Code.ALREADY_EXISTS) {
                                listener.onUsernameTaken();
                                return;
                            }
                        }
                        String error = e != null ? e.getMessage() : "Update failed.";
                        Log.e(TAG, "Atomic profile update failed: " + error);
                        listener.onFailure(error);
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
    public void updateSessionId(String userId, String sessionId, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Updating session ID for user: " + userId);

        Map<String, Object> updates = new HashMap<>();
        updates.put("currentSessionId", sessionId);

        db.collection("users")
                .document(userId)
                .set(updates, SetOptions.merge())
                .addOnCompleteListener(listener);
    }

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Because it uses a snapshot listener, this method keeps the UI synced with live Firestore
     * updates instead of doing a one-time read.
     */
    public ListenerRegistration listenToSessionId(String userId, EventListener<DocumentSnapshot> listener) {
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        return db.collection("users").document(userId).addSnapshotListener(listener);
    }

    /**
     * Main logic block for this part of the feature.
     */
    public void archiveAndDeleteUser(OnCompleteListener<HttpsCallableResult> listener) {
        Log.d(TAG, "Calling archiveAndDeleteUser Cloud Function.");
        mFunctions.getHttpsCallable("archiveAndDeleteUser").call().addOnCompleteListener(task -> {
            if (task.isSuccessful()) Log.d(TAG, "archiveAndDeleteUser CF success.");
            else Log.e(TAG, "archiveAndDeleteUser CF failure.", task.getException());
            listener.onComplete(task);
        });
    }

    /**
     * Removes data/listeners/items and then updates local state so the UI matches the deletion.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void deleteUser(String userId, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Deleting user document: " + userId);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("users").document(userId).delete().addOnCompleteListener(task -> {
            if (task.isSuccessful()) Log.d(TAG, "User doc deleted.");
            else Log.e(TAG, "User doc deletion failed.", task.getException());
            listener.onComplete(task);
        });
    }

    /**
     * Main logic block for this part of the feature.
     */
    public void signIn(String email, String password, AuthListener listener) {
        Log.d(TAG, "Attempting sign in for: " + email);
        mAuth.signInWithEmailAndPassword(email, password).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "Sign in successful.");
                listener.onSuccess(mAuth.getCurrentUser());
            } else {
                String error = task.getException() != null ? task.getException().getMessage() : "Sign in failed.";
                Log.e(TAG, "Sign in failed: " + error);
                listener.onFailure(error);
            }
        });
    }

    /**
     * Main logic block for this part of the feature.
     */
    public void sendPasswordResetEmail(String email, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Sending password reset to: " + email);
        mAuth.sendPasswordResetEmail(email).addOnCompleteListener(task -> {
            if (task.isSuccessful()) Log.d(TAG, "Reset email sent.");
            else Log.e(TAG, "Reset email failed.", task.getException());
            listener.onComplete(task);
        });
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     */
    public void updateUserEmail(String newEmail, OnCompleteListener<Void> listener) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) {
            Log.d(TAG, "Updating email to: " + newEmail);
            user.updateEmail(newEmail).addOnCompleteListener(task -> {
                if (task.isSuccessful()) Log.d(TAG, "Email updated in Auth.");
                else Log.e(TAG, "Email update failed.", task.getException());
                listener.onComplete(task);
            });
        }
    }

    // -------------------------------------------------------------------------
    // LIGHTWEIGHT SERVER-SIDE SYSTEMS
    // -------------------------------------------------------------------------

    /**
     * Main logic block for this part of the feature.
     */
    public void followUser(String targetUserId, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Following user: " + targetUserId);
        Map<String, Object> data = new HashMap<>();
        data.put("targetUserId", targetUserId);
        data.put("action", "follow");
        mFunctions.getHttpsCallable("toggleFollow").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) Log.d(TAG, "Follow success via CF.");
            else Log.e(TAG, "Follow failed via CF.", task.getException());
            if (listener != null) listener.onComplete(task.isSuccessful() ? Tasks.forResult(null) : Tasks.forException(task.getException()));
        });
    }

    /**
     * Main logic block for this part of the feature.
     */
    public void unfollowUser(String targetUserId, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Unfollowing user: " + targetUserId);
        Map<String, Object> data = new HashMap<>();
        data.put("targetUserId", targetUserId);
        data.put("action", "unfollow");
        mFunctions.getHttpsCallable("toggleFollow").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) Log.d(TAG, "Unfollow success via CF.");
            else Log.e(TAG, "Unfollow failed via CF.", task.getException());
            if (listener != null) listener.onComplete(task.isSuccessful() ? Tasks.forResult(null) : Tasks.forException(task.getException()));
        });
    }

    /**
     * Main logic block for this part of the feature.
     */
    public void addReport(Report report, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Submitting report for target: " + report.getTargetId());
        Map<String, Object> data = new HashMap<>();
        data.put("targetId", report.getTargetId());
        data.put("targetType", report.getTargetType());
        data.put("reason", report.getReason());
        mFunctions.getHttpsCallable("submitReport").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) Log.d(TAG, "Report submitted success via CF.");
            else Log.e(TAG, "Report submission failed via CF.", task.getException());
            if (listener != null) listener.onComplete(task.isSuccessful() ? Tasks.forResult(null) : Tasks.forException(task.getException()));
        });
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public void getLeaderboard(LeaderboardListener listener) {
        Log.d(TAG, "Fetching leaderboard via Cloud Function.");
        mFunctions.getHttpsCallable("getLeaderboard").call().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                Log.d(TAG, "Leaderboard fetched success.");
                List<Map<String, Object>> list = (List<Map<String, Object>>) task.getResult().getData();
                listener.onDataLoaded(list);
            } else {
                String error = task.getException() != null ? task.getException().getMessage() : "Failed to fetch leaderboard.";
                Log.e(TAG, "Leaderboard fetch failed: " + error);
                listener.onError(error);
            }
        });
    }

    // -------------------------------------------------------------------------
    // SIGHTING & POST LIMIT CFs
    // -------------------------------------------------------------------------

    /**
     * Calls the `recordBirdSighting` Cloud Function.
     *
     * The CF enforces 1 sighting per user per species per 24 h using a server-side
     * Firestore transaction. All fields are written atomically — the cooldown timestamp
     * and the userBirdSightings doc are committed in one operation, so there is no
     * window where a concurrent call for the same user+species can slip through.
     *
     * onCooldown() is non-fatal: the bird is already saved to the user's collection.
     * The heatmap contribution is simply skipped until the cooldown expires.
     *
     * @param birdId        Species code / bird document ID
     * @param commonName    Display name (stored in the sighting doc)
     * @param userBirdId    The userBirds doc ID created in the preceding atomic transaction
     * @param latitude      GPS latitude (0.0 if unavailable)
     * @param longitude     GPS longitude (0.0 if unavailable)
     * @param state         State string (may be null)
     * @param locality      Locality string (may be null)
     * @param country       Country code, e.g. "US" (may be null)
     * @param quantity      Quantity string, e.g. "1" (may be null)
     * @param timestampMs   Epoch ms of the sighting (use System.currentTimeMillis() if unsure)
     * @param listener      Result callback
     */
    public void recordBirdSighting(
            String birdId,
            String commonName,
            String userBirdId,
            double latitude,
            double longitude,
            String state,
            String locality,
            String country,
            String quantity,
            long timestampMs,
            BirdSightingListener listener) {

        Log.d(TAG, "Calling recordBirdSighting CF for birdId=" + birdId);

        Map<String, Object> data = new HashMap<>();
        data.put("birdId",     birdId);
        data.put("commonName", commonName != null ? commonName : "");
        data.put("userBirdId", userBirdId != null ? userBirdId : "");
        data.put("latitude",   latitude);
        data.put("longitude",  longitude);
        data.put("state",      state    != null ? state    : "");
        data.put("locality",   locality != null ? locality : "");
        data.put("country",    country  != null ? country  : "US");
        data.put("quantity",   quantity != null ? quantity : "1");
        data.put("timestamp",  timestampMs);

        /**
         * Returns the current value/state this class needs somewhere else in the app.
         */
        mFunctions.getHttpsCallable("recordBirdSighting").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                Map<String, Object> res = (Map<String, Object>) task.getResult().getData();
                boolean recorded = res != null && Boolean.TRUE.equals(res.get("recorded"));
                if (recorded) {
                    Log.d(TAG, "recordBirdSighting: sighting recorded for birdId=" + birdId);
                    listener.onRecorded();
                } else {
                    Log.d(TAG, "recordBirdSighting: cooldown active for birdId=" + birdId);
                    listener.onCooldown();
                }
            } else {
                String error = task.getException() != null ? task.getException().getMessage() : "recordBirdSighting failed.";
                Log.e(TAG, "recordBirdSighting CF failed: " + error);
                listener.onFailure(error);
            }
        });
    }

    /**
     * Calls the `recordForumPost` Cloud Function.
     *
     * The CF enforces 3 posts per user per UTC calendar day using a server-side
     * Firestore transaction. The count resets automatically at midnight UTC.
     * Concurrent taps cannot both slip through because the read+increment
     * happen atomically inside a single transaction.
     *
     * Call this BEFORE uploading any image or writing the post doc so that
     * Storage quota is not burned on a post that will be rejected.
     *
     * @param listener Result callback
     */
    /**
     * Main logic block for this part of the feature.
     */
    public void recordForumPost(ForumPostLimitListener listener) {
        Log.d(TAG, "Calling recordForumPost CF.");
        mFunctions.getHttpsCallable("recordForumPost").call().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                Map<String, Object> res = (Map<String, Object>) task.getResult().getData();
                boolean allowed = res != null && Boolean.TRUE.equals(res.get("allowed"));
                if (allowed) {
                    int remaining = res.get("remaining") != null
                            ? ((Number) res.get("remaining")).intValue() : 0;
                    Log.d(TAG, "recordForumPost: allowed, remaining=" + remaining);
                    listener.onAllowed(remaining);
                } else {
                    Log.d(TAG, "recordForumPost: daily limit reached.");
                    listener.onLimitReached();
                }
            } else {
                String error = task.getException() != null ? task.getException().getMessage() : "recordForumPost failed.";
                Log.e(TAG, "recordForumPost CF failed: " + error);
                listener.onFailure(error);
            }
        });
    }

    // -------------------------------------------------------------------------
    // FORUM METHODS
    // -------------------------------------------------------------------------

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void addForumPost(ForumPost post, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Adding forum post: " + post.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("forumThreads").document(post.getId()).set(post).addOnCompleteListener(task -> {
            if (task.isSuccessful()) Log.d(TAG, "Post added successfully.");
            else Log.e(TAG, "Failed to add post.", task.getException());
            listener.onComplete(task);
        });
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void updateForumPost(String postId, Map<String, Object> updates, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Updating forum post: " + postId);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("forumThreads").document(postId).update(updates).addOnCompleteListener(task -> {
            if (task.isSuccessful()) Log.d(TAG, "Post updated successfully.");
            else Log.e(TAG, "Failed to update post.", task.getException());
            listener.onComplete(task);
        });
    }

    /**
     * Removes data/listeners/items and then updates local state so the UI matches the deletion.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void deleteForumPost(String postId, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Deleting forum post: " + postId);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("forumThreads").document(postId).delete().addOnCompleteListener(task -> {
            if (task.isSuccessful()) Log.d(TAG, "Post deleted.");
            else Log.e(TAG, "Failed to delete post.", task.getException());
            listener.onComplete(task);
        });
    }

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    public void addForumComment(String postId, ForumComment comment, OnCompleteListener<DocumentReference> listener) {
        Log.d(TAG, "Adding comment to post: " + postId);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("forumThreads").document(postId).collection("comments").add(comment).addOnCompleteListener(task -> {
            if (task.isSuccessful()) Log.d(TAG, "Comment added.");
            else Log.e(TAG, "Failed to add comment.", task.getException());
            listener.onComplete(task);
        });
    }

    /**
     * Removes data/listeners/items and then updates local state so the UI matches the deletion.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void deleteForumComment(String postId, String commentId, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Deleting comment: " + commentId + " from post: " + postId);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("forumThreads").document(postId).collection("comments").document(commentId).delete().addOnCompleteListener(task -> {
            if (task.isSuccessful()) Log.d(TAG, "Comment deleted.");
            else Log.e(TAG, "Failed to delete comment.", task.getException());
            listener.onComplete(task);
        });
    }

    // -------------------------------------------------------------------------
    // BIRD & SIGHTING METHODS
    // -------------------------------------------------------------------------

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void addBird(Bird bird, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Adding bird: " + bird.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("birds").document(bird.getId()).set(bird).addOnCompleteListener(task -> {
            if (task.isSuccessful()) Log.d(TAG, "Bird added.");
            else Log.e(TAG, "Failed to add bird.", task.getException());
            listener.onComplete(task);
        });
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    public void getBirdById(String birdId, OnCompleteListener<DocumentSnapshot> listener) {
        Log.d(TAG, "Fetching bird: " + birdId);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("birds").document(birdId).get().addOnCompleteListener(listener);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    public void getAllBirds(OnCompleteListener<QuerySnapshot> listener) {
        Log.d(TAG, "Fetching all birds.");
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("birds").get().addOnCompleteListener(listener);
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void updateBird(Bird bird, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Updating bird: " + bird.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("birds").document(bird.getId()).set(bird).addOnCompleteListener(listener);
    }

    /**
     * Removes data/listeners/items and then updates local state so the UI matches the deletion.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void deleteBird(String birdId, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Deleting bird: " + birdId);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("birds").document(birdId).delete().addOnCompleteListener(listener);
    }

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void addUserBird(UserBird userBird, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Adding userBird sighting: " + userBird.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("userBirds").document(userBird.getId()).set(userBird).addOnCompleteListener(task -> {
            if (task.isSuccessful()) Log.d(TAG, "userBird added.");
            else Log.e(TAG, "Failed to add userBird.", task.getException());
            listener.onComplete(task);
        });
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    public void getUserBirdById(String userBirdId, OnCompleteListener<DocumentSnapshot> listener) {
        Log.d(TAG, "Fetching userBird: " + userBirdId);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("userBirds").document(userBirdId).get().addOnCompleteListener(listener);
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void updateUserBird(UserBird userBird, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Updating userBird: " + userBird.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("userBirds").document(userBird.getId()).set(userBird).addOnCompleteListener(listener);
    }

    /**
     * Removes data/listeners/items and then updates local state so the UI matches the deletion.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void deleteUserBird(String userBirdId, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Deleting userBird: " + userBirdId);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("userBirds").document(userBirdId).delete().addOnCompleteListener(listener);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    public Task<QuerySnapshot> getAllUserBirdSightingsForCurrentUser() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            Log.e(TAG, "Cannot fetch sightings: No authenticated user.");
            return Tasks.forException(new IllegalStateException("User not authenticated."));
        }
        Log.d(TAG, "Fetching all sightings for user: " + currentUser.getUid());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        return db.collection("userBirdSightings").whereEqualTo("userId", currentUser.getUid()).get();
    }

    // -------------------------------------------------------------------------
    // COLLECTION SLOT & IMAGE METHODS
    // -------------------------------------------------------------------------

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void addCollectionSlot(String userId, String collectionSlotId, CollectionSlot collectionSlot, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Adding collection slot: " + collectionSlotId + " for user: " + userId);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("users").document(userId).collection("collectionSlot").document(collectionSlotId).set(collectionSlot).addOnCompleteListener(listener);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    public void getCollectionSlotById(String userId, String collectionSlotId, OnCompleteListener<DocumentSnapshot> listener) {
        Log.d(TAG, "Fetching collection slot: " + collectionSlotId);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("users").document(userId).collection("collectionSlot").document(collectionSlotId).get().addOnCompleteListener(listener);
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void updateCollectionSlot(String userId, CollectionSlot collectionSlot, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Updating collection slot: " + collectionSlot.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("users").document(userId).collection("collectionSlot").document(collectionSlot.getId()).set(collectionSlot).addOnCompleteListener(listener);
    }

    /**
     * Removes data/listeners/items and then updates local state so the UI matches the deletion.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void deleteCollectionSlot(String userId, String collectionSlotId, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Deleting collection slot: " + collectionSlotId);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("users").document(userId).collection("collectionSlot").document(collectionSlotId).delete().addOnCompleteListener(listener);
    }

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void addUserBirdImage(String userId, String userBirdImageId, UserBirdImage userBirdImage, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Adding bird image record: " + userBirdImageId);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("users").document(userId).collection("userBirdImage").document(userBirdImageId).set(userBirdImage).addOnCompleteListener(listener);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    public void getUserBirdImageById(String userId, String userBirdImageId, OnCompleteListener<DocumentSnapshot> listener) {
        Log.d(TAG, "Fetching bird image record: " + userBirdImageId);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("users").document(userId).collection("userBirdImage").document(userBirdImageId).get().addOnCompleteListener(listener);
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void updateUserBirdImage(String userId, UserBirdImage userBirdImage, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Updating bird image record: " + userBirdImage.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("users").document(userId).collection("userBirdImage").document(userBirdImage.getId()).set(userBirdImage).addOnCompleteListener(listener);
    }

    /**
     * Removes data/listeners/items and then updates local state so the UI matches the deletion.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void deleteUserBirdImage(String userId, String userBirdImageId, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Deleting bird image record: " + userBirdImageId);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("users").document(userId).collection("userBirdImage").document(userBirdImageId).delete().addOnCompleteListener(listener);
    }

    // -------------------------------------------------------------------------
    // BIRD FACTS & MEDIA
    // -------------------------------------------------------------------------

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void addBirdFact(BirdFact birdFact, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Adding bird fact for: " + birdFact.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("birdFacts").document(birdFact.getId()).set(birdFact).addOnCompleteListener(listener);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    public void getBirdFactById(String birdFactsId, OnCompleteListener<DocumentSnapshot> listener) {
        Log.d(TAG, "Fetching bird fact: " + birdFactsId);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("birdFacts").document(birdFactsId).get().addOnCompleteListener(listener);
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void updateBirdFact(BirdFact birdFact, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Updating bird fact: " + birdFact.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("birdFacts").document(birdFact.getId()).set(birdFact).addOnCompleteListener(listener);
    }

    /**
     * Removes data/listeners/items and then updates local state so the UI matches the deletion.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void deleteBirdFact(String birdFactsId, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Deleting bird fact: " + birdFactsId);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("birdFacts").document(birdFactsId).delete().addOnCompleteListener(listener);
    }

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void addIdentification(Identification identification, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Adding identification record: " + identification.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("identifications").document(identification.getId()).set(identification).addOnCompleteListener(listener);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    public void getIdentificationById(String id, OnCompleteListener<DocumentSnapshot> listener) {
        Log.d(TAG, "Fetching identification: " + id);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("identifications").document(id).get().addOnCompleteListener(listener);
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void updateIdentification(Identification identification, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Updating identification: " + identification.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("identifications").document(identification.getId()).set(identification).addOnCompleteListener(listener);
    }

    /**
     * Removes data/listeners/items and then updates local state so the UI matches the deletion.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void deleteIdentification(String id, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Deleting identification: " + id);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("identifications").document(id).delete().addOnCompleteListener(listener);
    }

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void addMedia(Media media, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Adding media record: " + media.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("media").document(media.getId()).set(media).addOnCompleteListener(listener);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    public void getMediaById(String id, OnCompleteListener<DocumentSnapshot> listener) {
        Log.d(TAG, "Fetching media: " + id);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("media").document(id).get().addOnCompleteListener(listener);
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void updateMedia(Media media, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Updating media: " + media.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("media").document(media.getId()).set(media).addOnCompleteListener(listener);
    }

    /**
     * Removes data/listeners/items and then updates local state so the UI matches the deletion.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void deleteMedia(String id, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Deleting media: " + id);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("media").document(id).delete().addOnCompleteListener(listener);
    }

    // -------------------------------------------------------------------------
    // LOCATIONS & BIRD CARDS
    // -------------------------------------------------------------------------

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public void addLocation(Location location, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Adding location: " + location.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("locations").document(location.getId()).set(location).addOnCompleteListener(listener);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public void getLocationById(String id, OnCompleteListener<DocumentSnapshot> listener) {
        Log.d(TAG, "Fetching location: " + id);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("locations").document(id).get().addOnCompleteListener(listener);
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public void updateLocation(Location location, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Updating location: " + location.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("locations").document(location.getId()).set(location).addOnCompleteListener(listener);
    }

    /**
     * Removes data/listeners/items and then updates local state so the UI matches the deletion.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    public void deleteLocation(String id, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Deleting location: " + id);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("locations").document(id).delete().addOnCompleteListener(listener);
    }

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void addBirdCard(BirdCard birdCard, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Adding bird card: " + birdCard.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("birdCards").document(birdCard.getId()).set(birdCard).addOnCompleteListener(listener);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    public void getBirdCardById(String id, OnCompleteListener<DocumentSnapshot> listener) {
        Log.d(TAG, "Fetching bird card: " + id);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("birdCards").document(id).get().addOnCompleteListener(listener);
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void updateBirdCard(BirdCard birdCard, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Updating bird card: " + birdCard.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("birdCards").document(birdCard.getId()).set(birdCard).addOnCompleteListener(listener);
    }

    /**
     * Removes data/listeners/items and then updates local state so the UI matches the deletion.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void deleteBirdCard(String id, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Deleting bird card: " + id);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("birdCards").document(id).delete().addOnCompleteListener(listener);
    }

    // -------------------------------------------------------------------------
    // SIGHTINGS & HUNTER SIGHTINGS
    // -------------------------------------------------------------------------

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void addUserBirdSighting(UserBirdSighting sighting, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Adding user bird sighting: " + sighting.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("userBirdSightings").document(sighting.getId()).set(sighting).addOnCompleteListener(listener);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    public void getUserBirdSightingById(String id, OnCompleteListener<DocumentSnapshot> listener) {
        Log.d(TAG, "Fetching user bird sighting: " + id);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("userBirdSightings").document(id).get().addOnCompleteListener(listener);
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void updateUserBirdSighting(UserBirdSighting sighting, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Updating user bird sighting: " + sighting.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("userBirdSightings").document(sighting.getId()).set(sighting).addOnCompleteListener(listener);
    }

    /**
     * Removes data/listeners/items and then updates local state so the UI matches the deletion.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void deleteUserBirdSighting(String id, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Deleting user bird sighting: " + id);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("userBirdSightings").document(id).delete().addOnCompleteListener(listener);
    }

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void addHunterSighting(HunterSighting sighting, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Adding hunter sighting: " + sighting.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("hunterSightings").document(sighting.getId()).set(sighting).addOnCompleteListener(listener);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    public void getHunterSightingById(String id, OnCompleteListener<DocumentSnapshot> listener) {
        Log.d(TAG, "Fetching hunter sighting: " + id);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("hunterSightings").document(id).get().addOnCompleteListener(listener);
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void updateHunterSighting(HunterSighting sighting, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Updating hunter sighting: " + sighting.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("hunterSightings").document(sighting.getId()).set(sighting).addOnCompleteListener(listener);
    }

    /**
     * Removes data/listeners/items and then updates local state so the UI matches the deletion.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void deleteHunterSighting(String id, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Deleting hunter sighting: " + id);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("hunterSightings").document(id).delete().addOnCompleteListener(listener);
    }

    // -------------------------------------------------------------------------
    // REPORTS
    // -------------------------------------------------------------------------

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    public void getReportById(String id, OnCompleteListener<DocumentSnapshot> listener) {
        Log.d(TAG, "Fetching report: " + id);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("reports").document(id).get().addOnCompleteListener(listener);
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void updateReport(Report report, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Updating report: " + report.getId());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("reports").document(report.getId()).set(report).addOnCompleteListener(listener);
    }

    /**
     * Removes data/listeners/items and then updates local state so the UI matches the deletion.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    public void deleteReport(String id, OnCompleteListener<Void> listener) {
        Log.d(TAG, "Deleting report: " + id);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("reports").document(id).delete().addOnCompleteListener(listener);
    }

    // -------------------------------------------------------------------------
    // SYNC & LIMIT HELPERS
    // -------------------------------------------------------------------------

    /**
     * Main logic block for this part of the feature.
     */
    public void recordPfpChange(String changeId, PfpChangeLimitListener listener) {
        Log.d(TAG, "Calling recordPfpChange Cloud Function.");
        Map<String, Object> data = new HashMap<>();
        data.put("changeId", changeId);
        mFunctions.getHttpsCallable("recordPfpChange").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                Map<String, Object> res = (Map<String, Object>) task.getResult().getData();
                int remaining = ((Number) res.get("pfpChangesToday")).intValue();
                Log.d(TAG, "recordPfpChange success. Remaining: " + remaining);
                listener.onSuccess(remaining, null);
            } else {
                String error = task.getException() != null ? task.getException().getMessage() : "Record failed.";
                Log.e(TAG, "recordPfpChange failed: " + error);
                listener.onFailure(error);
            }
        });
    }

    /**
     * Main logic block for this part of the feature.
     */
    public void callOpenAiImageModeration(String base64Image, OpenAiModerationListener listener) {
        Log.d(TAG, "Calling moderatePfpImage Cloud Function.");
        Map<String, Object> data = new HashMap<>();
        data.put("imageBase64", base64Image);
        mFunctions.getHttpsCallable("moderatePfpImage").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                Map<String, Object> res = (Map<String, Object>) task.getResult().getData();
                boolean isApp = (Boolean) res.get("isAppropriate");
                String reason = (String) res.get("moderationReason");
                Log.d(TAG, "moderatePfpImage success. Appropriate: " + isApp + ", Reason: " + reason);
                listener.onSuccess(isApp, reason);
            } else {
                String error = task.getException() != null ? task.getException().getMessage() : "Moderation call failed.";
                Log.e(TAG, "moderatePfpImage failed: " + error);
                listener.onFailure(error);
            }
        });
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public void getBirdDetailsAndFacts(String birdId, OnCompleteListener<HttpsCallableResult> listener) {
        Log.d(TAG, "Calling getBirdDetailsAndFacts Cloud Function for: " + birdId);
        Map<String, Object> data = new HashMap<>();
        data.put("birdId", birdId);
        mFunctions.getHttpsCallable("getBirdDetailsAndFacts").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) Log.d(TAG, "getBirdDetailsAndFacts success.");
            else Log.e(TAG, "getBirdDetailsAndFacts failed.", task.getException());
            listener.onComplete(task);
        });
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    public void getOpenAiRequestsRemaining(OpenAiRequestLimitListener listener) {
        if (mAuth.getUid() == null) { Log.e(TAG, "Cannot get OpenAI limits: No user."); return; }
        Log.d(TAG, "Fetching OpenAI limits for: " + mAuth.getUid());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("users").document(mAuth.getUid()).get().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                DocumentSnapshot doc = task.getResult();
                Long remaining = doc.getLong("openAiRequestsRemaining");
                Log.d(TAG, "OpenAI requests remaining: " + remaining);
                listener.onCheckComplete(remaining != null && remaining > 0, remaining != null ? remaining.intValue() : 0, null);
            } else {
                String error = task.getException() != null ? task.getException().getMessage() : "Fetch failed.";
                Log.e(TAG, "Failed to get OpenAI requests remaining: " + error);
                listener.onFailure(error);
            }
        });
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    public void getPfpChangesRemaining(PfpChangeLimitListener listener) {
        if (mAuth.getUid() == null) { Log.e(TAG, "Cannot get PFP limits: No user."); return; }
        Log.d(TAG, "Fetching PFP limits for: " + mAuth.getUid());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("users").document(mAuth.getUid()).get().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                DocumentSnapshot doc = task.getResult();
                Long remaining = doc.getLong("pfpChangesToday");
                Log.d(TAG, "PFP changes remaining: " + remaining);
                listener.onSuccess(remaining != null ? remaining.intValue() : 0, null);
            } else {
                String error = task.getException() != null ? task.getException().getMessage() : "Fetch failed.";
                Log.e(TAG, "Failed to get PFP changes remaining: " + error);
                listener.onFailure(error);
            }
        });
    }

    /**
     * Main logic block for this part of the feature.
     */
    public void triggerEbirdDataFetch(OnCompleteListener<HttpsCallableResult> listener) {
        Log.d(TAG, "Calling triggerEbirdDataFetch Cloud Function.");
        mFunctions.getHttpsCallable("triggerEbirdDataFetch").call().addOnCompleteListener(task -> {
            if (task.isSuccessful()) Log.d(TAG, "triggerEbirdDataFetch success.");
            else Log.e(TAG, "triggerEbirdDataFetch failure.", task.getException());
            listener.onComplete(task);
        });
    }

    /**
     * Main logic block for this part of the feature.
     */
    public void syncGeorgiaBirdList(OnCompleteListener<HttpsCallableResult> listener) {
        Log.d(TAG, "Calling getGeorgiaBirds Cloud Function.");
        mFunctions.getHttpsCallable("getGeorgiaBirds").call().addOnCompleteListener(task -> {
            if (task.isSuccessful()) Log.d(TAG, "getGeorgiaBirds success.");
            else Log.e(TAG, "getGeorgiaBirds failure.", task.getException());
            listener.onComplete(task);
        });
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    public void isFollowing(String targetUserId, OnCompleteListener<Boolean> listener) {
        if (mAuth.getUid() == null) { listener.onComplete(Tasks.forResult(false)); return; }
        Log.d(TAG, "Checking follow status for: " + targetUserId);
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("users").document(mAuth.getUid()).collection("following").document(targetUserId).get().addOnCompleteListener(task -> {
            boolean isFol = task.isSuccessful() && task.getResult() != null && task.getResult().exists();
            Log.d(TAG, "Follow status result: " + isFol);
            listener.onComplete(Tasks.forResult(isFol));
        });
    }
}