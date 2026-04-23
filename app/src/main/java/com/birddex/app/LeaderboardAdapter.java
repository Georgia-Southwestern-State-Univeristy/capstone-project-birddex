package com.birddex.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.imageview.ShapeableImageView;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LeaderboardAdapter extends RecyclerView.Adapter<LeaderboardAdapter.LeaderboardViewHolder> {

    public interface OnLeaderboardUserClickListener {
        void onUserClick(LeaderboardEntry entry);
    }

    private final List<LeaderboardEntry> entries = new ArrayList<>();
    private final OnLeaderboardUserClickListener listener;
    private final String currentUserId;
    private final NumberFormat numberFormat = NumberFormat.getInstance(Locale.US);

    public LeaderboardAdapter(String currentUserId, OnLeaderboardUserClickListener listener) {
        this.currentUserId = currentUserId;
        this.listener = listener;
    }

    public void setEntries(List<LeaderboardEntry> newEntries) {
        entries.clear();
        if (newEntries != null) {
            entries.addAll(newEntries);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public LeaderboardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_leaderboard_row, parent, false);
        return new LeaderboardViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LeaderboardViewHolder holder, int position) {
        holder.bind(entries.get(position));
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    class LeaderboardViewHolder extends RecyclerView.ViewHolder {
        private final View container;
        private final TextView tvRank;
        private final ShapeableImageView ivPfp;
        private final TextView tvUsername;
        private final TextView tvPoints;
        private final TextView tvYouBadge;

        LeaderboardViewHolder(@NonNull View itemView) {
            super(itemView);
            container = itemView.findViewById(R.id.rowContainer);
            tvRank = itemView.findViewById(R.id.tvLeaderboardRank);
            ivPfp = itemView.findViewById(R.id.ivLeaderboardPfp);
            tvUsername = itemView.findViewById(R.id.tvLeaderboardUsername);
            tvPoints = itemView.findViewById(R.id.tvLeaderboardPoints);
            tvYouBadge = itemView.findViewById(R.id.tvLeaderboardYouBadge);
        }

        void bind(LeaderboardEntry entry) {
            tvRank.setText(String.valueOf(entry.getRank()));
            tvUsername.setText(entry.getUsername() == null || entry.getUsername().trim().isEmpty()
                    ? "BirdDex User"
                    : entry.getUsername().trim());
            tvPoints.setText(numberFormat.format(entry.getTotalPoints()) + " pts");

            boolean isCurrentUser = currentUserId != null && currentUserId.equals(entry.getUserId());
            tvYouBadge.setVisibility(isCurrentUser ? View.VISIBLE : View.GONE);
            container.setActivated(isCurrentUser);
            container.setSelected(isCurrentUser);

            Glide.with(itemView.getContext())
                    .load(entry.getProfilePictureUrl())
                    .placeholder(R.drawable.ic_profile)
                    .error(R.drawable.ic_profile)
                    .into(ivPfp);

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onUserClick(entry);
            });
        }
    }
}
