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
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ForumCommentAdapter: Adapter that converts model data into rows/cards for a RecyclerView or similar list UI.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class ForumCommentAdapter extends RecyclerView.Adapter<ForumCommentAdapter.CommentViewHolder> {

    private List<ForumComment> allComments = new ArrayList<>();
    private List<ForumComment> topLevelComments = new ArrayList<>();
    private OnCommentInteractionListener listener;
    private Set<String> expandedCommentIds = new HashSet<>();
    private String currentUserId;

    public interface OnCommentInteractionListener {
        void onCommentLikeClick(ForumComment comment);
        void onCommentReplyClick(ForumComment comment);
        void onCommentOptionsClick(ForumComment comment, View view);
        void onUserClick(String userId);
    }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public ForumCommentAdapter(OnCommentInteractionListener listener) {
        this.listener = listener;
        this.currentUserId = FirebaseAuth.getInstance().getUid();
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    public void setComments(List<ForumComment> comments) {
        this.allComments = comments;
        this.topLevelComments = comments.stream()
                .filter(c -> c.getParentCommentId() == null)
                .collect(Collectors.toList());
        notifyDataSetChanged();
    }

    /**
     * Main logic block for this part of the feature.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     */
    @NonNull
    @Override
    public CommentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_forum_comment, parent, false);
        return new CommentViewHolder(view);
    }

    /**
     * Main logic block for this part of the feature.
     */
    @Override
    public void onBindViewHolder(@NonNull CommentViewHolder holder, int position) {
        ForumComment comment = topLevelComments.get(position);
        List<ForumComment> replies = allComments.stream()
                .filter(c -> comment.getId().equals(c.getParentCommentId()))
                .collect(Collectors.toList());

        boolean isExpanded = expandedCommentIds.contains(comment.getId());
        holder.bind(comment, replies, isExpanded, listener, currentUserId, (id, expand) -> {
            if (expand) expandedCommentIds.add(id);
            else expandedCommentIds.remove(id);
            notifyItemChanged(position);
        });
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    @Override
    public int getItemCount() {
        return topLevelComments.size();
    }

    interface OnExpandListener {
        void onExpandToggle(String commentId, boolean expand);
    }

    static class CommentViewHolder extends RecyclerView.ViewHolder {
        ImageView ivUserPfp;
        TextView tvUsername;
        TextView tvTimestamp;
        TextView tvMeta;
        TextView tvText;
        LinearLayout btnLike;
        ImageView ivLikeIcon;
        TextView tvLikeCount;
        TextView btnReply;
        TextView tvShowReplies;
        RecyclerView rvReplies;
        TextView tvReplyingTo;
        ImageView btnOptions;
        View llActions;

        /**
         * Main logic block for this part of the feature.
         * It grabs layout/view references here so later code can read from them, update them, or
         * attach listeners.
         */
        public CommentViewHolder(@NonNull View itemView) {
            super(itemView);
            // Bind or inflate the UI pieces this method needs before it can update the screen.
            ivUserPfp = itemView.findViewById(R.id.ivCommentUserPfp);
            tvUsername = itemView.findViewById(R.id.tvCommentUsername);
            tvTimestamp = itemView.findViewById(R.id.tvCommentTimestamp);
            tvMeta = itemView.findViewById(R.id.tvCommentMeta);
            tvText = itemView.findViewById(R.id.tvCommentText);
            btnLike = itemView.findViewById(R.id.btnLikeComment);
            ivLikeIcon = itemView.findViewById(R.id.ivCommentLikeIcon);
            tvLikeCount = itemView.findViewById(R.id.tvCommentLikeCount);
            btnReply = itemView.findViewById(R.id.btnReplyComment);
            tvShowReplies = itemView.findViewById(R.id.tvShowReplies);
            rvReplies = itemView.findViewById(R.id.rvReplies);
            tvReplyingTo = itemView.findViewById(R.id.tvReplyingTo);
            btnOptions = itemView.findViewById(R.id.btnCommentOptions);
            llActions = itemView.findViewById(R.id.llActions);
        }

        /**
         * Connects already-fetched data to views so the user can see the current state.
         * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
         * flow.
         * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
         * rendered on screen.
         * Image loading happens here, which is why placeholder/error behavior for profile
         * photos/cards/posts usually traces back to this code path.
         */
        public void bind(ForumComment comment, List<ForumComment> replies, boolean isExpanded, OnCommentInteractionListener listener, String currentUserId, OnExpandListener expandListener) {
            tvUsername.setText(comment.getUsername());
            tvText.setText(comment.getText());
            tvLikeCount.setText(String.valueOf(comment.getLikeCount()));
            llActions.setVisibility(View.VISIBLE);

            if (comment.getParentUsername() != null && !comment.getParentUsername().isEmpty()) {
                tvReplyingTo.setVisibility(View.VISIBLE);
                tvReplyingTo.setText("replying to @" + comment.getParentUsername());
            } else {
                tvReplyingTo.setVisibility(View.GONE);
            }

            if (comment.isEdited()) {
                tvMeta.setVisibility(View.VISIBLE);
                tvMeta.setText("Edited");
            } else {
                tvMeta.setVisibility(View.GONE);
            }

            if (comment.getTimestamp() != null) {
                long time = comment.getTimestamp().toDate().getTime();
                tvTimestamp.setText(DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE));
            } else {
                tvTimestamp.setText("");
            }

            // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
            Glide.with(itemView.getContext())
                    .load(comment.getUserProfilePictureUrl())
                    .placeholder(R.drawable.ic_profile)
                    .into(ivUserPfp);

            // Like status icon update
            if (currentUserId != null && comment.getLikedBy() != null && comment.getLikedBy().containsKey(currentUserId)) {
                ivLikeIcon.setImageResource(R.drawable.ic_favorite);
            } else {
                ivLikeIcon.setImageResource(R.drawable.ic_favorite_border);
            }

            // Attach the user interaction that should run when this control is tapped.
            btnLike.setOnClickListener(v -> listener.onCommentLikeClick(comment));
            btnReply.setOnClickListener(v -> listener.onCommentReplyClick(comment));
            btnOptions.setOnClickListener(v -> listener.onCommentOptionsClick(comment, v));
            ivUserPfp.setOnClickListener(v -> listener.onUserClick(comment.getUserId()));
            tvUsername.setOnClickListener(v -> listener.onUserClick(comment.getUserId()));

            if (replies != null && !replies.isEmpty()) {
                if (isExpanded) {
                    tvShowReplies.setVisibility(View.VISIBLE);
                    tvShowReplies.setText("Hide replies");
                    tvShowReplies.setOnClickListener(v -> expandListener.onExpandToggle(comment.getId(), false));

                    rvReplies.setVisibility(View.VISIBLE);
                    rvReplies.setLayoutManager(new LinearLayoutManager(itemView.getContext()));
                    // Hook the data source to the list/grid adapter so model objects can render as UI rows/cards.
                    rvReplies.setAdapter(new ReplyAdapter(replies, listener, currentUserId));
                } else {
                    tvShowReplies.setVisibility(View.VISIBLE);
                    int remainingCount = replies.size() - 1;
                    if (remainingCount > 0) {
                        tvShowReplies.setText("Show " + remainingCount + " more " + (remainingCount == 1 ? "reply" : "replies"));
                    } else {
                        tvShowReplies.setVisibility(View.GONE);
                    }
                    tvShowReplies.setOnClickListener(v -> expandListener.onExpandToggle(comment.getId(), true));

                    rvReplies.setVisibility(View.VISIBLE);
                    rvReplies.setLayoutManager(new LinearLayoutManager(itemView.getContext()));
                    List<ForumComment> firstReplyOnly = new ArrayList<>();
                    firstReplyOnly.add(replies.get(0));
                    rvReplies.setAdapter(new ReplyAdapter(firstReplyOnly, listener, currentUserId));
                }
            } else {
                tvShowReplies.setVisibility(View.GONE);
                rvReplies.setVisibility(View.GONE);
            }
        }
    }

    static class ReplyAdapter extends RecyclerView.Adapter<ReplyAdapter.ReplyViewHolder> {
        private List<ForumComment> replies;
        private OnCommentInteractionListener listener;
        private String currentUserId;

        /**
         * Main logic block for this part of the feature.
         */
        ReplyAdapter(List<ForumComment> replies, OnCommentInteractionListener listener, String currentUserId) {
            this.replies = replies;
            this.listener = listener;
            this.currentUserId = currentUserId;
        }

        /**
         * Main logic block for this part of the feature.
         * It grabs layout/view references here so later code can read from them, update them, or
         * attach listeners.
         */
        @NonNull
        @Override
        public ReplyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // Bind or inflate the UI pieces this method needs before it can update the screen.
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_forum_comment, parent, false);
            return new ReplyViewHolder(view);
        }

        /**
         * Main logic block for this part of the feature.
         */
        @Override
        public void onBindViewHolder(@NonNull ReplyViewHolder holder, int position) {
            holder.bind(replies.get(position), listener, currentUserId);
        }

        /**
         * Returns the current value/state this class needs somewhere else in the app.
         */
        @Override
        public int getItemCount() {
            return replies.size();
        }

        static class ReplyViewHolder extends RecyclerView.ViewHolder {
            ImageView ivUserPfp;
            TextView tvUsername;
            TextView tvTimestamp;
            TextView tvMeta;
            TextView tvText;
            LinearLayout btnLike;
            ImageView ivLikeIcon;
            TextView tvLikeCount;
            RecyclerView rvReplies;
            TextView tvReplyingTo;
            ImageView btnOptions;
            View llActions;

            /**
             * Main logic block for this part of the feature.
             * It grabs layout/view references here so later code can read from them, update them, or
             * attach listeners.
             */
            ReplyViewHolder(@NonNull View itemView) {
                super(itemView);
                // Bind or inflate the UI pieces this method needs before it can update the screen.
                ivUserPfp = itemView.findViewById(R.id.ivCommentUserPfp);
                tvUsername = itemView.findViewById(R.id.tvCommentUsername);
                tvTimestamp = itemView.findViewById(R.id.tvCommentTimestamp);
                tvMeta = itemView.findViewById(R.id.tvCommentMeta);
                tvText = itemView.findViewById(R.id.tvCommentText);
                btnLike = itemView.findViewById(R.id.btnLikeComment);
                ivLikeIcon = itemView.findViewById(R.id.ivCommentLikeIcon);
                tvLikeCount = itemView.findViewById(R.id.tvCommentLikeCount);
                rvReplies = itemView.findViewById(R.id.rvReplies);
                tvReplyingTo = itemView.findViewById(R.id.tvReplyingTo);
                btnOptions = itemView.findViewById(R.id.btnCommentOptions);
                llActions = itemView.findViewById(R.id.llActions);

                // Replies don't have further nesting or their own reply buttons in this design
                llActions.setVisibility(View.GONE);
                rvReplies.setVisibility(View.GONE);
            }

            /**
             * Connects already-fetched data to views so the user can see the current state.
             * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
             * flow.
             * Image loading happens here, which is why placeholder/error behavior for profile
             * photos/cards/posts usually traces back to this code path.
             */
            void bind(ForumComment reply, OnCommentInteractionListener listener, String currentUserId) {
                tvUsername.setText(reply.getUsername());
                tvText.setText(reply.getText());
                tvLikeCount.setText(String.valueOf(reply.getLikeCount()));

                if (reply.getParentUsername() != null && !reply.getParentUsername().isEmpty()) {
                    tvReplyingTo.setVisibility(View.VISIBLE);
                    tvReplyingTo.setText("replying to @" + reply.getParentUsername());
                } else {
                    tvReplyingTo.setVisibility(View.GONE);
                }

                if (reply.isEdited()) {
                    tvMeta.setVisibility(View.VISIBLE);
                    tvMeta.setText("Edited");
                } else {
                    tvMeta.setVisibility(View.GONE);
                }

                if (reply.getTimestamp() != null) {
                    long time = reply.getTimestamp().toDate().getTime();
                    tvTimestamp.setText(DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE));
                } else {
                    tvTimestamp.setText("");
                }

                // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
                Glide.with(itemView.getContext())
                        .load(reply.getUserProfilePictureUrl())
                        .placeholder(R.drawable.ic_profile)
                        .into(ivUserPfp);

                // Like status icon update
                if (currentUserId != null && reply.getLikedBy() != null && reply.getLikedBy().containsKey(currentUserId)) {
                    ivLikeIcon.setImageResource(R.drawable.ic_favorite);
                } else {
                    ivLikeIcon.setImageResource(R.drawable.ic_favorite_border);
                }

                // Attach the user interaction that should run when this control is tapped.
                btnLike.setOnClickListener(v -> listener.onCommentLikeClick(reply));
                btnOptions.setOnClickListener(v -> listener.onCommentOptionsClick(reply, v));
                ivUserPfp.setOnClickListener(v -> listener.onUserClick(reply.getUserId()));
                tvUsername.setOnClickListener(v -> listener.onUserClick(reply.getUserId()));
            }
        }
    }
}