package com.birddex.app;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import com.google.firebase.firestore.DocumentSnapshot;
import java.util.HashMap;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.List;

public class SearchCollectionFragment extends Fragment {

    private static final String TAG = "SearchCollectionFragment";
    private RecyclerView rvCollection;
    private CollectionCardAdapter adapter;
    private final List<CollectionSlot> slots = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_search_collection, container, false);

        rvCollection = v.findViewById(R.id.rvCollection);

        // Vertical card list
        rvCollection.setHasFixedSize(true);
        rvCollection.setLayoutManager(new GridLayoutManager(getContext(), 3));

        // Always show exactly 15 cards
        ensure15Slots();

        adapter = new CollectionCardAdapter(slots);
        rvCollection.setAdapter(adapter);

        fetchUserCollection();

        return v;
    }

    private void fetchUserCollection() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .collection("collectionSlot")
                .whereLessThan("slotIndex", 15)
                .orderBy("slotIndex", Query.Direction.ASCENDING)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    ensure15Slots();

                    Log.d(TAG, "Found " + queryDocumentSnapshots.size() + " documents in collection.");

                    // Place results into their slotIndex (0..14)
                    for (QueryDocumentSnapshot document : queryDocumentSnapshots) {
                        Log.d(TAG, "Document data: " + document.getData());

                        Long idxL = document.getLong("slotIndex");
                        int idx = idxL != null ? idxL.intValue() : -1;
                        if (idx < 0 || idx >= 15) continue;

                        CollectionSlot slot = slots.get(idx);
                        slot.setSlotIndex(idx);
                        slot.setImageUrl(document.getString("imageUrl"));
                        slot.setRarity(document.getString("rarity"));
                        slot.setCommonName(document.getString("commonName"));
                        slot.setScientificName(document.getString("scientificName"));
                        String docCommon = document.getString("commonName");
                        String docSci = document.getString("scientificName");
                        String userBirdId = document.getString("userBirdId");
                        String slotDocId = document.getId();

                        boolean missingNames = (docCommon == null || docCommon.trim().isEmpty())
                                && (docSci == null || docSci.trim().isEmpty());

                        if (missingNames && userBirdId != null && !userBirdId.trim().isEmpty()) {
                            FirebaseFirestore.getInstance()
                                    .collection("userBirds")
                                    .document(userBirdId)
                                    .get()
                                    .addOnSuccessListener(userBirdSnap -> {
                                        String birdId = userBirdSnap.getString("birdSpeciesId");
                                        if (birdId == null || birdId.trim().isEmpty()) return;

                                        FirebaseFirestore.getInstance()
                                                .collection("birds")
                                                .document(birdId)
                                                .get()
                                                .addOnSuccessListener(birdSnap -> {
                                                    String commonName = birdSnap.getString("commonName");
                                                    String scientificName = birdSnap.getString("scientificName");

                                                    // Update UI slot
                                                    if (commonName != null) slot.setCommonName(commonName);
                                                    if (scientificName != null) slot.setScientificName(scientificName);
                                                    adapter.notifyItemChanged(idx);

                                                    // Persist back into collectionSlot doc so it’s fixed permanently
                                                    Map<String, Object> updates = new HashMap<>();
                                                    if (commonName != null) updates.put("commonName", commonName);
                                                    if (scientificName != null) updates.put("scientificName", scientificName);

                                                    if (!updates.isEmpty()) {
                                                        FirebaseFirestore.getInstance()
                                                                .collection("users")
                                                                .document(user.getUid())
                                                                .collection("collectionSlot")
                                                                .document(slotDocId)
                                                                .update(updates);
                                                    }
                                                });
                                    });
                        }
                    }

                    adapter.notifyDataSetChanged();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error fetching collection", e);
                    Toast.makeText(getContext(), "Failed to load collection.", Toast.LENGTH_SHORT).show();
                });
    }

    private void ensure15Slots() {
        slots.clear();
        for (int i = 0; i < 15; i++) {
            CollectionSlot s = new CollectionSlot();
            s.setSlotIndex(i);
            s.setImageUrl(null);
            s.setRarity(null);
            slots.add(s);
        }
    }
}