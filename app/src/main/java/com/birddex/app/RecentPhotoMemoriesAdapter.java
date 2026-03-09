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
/**
 * RecentPhotoMemoriesAdapter: Adapter that converts model data into rows/cards for a RecyclerView or similar list UI.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class RecentPhotoMemoriesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_PHOTO = 1;

    private final Context context;
    private final Runnable onPhotosChanged;
    private final List<MemoryItem> items = new ArrayList<>();
    private boolean isDialogShowing = false;

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public RecentPhotoMemoriesAdapter(Context context, Runnable onPhotosChanged) {
        this.context = context;
        this.onPhotosChanged = onPhotosChanged;
    }

    /**
     * Main logic block for this part of the feature.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    public void submitList(List<MemoryItem> newItems) {
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
        else return new PhotoVH(inflater.inflate(R.layout.item_recent_photo, parent, false));
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
        MemoryItem item = items.get(position);
        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).tvHeader.setText(item.headerTitle);
            return;
        }
        PhotoVH photoVH = (PhotoVH) holder;
        photoVH.tvDate.setText(item.photoDate);
        // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
        Glide.with(photoVH.itemView.getContext()).load(item.imageUrl).centerCrop().into(photoVH.ivPhoto);
        // Attach the user interaction that should run when this control is tapped.
        photoVH.itemView.setOnClickListener(v -> showImagePreviewDialog(item));
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * Image loading happens here, which is why placeholder/error behavior for profile
     * photos/cards/posts usually traces back to this code path.
     */
    private void showImagePreviewDialog(MemoryItem item) {
        if (isDialogShowing) return;
        isDialogShowing = true;

        Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.dialog_recent_photo_preview);
        dialog.setOnDismissListener(d -> isDialogShowing = false);

        // Bind or inflate the UI pieces this method needs before it can update the screen.
        ImageView ivPreview = dialog.findViewById(R.id.ivRecentPhotoPreview);
        TextView tvDate = dialog.findViewById(R.id.tvRecentPhotoPreviewDate);
        Button btnSave = dialog.findViewById(R.id.btnSaveToCameraRoll);
        Button btnDelete = dialog.findViewById(R.id.btnDeleteRecentPhoto);

        tvDate.setText(item.photoDate);
        // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
        Glide.with(context).load(item.imageUrl).fitCenter().into(ivPreview);

        // Attach the user interaction that should run when this control is tapped.
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

    /**
     * Removes data/listeners/items and then updates local state so the UI matches the deletion.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void deletePhoto(MemoryItem item, Dialog dialog) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        FirebaseFirestore.getInstance().collection("users").document(user.getUid())
                // Persist the new state so the action is saved outside the current screen.
                .collection("userBirdImage").document(item.documentId).delete()
                .addOnSuccessListener(aVoid -> {
                    dialog.dismiss();
                    if (onPhotosChanged != null) onPhotosChanged.run();
                });
    }

    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     * Image loading happens here, which is why placeholder/error behavior for profile
     * photos/cards/posts usually traces back to this code path.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void saveImageToCameraRoll(String imageUrl) {
        // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
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
                        // Persist the new state so the action is saved outside the current screen.
                        context.getContentResolver().update(uri, values, null, null);
                    }
                    // Give the user immediate feedback about the result of this action.
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
