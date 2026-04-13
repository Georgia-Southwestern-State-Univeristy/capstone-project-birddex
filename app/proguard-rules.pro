# -----------------------------------------
# BirdDex - safer starting ProGuard/R8 rules
# -----------------------------------------

# Keep useful metadata/annotations that libraries like Firestore use.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Optional but helpful for readable crash traces during testing.
-keepattributes SourceFile,LineNumberTable

# -----------------------------
# App entry points / manifest
# -----------------------------

# Application class
-keep class com.birddex.app.BirdDexAppCheck { *; }

# Firebase Messaging service
-keep class com.birddex.app.MyFirebaseMessagingService { *; }

# -----------------------------
# Glide
# -----------------------------

# Keep Glide modules / generated glue
# Glide
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep class * extends com.bumptech.glide.module.LibraryGlideModule
-keep class com.birddex.app.BirdDexGlideModule { *; }

-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# -----------------------------
# Firestore model classes
# -----------------------------
# App uses doc.toObject(...) heavily.
# These are the biggest runtime-risk classes when minify is turned on.

-keep class com.birddex.app.User {
    public <init>();
    *;
}

-keep class com.birddex.app.Bird {
    public <init>();
    *;
}

-keep class com.birddex.app.ForumPost {
    public <init>();
    *;
}

-keep class com.birddex.app.ForumComment {
    public <init>();
    *;
}

-keep class com.birddex.app.CollectionSlot {
    public <init>();
    *;
}

-keep class com.birddex.app.TrackedBird {
    public <init>();
    *;
}

-keep class com.birddex.app.OpenAiApi$* { *; }
-keep class com.birddex.app.EbirdApi$* { *; }
-keep class com.birddex.app.CaptureGuardHelper$* { *; }

# These are also model/data classes that are likely to be serialized,
# mapped, or passed around in Firebase-heavy flows.
-keep class com.birddex.app.Report {
    public <init>();
    *;
}

-keep class com.birddex.app.UserSettings {
    public <init>();
    *;
}

# -----------------------------
# Firestore annotations
# -----------------------------
# Keep Firestore annotation-bearing members intact.
-keepclassmembers class * {
    @com.google.firebase.firestore.DocumentId *;
    @com.google.firebase.firestore.PropertyName *;
    @com.google.firebase.firestore.Exclude *;
    @com.google.firebase.firestore.ServerTimestamp *;
}

# -----------------------------
# Firebase / Google Play / Maps
# -----------------------------
# Keep Maps Utils for Heatmap rendering
-keep class com.google.maps.android.heatmaps.** { *; }
-keep class com.google.maps.android.quadtree.** { *; }
-keep class com.google.maps.android.geometry.** { *; }
-keep class com.google.maps.android.projection.** { *; }

-dontwarn com.google.android.gms.**
-dontwarn com.google.firebase.**
-dontwarn com.google.maps.android.**

# -----------------------------
# Heatmap Internal Data Models
# -----------------------------
# These are used inside NearbyHeatmapActivity to process eBird data.
-keep class com.birddex.app.NearbyHeatmapActivity$HotspotSighting { *; }
-keep class com.birddex.app.NearbyHeatmapActivity$HotspotBucket { *; }
-keep class com.birddex.app.NearbyHeatmapActivity$BirdSheetRow { *; }
-keep class com.birddex.app.NearbyHeatmapActivity$HeatPoint { *; }

# -----------------------------
# Camera / cropper / UI
# -----------------------------
-dontwarn androidx.camera.**
-dontwarn com.canhub.cropper.**
-keep class com.birddex.app.CameraOverlayView { *; }

# -----------------------------
# Networking (OkHttp/Volley)
# -----------------------------
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class com.android.volley.** { *; }

# -----------------------------
# Architecture Components
# -----------------------------
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(...);
}
