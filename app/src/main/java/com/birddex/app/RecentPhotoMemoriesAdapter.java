package com.birddex.app;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class RecentPhotoMemoriesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_PHOTO = 1;

    private final Context context;
    private final Runnable onPhotosChanged;
    private final List<MemoryItem> items = new ArrayList<>();

    public RecentPhotoMemoriesAdapter(Context context, Runnable onPhotosChanged) {
        this.context = context;
        this.onPhotosChanged = onPhotosChanged;
    }

    public void submitList(List<MemoryItem> newItems) {
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
        } else {
            View view = inflater.inflate(R.layout.item_recent_photo, parent, false);
            return new PhotoVH(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        MemoryItem item = items.get(position);

        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).tvHeader.setText(item.headerTitle);
            return;
        }

        PhotoVH photoVH = (PhotoVH) holder;
        photoVH.tvDate.setText(item.photoDate);

        Glide.with(photoVH.itemView.getContext())
                .load(item.imageUrl)
                .centerCrop()
                .into(photoVH.ivPhoto);

        photoVH.itemView.setOnClickListener(v -> showImagePreviewDialog(item));
    }

    private void showImagePreviewDialog(MemoryItem item) {
        Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.dialog_recent_photo_preview);

        ImageView ivPreview = dialog.findViewById(R.id.ivRecentPhotoPreview);
        TextView tvDate = dialog.findViewById(R.id.tvRecentPhotoPreviewDate);
        Button btnSaveToCameraRoll = dialog.findViewById(R.id.btnSaveToCameraRoll);
        Button btnDeleteImage = dialog.findViewById(R.id.btnDeleteRecentPhoto);

        tvDate.setText(item.photoDate);

        Glide.with(context)
                .load(item.imageUrl)
                .fitCenter()
                .into(ivPreview);

        btnSaveToCameraRoll.setOnClickListener(v -> saveImageToCameraRoll(item.imageUrl));
        btnDeleteImage.setOnClickListener(v -> {
            Toast.makeText(context, "Delete button is not wired up yet.", Toast.LENGTH_SHORT).show();
        });

        ivPreview.setOnClickListener(v -> dialog.dismiss());

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        dialog.show();
    }

    private void saveImageToCameraRoll(String imageUrl) {
        Glide.with(context)
                .asBitmap()
                .load(imageUrl)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource,
                                                @Nullable Transition<? super Bitmap> transition) {
                        try {
                            String fileName = "BirdDex_" + System.currentTimeMillis() + ".jpg";

                            ContentValues values = new ContentValues();
                            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BirdDex");
                                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                            }

                            Uri uri = context.getContentResolver().insert(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    values
                            );

                            if (uri == null) {
                                Toast.makeText(context, "Failed to save image.", Toast.LENGTH_SHORT).show();
                                return;
                            }

                            OutputStream outputStream = context.getContentResolver().openOutputStream(uri);
                            if (outputStream == null) {
                                Toast.makeText(context, "Failed to save image.", Toast.LENGTH_SHORT).show();
                                return;
                            }

                            resource.compress(Bitmap.CompressFormat.JPEG, 95, outputStream);
                            outputStream.flush();
                            outputStream.close();

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                values.clear();
                                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                                context.getContentResolver().update(uri, values, null, null);
                            }

                            Toast.makeText(context, "Saved to camera roll.", Toast.LENGTH_SHORT).show();

                        } catch (Exception e) {
                            Toast.makeText(context, "Failed to save image.", Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                    }
                });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView tvHeader;

        HeaderVH(@NonNull View itemView) {
            super(itemView);
            tvHeader = itemView.findViewById(R.id.tvRecentHeader);
        }
    }

    static class PhotoVH extends RecyclerView.ViewHolder {
        ImageView ivPhoto;
        TextView tvDate;

        PhotoVH(@NonNull View itemView) {
            super(itemView);
            ivPhoto = itemView.findViewById(R.id.ivRecentPhoto);
            tvDate = itemView.findViewById(R.id.tvRecentPhotoDate);
        }
    }

    public static class MemoryItem {
        int type;
        String headerTitle;
        String imageUrl;
        String photoDate;
        String documentId;

        static MemoryItem createHeader(String title) {
            MemoryItem item = new MemoryItem();
            item.type = TYPE_HEADER;
            item.headerTitle = title;
            return item;
        }

        static MemoryItem createPhoto(String imageUrl, String photoDate, String documentId) {
            MemoryItem item = new MemoryItem();
            item.type = TYPE_PHOTO;
            item.imageUrl = imageUrl;
            item.photoDate = photoDate;
            item.documentId = documentId;
            return item;
        }
    }
}