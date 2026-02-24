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

/**
 * Shows the user's collection as a vertical list of "cards".
 *
 * - Always expects 15 items (slotIndex 0..14)
 * - If imageUrl is null/empty, it shows the UNKNOWN placeholder card.
 */
public class CollectionCardAdapter extends RecyclerView.Adapter<CollectionCardAdapter.VH> {

    private final List<CollectionSlot> slots; // slotIndex 0..14

    public CollectionCardAdapter(@NonNull List<CollectionSlot> slots) {
        this.slots = slots;
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView txtBirdName;
        ImageView imageView3;
        TextView txtScientific;
        TextView txtRarity;
        TextView txtConfidence;
        TextView txtFooter;

        VH(@NonNull View itemView) {
            super(itemView);
            txtBirdName = itemView.findViewById(R.id.txtBirdName);
            imageView3 = itemView.findViewById(R.id.imageView3);
            txtScientific = itemView.findViewById(R.id.txtScientific);
            txtRarity = itemView.findViewById(R.id.txtRarity);
            txtConfidence = itemView.findViewById(R.id.txtConfidence);
            txtFooter = itemView.findViewById(R.id.txtFooter);
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

        boolean hasImage = url != null && !url.trim().isEmpty();

        if (hasImage) {
            holder.txtBirdName.setText("CAPTURED BIRD");
            holder.txtScientific.setText("Scientific: --");
            holder.txtRarity.setText("Rarity: " + (rarity != null ? rarity : "--"));
            holder.txtConfidence.setText("Confidence: --");
            holder.txtFooter.setText("BirdDex • Captured");

            Glide.with(holder.itemView.getContext())
                    .load(url)
                    .centerCrop()
                    .into(holder.imageView3);
        } else {
            // Placeholder / unverified slot
            holder.txtBirdName.setText("UNKNOWN BIRD");
            holder.txtScientific.setText("Scientific: --");
            holder.txtRarity.setText("Rarity: Unknown");
            holder.txtConfidence.setText("Confidence: --");
            holder.txtFooter.setText("BirdDex • Not Yet Captured");

            holder.imageView3.setImageResource(R.drawable.birddexlogo);
        }
    }

    @Override
    public int getItemCount() {
        return slots.size();
    }
}