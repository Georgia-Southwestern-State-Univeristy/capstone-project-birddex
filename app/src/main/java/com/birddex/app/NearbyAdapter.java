package com.birddex.app;

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

public class NearbyAdapter extends RecyclerView.Adapter<NearbyAdapter.NearbyViewHolder> {

    private List<Bird> birdList;

    public NearbyAdapter(List<Bird> birdList) {
        this.birdList = birdList;
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

        // Using a placeholder image for now, as Bird class doesn't seem to have a picture URL yet.
        // You can update this later when you add an image URL to your Bird model or fetch it from elsewhere.
        Glide.with(holder.itemView.getContext())
                .load(R.drawable.bird_image)
                .into(holder.ivBirdImage);
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
