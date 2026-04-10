package com.birddex.app;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.imageview.ShapeableImageView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * NearbyAdapter displays bird sightings in a list.
 * Fixed Race Condition:
 * 1. Navigation Flooding: Added isNavigating guard.
 */
/**
 * NearbyAdapter: Adapter that converts model data into rows/cards for a RecyclerView or similar list UI.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class NearbyAdapter extends RecyclerView.Adapter<NearbyAdapter.NearbyViewHolder> {

    public interface OnNearbyBirdActionListener {
        void onOpenBirdInfo(@NonNull Bird bird);
        void onShowBirdOnMap(@NonNull Bird bird);
    }

    private List<Bird> birdList;
    private boolean isNavigating = false;
    private final OnNearbyBirdActionListener actionListener;

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public NearbyAdapter(List<Bird> birdList, OnNearbyBirdActionListener actionListener) {
        this.birdList = birdList;
        this.actionListener = actionListener;
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
    public void setNavigating(boolean navigating) {
        this.isNavigating = navigating;
    }

    /**
     * Main logic block for this part of the feature.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     */
    @NonNull
    @Override
    public NearbyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_nearby_bird, parent, false);
        return new NearbyViewHolder(view);
    }

    /**
     * Main logic block for this part of the feature.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * Image loading happens here, which is why placeholder/error behavior for profile
     * photos/cards/posts usually traces back to this code path.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
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

        // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
        BirdImageLoader.loadBirdImageInto(holder.ivBirdImage, bird.getId(), bird.getCommonName(), bird.getScientificName());

        // Attach the user interaction that should run when this control is tapped.
        holder.itemView.setOnClickListener(v -> {
            if (isNavigating) return;

            BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(v.getContext(), R.style.BirdDexNearbyBottomSheetDialog);
            View sheetView = LayoutInflater.from(v.getContext())
                    .inflate(R.layout.bottom_sheet_nearby_bird_actions, null, false);

            TextView tvBirdName = sheetView.findViewById(R.id.tvBirdName);
            TextView tvBirdSubtitle = sheetView.findViewById(R.id.tvBirdSubtitle);
            LinearLayout btnBirdInfo = sheetView.findViewById(R.id.btnBirdInfo);
            LinearLayout btnViewOnMap = sheetView.findViewById(R.id.btnViewOnMap);
            TextView tvMapHelper = sheetView.findViewById(R.id.tvMapHelper);
            ImageView ivMapAction = sheetView.findViewById(R.id.ivMapAction);

            String commonName = safeText(bird.getCommonName(), "Unknown Bird");
            String scientificName = safeText(bird.getScientificName(), null);
            tvBirdName.setText(commonName);
            if (scientificName != null && !scientificName.equalsIgnoreCase(commonName)) {
                tvBirdSubtitle.setVisibility(View.VISIBLE);
                tvBirdSubtitle.setText(scientificName);
            } else {
                tvBirdSubtitle.setVisibility(View.GONE);
            }

            boolean hasCoordinates = bird.getLastSeenLatitudeGeorgia() != null
                    && bird.getLastSeenLongitudeGeorgia() != null;

            btnViewOnMap.setEnabled(hasCoordinates);
            btnViewOnMap.setAlpha(hasCoordinates ? 1f : 0.45f);
            ivMapAction.setAlpha(hasCoordinates ? 1f : 0.45f);
            tvMapHelper.setText(hasCoordinates
                    ? "Open the heatmap to this specific bird's sighting."
                    : "Map unavailable because this sighting has no saved coordinates.");
            tvMapHelper.setAlpha(hasCoordinates ? 0.90f : 0.65f);

            btnBirdInfo.setOnClickListener(view -> {
                bottomSheetDialog.dismiss();
                isNavigating = true;

                if (actionListener != null) {
                    actionListener.onOpenBirdInfo(bird);
                } else {
                    Intent intent = new Intent(v.getContext(), BirdWikiActivity.class);
                    intent.putExtra(BirdWikiActivity.EXTRA_BIRD_ID, bird.getId());
                    // Move into the next screen and pass the identifiers/data that screen needs.
                    v.getContext().startActivity(intent);
                }
            });

            btnViewOnMap.setOnClickListener(view -> {
                if (!hasCoordinates) return;

                bottomSheetDialog.dismiss();
                isNavigating = true;

                if (actionListener != null) {
                    actionListener.onShowBirdOnMap(bird);
                }
            });

            bottomSheetDialog.setContentView(sheetView);
            bottomSheetDialog.show();
        });
    }

    private String safeText(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.trim();
    }

    /**
     * Returns the current value/state this class needs somewhere else in the app.
     */
    @Override
    public int getItemCount() {
        return birdList.size();
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    public void updateList(List<Bird> newList) {
        this.birdList = newList;
        notifyDataSetChanged();
    }

    static class NearbyViewHolder extends RecyclerView.ViewHolder {
        ShapeableImageView ivBirdImage;
        TextView tvBirdName;
        TextView tvTimestamp;

        /**
         * Main logic block for this part of the feature.
         * It grabs layout/view references here so later code can read from them, update them, or
         * attach listeners.
         */
        public NearbyViewHolder(@NonNull View itemView) {
            super(itemView);
            // Bind or inflate the UI pieces this method needs before it can update the screen.
            ivBirdImage = itemView.findViewById(R.id.ivBirdImage);
            tvBirdName = itemView.findViewById(R.id.tvBirdName);
            tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
        }
    }
}
