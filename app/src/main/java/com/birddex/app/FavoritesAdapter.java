package com.birddex.app;

import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * FavoritesAdapter: Adapter that converts model data into rows/cards for a RecyclerView or similar list UI.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class FavoritesAdapter extends RecyclerView.Adapter<FavoritesAdapter.VH> {

    public interface OnFavoriteInteractionListener {
        void onFavoriteClicked(int position, @Nullable CollectionSlot slot);
        boolean onFavoriteLongPressed(int position, @Nullable CollectionSlot slot);
    }

    private final List<CollectionSlot> items = new ArrayList<>();
    private final boolean editable;
    private final OnFavoriteInteractionListener listener;

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public FavoritesAdapter(boolean editable, @NonNull OnFavoriteInteractionListener listener) {
        this.editable = editable;
        this.listener = listener;
    }

    /**
     * Main logic block for this part of the feature.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    public void submitList(@NonNull List<CollectionSlot> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    static class VH extends RecyclerView.ViewHolder {
        CardView cardContainer;
        View cardInner;
        TextView txtBirdName;
        ImageView imgBird;
        TextView txtScientific;
        TextView txtLocation;
        TextView txtDateCaught;
        TextView txtFooter;

        /**
         * Main logic block for this part of the feature.
         * It grabs layout/view references here so later code can read from them, update them, or
         * attach listeners.
         * Location values are handled here, so this is part of the logic that decides what area/bird
         * sightings the user sees.
         */
        VH(@NonNull View itemView) {
            super(itemView);
            // Bind or inflate the UI pieces this method needs before it can update the screen.
            cardContainer = itemView.findViewById(R.id.cardContainer);
            cardInner = itemView.findViewById(R.id.cardInner);
            txtBirdName = itemView.findViewById(R.id.txtBirdName);
            imgBird = itemView.findViewById(R.id.imgBird);
            txtScientific = itemView.findViewById(R.id.txtScientific);
            txtLocation = itemView.findViewById(R.id.txtLocation);
            txtDateCaught = itemView.findViewById(R.id.txtDateCaught);
            txtFooter = itemView.findViewById(R.id.txtFooter);
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
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        View v = LayoutInflater.from(parent.getContext()).inflate(viewType, parent, false);
        VH holder = new VH(v);
        applyCompactFavoriteStyle(holder);
        return holder;
    }

    @Override
    public int getItemViewType(int position) {
        CollectionSlot slot = items.get(position);
        return CardRarityHelper.getLayoutResId(slot != null ? slot.getRarity() : CardRarityHelper.COMMON);
    }

    /**
     * Main logic block for this part of the feature.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private void applyCompactFavoriteStyle(@NonNull VH holder) {
        float density = holder.itemView.getResources().getDisplayMetrics().density;

        RecyclerView.LayoutParams rootLp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rootLp.bottomMargin = (int) (4 * density);
        holder.itemView.setLayoutParams(rootLp);

        if (holder.cardContainer != null) {
            ViewGroup.LayoutParams baseParams = holder.cardContainer.getLayoutParams();
            if (baseParams instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams cardLp = (ViewGroup.MarginLayoutParams) baseParams;
                cardLp.setMargins((int) (2 * density), (int) (2 * density), (int) (2 * density), (int) (2 * density));
                holder.cardContainer.setLayoutParams(cardLp);
            }
            holder.cardContainer.setRadius(14 * density);
            ViewGroup.LayoutParams containerLp = holder.cardContainer.getLayoutParams();
            if (containerLp != null) {
                containerLp.height = (int) (210 * density);
                holder.cardContainer.setLayoutParams(containerLp);
            }
        }

        if (holder.cardInner != null) {
            int compactPadding = (int) (6 * density);
            holder.cardInner.setPadding(compactPadding, compactPadding, compactPadding, compactPadding);
        }

        if (holder.txtBirdName != null) {
            holder.txtBirdName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            holder.txtBirdName.setMinLines(2);
            holder.txtBirdName.setMaxLines(2);
            holder.txtBirdName.setEllipsize(TextUtils.TruncateAt.END);
            holder.txtBirdName.setGravity(Gravity.CENTER);
        }

        if (holder.txtScientific != null) {
            holder.txtScientific.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7);
            holder.txtScientific.setMaxLines(1);
            holder.txtScientific.setEllipsize(TextUtils.TruncateAt.END);
        }

        if (holder.txtLocation != null) {
            holder.txtLocation.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7);
            holder.txtLocation.setMaxLines(2);
            holder.txtLocation.setEllipsize(TextUtils.TruncateAt.END);
            holder.txtLocation.setGravity(Gravity.CENTER_HORIZONTAL);
        }

        if (holder.txtDateCaught != null) {
            holder.txtDateCaught.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7);
            holder.txtDateCaught.setMaxLines(1);
            holder.txtDateCaught.setEllipsize(TextUtils.TruncateAt.END);
            holder.txtDateCaught.setGravity(Gravity.CENTER_HORIZONTAL);
        }

        if (holder.txtFooter != null) {
            holder.txtFooter.setVisibility(View.GONE);
        }

        if (holder.imgBird != null && holder.imgBird.getLayoutParams() != null) {
            ViewGroup.LayoutParams imageLp = holder.imgBird.getLayoutParams();
            imageLp.height = (int) (82 * density);
            holder.imgBird.setLayoutParams(imageLp);
        }
    }

    /**
     * Main logic block for this part of the feature.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * Image loading happens here, which is why placeholder/error behavior for profile
     * photos/cards/posts usually traces back to this code path.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        CollectionSlot slot = items.get(position);
        boolean hasSlot = slot != null && slot.getImageUrl() != null && !slot.getImageUrl().trim().isEmpty();

        if (hasSlot && slot != null) {
            String common = slot.getCommonName();
            String sci = slot.getScientificName();

            if (holder.txtBirdName != null) holder.txtBirdName.setText(!isBlank(common) ? common : (!isBlank(sci) ? sci : "Unknown Bird"));
            if (holder.txtScientific != null) holder.txtScientific.setText(!isBlank(sci) ? sci : "--");
            if (holder.txtLocation != null) holder.txtLocation.setText(CardFormatUtils.formatLocation(slot.getState(), slot.getLocality()));
            if (holder.txtDateCaught != null) holder.txtDateCaught.setText(formatShortDate(slot.getTimestamp()));

            if (holder.imgBird != null) {
                // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
                Glide.with(holder.itemView.getContext())
                        .load(slot.getImageUrl())
                        .placeholder(R.drawable.bg_image_placeholder)
                        .fitCenter()
                        .into(holder.imgBird);
            }

            holder.itemView.setAlpha(1f);
        } else {
            if (holder.txtBirdName != null) holder.txtBirdName.setText(editable ? "Tap to add" : "No favorite");
            if (holder.txtScientific != null) holder.txtScientific.setText("Favorite slot");
            if (holder.txtLocation != null) holder.txtLocation.setText("");
            if (holder.txtDateCaught != null) holder.txtDateCaught.setText(editable ? "Choose from collection" : "Nothing selected");
            if (holder.imgBird != null) holder.imgBird.setImageResource(R.drawable.birddexlogo);
            holder.itemView.setAlpha(0.94f);
        }

        // Attach the user interaction that should run when this control is tapped.
        holder.itemView.setOnClickListener(v -> listener.onFavoriteClicked(position, slot));
        holder.itemView.setOnLongClickListener(v -> editable && listener.onFavoriteLongPressed(position, slot));
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    @Override
    public int getItemCount() {
        return items.size();
    }

    /**
     * Main logic block for this part of the feature.
     */
    private String formatShortDate(Date date) {
        if (date == null) return "--";
        return new SimpleDateFormat("M/d/yy", Locale.US).format(date);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}