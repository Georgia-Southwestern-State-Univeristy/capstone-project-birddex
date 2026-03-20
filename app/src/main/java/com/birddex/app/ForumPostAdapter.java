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

/**
 * ForumPostAdapter: Adapter that converts model data into rows/cards for a RecyclerView or similar list UI.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class ForumPostAdapter extends RecyclerView.Adapter<ForumPostAdapter.PostViewHolder> {

    private List<ForumPost> postList = new ArrayList<>();
    private OnPostClickListener listener;

    public interface OnPostClickListener {
        void onLikeClick(ForumPost post);
        void onCommentClick(ForumPost post);
        void onPostClick(ForumPost post);
        void onOptionsClick(ForumPost post, View view);
        void onUserClick(String userId);
        void onMapClick(ForumPost post);
    }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public ForumPostAdapter(OnPostClickListener listener) {
        this.listener = listener;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    public void setPosts(List<ForumPost> posts) {
        this.postList = posts;
        notifyDataSetChanged();
    }

    /**
     * Main logic block for this part of the feature.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     */
    @NonNull
    @Override
    public PostViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_forum_post, parent, false);
        return new PostViewHolder(view);
    }

    /**
     * Main logic block for this part of the feature.
     */
    @Override
    public void onBindViewHolder(@NonNull PostViewHolder holder, int position) {
        ForumPost post = postList.get(position);
        holder.bind(post, listener);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
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
        LinearLayout btnViewOnMap;
        
        TextView tvSpottedBadge;
        TextView tvHuntedBadge;

        /**
         * Main logic block for this part of the feature.
         * It grabs layout/view references here so later code can read from them, update them, or
         * attach listeners.
         */
        public PostViewHolder(@NonNull View itemView) {
            super(itemView);
            // Bind or inflate the UI pieces this method needs before it can update the screen.
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
            btnViewOnMap = itemView.findViewById(R.id.btnViewOnMap);
            
            tvSpottedBadge = itemView.findViewById(R.id.tvSpottedBadge);
            tvHuntedBadge = itemView.findViewById(R.id.tvHuntedBadge);
        }

        /**
         * Connects already-fetched data to views so the user can see the current state.
         * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
         * flow.
         * Image loading happens here, which is why placeholder/error behavior for profile
         * photos/cards/posts usually traces back to this code path.
         * Location values are handled here, so this is part of the logic that decides what area/bird
         * sightings the user sees.
         */
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

            // View on Map button visibility - Show for both spotted and hunted posts if location is shared
            if (btnViewOnMap != null) {
                boolean hasLocation = post.isShowLocation() && post.getLatitude() != null && post.getLongitude() != null;
                if ((post.isSpotted() || post.isHunted()) && hasLocation) {
                    btnViewOnMap.setVisibility(View.VISIBLE);
                    // Attach the user interaction that should run when this control is tapped.
                    btnViewOnMap.setOnClickListener(v -> listener.onMapClick(post));
                } else {
                    btnViewOnMap.setVisibility(View.GONE);
                }
            }

            // Timestamp
            if (post.getTimestamp() != null) {
                long time = post.getTimestamp().toDate().getTime();
                tvTimestamp.setText(DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            }

            // User Profile Picture
            // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
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
                ivLikeIcon.setImageResource(R.drawable.ic_favorite);
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
