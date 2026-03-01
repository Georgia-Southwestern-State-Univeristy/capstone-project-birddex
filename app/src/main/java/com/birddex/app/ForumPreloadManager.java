package com.birddex.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.List;

public class ForumPreloadManager {

    private static final String TAG = "ForumPreloadManager";
    private static ForumPreloadManager instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Runnable> pendingCallbacks = new ArrayList<>();
    private final List<ForumPost> cachedPosts = new ArrayList<>();

    private boolean preloadStarted = false;
    private boolean preloadFinished = false;

    private ForumPreloadManager() {
    }

    public static synchronized ForumPreloadManager getInstance() {
        if (instance == null) {
            instance = new ForumPreloadManager();
        }
        return instance;
    }

    public void preload(Runnable callback) {
        synchronized (this) {
            if (callback != null) {
                if (preloadFinished) {
                    postToMain(callback);
                    return;
                }
                pendingCallbacks.add(callback);
            }

            if (preloadStarted) {
                return;
            }

            preloadStarted = true;
        }

        FirebaseFirestore.getInstance()
                .collection("forumThreads")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(value -> {
                    List<ForumPost> posts = new ArrayList<>();

                    for (DocumentSnapshot doc : value.getDocuments()) {
                        ForumPost post = doc.toObject(ForumPost.class);
                        if (post != null) {
                            post.setId(doc.getId());
                            posts.add(post);
                        }
                    }

                    updateCache(posts);
                    finishPreload();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to preload forum posts.", e);
                    finishPreload();
                });
    }

    public synchronized List<ForumPost> getCachedPosts() {
        return new ArrayList<>(cachedPosts);
    }

    public synchronized void updateCache(List<ForumPost> posts) {
        cachedPosts.clear();
        if (posts != null) {
            cachedPosts.addAll(posts);
        }

        preloadFinished = true;
        preloadStarted = true;
    }

    private synchronized void finishPreload() {
        preloadFinished = true;
        preloadStarted = true;

        List<Runnable> callbacks = new ArrayList<>(pendingCallbacks);
        pendingCallbacks.clear();

        for (Runnable callback : callbacks) {
            postToMain(callback);
        }
    }

    private void postToMain(Runnable runnable) {
        mainHandler.post(runnable);
    }
}