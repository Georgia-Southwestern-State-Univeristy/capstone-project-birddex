package com.birddex.app;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.List;

public class ForumCommentAdapter extends RecyclerView.Adapter<ForumCommentAdapter.CommentViewHolder> {

    private List<ForumComment> commentList = new ArrayList<>();
    private OnCommentLikeClickListener listener;

    public interface OnCommentLikeClickListener {
        void onCommentLikeClick(ForumComment comment);
    }

    public ForumCommentAdapter(OnCommentLikeClickListener listener) {
        this.listener = listener;
    }

    public void setComments(List<ForumComment> comments) {
        this.commentList = comments;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public CommentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_forum_comment, parent, false);
        return new CommentViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CommentViewHolder holder, int position) {
        ForumComment comment = commentList.get(position);
        holder.bind(comment, listener);
    }

    @Override
    public int getItemCount() {
        return commentList.size();
    }

    static class CommentViewHolder extends RecyclerView.ViewHolder {
        ImageView ivUserPfp;
        TextView tvUsername;
        TextView tvTimestamp;
        TextView tvText;
        LinearLayout btnLike;
        ImageView ivLikeIcon;
        TextView tvLikeCount;

        public CommentViewHolder(@NonNull View itemView) {
            super(itemView);
            ivUserPfp = itemView.findViewById(R.id.ivCommentUserPfp);
            tvUsername = itemView.findViewById(R.id.tvCommentUsername);
            tvTimestamp = itemView.findViewById(R.id.tvCommentTimestamp);
            tvText = itemView.findViewById(R.id.tvCommentText);
            btnLike = itemView.findViewById(R.id.btnLikeComment);
            ivLikeIcon = itemView.findViewById(R.id.ivCommentLikeIcon);
            tvLikeCount = itemView.findViewById(R.id.tvCommentLikeCount);
        }

        public void bind(ForumComment comment, OnCommentLikeClickListener listener) {
            tvUsername.setText(comment.getUsername());
            tvText.setText(comment.getText());
            tvLikeCount.setText(String.valueOf(comment.getLikeCount()));

            if (comment.getTimestamp() != null) {
                long time = comment.getTimestamp().toDate().getTime();
                tvTimestamp.setText(DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE));
            }

            Glide.with(itemView.getContext())
                    .load(comment.getUserProfilePictureUrl())
                    .placeholder(R.drawable.ic_profile)
                    .into(ivUserPfp);

            String currentUserId = FirebaseAuth.getInstance().getUid();
            if (currentUserId != null && comment.getLikedBy() != null && comment.getLikedBy().containsKey(currentUserId)) {
                // ivLikeIcon.setImageResource(R.drawable.ic_favorite); // If filled heart available
            } else {
                ivLikeIcon.setImageResource(R.drawable.ic_favorite_border);
            }

            btnLike.setOnClickListener(v -> listener.onCommentLikeClick(comment));
        }
    }
}
