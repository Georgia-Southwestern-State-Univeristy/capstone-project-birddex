package com.birddex.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;

public class ProfileFavoriteCardsAdapter
        extends RecyclerView.Adapter<ProfileFavoriteCardsAdapter.VH> {

    public interface OnFavoriteSlotClickListener {
        void onFavoriteSlotClick(int position, @Nullable CollectionSlot slot);
    }

    private final List<CollectionSlot> slots = new ArrayList<>();
    private final OnFavoriteSlotClickListener listener;

    public ProfileFavoriteCardsAdapter(@Nullable List<CollectionSlot> initialSlots,
                                       @Nullable OnFavoriteSlotClickListener listener) {
        this.listener = listener;
        setSlots(initialSlots);
    }

    public void setSlots(@Nullable List<CollectionSlot> newSlots) {
        slots.clear();

        if (newSlots != null) {
            for (CollectionSlot slot : newSlots) {
                if (slots.size() >= 3) break;
                slots.add(slot);
            }
        }

        while (slots.size() < 3) {
            slots.add(null);
        }

        notifyDataSetChanged();
    }

    @Nullable
    public CollectionSlot getSlot(int position) {
        if (position < 0 || position >= slots.size()) return null;
        return slots.get(position);
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView txtBirdName;
        ImageView imgBird;
        TextView txtScientific;
        TextView txtRarity;

        VH(@NonNull View itemView) {
            super(itemView);
            txtBirdName = itemView.findViewById(R.id.txtBirdName);
            imgBird = itemView.findViewById(R.id.imgBird);
            txtScientific = itemView.findViewById(R.id.txtScientific);
            txtRarity = itemView.findViewById(R.id.txtRarity);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_profile_favorite_slot, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        CollectionSlot slot = slots.get(position);
        boolean hasCard = slot != null
                && slot.getImageUrl() != null
                && !slot.getImageUrl().trim().isEmpty();

        if (hasCard) {
            String common = safe(slot.getCommonName(), "UNKNOWN");
            String scientific = safe(slot.getScientificName(), "--");
            String rarity = safe(slot.getRarity(), "Favorite Card");

            holder.txtBirdName.setText(common);
            holder.txtScientific.setText(scientific);
            holder.txtRarity.setText(rarity);

            Glide.with(holder.itemView.getContext())
                    .load(slot.getImageUrl())
                    .placeholder(R.drawable.birddexlogo)
                    .error(R.drawable.birddexlogo)
                    .centerCrop()
                    .into(holder.imgBird);

            holder.itemView.setAlpha(1f);
        } else {
            holder.txtBirdName.setText("UNKNOWN");
            holder.txtScientific.setText("--");
            holder.txtRarity.setText("Tap to choose");
            holder.imgBird.setImageResource(R.drawable.birddexlogo);
            holder.itemView.setAlpha(0.95f);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onFavoriteSlotClick(position, slot);
            }
        });
    }

    @Override
    public int getItemCount() {
        return 3;
    }

    @NonNull
    private String safe(@Nullable String value, @NonNull String fallback) {
        return (value == null || value.trim().isEmpty()) ? fallback : value;
    }
}