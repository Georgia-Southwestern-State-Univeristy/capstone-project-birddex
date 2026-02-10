import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.gms.google.services) //added
}

android {
    namespace = "com.example.birddex"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.birddex.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val properties = Properties()
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(localPropertiesFile.inputStream())
        }

        // Logic: Try local.properties first, then System Environment Variables (GitHub Secrets), then default to empty
        val nuthatchApiKey = properties.getProperty("NUTHATCH_API_KEY") 
            ?: System.getenv("NUTHATCH_API_KEY") 
            ?: ""
        buildConfigField("String", "NUTHATCH_API_KEY", "\"$nuthatchApiKey\"")

        val ebirdApiKey = properties.getProperty("EBIRD_API_KEY") 
            ?: System.getenv("EBIRD_API_KEY") 
            ?: ""
        buildConfigField("String", "EBIRD_API_KEY", "\"$ebirdApiKey\"")

        val openaiApiKey = properties.getProperty("OPENAI_API_KEY") 
            ?: System.getenv("OPENAI_API_KEY") 
            ?: ""
        buildConfigField("String", "OPENAI_API_KEY", "\"$openaiApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}


dependencies {
    val cameraxVersion = "1.3.4"

    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    implementation("com.vanniktech:android-image-cropper:4.3.3")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(platform("com.google.firebase:firebase-bom:34.8.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")

    // Networking and Image Libraries
    implementation("com.android.volley:volley:1.2.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")

    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.google.android.gms:play-services-basement:18.4.0")
    implementation(libs.guava)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

}
