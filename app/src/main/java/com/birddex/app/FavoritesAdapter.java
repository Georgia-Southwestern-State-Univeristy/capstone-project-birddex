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

        if (holder.cardContainer != null) {
            // Disable compat padding to prevent white spacing issue
            holder.cardContainer.setUseCompatPadding(false);
            
            ViewGroup.LayoutParams baseParams = holder.cardContainer.getLayoutParams();
            if (baseParams instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams cardLp = (ViewGroup.MarginLayoutParams) baseParams;
                // Matching the 3dp margin from collection adapter
                int m = (int) (3 * density);
                cardLp.setMargins(m, m, m, m);
                holder.cardContainer.setLayoutParams(cardLp);
            }
            holder.cardContainer.setRadius(12 * density);
            
            // Allow card to wrap its content height
            ViewGroup.LayoutParams containerLp = holder.cardContainer.getLayoutParams();
            if (containerLp != null) {
                containerLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                holder.cardContainer.setLayoutParams(containerLp);
            }
        }

        if (holder.cardInner != null) {
            // Matching the 4dp inner padding from collection adapter
            int p = (int) (4 * density);
            holder.cardInner.setPadding(p, p, p, p);
        }

        if (holder.txtBirdName != null) {
            holder.txtBirdName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            holder.txtBirdName.setMinLines(2);
            holder.txtBirdName.setMaxLines(3);
            holder.txtBirdName.setEllipsize(TextUtils.TruncateAt.END);
            holder.txtBirdName.setGravity(Gravity.CENTER);
        }

        if (holder.imgBird != null) {
            ViewGroup.LayoutParams imageLp = holder.imgBird.getLayoutParams();
            if (imageLp != null) {
                imageLp.height = holder.itemView.getResources().getDimensionPixelSize(R.dimen.collection_card_image_height);
                holder.imgBird.setLayoutParams(imageLp);
                holder.imgBird.setScaleType(ImageView.ScaleType.FIT_CENTER);
            }
        }

        if (holder.txtScientific != null) {
            holder.txtScientific.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
            holder.txtScientific.setMaxLines(1);
            holder.txtScientific.setEllipsize(TextUtils.TruncateAt.END);
        }

        if (holder.txtLocation != null) {
            holder.txtLocation.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7);
            holder.txtLocation.setMaxLines(1);
            holder.txtLocation.setEllipsize(TextUtils.TruncateAt.END);
            holder.txtLocation.setGravity(Gravity.CENTER_HORIZONTAL);
        }

        if (holder.txtDateCaught != null) {
            holder.txtDateCaught.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
            holder.txtDateCaught.setGravity(Gravity.CENTER_HORIZONTAL);
            holder.txtDateCaught.setMaxLines(1);
        }

        if (holder.txtFooter != null) {
            holder.txtFooter.setVisibility(View.GONE);
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
        boolean hasSlot = slot != null && !isBlank(slot.getImageUrl());

        if (hasSlot) {
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
            if (holder.imgBird != null) {
                holder.imgBird.setImageResource(R.drawable.birddexlogo);
                holder.imgBird.setScaleType(ImageView.ScaleType.FIT_CENTER);
            }
            holder.itemView.setAlpha(0.88f);
        }

        // Attach the user interaction that should run when this control is tapped.
        holder.itemView.setOnClickListener(v -> listener.onFavoriteClicked(position, slot));
        holder.itemView.setOnLongClickListener(v -> {
            if (editable) {
                return listener.onFavoriteLongPressed(position, slot);
            }
            return false;
        });
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
