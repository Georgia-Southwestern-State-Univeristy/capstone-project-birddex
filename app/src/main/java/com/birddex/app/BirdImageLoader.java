package com.birddex.app;

import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;

import java.util.List;

/**
 * BirdImageLoader resolves BirdDex bird images from Firestore image collections and loads them
 * into an ImageView.
 */
public final class BirdImageLoader {

    private static final String TAG = "BirdImageLoader";
    private static final FirebaseFirestore db = FirebaseFirestore.getInstance();

    // Simple in-memory cache so repeated birds do not keep hitting Firestore.
    private static final LruCache<String, String> imageUrlCache = new LruCache<>(500);
    private static final Object cacheLock = new Object();

    private BirdImageLoader() {
    }

    /**
     * Loads a bird image into the provided ImageView by trying the species code/document id first,
     * then falling back to common/scientific name lookups.
     */
    public static void loadBirdImageInto(@NonNull ImageView imageView,
                                         @Nullable String birdId,
                                         @Nullable String commonName,
                                         @Nullable String scientificName) {
        String requestKey = firstNonBlank(birdId, commonName, scientificName, "__none__");
        imageView.setTag(requestKey);
        Glide.with(imageView).clear(imageView);
        imageView.setImageDrawable(null);
        imageView.setVisibility(View.INVISIBLE);

        if (isBlank(birdId) && isBlank(commonName) && isBlank(scientificName)) {
            imageView.setVisibility(View.GONE);
            return;
        }

        // Fast path: use the in-memory URL cache if this bird was already resolved earlier.
        String cachedUrl = getCachedUrl(birdId, commonName, scientificName);
        if (!isBlank(cachedUrl)) {
            loadResolvedUrl(imageView, requestKey, cachedUrl);
            return;
        }

        tryFromCollection(imageView, requestKey, "nuthatch_images", birdId, commonName, scientificName, () ->
                tryFromCollection(imageView, requestKey, "inaturalist_images", birdId, commonName, scientificName, () -> {
                    if (!isStillBound(imageView, requestKey)) return;
                    imageView.setImageDrawable(null);
                    imageView.setVisibility(View.GONE);
                })
        );
    }

    private static void tryFromCollection(@NonNull ImageView imageView,
                                          @NonNull String requestKey,
                                          @NonNull String collectionName,
                                          @Nullable String birdId,
                                          @Nullable String commonName,
                                          @Nullable String scientificName,
                                          @NonNull Runnable onNotFound) {
        if (!isStillBound(imageView, requestKey)) return;

        if (!isBlank(birdId)) {
            db.collection(collectionName).document(birdId).get(Source.SERVER)
                    .addOnSuccessListener(doc -> {
                        if (!isStillBound(imageView, requestKey)) return;
                        if (doc.exists()) {
                            processImageDoc(imageView, requestKey, birdId, commonName, scientificName, doc);
                        } else {
                            queryFallbacks(imageView, requestKey, collectionName, birdId, commonName, scientificName, onNotFound);
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Failed direct image lookup in " + collectionName + " for birdId=" + birdId, e);
                        if (!isStillBound(imageView, requestKey)) return;
                        queryFallbacks(imageView, requestKey, collectionName, birdId, commonName, scientificName, onNotFound);
                    });
        } else {
            queryFallbacks(imageView, requestKey, collectionName, birdId, commonName, scientificName, onNotFound);
        }
    }

    private static void queryFallbacks(@NonNull ImageView imageView,
                                       @NonNull String requestKey,
                                       @NonNull String collectionName,
                                       @Nullable String birdId,
                                       @Nullable String commonName,
                                       @Nullable String scientificName,
                                       @NonNull Runnable onNotFound) {
        if (!isStillBound(imageView, requestKey)) return;

        queryByField(imageView, requestKey, collectionName, "speciesCode", birdId, birdId, commonName, scientificName, () ->
                queryByField(imageView, requestKey, collectionName, "commonName", commonName, birdId, commonName, scientificName, () ->
                        queryByField(imageView, requestKey, collectionName, "scientificName", scientificName, birdId, commonName, scientificName, onNotFound)
                )
        );
    }

    private static void queryByField(@NonNull ImageView imageView,
                                     @NonNull String requestKey,
                                     @NonNull String collectionName,
                                     @NonNull String fieldName,
                                     @Nullable String value,
                                     @Nullable String birdId,
                                     @Nullable String commonName,
                                     @Nullable String scientificName,
                                     @NonNull Runnable onNotFound) {
        if (!isStillBound(imageView, requestKey)) return;

        if (isBlank(value)) {
            onNotFound.run();
            return;
        }

        db.collection(collectionName)
                .whereEqualTo(fieldName, value)
                .limit(1)
                .get(Source.SERVER)
                .addOnSuccessListener(querySnapshot -> {
                    if (!isStillBound(imageView, requestKey)) return;
                    handleQueryResult(imageView, requestKey, birdId, commonName, scientificName, querySnapshot, onNotFound);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed image query in " + collectionName + " where " + fieldName + "=" + value, e);
                    if (!isStillBound(imageView, requestKey)) return;
                    onNotFound.run();
                });
    }

    private static void handleQueryResult(@NonNull ImageView imageView,
                                          @NonNull String requestKey,
                                          @Nullable String birdId,
                                          @Nullable String commonName,
                                          @Nullable String scientificName,
                                          @Nullable QuerySnapshot querySnapshot,
                                          @NonNull Runnable onNotFound) {
        if (!isStillBound(imageView, requestKey)) return;

        if (querySnapshot != null && !querySnapshot.isEmpty()) {
            processImageDoc(imageView, requestKey, birdId, commonName, scientificName, querySnapshot.getDocuments().get(0));
        } else {
            onNotFound.run();
        }
    }

    private static void processImageDoc(@NonNull ImageView imageView,
                                        @NonNull String requestKey,
                                        @Nullable String birdId,
                                        @Nullable String commonName,
                                        @Nullable String scientificName,
                                        @NonNull DocumentSnapshot imageDoc) {
        if (!isStillBound(imageView, requestKey)) return;

        String imageUrl = extractImageUrl(imageDoc);
        if (isBlank(imageUrl)) {
            imageView.setImageDrawable(null);
            imageView.setVisibility(View.GONE);
            return;
        }

        cacheResolvedUrl(birdId, commonName, scientificName, imageUrl);
        loadResolvedUrl(imageView, requestKey, imageUrl);
    }

    private static void loadResolvedUrl(@NonNull ImageView imageView,
                                        @NonNull String requestKey,
                                        @NonNull String imageUrl) {
        if (!isStillBound(imageView, requestKey)) return;

        imageView.setVisibility(View.VISIBLE);

        Glide.with(imageView)
                .load(imageUrl)
                .thumbnail(0.25f)
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                        if (e != null) {
                            Log.e(TAG, "Bird image failed to load for url=" + model, e);
                        }
                        if (!isStillBound(imageView, requestKey)) return false;
                        imageView.setImageDrawable(null);
                        imageView.setVisibility(View.GONE);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                        if (!isStillBound(imageView, requestKey)) return false;
                        imageView.setVisibility(View.VISIBLE);
                        return false;
                    }
                })
                .into(imageView);
    }

    @Nullable
    private static String extractImageUrl(@NonNull DocumentSnapshot imageDoc) {
        String singleUrl = imageDoc.getString("imageUrl");
        if (!isBlank(singleUrl)) {
            return singleUrl;
        }

        Object imageUrlsObj = imageDoc.get("imageUrls");
        if (imageUrlsObj instanceof List) {
            for (Object item : (List<?>) imageUrlsObj) {
                String candidate = item == null ? null : String.valueOf(item);
                if (!isBlank(candidate)) {
                    return candidate;
                }
            }
        }

        return null;
    }

    @Nullable
    private static String getCachedUrl(@Nullable String birdId,
                                       @Nullable String commonName,
                                       @Nullable String scientificName) {
        synchronized (cacheLock) {
            String idKey = buildIdCacheKey(birdId);
            if (idKey != null) {
                String byId = imageUrlCache.get(idKey);
                if (!isBlank(byId)) return byId;
            }

            String commonKey = buildCommonNameCacheKey(commonName);
            if (commonKey != null) {
                String byCommon = imageUrlCache.get(commonKey);
                if (!isBlank(byCommon)) return byCommon;
            }

            String scientificKey = buildScientificNameCacheKey(scientificName);
            if (scientificKey != null) {
                String byScientific = imageUrlCache.get(scientificKey);
                if (!isBlank(byScientific)) return byScientific;
            }
        }
        return null;
    }

    private static void cacheResolvedUrl(@Nullable String birdId,
                                         @Nullable String commonName,
                                         @Nullable String scientificName,
                                         @NonNull String imageUrl) {
        synchronized (cacheLock) {
            String idKey = buildIdCacheKey(birdId);
            if (idKey != null) {
                imageUrlCache.put(idKey, imageUrl);
            }

            String commonKey = buildCommonNameCacheKey(commonName);
            if (commonKey != null) {
                imageUrlCache.put(commonKey, imageUrl);
            }

            String scientificKey = buildScientificNameCacheKey(scientificName);
            if (scientificKey != null) {
                imageUrlCache.put(scientificKey, imageUrl);
            }
        }
    }

    @Nullable
    private static String buildIdCacheKey(@Nullable String birdId) {
        return isBlank(birdId) ? null : "id:" + birdId.trim().toLowerCase();
    }

    @Nullable
    private static String buildCommonNameCacheKey(@Nullable String commonName) {
        return isBlank(commonName) ? null : "common:" + commonName.trim().toLowerCase();
    }

    @Nullable
    private static String buildScientificNameCacheKey(@Nullable String scientificName) {
        return isBlank(scientificName) ? null : "scientific:" + scientificName.trim().toLowerCase();
    }

    private static boolean isStillBound(@NonNull ImageView imageView, @NonNull String requestKey) {
        Object currentTag = imageView.getTag();
        return currentTag != null && requestKey.equals(String.valueOf(currentTag));
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    @NonNull
    private static String firstNonBlank(@Nullable String a,
                                        @Nullable String b,
                                        @Nullable String c,
                                        @NonNull String fallback) {
        if (!isBlank(a)) return a.trim();
        if (!isBlank(b)) return b.trim();
        if (!isBlank(c)) return c.trim();
        return fallback;
    }
}