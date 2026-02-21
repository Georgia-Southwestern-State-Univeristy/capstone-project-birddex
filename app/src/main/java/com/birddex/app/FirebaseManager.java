package com.birddex.app;

import android.util.Log;

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

    public interface AuthListener {
        void onSuccess(FirebaseUser user);
        void onFailure(String errorMessage);
        void onDisplayNameTaken();
    }

    public interface PasswordResetListener {
        void onSuccess();
        void onFailure(String errorMessage);
    }

    public interface DisplayNameCheckListener {
        void onCheckComplete(boolean isAvailable);
        void onFailure(String errorMessage);
    }

    public FirebaseManager() {
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        mFunctions = FirebaseFunctions.getInstance();
    }

    public void createAccount(String displayName, String email, String password, AuthListener listener) {
        // First, check if the display name is available using a Cloud Function
        checkDisplayNameAvailability(displayName, new DisplayNameCheckListener() {
            @Override
            public void onCheckComplete(boolean isAvailable) {
                if (isAvailable) {
                    // Display name is available, proceed with account creation
                    mAuth.createUserWithEmailAndPassword(email, password)
                            .addOnCompleteListener(authTask -> {
                                if (authTask.isSuccessful()) {
                                    FirebaseUser firebaseUser = mAuth.getCurrentUser();
                                    if (firebaseUser != null) {
                                        User user = new User(firebaseUser.getUid(), email, displayName, new Date(), null);
                                        addUser(user, task1 -> {
                                            if (task1.isSuccessful()) {
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
                    // Display name is already taken
                    listener.onDisplayNameTaken();
                }
            }

            @Override
            public void onFailure(String errorMessage) {
                // Error during display name check
                listener.onFailure("Error checking display name: " + errorMessage);
            }
        });
    }

    public void checkDisplayNameAvailability(String displayName, DisplayNameCheckListener listener) {
        Map<String, Object> data = new HashMap<>();
        data.put("displayName", displayName);

        mFunctions.getHttpsCallable("checkDisplayName")
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
                                listener.onFailure("Invalid response from checkDisplayName function.");
                            }
                        } else {
                            listener.onFailure("Invalid response format from checkDisplayName function.");
                        }
                    } else {
                        String errorMessage = "Callable function call failed.";
                        if (task.getException() != null) {
                            errorMessage += " " + task.getException().getMessage();
                        }
                        Log.e(TAG, "checkDisplayNameAvailability failed: " + errorMessage, task.getException());
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

    public void updateUserProfile(User user, OnCompleteListener<Void> listener) {
        if (user == null || user.getId() == null) {
            Log.e(TAG, "Cannot update user in Firestore, User object or ID is null.");
            if (listener != null) listener.onComplete(Tasks.forException(new IllegalArgumentException("User object or ID is null.")));
            return;
        }
        db.collection("users").document(user.getId()).set(user).addOnCompleteListener(listener);
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

    public void sendPasswordResetEmail(String email, PasswordResetListener listener) {
        mAuth.sendPasswordResetEmail(email)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        listener.onSuccess();
                    } else {
                        String message = "Failed to send reset email.";
                        if (task.getException() != null) {
                            message += " " + task.getException().getMessage();
                        }
                        listener.onFailure(message);
                    }
                });
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
        db.collection("userBirds").document(userBird.getId()).set(userBird).addOnCompleteListener(listener);
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
