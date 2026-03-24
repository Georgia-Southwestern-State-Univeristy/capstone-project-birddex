package com.birddex.app;

import android.content.Intent;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/**
 * CollectionCardAdapter: Adapter that converts model data into rows/cards for a RecyclerView or similar list UI.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class CollectionCardAdapter extends RecyclerView.Adapter<CollectionCardAdapter.VH> {

    public static final String EXTRA_IMAGE_URL = "com.birddex.app.extra.IMAGE_URL";
    public static final String EXTRA_COMMON_NAME = "com.birddex.app.extra.COMMON_NAME";
    public static final String EXTRA_SCI_NAME = "com.birddex.app.extra.SCI_NAME";
    public static final String EXTRA_STATE = "com.birddex.app.extra.STATE";
    public static final String EXTRA_LOCALITY = "com.birddex.app.extra.LOCALITY";
    public static final String EXTRA_CAUGHT_TIME = "com.birddex.app.extra.CAUGHT_TIME";
    public static final String EXTRA_BIRD_ID = "com.birddex.app.extra.BIRD_ID";
    public static final String EXTRA_SLOT_ID = "com.birddex.app.extra.SLOT_ID";
    public static final String EXTRA_RARITY = "com.birddex.app.extra.RARITY";
    public static final String EXTRA_IS_FAVORITE = "com.birddex.app.extra.IS_FAVORITE";

    private final List<CollectionSlot> slots;
    private boolean isNavigating = false;

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public CollectionCardAdapter(@NonNull List<CollectionSlot> slots) {
        this.slots = slots;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setNavigating(boolean navigating) {
        this.isNavigating = navigating;
    }

    static class VH extends RecyclerView.ViewHolder {
        CardView cardContainer;
        View cardInner;
        TextView txtBirdName;
        ImageView imgBird;
        TextView txtScientific, txtLocation, txtDateCaught, txtFooter;

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
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        View v = LayoutInflater.from(parent.getContext()).inflate(viewType, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int spacing = (int) (2 * density); // Tiny spacing back between cards
        int parentWidth = parent.getMeasuredWidth() > 0 ? parent.getMeasuredWidth() : parent.getResources().getDisplayMetrics().widthPixels;
        int availableWidth = parentWidth - parent.getPaddingLeft() - parent.getPaddingRight() - (spacing * 4);
        int itemWidth = Math.max(1, availableWidth / 3);

        // Use WRAP_CONTENT for height to remove white space at the bottom
        v.setLayoutParams(new RecyclerView.LayoutParams(itemWidth, ViewGroup.LayoutParams.WRAP_CONTENT));

        VH holder = new VH(v);
        applyCompactCollectionStyle(holder);
        return holder;
    }

    @Override
    public int getItemViewType(int position) {
        CollectionSlot slot = slots.get(position);
        return CardRarityHelper.getLayoutResId(slot != null ? slot.getRarity() : CardRarityHelper.COMMON);
    }

    /**
     * Main logic block for this part of the feature.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private void applyCompactCollectionStyle(@NonNull VH holder) {
        float d = holder.itemView.getResources().getDisplayMetrics().density;

        if (holder.cardContainer != null) {
            // Disable compat padding to prevent extra "white space" borders on some OS versions
            holder.cardContainer.setUseCompatPadding(false);

            ViewGroup.LayoutParams baseParams = holder.cardContainer.getLayoutParams();
            if (baseParams instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) baseParams;
                // Tiny margins for a sleeker grid look
                int m = (int) (3 * d); // Restored a small margin (3dp)
                lp.setMargins(m, m, m, m);
                holder.cardContainer.setLayoutParams(lp);
            }
            holder.cardContainer.setRadius(12 * d);

            // Allow card to wrap its content
            ViewGroup.LayoutParams conLp = holder.cardContainer.getLayoutParams();
            if (conLp != null) {
                conLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                holder.cardContainer.setLayoutParams(conLp);
            }
        }

        if (holder.cardInner != null) {
            // Tiny inner padding back to give image and text a small buffer
            int p = (int) (4 * d); // Restored a small inner padding (4dp)
            holder.cardInner.setPadding(p, p, p, p);
        }

        if (holder.txtBirdName != null) {
            holder.txtBirdName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            // Reserve a bit more vertical room for long names while capping runaway growth.
            holder.txtBirdName.setMinLines(2);
            holder.txtBirdName.setMaxLines(3);
            holder.txtBirdName.setEllipsize(TextUtils.TruncateAt.END);
            holder.txtBirdName.setGravity(Gravity.CENTER);

            // Adjust margins/padding if needed
            if (holder.txtBirdName.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holder.txtBirdName.getLayoutParams();
                lp.topMargin = (int) (2 * d);
                lp.bottomMargin = (int) (2 * d);
                holder.txtBirdName.setLayoutParams(lp);
            }
        }

        if (holder.imgBird != null) {
            ViewGroup.LayoutParams imgLp = holder.imgBird.getLayoutParams();
            if (imgLp != null) {
                // Keep cards compact while giving header text an extra line of room.
                imgLp.height = (int) (128 * d);
                holder.imgBird.setLayoutParams(imgLp);
                // Use fitCenter to ensure the image is not cut off
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
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        CollectionSlot slot = slots.get(position);
        String url = slot != null ? slot.getImageUrl() : null;
        boolean hasImage = url != null && !url.trim().isEmpty();

        // Attach the user interaction that should run when this control is tapped.
        holder.itemView.setOnClickListener(v -> {
            if (!hasImage || isNavigating || slot == null) return;
            isNavigating = true;
            Intent i = new Intent(v.getContext(), ViewBirdCardActivity.class);
            i.putExtra(EXTRA_IMAGE_URL, url);
            i.putExtra(EXTRA_COMMON_NAME, slot.getCommonName());
            i.putExtra(EXTRA_SCI_NAME, slot.getScientificName());
            i.putExtra(EXTRA_STATE, slot.getState());
            i.putExtra(EXTRA_LOCALITY, slot.getLocality());
            i.putExtra(EXTRA_BIRD_ID, slot.getBirdId());
            i.putExtra(EXTRA_SLOT_ID, slot.getId());
            i.putExtra(EXTRA_RARITY, slot.getRarity());
            i.putExtra(EXTRA_IS_FAVORITE, slot.isFavorite());
            if (slot.getTimestamp() != null) i.putExtra(EXTRA_CAUGHT_TIME, slot.getTimestamp().getTime());
            ViewBirdCardActivity.attachSwipeExtras(i, slots, holder.getBindingAdapterPosition());
            // Move into the next screen and pass the identifiers/data that screen needs.
            v.getContext().startActivity(i);
        });

        if (hasImage && slot != null) {
            String n = slot.getCommonName(), s = slot.getScientificName();
            if (holder.txtBirdName != null) holder.txtBirdName.setText(!isBlank(n) ? n : (!isBlank(s) ? s : "Unknown Bird"));
            if (holder.txtScientific != null) holder.txtScientific.setText(!isBlank(s) ? s : "--");
            if (holder.txtLocation != null) holder.txtLocation.setText(CardFormatUtils.formatLocation(slot.getState(), slot.getLocality()));
            if (holder.txtDateCaught != null) holder.txtDateCaught.setText(slot.getTimestamp() != null ? new SimpleDateFormat("M/d/yy", Locale.US).format(slot.getTimestamp()) : "--");
            if (holder.imgBird != null) {
                // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
                Glide.with(holder.itemView.getContext())
                        .load(url)
                        .fitCenter()
                        .placeholder(R.drawable.bg_image_placeholder)
                        .into(holder.imgBird);
            }
            holder.itemView.setAlpha(1f);
        } else {
            if (holder.txtBirdName != null) holder.txtBirdName.setText("Unknown Bird");
            if (holder.txtScientific != null) holder.txtScientific.setText("--");
            if (holder.txtLocation != null) holder.txtLocation.setText("Location: --");
            if (holder.txtDateCaught != null) holder.txtDateCaught.setText("Date: --");
            if (holder.imgBird != null) {
                holder.imgBird.setImageResource(R.drawable.birddexlogo);
                holder.imgBird.setScaleType(ImageView.ScaleType.FIT_CENTER);
            }
            holder.itemView.setAlpha(0.88f);
        }
    }

    @Override
    public int getItemCount() {
        return slots.size();
    }

    private boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }
}