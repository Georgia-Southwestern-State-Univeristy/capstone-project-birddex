package com.birddex.app;

import android.content.Intent;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.DocumentSnapshot;

import java.util.List;

public class CollectionCardAdapter extends RecyclerView.Adapter<CollectionCardAdapter.VH> {

    public static final String EXTRA_IMAGE_URL = "com.birddex.app.extra.IMAGE_URL";
    public static final String EXTRA_COMMON_NAME = "com.birddex.app.extra.COMMON_NAME";
    public static final String EXTRA_SCI_NAME = "com.birddex.app.extra.SCI_NAME";
    public static final String EXTRA_RARITY = "com.birddex.app.extra.RARITY";

    private static final String TAG = "CollectionCardAdapter";

    private final List<CollectionSlot> slots;
    private final FirebaseFirestore db;
    @Nullable private final String userId; // Can be null if not logged in

    // Rarity view types
    private static final int VIEW_TYPE_COMMON = 0;
    private static final int VIEW_TYPE_UNCOMMON = 1;
    private static final int VIEW_TYPE_RARE = 2;
    private static final int VIEW_TYPE_EPIC = 3;
    private static final int VIEW_TYPE_LEGENDARY = 4;

    public CollectionCardAdapter(@NonNull List<CollectionSlot> slots, @NonNull FirebaseFirestore db, @Nullable String userId) {
        this.slots = slots;
        this.db = db;
        this.userId = userId;
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView txtBirdName;
        ImageView imageView3; // Renamed from imageView3 to imgBird
        TextView txtScientific;
        TextView txtRarity;

        VH(@NonNull View itemView) {
            super(itemView);
            txtBirdName = itemView.findViewById(R.id.txtBirdName);
            imageView3 = itemView.findViewById(R.id.imgBird); // *** CHANGED ID HERE ***
            txtScientific = itemView.findViewById(R.id.txtScientific);
            txtRarity = itemView.findViewById(R.id.txtRarity);
        }
    }

    @Override
    public int getItemViewType(int position) {
        CollectionSlot slot = slots.get(position);
        if (slot == null || slot.getRarity() == null || slot.getRarity().trim().isEmpty()) {
            return VIEW_TYPE_COMMON; // Default to common if rarity is not set
        }

        switch (slot.getRarity().toLowerCase()) {
            case "common":
                return VIEW_TYPE_COMMON;
            case "uncommon":
                return VIEW_TYPE_UNCOMMON;
            case "rare":
                return VIEW_TYPE_RARE;
            case "epic":
                return VIEW_TYPE_EPIC;
            case "legendary":
                return VIEW_TYPE_LEGENDARY;
            default:
                return VIEW_TYPE_COMMON; // Fallback for any unrecognised rarities
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v;
        switch (viewType) {
            case VIEW_TYPE_COMMON:
            default:
                v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.view_bird_card, parent, false); // *** CHANGED LAYOUT HERE ***
                break;
            case VIEW_TYPE_UNCOMMON:
                // TODO: Replace with R.layout.item_collection_cell_uncommon when created
                v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.view_bird_card, parent, false); // Temporarily use common layout
                break;
            case VIEW_TYPE_RARE:
                // TODO: Replace with R.layout.item_collection_cell_rare when created
                v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.view_bird_card, parent, false); // Temporarily use common layout
                break;
            case VIEW_TYPE_EPIC:
                // TODO: Replace with R.layout.item_collection_cell_epic when created
                v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.view_bird_card, parent, false); // Temporarily use common layout
                break;
            case VIEW_TYPE_LEGENDARY:
                // TODO: Replace with R.layout.item_collection_cell_legendary when created
                v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.view_bird_card, parent, false); // Temporarily use common layout
                break;
        }
        return new VH(v);
    }

    private String formatBirdName(String name) {
        if (name == null) return "UNKNOWN";

        String trimmed = name.trim().replaceAll("\\s+", " ");
        if (trimmed.isEmpty()) return "UNKNOWN";

        String[] parts = trimmed.split(" ");

        // If exactly 2 words, stack them
        if (parts.length == 2) {
            return parts[0] + "\n" + parts[1];
        }

        // Otherwise let Android wrap naturally
        return trimmed;
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        CollectionSlot slot = slots.get(position);
        
        // Default to placeholder image and disable click listener initially
        holder.imageView3.setImageResource(R.drawable.birddexlogo); 
        holder.itemView.setOnClickListener(null);
        
        String rarity = slot != null ? slot.getRarity() : null;
        String common = slot != null ? slot.getCommonName() : null;
        String sci = slot != null ? slot.getScientificName() : null;
        String userBirdId = slot != null ? slot.getUserBirdId() : null;

        Log.d(TAG, "onBindViewHolder for position: " + position + ", userBirdId: " + userBirdId + ", userId: " + userId);

        // Load bird name and scientific name
        if (common != null && !common.trim().isEmpty()) {
            holder.txtBirdName.setText(formatBirdName(common));
        } else if (sci != null && !sci.trim().isEmpty()) {
            holder.txtBirdName.setText(formatBirdName(sci));
        } else {
            holder.txtBirdName.setText("Unknown\nBird");
        }

        if (sci != null && !sci.trim().isEmpty()) {
            holder.txtScientific.setText(sci);
        } else {
            holder.txtScientific.setText("--");
        }

        // Rarity: always display the rarity from the slot data or default to "common"
        if (rarity != null && !rarity.trim().isEmpty()) {
            holder.txtRarity.setText("Rarity: " + rarity);
        } else {
            holder.txtRarity.setText("Rarity: common"); // Default to "common"
        }

        // --- Fetch and load image from UserBirdImage directly within onBindViewHolder ---
        if (userId != null && userBirdId != null && !userBirdId.trim().isEmpty()) {
            Log.d(TAG, "Attempting to fetch UserBirdImage for user: " + userId + ", userBirdId: " + userBirdId);
            db.collection("users").document(userId)
                    .collection("userBirdImage")
                    .whereEqualTo("userBirdRefId", userBirdId) // Assuming this field links to UserBird ID
                    .limit(1)
                    .get()
                    .addOnSuccessListener(queryDocumentSnapshots -> {
                        if (!queryDocumentSnapshots.isEmpty()) {
                            DocumentSnapshot userBirdImageDoc = queryDocumentSnapshots.getDocuments().get(0);
                            String imageUrl = userBirdImageDoc.getString("imageUrl");
                            Log.d(TAG, "Fetched imageUrl for userBirdId " + userBirdId + ": " + imageUrl);

                            if (imageUrl != null && !imageUrl.trim().isEmpty()) {
                                Glide.with(holder.itemView.getContext())
                                        .load(imageUrl)
                                        .centerCrop()
                                        .into(holder.imageView3);
                                // Set click listener only if image is successfully loaded
                                holder.itemView.setOnClickListener(v -> {
                                    Intent i = new Intent(v.getContext(), ViewBirdCardActivity.class);
                                    i.putExtra(EXTRA_IMAGE_URL, imageUrl); 
                                    i.putExtra(EXTRA_COMMON_NAME, common);
                                    i.putExtra(EXTRA_SCI_NAME, sci);
                                    i.putExtra(EXTRA_RARITY, rarity);
                                    v.getContext().startActivity(i);
                                });
                            } else {
                                Log.w(TAG, "imageUrl is null or empty for userBirdId: " + userBirdId + ". Displaying placeholder.");
                                holder.imageView3.setImageResource(R.drawable.birddexlogo); // Fallback to placeholder
                                holder.itemView.setOnClickListener(null); // Disable click if no image
                            }
                        } else {
                            Log.w(TAG, "No UserBirdImage document found for userBirdId: " + userBirdId + ". Displaying placeholder.");
                            holder.imageView3.setImageResource(R.drawable.birddexlogo); // Fallback to placeholder
                            holder.itemView.setOnClickListener(null); // Disable click if no image
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to fetch UserBirdImage for userBirdId: " + userBirdId + ". Error: " + e.getMessage(), e);
                        holder.imageView3.setImageResource(R.drawable.birddexlogo); // Fallback to placeholder on error
                        holder.itemView.setOnClickListener(null); // Disable click on error
                    });
        } else {
            Log.d(TAG, "Skipping image fetch: userId or userBirdId is null/empty. userId: " + userId + ", userBirdId: " + userBirdId);
            // If userId or userBirdId is not available, ensure placeholder is shown and click is disabled
            holder.imageView3.setImageResource(R.drawable.birddexlogo);
            holder.itemView.setOnClickListener(null);
        }
        // --- END Fetch and load image from UserBirdImage ---
    }

    @Override
    public int getItemCount() {
        return slots.size();
    }
}