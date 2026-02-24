package com.birddex.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.List;

public class CollectionCardAdapter extends RecyclerView.Adapter<CollectionCardAdapter.VH> {

    private final List<CollectionSlot> slots;

    public CollectionCardAdapter(@NonNull List<CollectionSlot> slots) {
        this.slots = slots;
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView txtBirdName;
        ImageView imageView3;
        TextView txtScientific;
        TextView txtRarity;

        VH(@NonNull View itemView) {
            super(itemView);
            txtBirdName = itemView.findViewById(R.id.txtBirdName);
            imageView3 = itemView.findViewById(R.id.imageView3);
            txtScientific = itemView.findViewById(R.id.txtScientific);
            txtRarity = itemView.findViewById(R.id.txtRarity);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_collection_cell_unknown, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        CollectionSlot slot = slots.get(position);
        String url = slot != null ? slot.getImageUrl() : null;
        String rarity = slot != null ? slot.getRarity() : null;

        String common = slot != null ? slot.getCommonName() : null;
        String sci = slot != null ? slot.getScientificName() : null;

        boolean hasImage = url != null && !url.trim().isEmpty();

        if (hasImage) {
            if (common != null && !common.trim().isEmpty()) {
                holder.txtBirdName.setText(common);
            } else if (sci != null && !sci.trim().isEmpty()) {
                holder.txtBirdName.setText(sci); // fallback to scientific name
            } else {
                holder.txtBirdName.setText("Unknown Bird"); // never show "Captured"
            }

            // Scientific line (optional)
            if (sci != null && !sci.trim().isEmpty()) holder.txtScientific.setText(sci);
            else holder.txtScientific.setText("--");

            // Rarity
            if (rarity != null && !rarity.trim().isEmpty()) holder.txtRarity.setText("Rarity: " + rarity);
            else holder.txtRarity.setText("Rarity: --");

            Glide.with(holder.itemView.getContext())
                    .load(url)
                    .centerCrop()
                    .into(holder.imageView3);
        } else {
            holder.txtBirdName.setText("UNKNOWN");
            holder.txtScientific.setText("--");
            holder.txtRarity.setText("Rarity: Unknown");
            holder.imageView3.setImageResource(R.drawable.birddexlogo);
        }
    }

    @Override
    public int getItemCount() {
        return slots.size();
    }
}