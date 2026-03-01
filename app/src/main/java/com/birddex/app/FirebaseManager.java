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
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.WriteBatch;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException; // Added for specific error handling
import com.google.firebase.functions.HttpsCallableResult;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class FirebaseManager {

    private static final String TAG = "FirebaseManager";
    private final FirebaseAuth mAuth;
    private final FirebaseFirestore db;
    private final FirebaseFunctions mFunctions;
    private Context context;

    public interface AuthListener {
        void onSuccess(FirebaseUser user);
        void onFailure(String errorMessage);
        void onUsernameTaken();
        void onEmailTaken(); // Added for email check failure
    }

    // New listener for combined username and email check
    public interface UsernameAndEmailCheckListener {
        void onCheckComplete(boolean isUsernameAvailable, boolean isEmailAvailable);
        void onFailure(String errorMessage);
    }

    // New listener for PFP change result
    public interface PfpChangeLimitListener {
        void onSuccess(int pfpChangesToday, Date pfpCooldownResetTimestamp);
        void onFailure(String errorMessage);
        void onLimitExceeded();
    }

    // New listener for OpenAI request limit
    public interface OpenAiRequestLimitListener {
        void onCheckComplete(boolean hasRequestsRemaining, int remaining, Date openAiCooldownResetTimestamp);
        void onFailure(String errorMessage);
    }

    // New listener for OpenAI image moderation result
    public interface OpenAiModerationListener {
        void onSuccess(boolean isAppropriate, String moderationReason);
        void onFailure(String errorMessage);
    }

    public FirebaseManager(Context context) {
        this.context = context.getApplicationContext();
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        mFunctions = FirebaseFunctions.getInstance();
    }

    public void createAccount(String username, String email, String password, AuthListener listener) {
        // First, check if the username AND email are available using a Cloud Function
        checkUsernameAndEmailAvailability(username, email, new UsernameAndEmailCheckListener() {
            @Override
            public void onCheckComplete(boolean isUsernameAvailable, boolean isEmailAvailable) {
                if (!isUsernameAvailable) {
                    listener.onUsernameTaken();
                    return;
                }
                if (!isEmailAvailable) {
                    listener.onEmailTaken(); // New callback for email taken
                    return;
                }

                // Both username and email are available, proceed with account creation
                mAuth.createUserWithEmailAndPassword(email, password)
                        .addOnCompleteListener(authTask -> {
                            if (authTask.isSuccessful()) {
                                FirebaseUser firebaseUser = mAuth.getCurrentUser();
                                if (firebaseUser != null) {
                                    // Send email verification
                                    firebaseUser.sendEmailVerification()
                                            .addOnCompleteListener(emailTask -> {
                                                if (emailTask.isSuccessful()) {
                                                    String message = context.getString(R.string.email_verification_expiration_message);
                                                    Log.d(TAG, "Email verification sent. " + message);
                                                } else {
                                                    Log.e(TAG, "Failed to send email verification.", emailTask.getException());
                                                }
                                            });

                                    // After Firebase Auth user is created (and Cloud Function has run),
                                    // explicitly add/update the username in Firestore.
                                    User newUser = new User(firebaseUser.getUid(), email, username, null, null); // Assuming null for createdAt and defaultLocationId
                                    addUser(newUser, addDocTask -> {
                                        if (addDocTask.isSuccessful()) {
                                            listener.onSuccess(firebaseUser);
                                        } else {
                                            Log.e(TAG, "Failed to add username to Firestore: ", addDocTask.getException());
                                            listener.onFailure("Account created, but failed to save username.");
                                        }
                                    });

                                } else {
                                    listener.onFailure("FirebaseUser is null after account creation.");
                                }
                            } else {
                                String message = "Sign up failed.";
                                if (authTask.getException() != null) {
                                    message += " " + authTask.getException().getMessage();
                                }
                                listener.onFailure(message);
                            }
                        });
            }

            @Override
            public void onFailure(String errorMessage) {
                // Error during username/email check
                listener.onFailure("Error checking username and email: " + errorMessage);
            }
        });
    }

    // Renamed and updated from checkUsernameAvailability
    public void checkUsernameAndEmailAvailability(String username, String email, UsernameAndEmailCheckListener listener) {
        Map<String, Object> data = new HashMap<>();
        data.put("username", username);
        data.put("email", email); // Now sending email as well

        mFunctions.getHttpsCallable("checkUsernameAndEmailAvailability") // Updated function name
                .call(data)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        HttpsCallableResult result = task.getResult();
                        if (result != null && result.getData() instanceof Map) {
                            Map<String, Object> resultMap = (Map<String, Object>) result.getData();
                            Boolean isUsernameAvailable = (Boolean) resultMap.get("isUsernameAvailable");
                            Boolean isEmailAvailable = (Boolean) resultMap.get("isEmailAvailable");

                            if (isUsernameAvailable != null && isEmailAvailable != null) {
                                listener.onCheckComplete(isUsernameAvailable, isEmailAvailable);
                            } else {
                                listener.onFailure("Invalid response from checkUsernameAndEmailAvailability function: missing availability booleans.");
                            }
                        } else {
                            listener.onFailure("Invalid response format from checkUsernameAndEmailAvailability function.");
                        }
                    } else {
                        String errorMessage = "Callable function call failed.";
                        if (task.getException() != null) {
                            errorMessage += " " + task.getException().getMessage();
                            if (task.getException() instanceof FirebaseFunctionsException) {
                                FirebaseFunctionsException ffe = (FirebaseFunctionsException) task.getException();
                                if (ffe.getCode() == FirebaseFunctionsException.Code.UNAUTHENTICATED) {
                                    // Note: This function doesn't require authentication, so this error might indicate a misconfiguration.
                                    errorMessage = "Function error: Authentication was unexpectedly required for availability check.";
                                }
                            }
                        }
                        Log.e(TAG, "checkUsernameAndEmailAvailability failed: " + errorMessage, task.getException());
                        listener.onFailure(errorMessage);
                    }
                });
    }

    public void addUser(User user, OnCompleteListener<Void> listener) {
        if (user == null || user.getId() == null) {
            Log.e(TAG, "Cannot add user to Firestore, User object or ID is null.");
            if (listener != null) listener.onComplete(Tasks.forException(new IllegalArgumentException("User object or ID is null.")));
            return;
        }
        // Use merge:true to ensure it updates existing fields (like username) and preserves new limit fields
        db.collection("users").document(user.getId()).set(user, com.google.firebase.firestore.SetOptions.merge()).addOnCompleteListener(listener);
    }

    public void getUserProfile(String userId, OnCompleteListener<DocumentSnapshot> listener) {
        db.collection("users").document(userId).get().addOnCompleteListener(listener);
    }

    /**
     * Fetches the username for a given user ID from Firestore.
     * @param userId The ID of the user whose username to fetch.
     * @param listener A listener to handle the completion of the task, returning the username or an error.
     */
    public void getUsername(String userId, OnCompleteListener<String> listener) {
        getUserProfile(userId, task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                DocumentSnapshot document = task.getResult();
                if (document.exists()) {
                    String username = document.getString("username");
                    if (username != null) {
                        listener.onComplete(Tasks.forResult(username));
                    } else {
                        listener.onComplete(Tasks.forException(new IllegalStateException("Username not found in user document.")));
                    }
                } else {
                    listener.onComplete(Tasks.forException(new IllegalStateException("User document not found.")));
                }
            } else {
                listener.onComplete(Tasks.forException(new Exception("Failed to fetch user profile for username: " + (task.getException() != null ? task.getException().getMessage() : "Unknown error"))));
            }
        });
    }

    public void updateUserProfile(User updatedUser, AuthListener listener) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null || updatedUser == null || updatedUser.getId() == null) {
            Log.e(TAG, "Cannot update user profile: currentUser, updatedUser, or updatedUser ID is null.");
            if (listener != null) listener.onFailure("User not authenticated or invalid user data.");
            return;
        }

        String userId = currentUser.getUid();
        String newUsername = updatedUser.getUsername();
        String currentUserEmail = currentUser.getEmail(); // Get current email for the check

        // Create a map with only the fields the client is allowed to update
        Map<String, Object> updates = new HashMap<>();
        if (newUsername != null) {
            updates.put("username", newUsername);
        }
        if (updatedUser.getProfilePictureUrl() != null) {
            updates.put("profilePictureUrl", updatedUser.getProfilePictureUrl());
        }
        if (updatedUser.getBio() != null) {
            updates.put("bio", updatedUser.getBio());
        }

        // If no updatable fields are present, don't perform a Firestore update.
        if (updates.isEmpty()) {
            listener.onSuccess(currentUser);
            return;
        }

        // 1. Fetch current user profile to compare usernames
        getUserProfile(userId, task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                User oldUser = task.getResult().toObject(User.class);
                String oldUsername = oldUser != null ? oldUser.getUsername() : null;

                boolean usernameChanged = (newUsername != null && !newUsername.equals(oldUsername)) || (newUsername == null && oldUsername != null);

                if (!usernameChanged) {
                    // Username is not changed, proceed with update without availability check
                    db.collection("users").document(userId).update(updates)
                            .addOnCompleteListener(updateTask -> {
                                if (updateTask.isSuccessful()) {
                                    listener.onSuccess(currentUser);
                                } else {
                                    listener.onFailure("Failed to update user profile: " + updateTask.getException().getMessage());
                                }
                            });
                } else {
                    // Username has changed, check availability (pass current email as it's not changing here)
                    checkUsernameAndEmailAvailability(newUsername, currentUserEmail, new UsernameAndEmailCheckListener() {
                        @Override
                        public void onCheckComplete(boolean isUsernameAvailable, boolean isEmailAvailable) {
                            if (isUsernameAvailable) {
                                db.collection("users").document(userId).update(updates)
                                        .addOnCompleteListener(updateTask -> {
                                            if (updateTask.isSuccessful()) {
                                                listener.onSuccess(currentUser);
                                            } else {
                                                listener.onFailure("Failed to update user profile: " + updateTask.getException().getMessage());
                                            }
                                        });
                            } else {
                                listener.onUsernameTaken(); // Only username is checked for update here
                            }
                        }

                        @Override
                        public void onFailure(String errorMessage) {
                            listener.onFailure("Error checking username availability: " + errorMessage);
                        }
                    });
                }
            } else {
                listener.onFailure("Failed to retrieve current user profile: " + (task.getException() != null ? task.getException().getMessage() : "Unknown error"));
            }
        });
    }

    public void deleteUser(String userId, OnCompleteListener<Void> listener) {
        db.collection("users").document(userId).delete().addOnCompleteListener(listener);
    }

    public void signIn(String email, String password, AuthListener listener) {
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        listener.onSuccess(mAuth.getCurrentUser());
                    } else {
                        String message = "Sign in failed.";
                        if (task.getException() != null) {
                            message += " " + task.getException().getMessage();
                        }
                        listener.onFailure(message);
                    }
                });
    }

    /**
     * Sends a password reset email to the specified email address.
     * @param email The email address to send the reset link to.
     * @param listener A listener to handle the completion of the reset email task.
     */
    public void sendPasswordResetEmail(String email, OnCompleteListener<Void> listener) {
        mAuth.sendPasswordResetEmail(email)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Toast.makeText(context, "Password reset email sent to " + email, Toast.LENGTH_LONG).show();
                    }
                    else {
                        Toast.makeText(context, "Failed to send password reset email: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
                    }
                    if (listener != null) {
                        listener.onComplete(task);
                    }
                });
    }

    /**
     * Updates the email address for the currently logged-in user.
     * Note: For security reasons, the user must have recently re-authenticated before calling this.
     * @param newEmail The new email address.
     * @param listener A listener to handle the completion of the email update task.
     */
    public void updateUserEmail(String newEmail, OnCompleteListener<Void> listener) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) {
            user.updateEmail(newEmail)
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            Toast.makeText(context, "User email updated to " + newEmail, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(context, "Failed to update email: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
                        }
                        if (listener != null) {
                            listener.onComplete(task);
                        }
                    });
        } else {
            Toast.makeText(context, "No user is currently logged in.", Toast.LENGTH_SHORT).show();
            if (listener != null) {
                listener.onComplete(Tasks.forException(new IllegalStateException("No user is currently logged in.")));
            }
        }
    }

    /**
     * Calls a Cloud Function to record a profile picture change and decrement the daily limit.
     * @param listener A listener to handle the result of the operation.
     */
    public void recordPfpChange(PfpChangeLimitListener listener) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            listener.onFailure("User not authenticated.");
            return;
        }

        mFunctions.getHttpsCallable("recordPfpChange")
                .call()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        HttpsCallableResult result = task.getResult();
                        if (result != null && result.getData() instanceof Map) {
                            Map<String, Object> resultMap = (Map<String, Object>) result.getData();
                            // Retrieve as Number and convert to int to avoid ClassCastException
                            Number pfpChangesTodayNum = (Number) resultMap.get("pfpChangesToday");
                            int pfpChangesToday = pfpChangesTodayNum != null ? pfpChangesTodayNum.intValue() : 0;

                            // Retrieve cooldown timestamp
                            com.google.firebase.Timestamp pfpCooldownTimestamp = (com.google.firebase.Timestamp) resultMap.get("pfpCooldownResetTimestamp");
                            Date pfpCooldownResetDate = pfpCooldownTimestamp != null ? pfpCooldownTimestamp.toDate() : null;

                            listener.onSuccess(pfpChangesToday, pfpCooldownResetDate);
                        } else {
                            listener.onFailure("Invalid response from recordPfpChange function.");
                        }
                    }
                    else {
                        String errorMessage = "Failed to record PFP change.";
                        if (task.getException() != null) {
                            errorMessage += " " + task.getException().getMessage();
                            if (task.getException() instanceof FirebaseFunctionsException) {
                                FirebaseFunctionsException ffe = (FirebaseFunctionsException) task.getException();
                                if (ffe.getCode() == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED) {
                                    listener.onLimitExceeded();
                                    return;
                                } else if (ffe.getCode() == FirebaseFunctionsException.Code.UNAUTHENTICATED) {
                                    errorMessage = "Authentication required to change profile picture.";
                                }
                            }
                        }
                        Log.e(TAG, "recordPfpChange failed: " + errorMessage, task.getException());
                        listener.onFailure(errorMessage);
                    }
                });
    }

    /**
     * Calls a Cloud Function to moderate a profile picture image using OpenAI Vision.
     * @param base64Image The Base64 encoded string of the image.
     * @param listener A listener to handle the result of the moderation.
     */
    public void callOpenAiImageModeration(String base64Image, OpenAiModerationListener listener) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            listener.onFailure("User not authenticated for image moderation.");
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("imageBase64", base64Image);

        mFunctions.getHttpsCallable("moderatePfpImage")
                .call(data)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        HttpsCallableResult result = task.getResult();
                        if (result != null && result.getData() instanceof Map) {
                            Map<String, Object> resultMap = (Map<String, Object>) result.getData();
                            Boolean isAppropriate = (Boolean) resultMap.get("isAppropriate");
                            String moderationReason = (String) resultMap.get("moderationReason");

                            if (isAppropriate != null && moderationReason != null) {
                                listener.onSuccess(isAppropriate, moderationReason);
                            } else {
                                listener.onFailure("Invalid response from moderatePfpImage function: missing required fields.");
                            }
                        } else {
                            listener.onFailure("Invalid response format from moderatePfpImage function.");
                        }
                    } else {
                        String errorMessage = "Failed to moderate image.";
                        if (task.getException() != null) {
                            errorMessage += " " + task.getException().getMessage();
                            if (task.getException() instanceof FirebaseFunctionsException) {
                                FirebaseFunctionsException ffe = (FirebaseFunctionsException) task.getException();
                                if (ffe.getCode() == FirebaseFunctionsException.Code.UNAUTHENTICATED) {
                                    errorMessage = "Authentication required for image moderation.";
                                }
                            }
                        }
                        Log.e(TAG, "moderatePfpImage failed: " + errorMessage, task.getException());
                        listener.onFailure(errorMessage);
                    }
                });
    }

    /**
     * Fetches the user's current OpenAI request remaining count and cooldown timestamp from Firestore.
     * @param listener A listener to handle the result.
     */
    public void getOpenAiRequestsRemaining(OpenAiRequestLimitListener listener) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            listener.onFailure("User not authenticated.");
            return;
        }

        db.collection("users").document(currentUser.getUid()).get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        DocumentSnapshot document = task.getResult();
                        if (document.exists()) {
                            Long remainingLong = document.getLong("openAiRequestsRemaining");
                            int remaining = remainingLong != null ? remainingLong.intValue() : 0;

                            com.google.firebase.Timestamp openAiCooldownTimestamp = document.getTimestamp("openAiCooldownResetTimestamp");
                            Date openAiCooldownResetDate = openAiCooldownTimestamp != null ? openAiCooldownTimestamp.toDate() : null;

                            listener.onCheckComplete(remaining > 0, remaining, openAiCooldownResetDate);
                        } else {
                            listener.onFailure("User document not found.");
                        }
                    }
                    else {
                        Log.e(TAG, "Failed to get OpenAI requests remaining: ", task.getException());
                        listener.onFailure("Failed to fetch OpenAI request limit: " + task.getException().getMessage());
                    }
                });
    }

    /**
     * Fetches the user's current PFP changes remaining count and cooldown timestamp from Firestore.
     * @param listener A listener to handle the result.
     */
    public void getPfpChangesRemaining(PfpChangeLimitListener listener) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            listener.onFailure("User not authenticated.");
            return;
        }

        db.collection("users").document(currentUser.getUid()).get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        DocumentSnapshot document = task.getResult();
                        if (document.exists()) {
                            Long remainingLong = document.getLong("pfpChangesToday");
                            int remaining = remainingLong != null ? remainingLong.intValue() : 0;

                            com.google.firebase.Timestamp pfpCooldownTimestamp = document.getTimestamp("pfpCooldownResetTimestamp");
                            Date pfpCooldownResetDate = pfpCooldownTimestamp != null ? pfpCooldownTimestamp.toDate() : null;

                            listener.onSuccess(remaining, pfpCooldownResetDate);
                        } else {
                            listener.onFailure("User document not found.");
                        }
                    }
                    else {
                        Log.e(TAG, "Failed to get PFP changes remaining: ", task.getException());
                        listener.onFailure("Failed to fetch PFP change limit: " + task.getException().getMessage());
                    }
                });
    }

    // --- FORUM METHODS (Updated to match existing database structure) ---

    public void addForumPost(ForumPost post, OnCompleteListener<Void> listener) {
        // Saving to the "forumThreads" collection as currently used in fragments
        db.collection("forumThreads").document(post.getId()).set(post).addOnCompleteListener(listener);
    }

    public void updateForumPost(String postId, Map<String, Object> updates, OnCompleteListener<Void> listener) {
        db.collection("forumThreads").document(postId).update(updates).addOnCompleteListener(listener);
    }

    public void deleteForumPost(String postId, OnCompleteListener<Void> listener) {
        db.collection("forumThreads").document(postId).delete().addOnCompleteListener(listener);
    }

    public void addForumComment(String postId, ForumComment comment, OnCompleteListener<DocumentReference> listener) {
        // Saving to the "comments" sub-collection inside "forumThreads"
        db.collection("forumThreads").document(postId).collection("comments").add(comment).addOnCompleteListener(listener);
    }

    public void deleteForumComment(String postId, String commentId, OnCompleteListener<Void> listener) {
        db.collection("forumThreads").document(postId).collection("comments").document(commentId).delete().addOnCompleteListener(listener);
    }

    // Bird Collection
    public void addBird(Bird bird, OnCompleteListener<Void> listener) {
        db.collection("birds").document(bird.getId()).set(bird).addOnCompleteListener(listener);
    }

    public void getBirdById(String birdId, OnCompleteListener<DocumentSnapshot> listener) {
        db.collection("birds").document(birdId).get().addOnCompleteListener(listener);
    }

    public void getAllBirds(OnCompleteListener<QuerySnapshot> listener) {
        db.collection("birds").get().addOnCompleteListener(listener);
    }

    public void updateBird(Bird bird, OnCompleteListener<Void> listener) {
        db.collection("birds").document(bird.getId()).set(bird).addOnCompleteListener(listener);
    }

    public void deleteBird(String birdId, OnCompleteListener<Void> listener) {
        db.collection("birds").document(birdId).delete().addOnCompleteListener(listener);
    }

    // UserBird Collection
    public void addUserBird(UserBird userBird, OnCompleteListener<Void> listener) {
        if (userBird == null || userBird.getUserId() == null || userBird.getBirdSpeciesId() == null) {
            Log.e(TAG, "Cannot add userBird to Firestore: UserBird object, userId, or birdSpeciesId is null.");
            if (listener != null) listener.onComplete(Tasks.forException(new IllegalArgumentException("User object or ID is null.")));
            return;
        }

        // 1. Check for duplicates
        db.collection("userBirds")
                .whereEqualTo("userId", userBird.getUserId())
                .whereEqualTo("birdSpeciesId", userBird.getBirdSpeciesId())
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        boolean isDuplicate = !task.getResult().isEmpty();
                        int pointsEarned = isDuplicate ? 0 : 1;

                        userBird.setIsDuplicate(isDuplicate);
                        userBird.setPointsEarned(pointsEarned);

                        Log.d(TAG, "Duplicate check for UserBird (user: " + userBird.getUserId() + ", bird: " + userBird.getBirdSpeciesId() + "): isDuplicate = " + isDuplicate + ", pointsEarned = " + pointsEarned);

                        // 2. Save the UserBird document with updated fields
                        db.collection("userBirds").document(userBird.getId()).set(userBird)
                                .addOnCompleteListener(saveTask -> {
                                    if (saveTask.isSuccessful()) {
                                        Log.d(TAG, "UserBird saved successfully: " + userBird.getId());
                                    }
                                    else {
                                        Log.e(TAG, "Failed to save UserBird: " + userBird.getId(), saveTask.getException());
                                    }
                                    // Pass on the original listener's result
                                    if (listener != null) {
                                        listener.onComplete(saveTask);
                                    }
                                });
                    }
                    else {
                        Log.e(TAG, "Failed to check for duplicate userBirds: ", task.getException());
                        if (listener != null) {
                            listener.onComplete(Tasks.forException(task.getException()));
                        }
                    }
                });
    }

    public void getUserBirdById(String userBirdId, OnCompleteListener<DocumentSnapshot> listener) {
        db.collection("userBirds").document(userBirdId).get().addOnCompleteListener(listener);
    }

    public Task<QuerySnapshot> getAllUserBirdSightingsForCurrentUser() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            Log.e(TAG, "Cannot get user bird sightings: User not authenticated.");
            return Tasks.forException(new IllegalStateException("User not authenticated."));
        }

        return db.collection("userBirdSightings")
                .whereEqualTo("userId", currentUser.getUid())
                .get();
    }

    public void updateUserBird(UserBird userBird, OnCompleteListener<Void> listener) {
        db.collection("userBirds").document(userBird.getId()).set(userBird).addOnCompleteListener(listener);
    }

    public void deleteUserBird(String userBirdId, OnCompleteListener<Void> listener) {
        db.collection("userBirds").document(userBirdId).delete().addOnCompleteListener(listener);
    }

    // CollectionSlot Subcollection
    public void addCollectionSlot(String userId, String collectionSlotId, CollectionSlot collectionSlot, OnCompleteListener<Void> listener) {
        db.collection("users").document(userId).collection("collectionSlot").document(collectionSlotId).set(collectionSlot).addOnCompleteListener(listener);
    }

    public void getCollectionSlotById(String userId, String collectionSlotId, OnCompleteListener<DocumentSnapshot> listener) {
        db.collection("users").document(userId).collection("collectionSlot").document(collectionSlotId).get().addOnCompleteListener(listener);
    }

    public void updateCollectionSlot(String userId, CollectionSlot collectionSlot, OnCompleteListener<Void> listener) {
        db.collection("users").document(userId).collection("collectionSlot").document(collectionSlot.getId()).set(collectionSlot).addOnCompleteListener(listener);
    }

    public void deleteCollectionSlot(String userId, String collectionSlotId, OnCompleteListener<Void> listener) {
        db.collection("users").document(userId).collection("collectionSlot").document(collectionSlotId).delete().addOnCompleteListener(listener);
    }

    // UserBirdImage Subcollection
    public void addUserBirdImage(String userId, String userBirdImageId, UserBirdImage userBirdImage, OnCompleteListener<Void> listener) {
        db.collection("users").document(userId).collection("userBirdImage").document(userBirdImageId).set(userBirdImage).addOnCompleteListener(listener);
    }

    public void getUserBirdImageById(String userId, String userBirdImageId, OnCompleteListener<DocumentSnapshot> listener) {
        db.collection("users").document(userId).collection("userBirdImage").document(userBirdImageId).get().addOnCompleteListener(listener);
    }

    public void updateUserBirdImage(String userId, UserBirdImage userBirdImage, OnCompleteListener<Void> listener) {
        db.collection("users").document(userId).collection("userBirdImage").document(userBirdImage.getId()).set(userBirdImage).addOnCompleteListener(listener);
    }

    public void deleteUserBirdImage(String userId, String userBirdImageId, OnCompleteListener<Void> listener) {
        db.collection("users").document(userId).collection("userBirdImage").document(userBirdImageId).delete().addOnCompleteListener(listener);
    }

    // BirdFact Collection
    public void addBirdFact(BirdFact birdFact, OnCompleteListener<Void> listener) {
        db.collection("birdFacts").document(birdFact.getId()).set(birdFact).addOnCompleteListener(listener);
    }

    public void getBirdFactById(String birdFactsId, OnCompleteListener<DocumentSnapshot> listener) {
        db.collection("birdFacts").document(birdFactsId).get().addOnCompleteListener(listener);
    }

    public void updateBirdFact(BirdFact birdFact, OnCompleteListener<Void> listener) {
        db.collection("birdFacts").document(birdFact.getId()).set(birdFact).addOnCompleteListener(listener);
    }

    public void deleteBirdFact(String birdFactsId, OnCompleteListener<Void> listener) {
        db.collection("birdFacts").document(birdFactsId).delete().addOnCompleteListener(listener);
    }

    // Identifications Collection
    public void addIdentification(Identification identification, OnCompleteListener<Void> listener) {
        db.collection("identifications").document(identification.getId()).set(identification).addOnCompleteListener(listener);
    }

    public void getIdentificationById(String identificationId, OnCompleteListener<DocumentSnapshot> listener) {
        db.collection("identifications").document(identificationId).get().addOnCompleteListener(listener);
    }

    public void updateIdentification(Identification identification, OnCompleteListener<Void> listener) {
        db.collection("identifications").document(identification.getId()).set(identification).addOnCompleteListener(listener);
    }

    public void deleteIdentification(String identificationId, OnCompleteListener<Void> listener) {
        db.collection("identifications").document(identificationId).delete().addOnCompleteListener(listener);
    }

    // Media Collection
    public void addMedia(Media media, OnCompleteListener<Void> listener) {
        db.collection("media").document(media.getId()).set(media).addOnCompleteListener(listener);
    }

    public void getMediaById(String mediaId, OnCompleteListener<DocumentSnapshot> listener) {
        db.collection("media").document(mediaId).get().addOnCompleteListener(listener);
    }

    public void updateMedia(Media media, OnCompleteListener<Void> listener) {
        db.collection("media").document(media.getId()).set(media).addOnCompleteListener(listener);
    }

    public void deleteMedia(String mediaId, OnCompleteListener<Void> listener) {
        db.collection("media").document(mediaId).delete().addOnCompleteListener(listener);
    }

    // Locations Collection
    public void addLocation(Location location, OnCompleteListener<Void> listener) {
        db.collection("locations").document(location.getId()).set(location).addOnCompleteListener(listener);
    }

    public void getLocationById(String locationId, OnCompleteListener<DocumentSnapshot> listener) {
        db.collection("locations").document(locationId).get().addOnCompleteListener(listener);
    }

    public void updateLocation(Location location, OnCompleteListener<Void> listener) {
        db.collection("locations").document(location.getId()).set(location).addOnCompleteListener(listener);
    }

    public void deleteLocation(String locationId, OnCompleteListener<Void> listener) {
        db.collection("locations").document(locationId).delete().addOnCompleteListener(listener);
    }

    // BirdCards Collection
    public void addBirdCard(BirdCard birdCard, OnCompleteListener<Void> listener) {
        db.collection("birdCards").document(birdCard.getId()).set(birdCard).addOnCompleteListener(listener);
    }

    public void getBirdCardById(String cardId, OnCompleteListener<DocumentSnapshot> listener) {
        db.collection("birdCards").document(cardId).get().addOnCompleteListener(listener);
    }

    public void updateBirdCard(BirdCard birdCard, OnCompleteListener<Void> listener) {
        db.collection("birdCards").document(birdCard.getId()).set(birdCard).addOnCompleteListener(listener);
    }

    public void deleteBirdCard(String cardId, OnCompleteListener<Void> listener) {
        db.collection("birdCards").document(cardId).delete().addOnCompleteListener(listener);
    }

    // UserBirdSightings Collection
    public void addUserBirdSighting(UserBirdSighting userBirdSighting, OnCompleteListener<Void> listener) {
        db.collection("userBirdSightings").document(userBirdSighting.getId()).set(userBirdSighting).addOnCompleteListener(listener);
    }

    public void getUserBirdSightingById(String userBirdSightId, OnCompleteListener<DocumentSnapshot> listener) {
        db.collection("userBirdSightings").document(userBirdSightId).get().addOnCompleteListener(listener);
    }

    public void updateUserBirdSighting(UserBirdSighting userBirdSighting, OnCompleteListener<Void> listener) {
        db.collection("userBirdSightings").document(userBirdSighting.getId()).set(userBirdSighting).addOnCompleteListener(listener);
    }

    public void deleteUserBirdSighting(String userBirdSightId, OnCompleteListener<Void> listener) {
        db.collection("userBirdSightings").document(userBirdSightId).delete().addOnCompleteListener(listener);
    }

    // HunterSightings Collection
    public void addHunterSighting(HunterSighting hunterSighting, OnCompleteListener<Void> listener) {
        db.collection("hunterSightings").document(hunterSighting.getId()).set(hunterSighting).addOnCompleteListener(listener);
    }

    public void getHunterSightingById(String hunterSightId, OnCompleteListener<DocumentSnapshot> listener) {
        db.collection("hunterSightings").document(hunterSightId).get().addOnCompleteListener(listener);
    }

    public void updateHunterSighting(HunterSighting hunterSighting, OnCompleteListener<Void> listener) {
        db.collection("hunterSightings").document(hunterSighting.getId()).set(hunterSighting).addOnCompleteListener(listener);
    }

    public void deleteHunterSighting(String hunterSightId, OnCompleteListener<Void> listener) {
        db.collection("hunterSightings").document(hunterSightId).delete().addOnCompleteListener(listener);
    }

    // Reports Collection
    public void addReport(Report report, OnCompleteListener<DocumentReference> listener) {
        // Check if a report already exists from this reporter for this target and type
        db.collection("reports")
                .whereEqualTo("reporterId", report.getReporterId())
                .whereEqualTo("targetId", report.getTargetId())
                .whereEqualTo("targetType", report.getTargetType())
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null && !task.getResult().isEmpty()) {
                        // Already reported by this user
                        Log.d(TAG, "User " + report.getReporterId() + " already reported " + report.getTargetType() + " " + report.getTargetId());
                        if (listener != null) {
                            listener.onComplete(Tasks.forException(new IllegalStateException("You have already reported this.")));
                        }
                    } else {
                        // Not reported yet, proceed
                        db.collection("reports").add(report).addOnCompleteListener(listener);
                    }
                });
    }

    public void getReportById(String reportId, OnCompleteListener<DocumentSnapshot> listener) {
        db.collection("reports").document(reportId).get().addOnCompleteListener(listener);
    }

    public void updateReport(Report report, OnCompleteListener<Void> listener) {
        db.collection("reports").document(report.getId()).set(report).addOnCompleteListener(listener);
    }

    public void deleteReport(String reportId, OnCompleteListener<Void> listener) {
        db.collection("reports").document(reportId).delete().addOnCompleteListener(listener);
    }

    // --- FOLLOW METHODS ---

    public void followUser(String targetUserId, OnCompleteListener<Void> listener) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            if (listener != null) listener.onComplete(Tasks.forException(new IllegalStateException("User not authenticated.")));
            return;
        }

        String currentUserId = currentUser.getUid();
        if (currentUserId.equals(targetUserId)) {
            if (listener != null) listener.onComplete(Tasks.forException(new IllegalArgumentException("Cannot follow yourself.")));
            return;
        }

        WriteBatch batch = db.batch();

        // Following structure: users/{myUserId}/following/{targetUserId}
        DocumentReference followingRef = db.collection("users").document(currentUserId)
                .collection("following").document(targetUserId);
        Map<String, Object> followingData = new HashMap<>();
        followingData.put("timestamp", FieldValue.serverTimestamp());
        batch.set(followingRef, followingData);

        // Followers structure: users/{targetUserId}/followers/{myUserId}
        DocumentReference followersRef = db.collection("users").document(targetUserId)
                .collection("followers").document(currentUserId);
        Map<String, Object> followersData = new HashMap<>();
        followersData.put("timestamp", FieldValue.serverTimestamp());
        batch.set(followersRef, followersData);

        // Increment counts on the user documents
        batch.update(db.collection("users").document(currentUserId), "followingCount", FieldValue.increment(1));
        batch.update(db.collection("users").document(targetUserId), "followerCount", FieldValue.increment(1));

        batch.commit().addOnCompleteListener(listener);
    }

    public void unfollowUser(String targetUserId, OnCompleteListener<Void> listener) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            if (listener != null) listener.onComplete(Tasks.forException(new IllegalStateException("User not authenticated.")));
            return;
        }

        String currentUserId = currentUser.getUid();

        WriteBatch batch = db.batch();

        // Remove from 'following' subcollection
        DocumentReference followingRef = db.collection("users").document(currentUserId)
                .collection("following").document(targetUserId);
        batch.delete(followingRef);

        // Remove from 'followers' subcollection
        DocumentReference followersRef = db.collection("users").document(targetUserId)
                .collection("followers").document(currentUserId);
        batch.delete(followersRef);

        // Decrement counts
        batch.update(db.collection("users").document(currentUserId), "followingCount", FieldValue.increment(-1));
        batch.update(db.collection("users").document(targetUserId), "followerCount", FieldValue.increment(-1));

        batch.commit().addOnCompleteListener(listener);
    }

    public void isFollowing(String targetUserId, OnCompleteListener<Boolean> listener) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            if (listener != null) listener.onComplete(Tasks.forResult(false));
            return;
        }

        db.collection("users").document(currentUser.getUid())
                .collection("following").document(targetUserId).get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        listener.onComplete(Tasks.forResult(task.getResult() != null && task.getResult().exists()));
                    } else {
                        listener.onComplete(Tasks.forResult(false));
                    }
                });
    }

    /**
     * Triggers an immediate fetch and store of eBird data into the eBirdApiSightings collection
     * by calling a Firebase Cloud Function.
     *
     * @param listener The listener to be notified upon completion or failure.
     */
    public void triggerEbirdDataFetch(OnCompleteListener<HttpsCallableResult> listener) {
        Log.d(TAG, "Calling Cloud Function: triggerEbirdDataFetch");
        mFunctions.getHttpsCallable("triggerEbirdDataFetch")
                .call()
                .addOnCompleteListener(listener);
    }
}
