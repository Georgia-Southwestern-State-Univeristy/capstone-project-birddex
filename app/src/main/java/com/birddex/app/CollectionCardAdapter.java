package com.birddex.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import android.content.Intent;

import java.util.List;

public class CollectionCardAdapter extends RecyclerView.Adapter<CollectionCardAdapter.VH> {

    public static final String EXTRA_IMAGE_URL = "com.birddex.app.extra.IMAGE_URL";
    public static final String EXTRA_COMMON_NAME = "com.birddex.app.extra.COMMON_NAME";
    public static final String EXTRA_SCI_NAME = "com.birddex.app.extra.SCI_NAME";
    public static final String EXTRA_RARITY = "com.birddex.app.extra.RARITY";

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

        // Tap to open full card view (only if slot has an image)
        holder.itemView.setOnClickListener(v -> {
            if (!hasImage) return;

            Intent i = new Intent(v.getContext(), ViewBirdCardActivity.class);
            i.putExtra(EXTRA_IMAGE_URL, url);
            i.putExtra(EXTRA_COMMON_NAME, common);
            i.putExtra(EXTRA_SCI_NAME, sci);
            i.putExtra(EXTRA_RARITY, rarity);
            v.getContext().startActivity(i);
        });

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