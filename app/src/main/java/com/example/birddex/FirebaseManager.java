package com.example.birddex;

import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * FirebaseManager is a helper class that centralizes Firebase-related operations.
 * It handles user authentication (sign-up, sign-in, password reset) and 
 * Firestore database interactions for user profiles.
 */
public class FirebaseManager {

    private static final String TAG = "FirebaseManager";
    private final FirebaseAuth mAuth;
    private final FirebaseFirestore db;

    /**
     * Listener interface for handling authentication result callbacks.
     */
    public interface AuthListener {
        void onSuccess(FirebaseUser user);
        void onFailure(String errorMessage);
        void onFullNameTaken();
    }

    /**
     * Listener interface for handling password reset result callbacks.
     */
    public interface PasswordResetListener {
        void onSuccess();
        void onFailure(String errorMessage);
    }

    public FirebaseManager() {
        // Initialize Firebase Auth and Firestore instances.
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
    }

    /**
     * Creates a new user account with an email and password.
     * Before creating the account in Auth, it checks Firestore to ensure the full name is unique.
     * If unique, it creates the Auth account and then saves the profile in Firestore.
     */
    public void createAccount(String fullName, String email, String password, AuthListener listener) {
        // First check if the full name already exists in Firestore.
        db.collection("users").whereEqualTo("fullName", fullName)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        if (task.getResult() != null && task.getResult().isEmpty()) {
                            // Full name is available, proceed to create the Firebase Auth account.
                            mAuth.createUserWithEmailAndPassword(email, password)
                                    .addOnCompleteListener(authTask -> {
                                        if (authTask.isSuccessful()) {
                                            Log.d(TAG, "createUserWithEmail:success");
                                            FirebaseUser user = mAuth.getCurrentUser();
                                            // Save the user profile information to Firestore.
                                            addUserToFirestore(user, fullName, email);
                                            listener.onSuccess(user);
                                        } else {
                                            Log.w(TAG, "createUserWithEmail:failure", authTask.getException());
                                            String message = "Sign up failed.";
                                            if (authTask.getException() != null && authTask.getException().getMessage() != null) {
                                                message += " " + authTask.getException().getMessage();
                                            }
                                            listener.onFailure(message);
                                        }
                                    });
                        } else {
                            // Inform the listener that the full name is already in use.
                            listener.onFullNameTaken();
                        }
                    } else {
                        // Handle potential Firestore query errors.
                        Log.w(TAG, "Error checking full name", task.getException());
                        listener.onFailure("Error checking full name.");
                    }
                });
    }

    /**
     * Adds user profile data (fullName, email) to the Firestore "users" collection.
     * The document ID matches the Firebase Auth UID.
     */
    private void addUserToFirestore(FirebaseUser firebaseUser, String fullName, String email) {
        if (firebaseUser == null) {
            Log.e(TAG, "Cannot add user to Firestore, FirebaseUser is null.");
            return;
        }
        Map<String, Object> user = new HashMap<>();
        user.put("fullName", fullName);
        user.put("email", email);

        db.collection("users").document(firebaseUser.getUid())
                .set(user)
                .addOnSuccessListener(aVoid -> Log.d(TAG, "DocumentSnapshot successfully written!"))
                .addOnFailureListener(e -> Log.w(TAG, "Error writing document", e));
    }

    /**
     * Signs in a user using their email and password.
     */
    public void signIn(String email, String password, AuthListener listener) {
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "signInWithEmail:success");
                        FirebaseUser user = mAuth.getCurrentUser();
                        listener.onSuccess(user);
                    } else {
                        Log.w(TAG, "signInWithEmail:failure", task.getException());
                        if (task.getException() != null) {
                            listener.onFailure("Sign in failed: " + task.getException().getMessage());
                        } else {
                            listener.onFailure("Sign in failed.");
                        }
                    }
                });
    }

    /**
     * Sends a password reset email to the specified email address.
     */
    public void sendPasswordResetEmail(String email, PasswordResetListener listener) {
        mAuth.sendPasswordResetEmail(email)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "Email sent.");
                        listener.onSuccess();
                    } else {
                        Log.w(TAG, "sendPasswordResetEmail:failure", task.getException());
                        if (task.getException() != null) {
                            listener.onFailure("Failed to send reset email: " + task.getException().getMessage());
                        } else {
                            listener.onFailure("Failed to send reset email.");
                        }
                    }
                });
    }
}
