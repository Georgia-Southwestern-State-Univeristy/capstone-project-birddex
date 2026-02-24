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
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.functions.FirebaseFunctions;
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
    }

    // Removed PasswordResetListener interface as it's replaced by OnCompleteListener<Void>
    // public interface PasswordResetListener {
    //     void onSuccess();
    //     void onFailure(String errorMessage);
    // }

    public interface UsernameCheckListener {
        void onCheckComplete(boolean isAvailable);
        void onFailure(String errorMessage);
    }

    public FirebaseManager(Context context) {
        this.context = context.getApplicationContext();
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        mFunctions = FirebaseFunctions.getInstance();
    }

    public void createAccount(String username, String email, String password, AuthListener listener) {
        // First, check if the username is available using a Cloud Function
        checkUsernameAvailability(username, new UsernameCheckListener() {
            @Override
            public void onCheckComplete(boolean isAvailable) {
                if (isAvailable) {
                    // Username is available, proceed with account creation
                    mAuth.createUserWithEmailAndPassword(email, password)
                            .addOnCompleteListener(authTask -> {
                                if (authTask.isSuccessful()) {
                                    FirebaseUser firebaseUser = mAuth.getCurrentUser();
                                    if (firebaseUser != null) {
                                        User user = new User(firebaseUser.getUid(), email, username, new Date(), null);
                                        addUser(user, task1 -> {
                                            if (task1.isSuccessful()) {
                                                // Send email verification
                                                firebaseUser.sendEmailVerification()
                                                        .addOnCompleteListener(emailTask -> {
                                                            if (emailTask.isSuccessful()) {
                                                                // Use context to get string resource
                                                                String message = context.getString(R.string.email_verification_expiration_message);
                                                                Log.d(TAG, "Email verification sent. " + message);
                                                            } else {
                                                                Log.e(TAG, "Failed to send email verification.", emailTask.getException());
                                                            }
                                                        });
                                                listener.onSuccess(firebaseUser);
                                            } else {
                                                listener.onFailure("Failed to save user profile.");
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
                } else {
                    // Username is already taken
                    listener.onUsernameTaken();
                }
            }

            @Override
            public void onFailure(String errorMessage) {
                // Error during username check
                listener.onFailure("Error checking username: " + errorMessage);
            }
        });
    }

    public void checkUsernameAvailability(String username, UsernameCheckListener listener) {
        Map<String, Object> data = new HashMap<>();
        data.put("username", username);

        mFunctions.getHttpsCallable("checkUsername")
                .call(data)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        HttpsCallableResult result = task.getResult();
                        if (result != null && result.getData() instanceof Map) {
                            Map<String, Object> resultMap = (Map<String, Object>) result.getData();
                            Boolean isAvailable = (Boolean) resultMap.get("isAvailable");
                            if (isAvailable != null) {
                                listener.onCheckComplete(isAvailable);
                            } else {
                                listener.onFailure("Invalid response from checkUsername function.");
                            }
                        } else {
                            listener.onFailure("Invalid response format from checkUsername function.");
                        }
                    } else {
                        String errorMessage = "Callable function call failed.";
                        if (task.getException() != null) {
                            errorMessage += " " + task.getException().getMessage();
                        }
                        Log.e(TAG, "checkUsernameAvailability failed: " + errorMessage, task.getException());
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
        db.collection("users").document(user.getId()).set(user).addOnCompleteListener(listener);
    }

    public void getUserProfile(String userId, OnCompleteListener<DocumentSnapshot> listener) {
        db.collection("users").document(userId).get().addOnCompleteListener(listener);
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

        // 1. Fetch current user profile to compare usernames
        getUserProfile(userId, task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                User oldUser = task.getResult().toObject(User.class);
                if (oldUser != null && newUsername.equals(oldUser.getUsername())) {
                    // Username is not changed, proceed with update without availability check
                    db.collection("users").document(userId).set(updatedUser)
                            .addOnCompleteListener(updateTask -> {
                                if (updateTask.isSuccessful()) {
                                    listener.onSuccess(currentUser);
                                } else {
                                    listener.onFailure("Failed to update user profile: " + updateTask.getException().getMessage());
                                }
                            });
                } else {
                    // Username has changed, check availability
                    checkUsernameAvailability(newUsername, new UsernameCheckListener() {
                        @Override
                        public void onCheckComplete(boolean isAvailable) {
                            if (isAvailable) {
                                db.collection("users").document(userId).set(updatedUser)
                                        .addOnCompleteListener(updateTask -> {
                                            if (updateTask.isSuccessful()) {
                                                listener.onSuccess(currentUser);
                                            } else {
                                                listener.onFailure("Failed to update user profile: " + updateTask.getException().getMessage());
                                            }
                                        });
                            } else {
                                listener.onUsernameTaken();
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
                    } else {
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
            // Using recommended verifyBeforeUpdateEmail instead of updateEmail
            user.verifyBeforeUpdateEmail(newEmail)
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            Toast.makeText(context, "Verification email sent to " + newEmail + ". Please verify to complete the update.", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(context, "Failed to initiate email update: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
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
                                    } else {
                                        Log.e(TAG, "Failed to save UserBird: " + userBird.getId(), saveTask.getException());
                                    }
                                    // Pass on the original listener's result
                                    if (listener != null) {
                                        listener.onComplete(saveTask);
                                    }
                                });
                    } else {
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

    // Threads Collection
    public void addThread(Thread thread, OnCompleteListener<Void> listener) {
        db.collection("threads").document(thread.getId()).set(thread).addOnCompleteListener(listener);
    }

    public void getThreadById(String threadId, OnCompleteListener<DocumentSnapshot> listener) {
        db.collection("threads").document(threadId).get().addOnCompleteListener(listener);
    }

    public void updateThread(Thread thread, OnCompleteListener<Void> listener) {
        db.collection("threads").document(thread.getId()).set(thread).addOnCompleteListener(listener);
    }

    public void deleteThread(String threadId, OnCompleteListener<Void> listener) {
        db.collection("threads").document(threadId).delete().addOnCompleteListener(listener);
    }

    // Posts Subcollection
    public void addPost(String threadId, Post post, OnCompleteListener<DocumentReference> listener) {
        if (post.getId() == null || post.getId().isEmpty()) {
            db.collection("threads").document(threadId).collection("posts").add(post).addOnCompleteListener(listener);
        }
        else {
            db.collection("threads").document(threadId).collection("posts").document(post.getId()).set(post).addOnCompleteListener(task -> listener.onComplete(null));
        }
    }

    public void getPostById(String threadId, String postId, OnCompleteListener<DocumentSnapshot> listener) {
        db.collection("threads").document(threadId).collection("posts").document(postId).get().addOnCompleteListener(listener);
    }

    public void updatePost(String threadId, Post post, OnCompleteListener<Void> listener) {
        db.collection("threads").document(threadId).collection("posts").document(post.getId()).set(post).addOnCompleteListener(listener);
    }

    public void deletePost(String threadId, String postId, OnCompleteListener<Void> listener) {
        db.collection("threads").document(threadId).collection("posts").document(postId).delete().addOnCompleteListener(listener);
    }

    // Reports Collection
    public void addReport(Report report, OnCompleteListener<DocumentReference> listener) {
        db.collection("reports").add(report).addOnCompleteListener(listener);
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