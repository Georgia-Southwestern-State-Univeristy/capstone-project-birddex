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

public class FavoritesAdapter extends RecyclerView.Adapter<FavoritesAdapter.VH> {

    public interface OnFavoriteInteractionListener {
        void onFavoriteClicked(int position, @Nullable CollectionSlot slot);
        boolean onFavoriteLongPressed(int position, @Nullable CollectionSlot slot);
    }

    private final List<CollectionSlot> items = new ArrayList<>();
    private final boolean editable;
    private final OnFavoriteInteractionListener listener;

    public FavoritesAdapter(boolean editable, @NonNull OnFavoriteInteractionListener listener) {
        this.editable = editable;
        this.listener = listener;
    }

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

        VH(@NonNull View itemView) {
            super(itemView);
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

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.view_bird_card, parent, false);
        VH holder = new VH(v);
        applyCompactFavoriteStyle(holder);
        return holder;
    }

    private void applyCompactFavoriteStyle(@NonNull VH holder) {
        float density = holder.itemView.getResources().getDisplayMetrics().density;

        RecyclerView.LayoutParams rootLp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rootLp.bottomMargin = (int) (4 * density);
        holder.itemView.setLayoutParams(rootLp);

        ViewGroup.MarginLayoutParams cardLp =
                (ViewGroup.MarginLayoutParams) holder.cardContainer.getLayoutParams();
        cardLp.setMargins((int) (2 * density), (int) (2 * density), (int) (2 * density), (int) (2 * density));
        holder.cardContainer.setLayoutParams(cardLp);
        holder.cardContainer.setRadius(14 * density);

        int compactPadding = (int) (6 * density);
        holder.cardInner.setPadding(compactPadding, compactPadding, compactPadding, compactPadding);

        holder.txtBirdName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        holder.txtScientific.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7);
        holder.txtLocation.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7);
        holder.txtDateCaught.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7);

        holder.txtBirdName.setMinLines(2);
        holder.txtBirdName.setMaxLines(2);
        holder.txtBirdName.setEllipsize(TextUtils.TruncateAt.END);
        holder.txtBirdName.setGravity(Gravity.CENTER);

        holder.txtScientific.setMaxLines(1);
        holder.txtScientific.setEllipsize(TextUtils.TruncateAt.END);

        holder.txtLocation.setMaxLines(2);
        holder.txtLocation.setEllipsize(TextUtils.TruncateAt.END);
        holder.txtLocation.setGravity(Gravity.CENTER_HORIZONTAL);

        holder.txtDateCaught.setMaxLines(1);
        holder.txtDateCaught.setEllipsize(TextUtils.TruncateAt.END);
        holder.txtDateCaught.setGravity(Gravity.CENTER_HORIZONTAL);

        holder.txtFooter.setVisibility(View.GONE);

        ViewGroup.LayoutParams imageLp = holder.imgBird.getLayoutParams();
        imageLp.height = (int) (82 * density);
        holder.imgBird.setLayoutParams(imageLp);

        ViewGroup.LayoutParams containerLp = holder.cardContainer.getLayoutParams();
        containerLp.height = (int) (210 * density);
        holder.cardContainer.setLayoutParams(containerLp);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        CollectionSlot slot = items.get(position);
        boolean hasSlot = slot != null && slot.getImageUrl() != null && !slot.getImageUrl().trim().isEmpty();

        if (hasSlot) {
            String common = slot.getCommonName();
            String sci = slot.getScientificName();

            holder.txtBirdName.setText(!isBlank(common) ? common : (!isBlank(sci) ? sci : "Unknown Bird"));
            holder.txtScientific.setText(!isBlank(sci) ? sci : "--");
            holder.txtLocation.setText(CardFormatUtils.formatLocation(slot.getState(), slot.getLocality()));
            holder.txtDateCaught.setText(formatShortDate(slot.getTimestamp()));

            Glide.with(holder.itemView.getContext())
                    .load(slot.getImageUrl())
                    .placeholder(R.drawable.bg_image_placeholder)
                    .fitCenter()
                    .into(holder.imgBird);

            holder.itemView.setAlpha(1f);
        } else {
            holder.txtBirdName.setText(editable ? "Tap to add" : "No favorite");
            holder.txtScientific.setText("Favorite slot");
            holder.txtLocation.setText("");
            holder.txtDateCaught.setText(editable ? "Choose from collection" : "Nothing selected");
            holder.imgBird.setImageResource(R.drawable.birddexlogo);
            holder.itemView.setAlpha(0.94f);
        }

        holder.itemView.setOnClickListener(v -> listener.onFavoriteClicked(position, slot));
        holder.itemView.setOnLongClickListener(v -> editable && listener.onFavoriteLongPressed(position, slot));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private String formatShortDate(Date date) {
        if (date == null) return "--";
        return new SimpleDateFormat("M/d/yy", Locale.US).format(date);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}