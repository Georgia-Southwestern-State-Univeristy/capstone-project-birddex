package com.birddex.app;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.appcheck.AppCheckToken;
import com.google.firebase.appcheck.FirebaseAppCheck;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@GlideModule
/**
 * BirdDexGlideModule: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class BirdDexGlideModule extends AppGlideModule {
    /**
     * Main logic block for this part of the feature.
     * Image loading happens here, which is why placeholder/error behavior for profile
     * photos/cards/posts usually traces back to this code path.
     */
    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    String host = original.url().host();
                    
                    // Build new request with standard headers to avoid 403 Forbidden from strict hosts
                    // We use a very common desktop User-Agent as some sites block mobile/generic agents
                    Request.Builder builder = original.newBuilder()
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                            .header("Accept", "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                            .header("Accept-Language", "en-US,en;q=0.9")
                            .header("Connection", "keep-alive");

                    // Removed the "Referer" header by default as it can cause 403 Forbidden 
                    // on many sites (like Wikipedia or Flickr) that have strict hotlinking policies
                    // or expect a Referer from their own domain.

                    // Add Firebase App Check token for Firebase Storage requests if enforcement is enabled.
                    if (host != null && host.contains("firebasestorage.googleapis.com")) {
                        try {
                            Task<AppCheckToken> task = FirebaseAppCheck.getInstance().getAppCheckToken(false);
                            // Interceptors run on Glide's background threads, so Tasks.await is safe here.
                            AppCheckToken tokenResult = Tasks.await(task, 5, TimeUnit.SECONDS);
                            if (tokenResult != null && tokenResult.getToken() != null) {
                                builder.header("X-Firebase-AppCheck", tokenResult.getToken());
                            }
                        } catch (Exception e) {
                            Log.w("BirdDexGlideModule", "App Check token retrieval failed: " + e.getMessage());
                        }
                    }

                    // Proceed with the request
                    Response response = chain.proceed(builder.build());
                    
                    // Log 403 errors to identify which image hosts are causing issues
                    if (response.code() == 403) {
                        Log.e("BirdDexGlideModule", "403 Forbidden for URL: " + original.url() + " (Host: " + host + ")");
                    }
                    
                    return response;
                })
                .build();

        registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(client));
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
