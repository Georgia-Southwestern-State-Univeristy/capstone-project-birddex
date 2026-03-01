package com.birddex.app;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.List;

public class ForumPostAdapter extends RecyclerView.Adapter<ForumPostAdapter.PostViewHolder> {

    private List<ForumPost> postList = new ArrayList<>();
    private OnPostClickListener listener;

    public interface OnPostClickListener {
        void onLikeClick(ForumPost post);
        void onCommentClick(ForumPost post);
        void onPostClick(ForumPost post);
        void onOptionsClick(ForumPost post, View view);
        void onUserClick(String userId);
    }

    public ForumPostAdapter(OnPostClickListener listener) {
        this.listener = listener;
    }

    public void setPosts(List<ForumPost> posts) {
        this.postList = posts;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PostViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_forum_post, parent, false);
        return new PostViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PostViewHolder holder, int position) {
        ForumPost post = postList.get(position);
        holder.bind(post, listener);
    }

    @Override
    public int getItemCount() {
        return postList.size();
    }

    static class PostViewHolder extends RecyclerView.ViewHolder {
        ImageView ivUserProfile;
        TextView tvUsername;
        TextView tvTimestamp;
        TextView tvMessage;
        ImageView ivBirdImage;
        View cvPostImage;
        LinearLayout btnLike;
        ImageView ivLikeIcon;
        TextView tvLikeCount;
        LinearLayout btnComment;
        TextView tvCommentCount;
        TextView tvViewCount;
        ImageButton btnOptions;
        
        TextView tvSpottedBadge;
        TextView tvHuntedBadge;

        public PostViewHolder(@NonNull View itemView) {
            super(itemView);
            ivUserProfile = itemView.findViewById(R.id.ivPostUserProfilePicture);
            tvUsername = itemView.findViewById(R.id.tvPostUsername);
            tvTimestamp = itemView.findViewById(R.id.tvPostTimestamp);
            tvMessage = itemView.findViewById(R.id.tvPostMessage);
            ivBirdImage = itemView.findViewById(R.id.ivPostBirdImage);
            cvPostImage = itemView.findViewById(R.id.cvPostImage);
            btnLike = itemView.findViewById(R.id.btnLike);
            ivLikeIcon = itemView.findViewById(R.id.ivLikeIcon);
            tvLikeCount = itemView.findViewById(R.id.tvLikeCount);
            btnComment = itemView.findViewById(R.id.btnComment);
            tvCommentCount = itemView.findViewById(R.id.tvCommentCount);
            tvViewCount = itemView.findViewById(R.id.tvViewCount);
            btnOptions = itemView.findViewById(R.id.btnPostOptions);
            
            tvSpottedBadge = itemView.findViewById(R.id.tvSpottedBadge);
            tvHuntedBadge = itemView.findViewById(R.id.tvHuntedBadge);
        }

        public void bind(ForumPost post, OnPostClickListener listener) {
            tvUsername.setText(post.getUsername());
            tvMessage.setText(post.getMessage());
            tvLikeCount.setText(String.valueOf(post.getLikeCount()));
            tvCommentCount.setText(String.valueOf(post.getCommentCount()));
            tvViewCount.setText(post.getViewCount() + " views");

            // Status badges
            if (tvSpottedBadge != null) {
                tvSpottedBadge.setVisibility(post.isSpotted() ? View.VISIBLE : View.GONE);
            }
            if (tvHuntedBadge != null) {
                tvHuntedBadge.setVisibility(post.isHunted() ? View.VISIBLE : View.GONE);
            }

            // Timestamp
            if (post.getTimestamp() != null) {
                long time = post.getTimestamp().toDate().getTime();
                tvTimestamp.setText(DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            }

            // User Profile Picture
            Glide.with(itemView.getContext())
                    .load(post.getUserProfilePictureUrl())
                    .placeholder(R.drawable.ic_profile)
                    .error(R.drawable.ic_profile)
                    .into(ivUserProfile);

            // Bird Image
            if (post.getBirdImageUrl() != null && !post.getBirdImageUrl().isEmpty()) {
                cvPostImage.setVisibility(View.VISIBLE);
                Glide.with(itemView.getContext())
                        .load(post.getBirdImageUrl())
                        .into(ivBirdImage);
            } else {
                cvPostImage.setVisibility(View.GONE);
            }

            // Like status
            String currentUserId = FirebaseAuth.getInstance().getUid();
            if (currentUserId != null && post.getLikedBy() != null && post.getLikedBy().containsKey(currentUserId)) {
                ivLikeIcon.setImageResource(R.drawable.ic_favorite_border); // Placeholder
            } else {
                ivLikeIcon.setImageResource(R.drawable.ic_favorite_border);
            }

            btnLike.setOnClickListener(v -> listener.onLikeClick(post));
            btnComment.setOnClickListener(v -> listener.onCommentClick(post));
            itemView.setOnClickListener(v -> listener.onPostClick(post));
            btnOptions.setOnClickListener(v -> listener.onOptionsClick(post, v));
            
            ivUserProfile.setOnClickListener(v -> listener.onUserClick(post.getUserId()));
            tvUsername.setOnClickListener(v -> listener.onUserClick(post.getUserId()));
        }
    }
}
