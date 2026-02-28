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

public class ChangeCardImageBrowserAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_PHOTO = 1;

    private final List<BrowserItem> items = new ArrayList<>();
    private final OnPhotoClickListener onPhotoClickListener;

    public ChangeCardImageBrowserAdapter(@NonNull OnPhotoClickListener onPhotoClickListener) {
        this.onPhotoClickListener = onPhotoClickListener;
    }

    public void submitList(@NonNull List<BrowserItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public boolean isHeader(int position) {
        if (position < 0 || position >= items.size()) return false;
        return items.get(position).type == TYPE_HEADER;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());

        if (viewType == TYPE_HEADER) {
            View view = inflater.inflate(R.layout.item_recent_photo_header, parent, false);
            return new HeaderVH(view);
        }

        View view = inflater.inflate(R.layout.item_change_card_image_photo, parent, false);
        return new PhotoVH(view);
    }

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

        Glide.with(photoVH.itemView.getContext())
                .load(item.imageUrl)
                .centerCrop()
                .into(photoVH.ivPhoto);

        photoVH.itemView.setOnClickListener(v -> onPhotoClickListener.onPhotoClick(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView tvHeader;

        HeaderVH(@NonNull View itemView) {
            super(itemView);
            tvHeader = itemView.findViewById(R.id.tvRecentHeader);
        }
    }

    static class PhotoVH extends RecyclerView.ViewHolder {
        final ImageView ivPhoto;
        final TextView tvDate;
        final TextView tvCurrentBadge;

        PhotoVH(@NonNull View itemView) {
            super(itemView);
            ivPhoto = itemView.findViewById(R.id.ivRecentPhoto);
            tvDate = itemView.findViewById(R.id.tvRecentPhotoDate);
            tvCurrentBadge = itemView.findViewById(R.id.tvCurrentBadge);
        }
    }

    public interface OnPhotoClickListener {
        void onPhotoClick(@NonNull BrowserItem item);
    }

    public static class BrowserItem {
        int type;
        String headerTitle;
        String imageUrl;
        String photoDate;
        Date timestamp;
        String userBirdRefId;
        boolean isCurrent;

        static BrowserItem createHeader(String title) {
            BrowserItem item = new BrowserItem();
            item.type = TYPE_HEADER;
            item.headerTitle = title;
            return item;
        }

        static BrowserItem createPhoto(String imageUrl,
                                       String photoDate,
                                       Date timestamp,
                                       String userBirdRefId,
                                       boolean isCurrent) {
            BrowserItem item = new BrowserItem();
            item.type = TYPE_PHOTO;
            item.imageUrl = imageUrl;
            item.photoDate = photoDate;
            item.timestamp = timestamp;
            item.userBirdRefId = userBirdRefId;
            item.isCurrent = isCurrent;
            return item;
        }
    }
}