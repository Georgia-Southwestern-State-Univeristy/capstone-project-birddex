package com.birddex.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * ChangeCardImageBrowserAdapter displays user's photos for selection.
 * Fixes: Added isChoosing guard to prevent duplicate selection callbacks.
 */
/**
 * ChangeCardImageBrowserAdapter: Adapter that converts model data into rows/cards for a RecyclerView or similar list UI.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class ChangeCardImageBrowserAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_PHOTO = 1;

    private final List<BrowserItem> items = new ArrayList<>();
    private final OnPhotoClickListener onPhotoClickListener;
    private boolean isChoosing = false;

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public ChangeCardImageBrowserAdapter(@NonNull OnPhotoClickListener onPhotoClickListener) {
        this.onPhotoClickListener = onPhotoClickListener;
    }

    /**
     * Main logic block for this part of the feature.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    public void submitList(@NonNull List<BrowserItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    public boolean isHeader(int position) {
        if (position < 0 || position >= items.size()) return false;
        return items.get(position).type == TYPE_HEADER;
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    @Override
    public int getItemViewType(int position) {
        return items.get(position).type;
    }

    /**
     * Main logic block for this part of the feature.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        if (viewType == TYPE_HEADER) return new HeaderVH(inflater.inflate(R.layout.item_recent_photo_header, parent, false));
        return new PhotoVH(inflater.inflate(R.layout.item_change_card_image_photo, parent, false));
    }

    /**
     * Main logic block for this part of the feature.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     * Image loading happens here, which is why placeholder/error behavior for profile
     * photos/cards/posts usually traces back to this code path.
     */
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        BrowserItem item = items.get(position);
        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).tvHeader.setText(item.headerTitle);
            return;
        }
        PhotoVH photoVH = (PhotoVH) holder;
        photoVH.tvDate.setText(item.photoDate);
        photoVH.tvCurrentBadge.setVisibility(item.isCurrent ? View.VISIBLE : View.GONE);
        // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
        Glide.with(photoVH.itemView.getContext()).load(item.imageUrl).centerCrop().into(photoVH.ivPhoto);
        
        // Attach the user interaction that should run when this control is tapped.
        holder.itemView.setOnClickListener(v -> {
            if (isChoosing) return;
            isChoosing = true;
            onPhotoClickListener.onPhotoClick(item);
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView tvHeader;
        HeaderVH(@NonNull View itemView) { super(itemView); tvHeader = itemView.findViewById(R.id.tvRecentHeader); }
    }

    static class PhotoVH extends RecyclerView.ViewHolder {
        final ImageView ivPhoto;
        final TextView tvDate, tvCurrentBadge;
        /**
         * Main logic block for this part of the feature.
         * It grabs layout/view references here so later code can read from them, update them, or
         * attach listeners.
         */
        PhotoVH(@NonNull View itemView) {
            super(itemView);
            // Bind or inflate the UI pieces this method needs before it can update the screen.
            ivPhoto = itemView.findViewById(R.id.ivRecentPhoto);
            tvDate = itemView.findViewById(R.id.tvRecentPhotoDate);
            tvCurrentBadge = itemView.findViewById(R.id.tvCurrentBadge);
        }
    }

    public interface OnPhotoClickListener { void onPhotoClick(@NonNull BrowserItem item); }

    public static class BrowserItem {
        int type; String headerTitle, imageUrl, photoDate, userBirdRefId; Date timestamp; boolean isCurrent;
        static BrowserItem createHeader(String title) { BrowserItem item = new BrowserItem(); item.type = TYPE_HEADER; item.headerTitle = title; return item; }
        /**
         * Builds data from the current screen/object state and writes it out to storage, Firebase, or
         * another service.
         */
        static BrowserItem createPhoto(String url, String date, Date ts, String ref, boolean current) {
            BrowserItem item = new BrowserItem(); item.type = TYPE_PHOTO; item.imageUrl = url; item.photoDate = date; item.timestamp = ts; item.userBirdRefId = ref; item.isCurrent = current; return item;
        }
    }
}
