package com.birddex.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * UserAdapter: Adapter that converts model data into rows/cards for a RecyclerView or similar list UI.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class UserAdapter extends RecyclerView.Adapter<UserAdapter.UserViewHolder> {

    private List<User> userList = new ArrayList<>();
    private OnUserClickListener listener;

    public interface OnUserClickListener {
        void onUserClick(User user);
    }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public UserAdapter(OnUserClickListener listener) {
        this.listener = listener;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    public void setUsers(List<User> users) {
        this.userList = users;
        notifyDataSetChanged();
    }

    /**
     * Main logic block for this part of the feature.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     */
    @NonNull
    @Override
    public UserViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_user_list, parent, false);
        return new UserViewHolder(view);
    }

    /**
     * Main logic block for this part of the feature.
     */
    @Override
    public void onBindViewHolder(@NonNull UserViewHolder holder, int position) {
        holder.bind(userList.get(position), listener);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    @Override
    public int getItemCount() {
        return userList.size();
    }

    static class UserViewHolder extends RecyclerView.ViewHolder {
        ShapeableImageView ivPfp;
        TextView tvUsername, tvBio;
        MaterialButton btnAction;

        /**
         * Main logic block for this part of the feature.
         * It grabs layout/view references here so later code can read from them, update them, or
         * attach listeners.
         */
        UserViewHolder(@NonNull View itemView) {
            super(itemView);
            // Bind or inflate the UI pieces this method needs before it can update the screen.
            ivPfp = itemView.findViewById(R.id.ivUserPfp);
            tvUsername = itemView.findViewById(R.id.tvUsername);
            tvBio = itemView.findViewById(R.id.tvBio);
            btnAction = itemView.findViewById(R.id.btnAction);
        }

        /**
         * Connects already-fetched data to views so the user can see the current state.
         * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
         * flow.
         * Image loading happens here, which is why placeholder/error behavior for profile
         * photos/cards/posts usually traces back to this code path.
         */
        void bind(User user, OnUserClickListener listener) {
            tvUsername.setText(user.getUsername());
            tvBio.setText(user.getBio() != null ? user.getBio() : "No bio yet.");

            // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
            Glide.with(itemView.getContext())
                    .load(user.getProfilePictureUrl())
                    .placeholder(R.drawable.ic_profile)
                    .into(ivPfp);

            // Attach the user interaction that should run when this control is tapped.
            btnAction.setOnClickListener(v -> listener.onUserClick(user));
            itemView.setOnClickListener(v -> listener.onUserClick(user));
        }
    }
}
