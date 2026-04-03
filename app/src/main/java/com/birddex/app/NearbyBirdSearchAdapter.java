package com.birddex.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.imageview.ShapeableImageView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * NearbyBirdSearchAdapter: Scrollable bird browser used by the Nearby search button.
 */
public class NearbyBirdSearchAdapter extends RecyclerView.Adapter<NearbyBirdSearchAdapter.ViewHolder> {

    public interface OnBirdClickListener {
        void onBirdClick(Bird bird);
    }

    private final List<Bird> allBirds = new ArrayList<>();
    private final List<Bird> filteredBirds = new ArrayList<>();
    private final OnBirdClickListener onBirdClickListener;

    public NearbyBirdSearchAdapter(@NonNull List<Bird> birds, @NonNull OnBirdClickListener listener) {
        this.onBirdClickListener = listener;
        submitList(birds);
    }

    public void submitList(@NonNull List<Bird> birds) {
        allBirds.clear();
        allBirds.addAll(birds);
        filter("");
    }

    public void filter(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        filteredBirds.clear();

        if (normalized.isEmpty()) {
            filteredBirds.addAll(allBirds);
        } else {
            for (Bird bird : allBirds) {
                String commonName = safeLower(bird.getCommonName());
                String scientificName = safeLower(bird.getScientificName());
                if (commonName.contains(normalized) || scientificName.contains(normalized)) {
                    filteredBirds.add(bird);
                }
            }
        }

        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_nearby_bird_search, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Bird bird = filteredBirds.get(position);

        holder.tvBirdName.setText(firstNonBlank(bird.getCommonName(), bird.getScientificName(), "Unknown Bird"));

        String scientificName = bird.getScientificName();
        if (scientificName != null && !scientificName.trim().isEmpty() && !scientificName.equalsIgnoreCase(bird.getCommonName())) {
            holder.tvBirdCount.setVisibility(View.VISIBLE);
            holder.tvBirdCount.setText(scientificName);
        } else {
            holder.tvBirdCount.setVisibility(View.GONE);
        }

        BirdImageLoader.loadBirdImageInto(holder.ivBirdImage, bird.getId(), bird.getCommonName(), bird.getScientificName());

        holder.itemView.setOnClickListener(v -> onBirdClickListener.onBirdClick(bird));
    }

    @Override
    public int getItemCount() {
        return filteredBirds.size();
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.getDefault());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ShapeableImageView ivBirdImage;
        final TextView tvBirdName;
        final TextView tvBirdCount;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivBirdImage = itemView.findViewById(R.id.ivBirdPlaceholder);
            tvBirdName = itemView.findViewById(R.id.tvBirdName);
            tvBirdCount = itemView.findViewById(R.id.tvBirdCount);
        }
    }
}