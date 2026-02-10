package com.example.birddex;

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
public class SimpleGridAdapter extends RecyclerView.Adapter<SimpleGridAdapter.VH> {

    private final List<String> imageUrls;

    public SimpleGridAdapter(List<String> imageUrls) {
        this.imageUrls = imageUrls;
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivCollectionImage;

        VH(@NonNull View itemView) {
            super(itemView);
            ivCollectionImage = itemView.findViewById(R.id.ivCollectionImage);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_collection_cell, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        String url = imageUrls.get(position);

        if (url != null && !url.isEmpty()) {
            // Load the bird image using Glide.
            Glide.with(holder.itemView.getContext())
                    .load(url)
                    .placeholder(R.drawable.ic_launcher_background) // Optional: add a placeholder
                    .centerCrop()
                    .into(holder.ivCollectionImage);
        } else {
            // Clear the image if no URL is provided (for empty slots).
            holder.ivCollectionImage.setImageDrawable(null);
            holder.ivCollectionImage.setBackgroundColor(0xFFEEEEEE); // Light grey for empty
        }
    }

    @Override
    public int getItemCount() {
        return imageUrls.size();
    }
}
