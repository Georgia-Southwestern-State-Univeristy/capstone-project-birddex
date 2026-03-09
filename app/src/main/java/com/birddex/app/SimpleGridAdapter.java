package com.birddex.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.List;

/**
 * SimpleGridAdapter displays bird images in the user's collection grid.
 * It uses Glide to load images from the stored Firebase URLs.
 */
/**
 * SimpleGridAdapter: Adapter that converts model data into rows/cards for a RecyclerView or similar list UI.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class SimpleGridAdapter extends RecyclerView.Adapter<SimpleGridAdapter.VH> {

    private final List<String> imageUrls;

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public SimpleGridAdapter(List<String> imageUrls) {
        this.imageUrls = imageUrls;
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivCollectionImage;

        /**
         * Main logic block for this part of the feature.
         * It grabs layout/view references here so later code can read from them, update them, or
         * attach listeners.
         */
        VH(@NonNull View itemView) {
            super(itemView);
            // Bind or inflate the UI pieces this method needs before it can update the screen.
            ivCollectionImage = itemView.findViewById(R.id.ivCollectionImage);
        }
    }

    /**
     * Main logic block for this part of the feature.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     */
    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                // Bind or inflate the UI pieces this method needs before it can update the screen.
                .inflate(R.layout.item_collection_cell, parent, false);
        return new VH(v);
    }

    /**
     * Main logic block for this part of the feature.
     * Image loading happens here, which is why placeholder/error behavior for profile
     * photos/cards/posts usually traces back to this code path.
     */
    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        String url = imageUrls.get(position);

        if (url != null && !url.isEmpty()) {
            // Load the bird image using Glide.
            // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
            Glide.with(holder.itemView.getContext())
                    .load(url)
                    .placeholder(R.drawable.ic_launcher_background) // Optional: add a placeholder
                    .centerCrop()
                    .into(holder.ivCollectionImage);
        } else {
            // Clear the image if no URL is provided (for empty slots).
            holder.ivCollectionImage.setImageDrawable(null);
            holder.ivCollectionImage.setBackgroundColor(
                    holder.itemView.getContext().getColor(R.color.placeholder_cell)
            ); // Theme-aware placeholder
        }
        }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    @Override
    public int getItemCount() {
        return imageUrls.size();
    }
}
