package com.birddex.app;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ForumCommentAdapter extends RecyclerView.Adapter<ForumCommentAdapter.CommentViewHolder> {

    private List<ForumComment> allComments = new ArrayList<>();
    private List<ForumComment> topLevelComments = new ArrayList<>();
    private OnCommentInteractionListener listener;

    public interface OnCommentInteractionListener {
        void onCommentLikeClick(ForumComment comment);
        void onCommentReplyClick(ForumComment comment);
        void onCommentOptionsClick(ForumComment comment, View view);
        void onUserClick(String userId); // New method
    }

    public ForumCommentAdapter(OnCommentInteractionListener listener) {
        this.listener = listener;
    }

    public void setComments(List<ForumComment> comments) {
        this.allComments = comments;
        this.topLevelComments = comments.stream()
                .filter(c -> c.getParentCommentId() == null)
                .collect(Collectors.toList());
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
        ForumComment comment = topLevelComments.get(position);
        List<ForumComment> replies = allComments.stream()
                .filter(c -> comment.getId().equals(c.getParentCommentId()))
                .collect(Collectors.toList());
        holder.bind(comment, replies, listener);
    }

    @Override
    public int getItemCount() {
        return topLevelComments.size();
    }

    static class CommentViewHolder extends RecyclerView.ViewHolder {
        ImageView ivUserPfp;
        TextView tvUsername;
        TextView tvTimestamp;
        TextView tvText;
        LinearLayout btnLike;
        ImageView ivLikeIcon;
        TextView tvLikeCount;
        TextView btnReply;
        RecyclerView rvReplies;
        TextView tvReplyingTo;
        ImageView btnOptions;

        public CommentViewHolder(@NonNull View itemView) {
            super(itemView);
            ivUserPfp = itemView.findViewById(R.id.ivCommentUserPfp);
            tvUsername = itemView.findViewById(R.id.tvCommentUsername);
            tvTimestamp = itemView.findViewById(R.id.tvCommentTimestamp);
            tvText = itemView.findViewById(R.id.tvCommentText);
            btnLike = itemView.findViewById(R.id.btnLikeComment);
            ivLikeIcon = itemView.findViewById(R.id.ivCommentLikeIcon);
            tvLikeCount = itemView.findViewById(R.id.tvCommentLikeCount);
            btnReply = itemView.findViewById(R.id.btnReplyComment);
            rvReplies = itemView.findViewById(R.id.rvReplies);
            tvReplyingTo = itemView.findViewById(R.id.tvReplyingTo);
            btnOptions = itemView.findViewById(R.id.btnCommentOptions);
        }

        public void bind(ForumComment comment, List<ForumComment> replies, OnCommentInteractionListener listener) {
            tvUsername.setText(comment.getUsername());
            tvText.setText(comment.getText());
            tvLikeCount.setText(String.valueOf(comment.getLikeCount()));

            if (comment.getParentUsername() != null && !comment.getParentUsername().isEmpty()) {
                tvReplyingTo.setVisibility(View.VISIBLE);
                tvReplyingTo.setText("replying to @" + comment.getParentUsername());
            } else {
                tvReplyingTo.setVisibility(View.GONE);
            }

            if (comment.getTimestamp() != null) {
                long time = comment.getTimestamp().toDate().getTime();
                tvTimestamp.setText(DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE));
            }

            Glide.with(itemView.getContext())
                    .load(comment.getUserProfilePictureUrl())
                    .placeholder(R.drawable.ic_profile)
                    .into(ivUserPfp);

            btnLike.setOnClickListener(v -> listener.onCommentLikeClick(comment));
            btnReply.setOnClickListener(v -> listener.onCommentReplyClick(comment));
            btnOptions.setOnClickListener(v -> listener.onCommentOptionsClick(comment, v));
            
            // New click listeners for PFP and Username
            ivUserPfp.setOnClickListener(v -> listener.onUserClick(comment.getUserId()));
            tvUsername.setOnClickListener(v -> listener.onUserClick(comment.getUserId()));

            if (replies != null && !replies.isEmpty()) {
                rvReplies.setVisibility(View.VISIBLE);
                rvReplies.setLayoutManager(new LinearLayoutManager(itemView.getContext()));
                rvReplies.setAdapter(new ReplyAdapter(replies, listener));
            } else {
                rvReplies.setVisibility(View.GONE);
            }
        }
    }

    static class ReplyAdapter extends RecyclerView.Adapter<ReplyAdapter.ReplyViewHolder> {
        private List<ForumComment> replies;
        private OnCommentInteractionListener listener;

        ReplyAdapter(List<ForumComment> replies, OnCommentInteractionListener listener) {
            this.replies = replies;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ReplyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_forum_comment, parent, false);
            return new ReplyViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ReplyViewHolder holder, int position) {
            holder.bind(replies.get(position), listener);
        }

        @Override
        public int getItemCount() {
            return replies.size();
        }

        static class ReplyViewHolder extends RecyclerView.ViewHolder {
            ImageView ivUserPfp;
            TextView tvUsername;
            TextView tvTimestamp;
            TextView tvText;
            LinearLayout btnLike;
            TextView tvLikeCount;
            TextView btnReply;
            RecyclerView rvReplies;
            TextView tvReplyingTo;
            ImageView btnOptions;

            ReplyViewHolder(@NonNull View itemView) {
                super(itemView);
                ivUserPfp = itemView.findViewById(R.id.ivCommentUserPfp);
                tvUsername = itemView.findViewById(R.id.tvCommentUsername);
                tvTimestamp = itemView.findViewById(R.id.tvCommentTimestamp);
                tvText = itemView.findViewById(R.id.tvCommentText);
                btnLike = itemView.findViewById(R.id.btnLikeComment);
                tvLikeCount = itemView.findViewById(R.id.tvCommentLikeCount);
                btnReply = itemView.findViewById(R.id.btnReplyComment);
                rvReplies = itemView.findViewById(R.id.rvReplies);
                tvReplyingTo = itemView.findViewById(R.id.tvReplyingTo);
                btnOptions = itemView.findViewById(R.id.btnCommentOptions);
                
                btnReply.setVisibility(View.GONE);
                rvReplies.setVisibility(View.GONE);
            }

            void bind(ForumComment reply, OnCommentInteractionListener listener) {
                tvUsername.setText(reply.getUsername());
                tvText.setText(reply.getText());
                tvLikeCount.setText(String.valueOf(reply.getLikeCount()));

                if (reply.getParentUsername() != null && !reply.getParentUsername().isEmpty()) {
                    tvReplyingTo.setVisibility(View.VISIBLE);
                    tvReplyingTo.setText("replying to @" + reply.getParentUsername());
                } else {
                    tvReplyingTo.setVisibility(View.GONE);
                }

                if (reply.getTimestamp() != null) {
                    long time = reply.getTimestamp().toDate().getTime();
                    tvTimestamp.setText(DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE));
                }

                Glide.with(itemView.getContext())
                        .load(reply.getUserProfilePictureUrl())
                        .placeholder(R.drawable.ic_profile)
                        .into(ivUserPfp);

                btnLike.setOnClickListener(v -> listener.onCommentLikeClick(reply));
                btnOptions.setOnClickListener(v -> listener.onCommentOptionsClick(reply, v));
                
                // New click listeners for PFP and Username
                ivUserPfp.setOnClickListener(v -> listener.onUserClick(reply.getUserId()));
                tvUsername.setOnClickListener(v -> listener.onUserClick(reply.getUserId()));
            }
        }
    }
}
