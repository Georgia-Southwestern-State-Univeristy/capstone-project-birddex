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

public class UserAdapter extends RecyclerView.Adapter<UserAdapter.UserViewHolder> {

    private List<User> userList = new ArrayList<>();
    private OnUserClickListener listener;

    public interface OnUserClickListener {
        void onUserClick(User user);
    }

    public UserAdapter(OnUserClickListener listener) {
        this.listener = listener;
    }

    public void setUsers(List<User> users) {
        this.userList = users;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public UserViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_user_list, parent, false);
        return new UserViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull UserViewHolder holder, int position) {
        holder.bind(userList.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return userList.size();
    }

    static class UserViewHolder extends RecyclerView.ViewHolder {
        ShapeableImageView ivPfp;
        TextView tvUsername, tvBio;
        MaterialButton btnAction;

        UserViewHolder(@NonNull View itemView) {
            super(itemView);
            ivPfp = itemView.findViewById(R.id.ivUserPfp);
            tvUsername = itemView.findViewById(R.id.tvUsername);
            tvBio = itemView.findViewById(R.id.tvBio);
            btnAction = itemView.findViewById(R.id.btnAction);
        }

        void bind(User user, OnUserClickListener listener) {
            tvUsername.setText(user.getUsername());
            tvBio.setText(user.getBio() != null ? user.getBio() : "No bio yet.");

            Glide.with(itemView.getContext())
                    .load(user.getProfilePictureUrl())
                    .placeholder(R.drawable.ic_profile)
                    .into(ivPfp);

            btnAction.setOnClickListener(v -> listener.onUserClick(user));
            itemView.setOnClickListener(v -> listener.onUserClick(user));
        }
    }
}
