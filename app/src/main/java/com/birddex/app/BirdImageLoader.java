package com.birddex.app;

import android.graphics.drawable.Drawable;
import android.util.Log;
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
        imageView.setImageDrawable(null);
        imageView.setVisibility(View.INVISIBLE);
        Glide.with(imageView).clear(imageView);

        if (isBlank(birdId) && isBlank(commonName) && isBlank(scientificName)) {
            imageView.setVisibility(View.GONE);
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
                            processImageDoc(imageView, requestKey, doc);
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

        queryByField(imageView, requestKey, collectionName, "speciesCode", birdId, () ->
                queryByField(imageView, requestKey, collectionName, "commonName", commonName, () ->
                        queryByField(imageView, requestKey, collectionName, "scientificName", scientificName, onNotFound)
                )
        );
    }

    private static void queryByField(@NonNull ImageView imageView,
                                     @NonNull String requestKey,
                                     @NonNull String collectionName,
                                     @NonNull String fieldName,
                                     @Nullable String value,
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
                    handleQueryResult(imageView, requestKey, querySnapshot, onNotFound);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed image query in " + collectionName + " where " + fieldName + "=" + value, e);
                    if (!isStillBound(imageView, requestKey)) return;
                    onNotFound.run();
                });
    }

    private static void handleQueryResult(@NonNull ImageView imageView,
                                          @NonNull String requestKey,
                                          @Nullable QuerySnapshot querySnapshot,
                                          @NonNull Runnable onNotFound) {
        if (!isStillBound(imageView, requestKey)) return;

        if (querySnapshot != null && !querySnapshot.isEmpty()) {
            processImageDoc(imageView, requestKey, querySnapshot.getDocuments().get(0));
        } else {
            onNotFound.run();
        }
    }

    private static void processImageDoc(@NonNull ImageView imageView,
                                        @NonNull String requestKey,
                                        @NonNull DocumentSnapshot imageDoc) {
        if (!isStillBound(imageView, requestKey)) return;

        String imageUrl = extractImageUrl(imageDoc);
        if (isBlank(imageUrl)) {
            imageView.setImageDrawable(null);
            imageView.setVisibility(View.GONE);
            return;
        }

        imageView.setVisibility(View.VISIBLE);

        Glide.with(imageView)
                .load(imageUrl)
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
