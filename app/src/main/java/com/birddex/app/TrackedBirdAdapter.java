package com.birddex.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * TrackedBirdAdapter: Adapter that converts model data into rows/cards for a RecyclerView or similar list UI.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class TrackedBirdAdapter extends RecyclerView.Adapter<TrackedBirdAdapter.VH> {

    public interface OnTrackedBirdInteractionListener {
        void onTrackedBirdClicked(@NonNull TrackedBird trackedBird);
        void onTrackedBirdRemoveClicked(@NonNull TrackedBird trackedBird);
    }

    private final boolean removable;
    private final OnTrackedBirdInteractionListener listener;
    private final List<TrackedBird> items = new ArrayList<>();

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public TrackedBirdAdapter(boolean removable, @NonNull OnTrackedBirdInteractionListener listener) {
        this.removable = removable;
        this.listener = listener;
    }

    /**
     * Main logic block for this part of the feature.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    public void submitList(@NonNull List<TrackedBird> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvCommonName;
        TextView tvScientificName;
        TextView tvTrackedAt;
        ImageButton btnRemove;

        /**
         * Main logic block for this part of the feature.
         * It grabs layout/view references here so later code can read from them, update them, or
         * attach listeners.
         */
        VH(@NonNull View itemView) {
            super(itemView);
            tvCommonName = itemView.findViewById(R.id.tvTrackedBirdName);
            tvScientificName = itemView.findViewById(R.id.tvTrackedBirdScientificName);
            tvTrackedAt = itemView.findViewById(R.id.tvTrackedBirdDate);
            btnRemove = itemView.findViewById(R.id.btnRemoveTrackedBird);
        }
    }

    /**
     * Main logic block for this part of the feature.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     */
    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tracked_bird, parent, false);
        return new VH(view);
    }

    /**
     * Main logic block for this part of the feature.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     */
    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        TrackedBird trackedBird = items.get(position);

        holder.tvCommonName.setText(!isBlank(trackedBird.getCommonName()) ? trackedBird.getCommonName() : "Unknown bird");
        holder.tvScientificName.setText(!isBlank(trackedBird.getScientificName()) ? trackedBird.getScientificName() : "Scientific name unavailable");
        holder.tvTrackedAt.setText(formatTrackedDate(trackedBird.getTrackedAt()));

        holder.btnRemove.setVisibility(removable ? View.VISIBLE : View.GONE);
        holder.btnRemove.setOnClickListener(v -> listener.onTrackedBirdRemoveClicked(trackedBird));
        holder.itemView.setOnClickListener(v -> listener.onTrackedBirdClicked(trackedBird));
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    @Override
    public int getItemCount() {
        return items.size();
    }

    /**
     * Main logic block for this part of the feature.
     */
    private String formatTrackedDate(Date trackedAt) {
        if (trackedAt == null) return "Tracking active";
        return "Tracking since " + new SimpleDateFormat("M/d/yy", Locale.US).format(trackedAt);
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
