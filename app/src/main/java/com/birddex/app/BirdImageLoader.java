package com.birddex.app;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.text.HtmlCompat;

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
import com.google.firebase.functions.FirebaseFunctions;

import java.util.HashMap;
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
    private static final LruCache<String, ImageMetadata> imageMetadataCache = new LruCache<>(500);
    private static final Object cacheLock = new Object();

    private BirdImageLoader() {
    }

    /**
     * Loads a bird image into the provided ImageView by trying the species code/document id first,
     * then falling back to common/scientific name lookups.
     *
     * Cache flow:
     * 1. In-memory resolved URL cache
     * 2. Firestore local cache
     * 3. Firestore server
     * 4. Glide memory/disk cache for the actual image file
     */
    public static void loadBirdImageIntoWithFetch(@NonNull Context context,
                                                  @NonNull ImageView imageView,
                                                  @Nullable View progressView,
                                                  @Nullable android.widget.TextView statusTextView,
                                                  @Nullable String birdId,
                                                  @Nullable String commonName,
                                                  @Nullable String scientificName) {
        loadBirdImageIntoWithFetch(context, imageView, progressView, statusTextView, birdId, commonName, scientificName, null);
    }

    public static void loadBirdImageIntoWithFetch(@NonNull Context context,
                                                  @NonNull ImageView imageView,
                                                  @Nullable View progressView,
                                                  @Nullable android.widget.TextView statusTextView,
                                                  @Nullable String birdId,
                                                  @Nullable String commonName,
                                                  @Nullable String scientificName,
                                                  @Nullable MetadataLoadCallback metadataLoadCallback) {
        loadBirdImageIntoWithMetadata(imageView, birdId, commonName, scientificName, new MetadataLoadCallback() {
            @Override
            public void onLoaded(@Nullable ImageMetadata metadata) {
                if (progressView != null) progressView.setVisibility(View.GONE);
                if (statusTextView != null) statusTextView.setVisibility(View.GONE);
                notifyMetadataLoaded(metadataLoadCallback, metadata);
            }

            @Override
            public void onNotFound() {
                if (progressView != null) progressView.setVisibility(View.VISIBLE);
                if (statusTextView != null) {
                    statusTextView.setText(context.getString(R.string.searching_reference_photo));
                    statusTextView.setVisibility(View.VISIBLE);
                }
                requestFetchedIdentificationImage(context, imageView, progressView, statusTextView,
                        birdId, commonName, scientificName, metadataLoadCallback);
            }
        });
    }

    public static void loadBirdImageIntoWithMetadata(@NonNull ImageView imageView,
                                                     @Nullable String birdId,
                                                     @Nullable String commonName,
                                                     @Nullable String scientificName,
                                                     @Nullable MetadataLoadCallback metadataLoadCallback) {
        String requestKey = firstNonBlank(birdId, commonName, scientificName, "__none__");
        imageView.setTag(requestKey);
        Glide.with(imageView).clear(imageView);
        imageView.setImageDrawable(null);
        imageView.setVisibility(View.INVISIBLE);

        if (isBlank(birdId) && isBlank(commonName) && isBlank(scientificName)) {
            imageView.setVisibility(View.GONE);
            notifyMetadataNotFound(metadataLoadCallback);
            return;
        }

        ImageMetadata cachedMetadata = getCachedMetadata(birdId, commonName, scientificName);
        if (cachedMetadata != null && !isBlank(cachedMetadata.imageUrl)) {
            loadResolvedUrl(imageView, requestKey, cachedMetadata.imageUrl, null, metadataLoadCallback, cachedMetadata);
            return;
        }

        loadBirdImageIntoInternal(imageView, requestKey, birdId, commonName, scientificName, null, metadataLoadCallback);
    }

    public static void loadBirdImageInto(@NonNull ImageView imageView,
                                         @Nullable String birdId,
                                         @Nullable String commonName,
                                         @Nullable String scientificName) {
        loadBirdImageInto(imageView, birdId, commonName, scientificName, null);
    }

    public static void loadBirdImageInto(@NonNull ImageView imageView,
                                         @Nullable String birdId,
                                         @Nullable String commonName,
                                         @Nullable String scientificName,
                                         @Nullable LoadCallback loadCallback) {
        String requestKey = firstNonBlank(birdId, commonName, scientificName, "__none__");
        imageView.setTag(requestKey);
        Glide.with(imageView).clear(imageView);
        imageView.setImageDrawable(null);
        imageView.setVisibility(View.INVISIBLE);

        if (isBlank(birdId) && isBlank(commonName) && isBlank(scientificName)) {
            imageView.setVisibility(View.GONE);
            notifyNotFound(loadCallback);
            return;
        }

        ImageMetadata cachedMetadata = getCachedMetadata(birdId, commonName, scientificName);
        if (cachedMetadata != null && !isBlank(cachedMetadata.imageUrl)) {
            loadResolvedUrl(imageView, requestKey, cachedMetadata.imageUrl, loadCallback, null, cachedMetadata);
            return;
        }

        loadBirdImageIntoInternal(imageView, requestKey, birdId, commonName, scientificName, loadCallback, null);
    }

    private static void loadBirdImageIntoInternal(@NonNull ImageView imageView,
                                                  @NonNull String requestKey,
                                                  @Nullable String birdId,
                                                  @Nullable String commonName,
                                                  @Nullable String scientificName,
                                                  @Nullable LoadCallback loadCallback,
                                                  @Nullable MetadataLoadCallback metadataLoadCallback) {
        tryFromCollection(imageView, requestKey, "nuthatch_images", birdId, commonName, scientificName, loadCallback, metadataLoadCallback, () ->
                tryFromCollection(imageView, requestKey, "inaturalist_images", birdId, commonName, scientificName, loadCallback, metadataLoadCallback, () ->
                        tryFromCollection(imageView, requestKey, "images_fecthed_Identifications", birdId, commonName, scientificName, loadCallback, metadataLoadCallback, () -> {
                            if (!isStillBound(imageView, requestKey)) return;
                            imageView.setImageDrawable(null);
                            imageView.setVisibility(View.GONE);
                            notifyNotFound(loadCallback);
                            notifyMetadataNotFound(metadataLoadCallback);
                        })
                )
        );
    }

    private static void tryFromCollection(@NonNull ImageView imageView,
                                          @NonNull String requestKey,
                                          @NonNull String collectionName,
                                          @Nullable String birdId,
                                          @Nullable String commonName,
                                          @Nullable String scientificName,
                                          @Nullable LoadCallback loadCallback,
                                          @Nullable MetadataLoadCallback metadataLoadCallback,
                                          @NonNull Runnable onNotFound) {
        if (!isStillBound(imageView, requestKey)) return;

        if (!isBlank(birdId)) {
            getDocumentCacheThenServer(collectionName, birdId,
                    doc -> {
                        if (!isStillBound(imageView, requestKey)) return;
                        if (doc != null && doc.exists()) {
                            processImageDoc(imageView, requestKey, collectionName, birdId, commonName, scientificName, doc, loadCallback, metadataLoadCallback);
                        } else {
                            queryFallbacks(imageView, requestKey, collectionName, birdId, commonName, scientificName, loadCallback, metadataLoadCallback, onNotFound);
                        }
                    },
                    e -> {
                        Log.e(TAG, "Failed direct image lookup in " + collectionName + " for birdId=" + birdId, e);
                        if (!isStillBound(imageView, requestKey)) return;
                        queryFallbacks(imageView, requestKey, collectionName, birdId, commonName, scientificName, loadCallback, metadataLoadCallback, onNotFound);
                    }
            );
        } else {
            queryFallbacks(imageView, requestKey, collectionName, birdId, commonName, scientificName, loadCallback, metadataLoadCallback, onNotFound);
        }
    }

    private static void queryFallbacks(@NonNull ImageView imageView,
                                       @NonNull String requestKey,
                                       @NonNull String collectionName,
                                       @Nullable String birdId,
                                       @Nullable String commonName,
                                       @Nullable String scientificName,
                                       @Nullable LoadCallback loadCallback,
                                       @Nullable MetadataLoadCallback metadataLoadCallback,
                                       @NonNull Runnable onNotFound) {
        if (!isStillBound(imageView, requestKey)) return;

        queryByField(imageView, requestKey, collectionName, "speciesCode", birdId, birdId, commonName, scientificName, loadCallback, metadataLoadCallback, () ->
                queryByField(imageView, requestKey, collectionName, "commonName", commonName, birdId, commonName, scientificName, loadCallback, metadataLoadCallback, () ->
                        queryByField(imageView, requestKey, collectionName, "scientificName", scientificName, birdId, commonName, scientificName, loadCallback, metadataLoadCallback, onNotFound)
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
                                     @Nullable LoadCallback loadCallback,
                                     @Nullable MetadataLoadCallback metadataLoadCallback,
                                     @NonNull Runnable onNotFound) {
        if (!isStillBound(imageView, requestKey)) return;

        if (isBlank(value)) {
            onNotFound.run();
            return;
        }

        getSingleQueryResultCacheThenServer(collectionName, fieldName, value,
                querySnapshot -> {
                    if (!isStillBound(imageView, requestKey)) return;
                    handleQueryResult(imageView, requestKey, collectionName, birdId, commonName, scientificName, querySnapshot, loadCallback, metadataLoadCallback, onNotFound);
                },
                e -> {
                    Log.e(TAG, "Failed image query in " + collectionName + " where " + fieldName + "=" + value, e);
                    if (!isStillBound(imageView, requestKey)) return;
                    onNotFound.run();
                }
        );
    }

    private static void getDocumentCacheThenServer(@NonNull String collectionName,
                                                   @NonNull String docId,
                                                   @NonNull DocumentHandler onSuccess,
                                                   @NonNull FailureHandler onFailure) {
        db.collection(collectionName).document(docId).get(Source.CACHE)
                .addOnSuccessListener(cacheDoc -> {
                    if (cacheDoc.exists()) {
                        onSuccess.handle(cacheDoc);
                    } else {
                        db.collection(collectionName).document(docId).get(Source.SERVER)
                                .addOnSuccessListener(onSuccess::handle)
                                .addOnFailureListener(onFailure::handle);
                    }
                })
                .addOnFailureListener(cacheError ->
                        db.collection(collectionName).document(docId).get(Source.SERVER)
                                .addOnSuccessListener(onSuccess::handle)
                                .addOnFailureListener(onFailure::handle)
                );
    }

    private static void getSingleQueryResultCacheThenServer(@NonNull String collectionName,
                                                            @NonNull String fieldName,
                                                            @NonNull String value,
                                                            @NonNull QueryHandler onSuccess,
                                                            @NonNull FailureHandler onFailure) {
        db.collection(collectionName)
                .whereEqualTo(fieldName, value)
                .limit(1)
                .get(Source.CACHE)
                .addOnSuccessListener(cacheSnapshot -> {
                    if (cacheSnapshot != null && !cacheSnapshot.isEmpty()) {
                        onSuccess.handle(cacheSnapshot);
                    } else {
                        db.collection(collectionName)
                                .whereEqualTo(fieldName, value)
                                .limit(1)
                                .get(Source.SERVER)
                                .addOnSuccessListener(onSuccess::handle)
                                .addOnFailureListener(onFailure::handle);
                    }
                })
                .addOnFailureListener(cacheError ->
                        db.collection(collectionName)
                                .whereEqualTo(fieldName, value)
                                .limit(1)
                                .get(Source.SERVER)
                                .addOnSuccessListener(onSuccess::handle)
                                .addOnFailureListener(onFailure::handle)
                );
    }

    private static void handleQueryResult(@NonNull ImageView imageView,
                                          @NonNull String requestKey,
                                          @NonNull String collectionName,
                                          @Nullable String birdId,
                                          @Nullable String commonName,
                                          @Nullable String scientificName,
                                          @Nullable QuerySnapshot querySnapshot,
                                          @Nullable LoadCallback loadCallback,
                                          @Nullable MetadataLoadCallback metadataLoadCallback,
                                          @NonNull Runnable onNotFound) {
        if (!isStillBound(imageView, requestKey)) return;

        if (querySnapshot != null && !querySnapshot.isEmpty()) {
            processImageDoc(imageView, requestKey, collectionName, birdId, commonName, scientificName, querySnapshot.getDocuments().get(0), loadCallback, metadataLoadCallback);
        } else {
            onNotFound.run();
        }
    }

    private static void processImageDoc(@NonNull ImageView imageView,
                                        @NonNull String requestKey,
                                        @NonNull String collectionName,
                                        @Nullable String birdId,
                                        @Nullable String commonName,
                                        @Nullable String scientificName,
                                        @NonNull DocumentSnapshot imageDoc,
                                        @Nullable LoadCallback loadCallback,
                                        @Nullable MetadataLoadCallback metadataLoadCallback) {
        if (!isStillBound(imageView, requestKey)) return;

        String imageUrl = extractImageUrl(imageDoc);
        if (isBlank(imageUrl)) {
            imageView.setImageDrawable(null);
            imageView.setVisibility(View.GONE);
            notifyNotFound(loadCallback);
            return;
        }

        ImageMetadata metadata = extractImageMetadata(imageDoc, collectionName, imageUrl);
        cacheResolvedData(birdId, commonName, scientificName, metadata);
        loadResolvedUrl(imageView, requestKey, imageUrl, loadCallback, metadataLoadCallback, metadata);
    }

    private static void loadResolvedUrl(@NonNull ImageView imageView,
                                        @NonNull String requestKey,
                                        @NonNull String imageUrl,
                                        @Nullable LoadCallback loadCallback,
                                        @Nullable MetadataLoadCallback metadataLoadCallback,
                                        @Nullable ImageMetadata metadata) {
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
                        notifyNotFound(loadCallback);
                        notifyMetadataNotFound(metadataLoadCallback);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                        if (!isStillBound(imageView, requestKey)) return false;
                        imageView.setVisibility(View.VISIBLE);
                        notifyLoaded(loadCallback);
                        notifyMetadataLoaded(metadataLoadCallback, metadata);
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

    private static void requestFetchedIdentificationImage(@NonNull Context context,
                                                          @NonNull ImageView imageView,
                                                          @Nullable View progressView,
                                                          @Nullable android.widget.TextView statusTextView,
                                                          @Nullable String birdId,
                                                          @Nullable String commonName,
                                                          @Nullable String scientificName,
                                                          @Nullable MetadataLoadCallback metadataLoadCallback) {
        final String requestKey = firstNonBlank(birdId, commonName, scientificName, "__none__");
        HashMap<String, Object> data = new HashMap<>();
        if (!isBlank(birdId)) data.put("birdId", birdId);
        if (!isBlank(commonName)) data.put("commonName", commonName);
        if (!isBlank(scientificName)) data.put("scientificName", scientificName);

        FirebaseFunctions.getInstance()
                .getHttpsCallable("fetchIdentificationReferenceImage")
                .call(data)
                .addOnSuccessListener(taskResult -> {
                    if (!isStillBound(imageView, requestKey)) return;
                    String imageUrl = extractImageUrlFromCallableResponse(taskResult.getData());
                    if (!isBlank(imageUrl)) {
                        ImageMetadata metadata = extractImageMetadataFromCallableResponse(taskResult.getData(), imageUrl);
                        cacheResolvedData(birdId, commonName, scientificName, metadata);
                        loadResolvedUrl(imageView, requestKey, imageUrl, new LoadCallback() {
                            @Override
                            public void onLoaded() {
                                if (progressView != null) progressView.setVisibility(View.GONE);
                                if (statusTextView != null) statusTextView.setVisibility(View.GONE);
                            }

                            @Override
                            public void onNotFound() {
                                if (progressView != null) progressView.setVisibility(View.GONE);
                                if (statusTextView != null) {
                                    statusTextView.setText("Reference photo unavailable");
                                    statusTextView.setVisibility(View.VISIBLE);
                                }
                            }
                        }, metadataLoadCallback, metadata);
                    } else {
                        if (progressView != null) progressView.setVisibility(View.GONE);
                        if (statusTextView != null) {
                            statusTextView.setText(extractUserMessageFromCallableResponse(taskResult.getData()));
                            statusTextView.setVisibility(View.VISIBLE);
                        }
                        notifyMetadataNotFound(metadataLoadCallback);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed remote identification image fetch", e);
                    if (!isStillBound(imageView, requestKey)) return;
                    if (progressView != null) progressView.setVisibility(View.GONE);
                    if (statusTextView != null) {
                        statusTextView.setText("Reference photo unavailable");
                        statusTextView.setVisibility(View.VISIBLE);
                    }
                    notifyMetadataNotFound(metadataLoadCallback);
                });
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static String extractImageUrlFromCallableResponse(@Nullable Object raw) {
        if (!(raw instanceof java.util.Map)) return null;
        Object value = ((java.util.Map<String, Object>) raw).get("imageUrl");
        return value instanceof String ? ((String) value).trim() : null;
    }

    @NonNull
    @SuppressWarnings("unchecked")
    private static String extractUserMessageFromCallableResponse(@Nullable Object raw) {
        if (raw instanceof java.util.Map) {
            Object value = ((java.util.Map<String, Object>) raw).get("userMessage");
            if (value instanceof String && !((String) value).trim().isEmpty()) {
                return ((String) value).trim();
            }
        }
        return "Reference photo unavailable";
    }

    @Nullable
    private static ImageMetadata getCachedMetadata(@Nullable String birdId,
                                                   @Nullable String commonName,
                                                   @Nullable String scientificName) {
        synchronized (cacheLock) {
            String idKey = buildIdCacheKey(birdId);
            if (idKey != null) {
                ImageMetadata byId = imageMetadataCache.get(idKey);
                if (byId != null && !isBlank(byId.imageUrl)) return byId;
                String byIdUrl = imageUrlCache.get(idKey);
                if (!isBlank(byIdUrl)) return new ImageMetadata(byIdUrl, null, null, null, true, null, null, null, false);
            }

            String commonKey = buildCommonNameCacheKey(commonName);
            if (commonKey != null) {
                ImageMetadata byCommon = imageMetadataCache.get(commonKey);
                if (byCommon != null && !isBlank(byCommon.imageUrl)) return byCommon;
                String byCommonUrl = imageUrlCache.get(commonKey);
                if (!isBlank(byCommonUrl)) return new ImageMetadata(byCommonUrl, null, null, null, true, null, null, null, false);
            }

            String scientificKey = buildScientificNameCacheKey(scientificName);
            if (scientificKey != null) {
                ImageMetadata byScientific = imageMetadataCache.get(scientificKey);
                if (byScientific != null && !isBlank(byScientific.imageUrl)) return byScientific;
                String byScientificUrl = imageUrlCache.get(scientificKey);
                if (!isBlank(byScientificUrl)) return new ImageMetadata(byScientificUrl, null, null, null, true, null, null, null, false);
            }
        }
        return null;
    }

    private static void cacheResolvedData(@Nullable String birdId,
                                          @Nullable String commonName,
                                          @Nullable String scientificName,
                                          @NonNull ImageMetadata metadata) {
        synchronized (cacheLock) {
            String idKey = buildIdCacheKey(birdId);
            if (idKey != null) {
                imageUrlCache.put(idKey, metadata.imageUrl);
                imageMetadataCache.put(idKey, metadata);
            }

            String commonKey = buildCommonNameCacheKey(commonName);
            if (commonKey != null) {
                imageUrlCache.put(commonKey, metadata.imageUrl);
                imageMetadataCache.put(commonKey, metadata);
            }

            String scientificKey = buildScientificNameCacheKey(scientificName);
            if (scientificKey != null) {
                imageUrlCache.put(scientificKey, metadata.imageUrl);
                imageMetadataCache.put(scientificKey, metadata);
            }
        }
    }

    @NonNull
    private static ImageMetadata extractImageMetadata(@NonNull DocumentSnapshot imageDoc,
                                                      @NonNull String collectionName,
                                                      @NonNull String imageUrl) {
        String source = cleanMetadataString(imageDoc.getString("source"));
        if (isBlank(source)) {
            source = deriveSourceLabel(collectionName);
        }

        String license = prettifyMetadataString(imageDoc.getString("license"));
        String attribution = cleanMetadataString(imageDoc.getString("attribution"));
        String sourcePageUrl = cleanMetadataString(imageDoc.getString("sourcePageUrl"));
        String licenseUrl = cleanMetadataString(imageDoc.getString("licenseUrl"));
        String title = cleanMetadataString(imageDoc.getString("title"));
        Boolean modified = imageDoc.getBoolean("modified");
        Boolean licenseVerified = imageDoc.getBoolean("licenseVerified");

        return new ImageMetadata(
                imageUrl,
                source,
                license,
                attribution,
                licenseVerified == null || licenseVerified,
                sourcePageUrl,
                licenseUrl,
                title,
                modified != null && modified
        );
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static ImageMetadata extractImageMetadataFromCallableResponse(@Nullable Object raw, @NonNull String fallbackImageUrl) {
        if (!(raw instanceof java.util.Map)) {
            return new ImageMetadata(fallbackImageUrl, null, null, null, true, null, null, null, false);
        }
        java.util.Map<String, Object> map = (java.util.Map<String, Object>) raw;
        String imageUrl = valueAsTrimmedString(map.get("imageUrl"));
        if (isBlank(imageUrl)) imageUrl = fallbackImageUrl;
        String source = prettifyMetadataString(valueAsTrimmedString(map.get("source")));
        String license = prettifyMetadataString(valueAsTrimmedString(map.get("license")));
        String attribution = cleanMetadataString(valueAsTrimmedString(map.get("attribution")));
        String sourcePageUrl = cleanMetadataString(valueAsTrimmedString(map.get("sourcePageUrl")));
        String licenseUrl = cleanMetadataString(valueAsTrimmedString(map.get("licenseUrl")));
        String title = cleanMetadataString(valueAsTrimmedString(map.get("title")));
        boolean modified = false;
        Object modifiedObj = map.get("modified");
        if (modifiedObj instanceof Boolean) {
            modified = (Boolean) modifiedObj;
        }
        boolean licenseVerified = true;
        Object verifiedObj = map.get("licenseVerified");
        if (verifiedObj instanceof Boolean) {
            licenseVerified = (Boolean) verifiedObj;
        }
        if (isBlank(licenseUrl)) {
            licenseUrl = inferLicenseUrl(license);
        }
        return new ImageMetadata(imageUrl, source, license, attribution, licenseVerified, sourcePageUrl, licenseUrl, title, modified);
    }

    @Nullable
    public static String buildAttributionText(@Nullable ImageMetadata metadata) {
        Spanned spanned = buildAttributionHtml(metadata);
        if (spanned == null) return null;
        String plain = spanned.toString().replaceAll("\n{3,}", "\n\n").trim();
        return plain.isEmpty() ? null : plain;
    }

    @Nullable
    public static Spanned buildAttributionHtml(@Nullable ImageMetadata metadata) {
        if (metadata == null) return null;

        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        String title = cleanMetadataString(metadata.title);
        String attribution = cleanMetadataString(metadata.attribution);
        String photoLabel = !isBlank(attribution) ? attribution : title;
        if (!isBlank(photoLabel)) {
            lines.add("Photo: " + htmlEscape(photoLabel));
        }

        String source = prettifyMetadataString(metadata.source);
        String sourcePageUrl = cleanMetadataString(metadata.sourcePageUrl);
        if (!isBlank(source)) {
            if (!isBlank(sourcePageUrl)) {
                lines.add("Source: <a href=\"" + htmlEscapeAttribute(sourcePageUrl) + "\">" + htmlEscape(source) + "</a>");
            } else {
                lines.add("Source: " + htmlEscape(source));
            }
        }

        String license = prettifyMetadataString(metadata.license);
        String licenseUrl = cleanMetadataString(metadata.licenseUrl);
        if (isBlank(licenseUrl)) {
            licenseUrl = inferLicenseUrl(license);
        }
        if (!isBlank(license)) {
            if (!isBlank(licenseUrl)) {
                lines.add("License: <a href=\"" + htmlEscapeAttribute(licenseUrl) + "\">" + htmlEscape(license) + "</a>");
            } else {
                lines.add("License: " + htmlEscape(license));
            }
        }

        lines.add(metadata.modified ? "Status: Modified image" : "Status: Unmodified image");

        if (lines.isEmpty()) {
            return null;
        }

        String html = TextUtils.join("<br>", lines);
        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY);
    }

    public static void applyAttributionText(@Nullable TextView textView, @Nullable ImageMetadata metadata) {
        if (textView == null) return;
        Spanned spanned = buildAttributionHtml(metadata);
        if (spanned == null || spanned.toString().trim().isEmpty()) {
            textView.setText("");
            textView.setVisibility(View.GONE);
            return;
        }
        textView.setText(spanned);
        textView.setLinksClickable(true);
        textView.setClickable(true);
        textView.setLongClickable(true);
        textView.setMovementMethod(LinkMovementMethod.getInstance());
        textView.setVisibility(View.VISIBLE);
    }

    @NonNull
    private static String htmlEscape(@Nullable String value) {
        return TextUtils.htmlEncode(value == null ? "" : value);
    }

    @NonNull
    private static String htmlEscapeAttribute(@Nullable String value) {
        return TextUtils.htmlEncode(value == null ? "" : value);
    }

    @Nullable
    private static String inferLicenseUrl(@Nullable String license) {
        String normalized = normalizeLicenseLabel(license);
        if (normalized == null) return null;
        switch (normalized) {
            case "cc by 4.0":
                return "https://creativecommons.org/licenses/by/4.0/";
            case "cc by 3.0":
                return "https://creativecommons.org/licenses/by/3.0/";
            case "cc by 2.0":
                return "https://creativecommons.org/licenses/by/2.0/";
            case "cc0":
            case "cc0 1.0":
                return "https://creativecommons.org/publicdomain/zero/1.0/";
            default:
                return null;
        }
    }

    @Nullable
    private static String normalizeLicenseLabel(@Nullable String license) {
        if (isBlank(license)) return null;
        String normalized = license.trim().toLowerCase().replace('_', ' ');
        normalized = normalized.replaceAll("\\s+", " ");
        normalized = normalized.replace("creative commons attribution", "cc by");
        normalized = normalized.replace("creative commons zero", "cc0");
        if (normalized.equals("cc-by")) return "cc by";
        if (normalized.equals("cc-by 4.0") || normalized.equals("cc by 4.0")) return "cc by 4.0";
        if (normalized.equals("cc-by 3.0") || normalized.equals("cc by 3.0")) return "cc by 3.0";
        if (normalized.equals("cc-by 2.0") || normalized.equals("cc by 2.0")) return "cc by 2.0";
        if (normalized.equals("cc0") || normalized.equals("cc0 1.0") || normalized.equals("cc-0") || normalized.equals("cc 0")) return "cc0 1.0";
        return normalized;
    }

    @NonNull
    private static String deriveSourceLabel(@NonNull String collectionName) {
        if ("nuthatch_images".equals(collectionName)) return "Nuthatch";
        if ("inaturalist_images".equals(collectionName)) return "iNaturalist";
        if ("images_fecthed_Identifications".equals(collectionName)) return "Fetched";
        return prettifyMetadataString(collectionName);
    }

    @Nullable
    private static String valueAsTrimmedString(@Nullable Object value) {
        if (value == null) return null;
        String raw = String.valueOf(value).trim();
        return raw.isEmpty() ? null : raw;
    }

    @Nullable
    private static String cleanMetadataString(@Nullable String value) {
        if (isBlank(value)) return null;
        String plain = HtmlCompat.fromHtml(String.valueOf(value), HtmlCompat.FROM_HTML_MODE_LEGACY).toString();
        plain = plain.replaceAll("\\s+", " ").trim();
        return plain.isEmpty() ? null : plain;
    }

    @Nullable
    private static String prettifyMetadataString(@Nullable String value) {
        String cleaned = cleanMetadataString(value);
        if (isBlank(cleaned)) return null;
        return cleaned.replace('_', ' ').trim();
    }

    @NonNull
    private static String joinParts(@NonNull java.util.List<String> parts, @NonNull String separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) builder.append(separator);
            builder.append(parts.get(i));
        }
        return builder.toString();
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

    private static void notifyLoaded(@Nullable LoadCallback loadCallback) {
        if (loadCallback != null) {
            loadCallback.onLoaded();
        }
    }

    private static void notifyNotFound(@Nullable LoadCallback loadCallback) {
        if (loadCallback != null) {
            loadCallback.onNotFound();
        }
    }

    private static void notifyMetadataLoaded(@Nullable MetadataLoadCallback metadataLoadCallback,
                                             @Nullable ImageMetadata metadata) {
        if (metadataLoadCallback != null) {
            metadataLoadCallback.onLoaded(metadata);
        }
    }

    private static void notifyMetadataNotFound(@Nullable MetadataLoadCallback metadataLoadCallback) {
        if (metadataLoadCallback != null) {
            metadataLoadCallback.onNotFound();
        }
    }

    public interface LoadCallback {
        void onLoaded();
        void onNotFound();
    }

    public interface MetadataLoadCallback {
        void onLoaded(@Nullable ImageMetadata metadata);
        void onNotFound();
    }

    public static final class ImageMetadata {
        @NonNull public final String imageUrl;
        @Nullable public final String source;
        @Nullable public final String license;
        @Nullable public final String attribution;
        public final boolean licenseVerified;
        @Nullable public final String sourcePageUrl;
        @Nullable public final String licenseUrl;
        @Nullable public final String title;
        public final boolean modified;

        public ImageMetadata(@NonNull String imageUrl,
                             @Nullable String source,
                             @Nullable String license,
                             @Nullable String attribution,
                             boolean licenseVerified,
                             @Nullable String sourcePageUrl,
                             @Nullable String licenseUrl,
                             @Nullable String title,
                             boolean modified) {
            this.imageUrl = imageUrl;
            this.source = source;
            this.license = license;
            this.attribution = attribution;
            this.licenseVerified = licenseVerified;
            this.sourcePageUrl = sourcePageUrl;
            this.licenseUrl = licenseUrl;
            this.title = title;
            this.modified = modified;
        }
    }

    private interface DocumentHandler {
        void handle(@NonNull DocumentSnapshot documentSnapshot);
    }

    private interface QueryHandler {
        void handle(@Nullable QuerySnapshot querySnapshot);
    }

    private interface FailureHandler {
        void handle(@NonNull Exception e);
    }
}
