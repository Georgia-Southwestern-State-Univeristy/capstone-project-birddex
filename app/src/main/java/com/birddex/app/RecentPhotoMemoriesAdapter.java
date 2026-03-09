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

/**
 * RecentPhotoMemoriesAdapter displays a gallery of bird photos.
 * Fixes: Added isDialogShowing guard to prevent duplicate dialogs.
 */
public class RecentPhotoMemoriesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_PHOTO = 1;

    private final Context context;
    private final Runnable onPhotosChanged;
    private final List<MemoryItem> items = new ArrayList<>();
    private boolean isDialogShowing = false;

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
        if (viewType == TYPE_HEADER) return new HeaderVH(inflater.inflate(R.layout.item_recent_photo_header, parent, false));
        else return new PhotoVH(inflater.inflate(R.layout.item_recent_photo, parent, false));
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
        Glide.with(photoVH.itemView.getContext()).load(item.imageUrl).centerCrop().into(photoVH.ivPhoto);
        photoVH.itemView.setOnClickListener(v -> showImagePreviewDialog(item));
    }

    private void showImagePreviewDialog(MemoryItem item) {
        if (isDialogShowing) return;
        isDialogShowing = true;

        Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.dialog_recent_photo_preview);
        dialog.setOnDismissListener(d -> isDialogShowing = false);

        ImageView ivPreview = dialog.findViewById(R.id.ivRecentPhotoPreview);
        TextView tvDate = dialog.findViewById(R.id.tvRecentPhotoPreviewDate);
        Button btnSave = dialog.findViewById(R.id.btnSaveToCameraRoll);
        Button btnDelete = dialog.findViewById(R.id.btnDeleteRecentPhoto);

        tvDate.setText(item.photoDate);
        Glide.with(context).load(item.imageUrl).fitCenter().into(ivPreview);

        btnSave.setOnClickListener(v -> saveImageToCameraRoll(item.imageUrl));
        btnDelete.setOnClickListener(v -> {
            new AlertDialog.Builder(context)
                    .setTitle("Delete Photo")
                    .setMessage("Are you sure?")
                    .setPositiveButton("Delete", (d, w) -> deletePhoto(item, dialog))
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        ivPreview.setOnClickListener(v -> dialog.dismiss());
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.show();
    }

    private void deletePhoto(MemoryItem item, Dialog dialog) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        FirebaseFirestore.getInstance().collection("users").document(user.getUid())
                .collection("userBirdImage").document(item.documentId).delete()
                .addOnSuccessListener(aVoid -> {
                    dialog.dismiss();
                    if (onPhotosChanged != null) onPhotosChanged.run();
                });
    }

    private void saveImageToCameraRoll(String imageUrl) {
        Glide.with(context).asBitmap().load(imageUrl).into(new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, "BirdDex_" + System.currentTimeMillis() + ".jpg");
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BirdDex");
                        values.put(MediaStore.Images.Media.IS_PENDING, 1);
                    }
                    Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) return;
                    OutputStream os = context.getContentResolver().openOutputStream(uri);
                    if (os == null) return;
                    resource.compress(Bitmap.CompressFormat.JPEG, 95, os);
                    os.flush(); os.close();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
                        context.getContentResolver().update(uri, values, null, null);
                    }
                    Toast.makeText(context, "Saved!", Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            }
            @Override public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {}
        });
    }

    @Override public int getItemCount() { return items.size(); }

    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView tvHeader;
        HeaderVH(@NonNull View itemView) { super(itemView); tvHeader = itemView.findViewById(R.id.tvRecentHeader); }
    }

    static class PhotoVH extends RecyclerView.ViewHolder {
        ImageView ivPhoto; TextView tvDate;
        PhotoVH(@NonNull View itemView) { super(itemView); ivPhoto = itemView.findViewById(R.id.ivRecentPhoto); tvDate = itemView.findViewById(R.id.tvRecentPhotoDate); }
    }

    public static class MemoryItem {
        int type; String headerTitle, imageUrl, photoDate, documentId;
        static MemoryItem createHeader(String title) { MemoryItem item = new MemoryItem(); item.type = TYPE_HEADER; item.headerTitle = title; return item; }
        static MemoryItem createPhoto(String url, String date, String id) { MemoryItem item = new MemoryItem(); item.type = TYPE_PHOTO; item.imageUrl = url; item.photoDate = date; item.documentId = id; return item; }
    }
}
