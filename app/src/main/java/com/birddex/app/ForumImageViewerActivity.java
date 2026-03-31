package com.birddex.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.birddex.app.databinding.ActivityForumImageViewerBinding;
import com.bumptech.glide.Glide;

/**
 * ForumImageViewerActivity shows a single forum image on its own screen so forum media can be
 * viewed at full size without affecting any non-forum image flow.
 */
public class ForumImageViewerActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_URL = "extra_image_url";

    private ActivityForumImageViewerBinding binding;

    /**
     * Creates the Intent used by forum screens when they need to open the full-image viewer.
     */
    public static Intent createIntent(Context context, String imageUrl) {
        Intent intent = new Intent(context, ForumImageViewerActivity.class);
        intent.putExtra(EXTRA_IMAGE_URL, imageUrl);
        return intent;
    }

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarHelper.applyStandardNavBar(this);
        binding = ActivityForumImageViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String imageUrl = getIntent().getStringExtra(EXTRA_IMAGE_URL);
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            finish();
            return;
        }

        binding.btnCloseForumImageViewer.setOnClickListener(v -> finish());
        binding.scrimCloseArea.setOnClickListener(v -> finish());

        Glide.with(this)
                .load(imageUrl)
                .placeholder(R.drawable.bg_image_placeholder)
                .error(R.drawable.bg_image_placeholder)
                .into(binding.ivForumFullImage);
    }
}