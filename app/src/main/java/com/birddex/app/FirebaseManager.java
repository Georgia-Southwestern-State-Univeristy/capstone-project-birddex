package com.birddex.app;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.Source;
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
 * Updated for the forum/profile moderation hardening pass:
 * - forum post/comment create and edit flows go through callable Cloud Functions
 * - username and bio updates are also enforced again on the backend through initializeUser
 * - the frontend still gives instant validation feedback, but the backend now enforces the same rules
 * - unused legacy direct forum create/update helpers were removed so the frontend only uses the backend write path
 */
public class FirebaseManager {

    private static final String TAG = "FirebaseManager";
    private final FirebaseAuth mAuth;
    private final FirebaseFirestore db;
    private final FirebaseFunctions mFunctions;
    private Context context;

    private String extractFunctionsErrorMessage(Exception e, String fallback) {
        if (e == null) return fallback;

        if (e instanceof FirebaseFunctionsException) {
            FirebaseFunctionsException ffe = (FirebaseFunctionsException) e;

            Object details = ffe.getDetails();
            if (details instanceof Map) {
                Object message = ((Map<?, ?>) details).get("message");
                if (message instanceof String && !((String) message).trim().isEmpty()) {
                    return (String) message;
                }
            }

            String msg = ffe.getMessage();
            if (msg != null && !msg.trim().isEmpty()) {
                return msg;
            }
        }

        String msg = e.getMessage();
        return (msg != null && !msg.trim().isEmpty()) ? msg : fallback;
    }

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

    public interface TrackedBirdStateListener {
        void onResult(boolean isTracked);
        void onFailure(String errorMessage);
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

    public interface ForumWriteListener {
        void onSuccess();
        void onFailure(String errorMessage);
    }

    public interface SimpleListener {
        void onSuccess();
        void onFailure(String errorMessage);
    }

    public interface ActionListener {
        void onSuccess();
        void onFailure(String errorMessage);
    }

    public interface LocationIdListener {
        void onSuccess(String locationId);
        void onFailure(String errorMessage);
    }

    public interface ModerationStateListener {
        void onSuccess(Map<String, Object> state);
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

    public FirebaseUser getCurrentUser() {
        return mAuth.getCurrentUser();
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
                                    listener.onFailure(extractFunctionsErrorMessage(e, "Profile setup failed. Please try again."));
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
                        String error = extractFunctionsErrorMessage(task.getException(), "Check failed.");
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
        db.collection("users").document(userId).get(Source.CACHE).addOnCompleteListener(cacheTask -> {
            if (cacheTask.isSuccessful() && cacheTask.getResult() != null && cacheTask.getResult().exists()) {
                Log.d(TAG, "Profile cache hit for: " + userId);
                listener.onComplete(cacheTask);
                return;
            }

            db.collection("users").document(userId).get(Source.SERVER).addOnCompleteListener(serverTask -> {
                if (serverTask.isSuccessful()) Log.d(TAG, "Profile fetch success for: " + userId);
                else Log.e(TAG, "Profile fetch failed for: " + userId, serverTask.getException());
                listener.onComplete(serverTask);
            });
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
        updateUserProfile(updatedUser, null, listener);
    }

    public void updateUserProfile(User updatedUser, String pfpChangeId, AuthListener listener) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) { Log.e(TAG, "Cannot update profile: No user authenticated."); return; }
        Log.d(TAG, "Updating profile for: " + currentUser.getUid());
        Map<String, Object> data = new HashMap<>();
        if (updatedUser.getUsername() != null) data.put("username", updatedUser.getUsername());
        if (updatedUser.getProfilePictureUrl() != null) data.put("profilePictureUrl", updatedUser.getProfilePictureUrl());
        if (updatedUser.getBio() != null) data.put("bio", updatedUser.getBio());
        if (updatedUser.getEmail() != null) data.put("email", updatedUser.getEmail());
        if (pfpChangeId != null && !pfpChangeId.trim().isEmpty()) data.put("pfpChangeId", pfpChangeId.trim());

        mFunctions.getHttpsCallable("updateUserProfile").call(data)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "Profile updated successfully via updateUserProfile CF.");
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
                        String error = extractFunctionsErrorMessage(e, "Update failed.");
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
        updateUserProfile(updatedUser, null, listener);
    }

    public void updateUserProfileAtomic(User updatedUser, String pfpChangeId, AuthListener listener) {
        updateUserProfile(updatedUser, pfpChangeId, listener);
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

    public void updateUserActiveStatus(String userId, boolean hasLoggedInBefore, Date lastActiveAt) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("hasLoggedInBefore", hasLoggedInBefore);
        updates.put("lastActiveAt", lastActiveAt);
        db.collection("users").document(userId).update(updates);
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




    public void trackBird(String birdId, String commonName, String scientificName, String imageUrl, OnCompleteListener<Void> listener) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            if (listener != null) {
                listener.onComplete(Tasks.forException(new IllegalStateException("User not authenticated.")));
            }
            return;
        }

        Log.d(TAG, "Tracking bird: " + birdId + " for user: " + currentUser.getUid());

        Map<String, Object> data = new HashMap<>();
        data.put("birdId", birdId);
        data.put("commonName", commonName != null ? commonName : "");
        data.put("scientificName", scientificName != null ? scientificName : "");
        data.put("imageUrl", imageUrl != null ? imageUrl : "");
        data.put("trackedAt", new Date());

        db.collection("users")
                .document(currentUser.getUid())
                .collection("trackedBirds")
                .document(birdId)
                .set(data, SetOptions.merge())
                .addOnCompleteListener(listener);
    }
    public void untrackBird(String birdId, OnCompleteListener<Void> listener) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            if (listener != null) {
                listener.onComplete(Tasks.forException(new IllegalStateException("User not authenticated.")));
            }
            return;
        }

        Log.d(TAG, "Untracking bird: " + birdId + " for user: " + currentUser.getUid());

        db.collection("users")
                .document(currentUser.getUid())
                .collection("trackedBirds")
                .document(birdId)
                .delete()
                .addOnCompleteListener(listener);
    }


    public void isBirdTracked(String birdId, TrackedBirdStateListener listener) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            listener.onFailure("User not authenticated.");
            return;
        }

        Log.d(TAG, "Checking tracked state for bird: " + birdId + " user: " + currentUser.getUid());

        db.collection("users")
                .document(currentUser.getUid())
                .collection("trackedBirds")
                .document(birdId)
                .get(Source.SERVER)
                .addOnSuccessListener(doc -> listener.onResult(doc.exists()))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to check tracked bird state.", e);
                    listener.onFailure(e.getMessage() != null ? e.getMessage() : "Failed to check tracked state.");
                });
    }


    public void getTrackedBirds(OnCompleteListener<QuerySnapshot> listener) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            if (listener != null) {
                listener.onComplete(Tasks.forException(new IllegalStateException("User not authenticated.")));
            }
            return;
        }

        Log.d(TAG, "Fetching tracked birds for user: " + currentUser.getUid());

        db.collection("users")
                .document(currentUser.getUid())
                .collection("trackedBirds")
                .get(Source.SERVER)
                .addOnCompleteListener(listener);
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
    // MODERATION & APPEALS
    // -------------------------------------------------------------------------

    /*** Fetches the moderation status of the currently logged-in user.
     */
    public void getMyModerationState(ModerationStateListener listener) {
        Log.d(TAG, "Calling getMyModerationState Cloud Function.");
        mFunctions.getHttpsCallable("getMyModerationState").call()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        Log.d(TAG, "getMyModerationState success.");
                        listener.onSuccess((Map<String, Object>) task.getResult().getData());
                    } else {
                        String error = extractFunctionsErrorMessage(task.getException(), "Failed to fetch moderation state.");
                        Log.e(TAG, "getMyModerationState failure: " + error);
                        listener.onFailure(error);
                    }
                });
    }

    /**
     * Submits an appeal for a specific moderation event.
     */
    public void submitModerationAppeal(String eventId, String reason, ForumWriteListener listener) {
        Log.d(TAG, "Calling submitModerationAppeal Cloud Function for event: " + eventId);
        Map<String, Object> data = new HashMap<>();
        data.put("moderationEventId", eventId);
        data.put("appealText", reason);
        mFunctions.getHttpsCallable("submitModerationAppeal").call(data)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "submitModerationAppeal success.");
                        listener.onSuccess();
                    } else {
                        String error = extractFunctionsErrorMessage(task.getException(), "Failed to submit appeal.");
                        Log.e(TAG, "submitModerationAppeal failure: " + error);
                        listener.onFailure(error);
                    }
                });
    }

    // -------------------------------------------------------------------------
    // MODERATION & APPEALS
    // -------------------------------------------------------------------------

    /**
     * Interface for receiving a list of pending moderation appeals.
     */
    public interface AppealsListListener {
        void onSuccess(List<Map<String, Object>> appeals);
        void onFailure(String errorMessage);
    }

    /**
     * Interface for receiving a list of pending moderation reports.
     */
    public interface ReportsListListener {
        void onSuccess(List<Map<String, Object>> reports);
        void onFailure(String errorMessage);
    }

    /**
     * Fetches all pending moderation appeals from the server via Cloud Function.
     */
    public void getPendingModerationAppeals(AppealsListListener listener) {
        Log.d(TAG, "Calling getPendingModerationAppeals Cloud Function.");
        mFunctions.getHttpsCallable("getPendingModerationAppeals").call()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        Log.d(TAG, "getPendingModerationAppeals success.");

                        Object resultData = task.getResult().getData();
                        List<Map<String, Object>> appeals = new ArrayList<>();

                        if (resultData instanceof Map) {
                            Object appealsData = ((Map<?, ?>) resultData).get("appeals");
                            if (appealsData instanceof List) {
                                appeals = (List<Map<String, Object>>) appealsData;
                            }
                        } else if (resultData instanceof List) {
                            // Backward-compatible fallback in case the callable ever returns a raw list.
                            appeals = (List<Map<String, Object>>) resultData;
                        }

                        listener.onSuccess(appeals);
                    } else {
                        String error = extractFunctionsErrorMessage(task.getException(), "Failed to fetch pending appeals.");
                        Log.e(TAG, "getPendingModerationAppeals failure: " + error);
                        listener.onFailure(error);
                    }
                });
    }

    /**
     * Fetches all pending moderation reports from the server via Cloud Function.
     */
    public void getPendingModerationReports(ReportsListListener listener) {
        Log.d(TAG, "Calling getPendingModerationReports Cloud Function.");
        mFunctions.getHttpsCallable("getPendingModerationReports").call()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        Log.d(TAG, "getPendingModerationReports success.");

                        Object resultData = task.getResult().getData();
                        List<Map<String, Object>> reports = new ArrayList<>();

                        if (resultData instanceof Map) {
                            Object reportsData = ((Map<?, ?>) resultData).get("reports");
                            if (reportsData instanceof List) {
                                reports = (List<Map<String, Object>>) reportsData;
                            }
                        } else if (resultData instanceof List) {
                            reports = (List<Map<String, Object>>) resultData;
                        }

                        listener.onSuccess(reports);
                    } else {
                        String error = extractFunctionsErrorMessage(task.getException(), "Failed to fetch pending reports.");
                        Log.e(TAG, "getPendingModerationReports failure: " + error);
                        listener.onFailure(error);
                    }
                });
    }

    /**
     * Submits a moderator's decision on a specific appeal.
     * Matches the ForumWriteListener used in ModeratorActivity.
     */
    public void reviewModerationAppeal(String appealId, String decision, String note, ForumWriteListener listener) {
        Log.d(TAG, "Calling reviewModerationAppeal. ID: " + appealId + " Decision: " + decision);

        Map<String, Object> data = new HashMap<>();
        data.put("appealId", appealId);
        data.put("decision", decision);
        data.put("decisionNote", note);

        mFunctions.getHttpsCallable("reviewModerationAppeal").call(data)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "reviewModerationAppeal success.");
                        if (listener != null) listener.onSuccess();
                    } else {
                        String error = extractFunctionsErrorMessage(task.getException(), "Failed to submit review.");
                        Log.e(TAG, "reviewModerationAppeal failure: " + error);
                        if (listener != null) listener.onFailure(error);
                    }
                });
    }

    /**
     * Reviews a pending content report and optionally creates moderator-issued moderation events.
     */
    public void reviewPendingReport(String reportId, String userAction, String contentAction, String note, ForumWriteListener listener) {
        Log.d(TAG, "Calling reviewPendingReport. ID: " + reportId + " UserAction: " + userAction + " ContentAction: " + contentAction);

        Map<String, Object> data = new HashMap<>();
        data.put("reportId", reportId);
        data.put("userAction", userAction);
        data.put("contentAction", contentAction);
        data.put("decisionNote", note);

        mFunctions.getHttpsCallable("reviewPendingReport").call(data)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "reviewPendingReport success.");
                        if (listener != null) listener.onSuccess();
                    } else {
                        String error = extractFunctionsErrorMessage(task.getException(), "Failed to review report.");
                        Log.e(TAG, "reviewPendingReport failure: " + error);
                        if (listener != null) listener.onFailure(error);
                    }
                });
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
    public void toggleForumPostLike(String postId, boolean liked, ActionListener listener) {
        Log.d(TAG, "Calling toggleForumPostLike Cloud Function for post: " + postId + " liked=" + liked);
        Map<String, Object> data = new HashMap<>();
        data.put("postId", postId);
        data.put("liked", liked);
        mFunctions.getHttpsCallable("toggleForumPostLike").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "toggleForumPostLike success.");
                if (listener != null) listener.onSuccess();
            } else {
                String error = extractFunctionsErrorMessage(task.getException(), "Failed to update like status.");
                Log.e(TAG, "toggleForumPostLike failed: " + error);
                if (listener != null) listener.onFailure(error);
            }
        });
    }

    /**
     * Main logic block for this part of the feature.
     */
    public void toggleForumCommentLike(String threadId, String commentId, boolean liked, ActionListener listener) {
        Log.d(TAG, "Calling toggleForumCommentLike Cloud Function for comment: " + commentId + " liked=" + liked);
        Map<String, Object> data = new HashMap<>();
        data.put("threadId", threadId);
        data.put("commentId", commentId);
        data.put("liked", liked);
        mFunctions.getHttpsCallable("toggleForumCommentLike").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "toggleForumCommentLike success.");
                if (listener != null) listener.onSuccess();
            } else {
                String error = extractFunctionsErrorMessage(task.getException(), "Failed to update comment like.");
                Log.e(TAG, "toggleForumCommentLike failed: " + error);
                if (listener != null) listener.onFailure(error);
            }
        });
    }

    /**
     * Records a post view on the backend without blocking the UI.
     */
    public void recordForumPostView(String postId) {
        Log.d(TAG, "Calling recordForumPostView Cloud Function for post: " + postId);
        Map<String, Object> data = new HashMap<>();
        data.put("postId", postId);
        mFunctions.getHttpsCallable("recordForumPostView").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) Log.d(TAG, "recordForumPostView success.");
            else Log.w(TAG, "recordForumPostView failed.", task.getException());
        });
    }

    /**
     * Backend-owned location creation/lookup used by card saving.
     */
    public void createOrGetLocation(Double latitude, Double longitude, String localityName, String state, String country, LocationIdListener listener) {
        Log.d(TAG, "Calling createOrGetLocation Cloud Function.");
        Map<String, Object> data = new HashMap<>();
        data.put("latitude", latitude);
        data.put("longitude", longitude);
        data.put("localityName", localityName);
        data.put("state", state);
        data.put("country", country);
        mFunctions.getHttpsCallable("createOrGetLocation").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null && task.getResult().getData() instanceof Map) {
                Map<String, Object> result = (Map<String, Object>) task.getResult().getData();
                Object locationId = result.get("locationId");
                if (locationId instanceof String && !((String) locationId).trim().isEmpty()) {
                    if (listener != null) listener.onSuccess((String) locationId);
                } else {
                    String error = "Location lookup succeeded but no locationId was returned.";
                    Log.e(TAG, error);
                    if (listener != null) listener.onFailure(error);
                }
            } else {
                String error = extractFunctionsErrorMessage(task.getException(), "Failed to create or get location.");
                Log.e(TAG, "createOrGetLocation failed: " + error);
                if (listener != null) listener.onFailure(error);
            }
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
        if (report.getSourceContext() != null && !report.getSourceContext().trim().isEmpty()) {
            data.put("sourceContext", report.getSourceContext().trim());
        }
        if (report.getThreadId() != null && !report.getThreadId().trim().isEmpty()) {
            data.put("threadId", report.getThreadId().trim());
        }
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
    public void recordForumPost(boolean showLocation, ForumPostLimitListener listener) {
        Log.d(TAG, "Calling recordForumPost CF. showLocation=" + showLocation);

        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("showLocation", showLocation);

        mFunctions.getHttpsCallable("recordForumPost")
                .call(data)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        Map<String, Object> res = (Map<String, Object>) task.getResult().getData();
                        boolean allowed = res != null && Boolean.TRUE.equals(res.get("allowed"));

                        if (allowed) {
                            int remaining = res.get("remaining") != null
                                    ? ((Number) res.get("remaining")).intValue()
                                    : 0;
                            Log.d(TAG, "recordForumPost: allowed, remaining=" + remaining);
                            listener.onAllowed(remaining);
                        } else {
                            Log.d(TAG, "recordForumPost: location-post daily limit reached.");
                            listener.onLimitReached();
                        }
                    } else {
                        String error = task.getException() != null
                                ? task.getException().getMessage()
                                : "recordForumPost failed.";
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

    /**
     * Returns a user-friendly error message from a callable Cloud Function task.
     */
    private String getCallableErrorMessage(Task<HttpsCallableResult> task, String fallbackMessage) {
        Exception exception = task.getException();
        if (exception instanceof FirebaseFunctionsException) {
            FirebaseFunctionsException ffe = (FirebaseFunctionsException) exception;
            Object details = ffe.getDetails();
            if (details instanceof Map) {
                Object userMessage = ((Map<?, ?>) details).get("userMessage");
                if (userMessage instanceof String && !((String) userMessage).trim().isEmpty()) {
                    return (String) userMessage;
                }

                Object message = ((Map<?, ?>) details).get("message");
                if (message instanceof String && !((String) message).trim().isEmpty()) {
                    return (String) message;
                }
            }

            String message = ffe.getMessage();
            if (message != null && !message.trim().isEmpty()) return message;
        }
        if (exception != null && exception.getMessage() != null) {
            return exception.getMessage();
        }
        return fallbackMessage;
    }

    /**
     * Main logic block for this part of the feature.
     * Final forum post creation now goes through a callable so the backend can enforce moderation.
     */
    public void createForumPost(ForumPost post, ForumWriteListener listener) {
        Log.d(TAG, "Calling createForumPost CF for post: " + post.getId());

        Map<String, Object> data = new HashMap<>();
        data.put("postId", post.getId());
        data.put("message", post.getMessage());
        data.put("birdImageUrl", post.getBirdImageUrl() != null ? post.getBirdImageUrl() : "");
        data.put("showLocation", post.isShowLocation());
        data.put("spotted", post.isSpotted());
        data.put("hunted", post.isHunted());
        if (post.getLatitude() != null) data.put("latitude", post.getLatitude());
        if (post.getLongitude() != null) data.put("longitude", post.getLongitude());

        mFunctions.getHttpsCallable("createForumPost").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "createForumPost CF succeeded.");
                listener.onSuccess();
            } else {
                String error = getCallableErrorMessage(task, "Failed to share post.");
                Log.e(TAG, "createForumPost CF failed: " + error);
                listener.onFailure(error);
            }
        });
    }

    /**
     * Main logic block for this part of the feature.
     * Final forum comment creation now goes through a callable so the backend can enforce moderation.
     */
    public void createForumComment(
            String threadId,
            String commentId,
            String text,
            String parentCommentId,
            ForumWriteListener listener
    ) {
        Log.d(TAG, "Calling createForumComment CF for thread: " + threadId + " commentId=" + commentId);

        Map<String, Object> data = new HashMap<>();
        data.put("threadId", threadId);
        data.put("commentId", commentId);
        data.put("text", text);
        data.put("parentCommentId", parentCommentId != null ? parentCommentId : "");

        mFunctions.getHttpsCallable("createForumComment").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "createForumComment CF succeeded.");
                listener.onSuccess();
            } else {
                String error = getCallableErrorMessage(task, "Failed to add comment.");
                Log.e(TAG, "createForumComment CF failed: " + error);
                listener.onFailure(error);
            }
        });
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * Post edits now go through a callable so the backend can re-check ownership, edit window, and moderation.
     */
    public void updateForumPostContent(
            String postId,
            String message,
            boolean spotted,
            boolean hunted,
            boolean showLocation,
            ForumWriteListener listener
    ) {
        Log.d(TAG, "Calling updateForumPostContent CF for post: " + postId);

        Map<String, Object> data = new HashMap<>();
        data.put("postId", postId);
        data.put("message", message);
        data.put("spotted", spotted);
        data.put("hunted", hunted);
        data.put("showLocation", showLocation);

        mFunctions.getHttpsCallable("updateForumPostContent").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "updateForumPostContent CF succeeded.");
                listener.onSuccess();
            } else {
                String error = getCallableErrorMessage(task, "Failed to update post.");
                Log.e(TAG, "updateForumPostContent CF failed: " + error);
                listener.onFailure(error);
            }
        });
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * Comment edits now go through a callable so the backend can re-check ownership, edit window, and moderation.
     */
    public void updateForumCommentContent(
            String threadId,
            String commentId,
            String text,
            ForumWriteListener listener
    ) {
        Log.d(TAG, "Calling updateForumCommentContent CF for thread: " + threadId + " commentId=" + commentId);

        Map<String, Object> data = new HashMap<>();
        data.put("threadId", threadId);
        data.put("commentId", commentId);
        data.put("text", text);

        mFunctions.getHttpsCallable("updateForumCommentContent").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "updateForumCommentContent CF succeeded.");
                listener.onSuccess();
            } else {
                String error = getCallableErrorMessage(task, "Failed to update comment.");
                Log.e(TAG, "updateForumCommentContent CF failed: " + error);
                listener.onFailure(error);
            }
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
        Log.d(TAG, "Calling deleteForumPost CF for post: " + postId);

        Map<String, Object> data = new HashMap<>();
        data.put("postId", postId);

        mFunctions.getHttpsCallable("deleteForumPost").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "deleteForumPost CF succeeded.");
                if (listener != null) listener.onComplete(Tasks.forResult(null));
            } else {
                String error = getCallableErrorMessage(task, "Failed to delete post.");
                Log.e(TAG, "deleteForumPost CF failed: " + error);
                if (listener != null) listener.onComplete(Tasks.forException(new Exception(error)));
            }
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
        Log.d(TAG, "Calling deleteForumComment CF for thread: " + postId + " commentId=" + commentId);

        Map<String, Object> data = new HashMap<>();
        data.put("threadId", postId);
        data.put("commentId", commentId);

        mFunctions.getHttpsCallable("deleteForumComment").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "deleteForumComment CF succeeded.");
                if (listener != null) listener.onComplete(Tasks.forResult(null));
            } else {
                String error = getCallableErrorMessage(task, "Failed to delete comment.");
                Log.e(TAG, "deleteForumComment CF failed: " + error);
                if (listener != null) listener.onComplete(Tasks.forException(new Exception(error)));
            }
        });
    }

    /**
     * Saves a forum post for the current user through the backend so the saved timestamp is canonical
     * and duplicate entries are prevented server-side.
     */
    public void saveForumPost(String postId, ForumWriteListener listener) {
        Log.d(TAG, "Calling saveForumPost CF for post: " + postId);

        Map<String, Object> data = new HashMap<>();
        data.put("threadId", postId);

        mFunctions.getHttpsCallable("saveForumPost").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "saveForumPost CF succeeded.");
                if (listener != null) listener.onSuccess();
            } else {
                String error = getCallableErrorMessage(task, "Failed to save post.");
                Log.e(TAG, "saveForumPost CF failed: " + error);
                if (listener != null) listener.onFailure(error);
            }
        });
    }

    /**
     * Removes a saved forum post entry for the current user through the backend.
     */
    public void unsaveForumPost(String postId, ForumWriteListener listener) {
        Log.d(TAG, "Calling unsaveForumPost CF for post: " + postId);

        Map<String, Object> data = new HashMap<>();
        data.put("threadId", postId);

        mFunctions.getHttpsCallable("unsaveForumPost").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "unsaveForumPost CF succeeded.");
                if (listener != null) listener.onSuccess();
            } else {
                String error = getCallableErrorMessage(task, "Failed to unsave post.");
                Log.e(TAG, "unsaveForumPost CF failed: " + error);
                if (listener != null) listener.onFailure(error);
            }
        });
    }

    /**
     * Sends a lightweight client-side filtered-content log to the backend so blocked attempts can
     * still be recorded even when the UI stops the submission before the normal write callable.
     */
    public void logFilteredContentAttempt(String submissionType, String fieldName, String text, String threadId, String commentId) {
        Map<String, Object> data = new HashMap<>();
        data.put("submissionType", submissionType != null ? submissionType : "unknown_client_block");
        data.put("fieldName", fieldName != null ? fieldName : "text");
        data.put("text", text != null ? text : "");
        if (threadId != null && !threadId.trim().isEmpty()) data.put("threadId", threadId);
        if (commentId != null && !commentId.trim().isEmpty()) data.put("commentId", commentId);

        mFunctions.getHttpsCallable("logFilteredContentAttempt").call(data)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "logFilteredContentAttempt succeeded for " + submissionType + " / " + fieldName);
                    } else {
                        Log.e(TAG, "logFilteredContentAttempt failed.", task.getException());
                    }
                });
    }

    /**
     * Reads whether the current user has already saved a forum post through the backend so the
     * Save/Unsave label does not depend on client Firestore read rules.
     */
    public void isForumPostSaved(String postId, OnCompleteListener<Boolean> listener) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            if (listener != null) listener.onComplete(Tasks.forResult(false));
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("threadId", postId);

        mFunctions.getHttpsCallable("getForumPostSaveState").call(data).addOnCompleteListener(task -> {
            if (listener == null) return;

            if (task.isSuccessful() && task.getResult() != null) {
                Object raw = task.getResult().getData();
                boolean isSaved = false;

                if (raw instanceof Map) {
                    Object saved = ((Map<?, ?>) raw).get("saved");
                    isSaved = Boolean.TRUE.equals(saved);
                }

                listener.onComplete(Tasks.forResult(isSaved));
            } else {
                String error = getCallableErrorMessage(task, "Failed to check saved post state.");
                listener.onComplete(Tasks.forException(new Exception(error)));
            }
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
        db.collection("birds").document(birdId).get(Source.CACHE).addOnCompleteListener(cacheTask -> {
            if (cacheTask.isSuccessful() && cacheTask.getResult() != null && cacheTask.getResult().exists()) {
                listener.onComplete(cacheTask);
                return;
            }
            db.collection("birds").document(birdId).get(Source.SERVER).addOnCompleteListener(listener);
        });
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
        db.collection("birds").get(Source.CACHE).addOnCompleteListener(cacheTask -> {
            if (cacheTask.isSuccessful() && cacheTask.getResult() != null && !cacheTask.getResult().isEmpty()) {
                listener.onComplete(cacheTask);
                return;
            }
            db.collection("birds").get(Source.SERVER).addOnCompleteListener(listener);
        });
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
        db.collection("birdFacts").document(birdFactsId).get(Source.CACHE).addOnCompleteListener(cacheTask -> {
            if (cacheTask.isSuccessful() && cacheTask.getResult() != null && cacheTask.getResult().exists()) {
                listener.onComplete(cacheTask);
                return;
            }
            db.collection("birdFacts").document(birdFactsId).get(Source.SERVER).addOnCompleteListener(listener);
        });
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
     * Finalizes a previously reserved profile picture change so the daily quota stays consumed only
     * after the whole profile update succeeds.
     */
    public void finalizePfpChange(String changeId, SimpleListener listener) {
        Log.d(TAG, "Calling finalizePfpChange Cloud Function.");
        Map<String, Object> data = new HashMap<>();
        data.put("changeId", changeId);
        mFunctions.getHttpsCallable("finalizePfpChange").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "finalizePfpChange success.");
                listener.onSuccess();
            } else {
                String error = extractFunctionsErrorMessage(task.getException(), "Failed to finalize profile picture change.");
                Log.e(TAG, "finalizePfpChange failed: " + error);
                listener.onFailure(error);
            }
        });
    }

    /**
     * Rolls back a previously reserved profile picture change so rejected or failed attempts do not
     * consume the user's daily quota.
     */
    public void rollbackPfpChange(String changeId, SimpleListener listener) {
        Log.d(TAG, "Calling rollbackPfpChange Cloud Function.");
        Map<String, Object> data = new HashMap<>();
        data.put("changeId", changeId);
        mFunctions.getHttpsCallable("rollbackPfpChange").call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "rollbackPfpChange success.");
                listener.onSuccess();
            } else {
                String error = extractFunctionsErrorMessage(task.getException(), "Failed to roll back profile picture change.");
                Log.e(TAG, "rollbackPfpChange failed: " + error);
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
    /**
     * Sends a bug report directly to Firestore.
     * This allows users to report issues without leaving the app or opening an email client.
     */
    public void submitBugReport(String subject, String description, String contactEmail, OnCompleteListener<Void> listener) {
        Map<String, Object> data = new HashMap<>();
        data.put("subject", subject);
        data.put("description", description);
        data.put("contactEmail", contactEmail);
        data.put("deviceModel", android.os.Build.MODEL);
        data.put("androidVersion", android.os.Build.VERSION.RELEASE);

        mFunctions.getHttpsCallable("submitBugReport")
                .call(data)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "Bug report submitted successfully via Cloud Function.");
                        if (listener != null) listener.onComplete(Tasks.forResult(null));
                    } else {
                        Log.e(TAG, "Failed to submit bug report via Cloud Function.", task.getException());
                        if (listener != null) listener.onComplete(Tasks.forException(task.getException()));
                    }
                });
    }
}