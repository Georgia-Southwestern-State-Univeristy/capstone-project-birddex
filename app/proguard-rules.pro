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
# Your app uses doc.toObject(...) heavily.
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

# These are also model/data classes that are likely to be serialized,
# mapped, or passed around in Firebase-heavy flows.
-keep class com.birddex.app.UserBird {
    public <init>();
    *;
}

-keep class com.birddex.app.UserBirdSighting {
    public <init>();
    *;
}

-keep class com.birddex.app.UserBirdImage {
    public <init>();
    *;
}

-keep class com.birddex.app.BirdFact {
    public <init>();
    *;
}

-keep class com.birddex.app.Report {
    public <init>();
    *;
}

-keep class com.birddex.app.HunterSighting {
    public <init>();
    *;
}

-keep class com.birddex.app.Identification {
    public <init>();
    *;
}

-keep class com.birddex.app.Location {
    public <init>();
    *;
}

-keep class com.birddex.app.Media {
    public <init>();
    *;
}

-keep class com.birddex.app.UserSettings {
    public <init>();
    *;
}

-keep class com.birddex.app.BirdCard {
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
# Usually not needed, but these help avoid noisy warnings in many Android apps.
-dontwarn com.google.android.gms.**
-dontwarn com.google.firebase.**
-dontwarn com.google.maps.android.**

# -----------------------------
# Camera / cropper
# -----------------------------
-dontwarn androidx.camera.**
-dontwarn com.canhub.cropper.**