package com.birddex.app;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.imageview.ShapeableImageView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * NearbyAdapter displays bird sightings in a list.
 * Fixed Race Condition:
 * 1. Navigation Flooding: Added isNavigating guard.
 */
public class NearbyAdapter extends RecyclerView.Adapter<NearbyAdapter.NearbyViewHolder> {

    private List<Bird> birdList;
    private boolean isNavigating = false;

    public NearbyAdapter(List<Bird> birdList) {
        this.birdList = birdList;
    }

    public void setNavigating(boolean navigating) {
        this.isNavigating = navigating;
    }

    @NonNull
    @Override
    public NearbyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_nearby_bird, parent, false);
        return new NearbyViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull NearbyViewHolder holder, int position) {
        Bird bird = birdList.get(position);
        holder.tvBirdName.setText(bird.getCommonName());

        if (bird.getLastSeenTimestampGeorgia() != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a, MMM dd", Locale.getDefault());
            String dateStr = sdf.format(new Date(bird.getLastSeenTimestampGeorgia()));
            holder.tvTimestamp.setText("Last seen: " + dateStr);
        } else {
            holder.tvTimestamp.setText("Time unknown");
        }

        Glide.with(holder.itemView.getContext())
                .load(R.drawable.bird_image)
                .into(holder.ivBirdImage);

        holder.itemView.setOnClickListener(v -> {
            if (isNavigating) return;
            isNavigating = true;
            Intent intent = new Intent(v.getContext(), BirdWikiActivity.class);
            intent.putExtra(BirdWikiActivity.EXTRA_BIRD_ID, bird.getId());
            v.getContext().startActivity(intent);
        });
    }

    @Override
    public int getItemCount() {
        return birdList.size();
    }

    public void updateList(List<Bird> newList) {
        this.birdList = newList;
        notifyDataSetChanged();
    }

    static class NearbyViewHolder extends RecyclerView.ViewHolder {
        ShapeableImageView ivBirdImage;
        TextView tvBirdName;
        TextView tvTimestamp;

        public NearbyViewHolder(@NonNull View itemView) {
            super(itemView);
            ivBirdImage = itemView.findViewById(R.id.ivBirdImage);
            tvBirdName = itemView.findViewById(R.id.tvBirdName);
            tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
        }
    }
}
