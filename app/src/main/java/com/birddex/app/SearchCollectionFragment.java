package com.birddex.app;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        rvCollection.setHasFixedSize(true);
        rvCollection.setLayoutManager(new GridLayoutManager(getContext(), 3));

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

                    for (QueryDocumentSnapshot document : queryDocumentSnapshots) {
                        Long idxL = document.getLong("slotIndex");
                        int idx = idxL != null ? idxL.intValue() : -1;
                        if (idx < 0 || idx >= 15) continue;

                        CollectionSlot slot = slots.get(idx);
                        slot.setSlotIndex(idx);
                        slot.setImageUrl(document.getString("imageUrl"));
                        slot.setTimestamp(document.getDate("timestamp"));
                        slot.setCommonName(document.getString("commonName"));
                        slot.setScientificName(document.getString("scientificName"));
                        slot.setState(document.getString("state"));
                        slot.setLocality(document.getString("locality"));
                        slot.setRarity(document.getString("rarity"));

                        String userBirdId = document.getString("userBirdId");
                        String slotDocId = document.getId();

                        boolean missingNames = isBlank(slot.getCommonName()) && isBlank(slot.getScientificName());
                        boolean missingLocation = isBlank(slot.getState()) && isBlank(slot.getLocality());
                        boolean missingTimestamp = slot.getTimestamp() == null;

                        if ((missingNames || missingLocation || missingTimestamp) &&
                                userBirdId != null && !userBirdId.trim().isEmpty()) {
                            backfillFromUserBird(user.getUid(), slotDocId, idx, slot, userBirdId,
                                    missingNames, missingLocation, missingTimestamp);
                        }
                    }

                    adapter.notifyDataSetChanged();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error fetching collection", e);
                    Toast.makeText(getContext(), "Failed to load collection.", Toast.LENGTH_SHORT).show();
                });
    }

    private void backfillFromUserBird(String userId,
                                      String slotDocId,
                                      int idx,
                                      CollectionSlot slot,
                                      String userBirdId,
                                      boolean missingNames,
                                      boolean missingLocation,
                                      boolean missingTimestamp) {

        FirebaseFirestore.getInstance()
                .collection("userBirds")
                .document(userBirdId)
                .get()
                .addOnSuccessListener(userBirdSnap -> {
                    if (!userBirdSnap.exists()) return;

                    Map<String, Object> baseUpdates = new HashMap<>();

                    String birdId = userBirdSnap.getString("birdSpeciesId");
                    String locationId = userBirdSnap.getString("locationId");
                    Date timeSpotted = userBirdSnap.getDate("timeSpotted");

                    if (missingTimestamp && timeSpotted != null) {
                        slot.setTimestamp(timeSpotted);
                        baseUpdates.put("timestamp", timeSpotted);
                        adapter.notifyItemChanged(idx);
                    }

                    if (!baseUpdates.isEmpty()) {
                        updateCollectionSlot(userId, slotDocId, baseUpdates);
                    }

                    if (missingNames && birdId != null && !birdId.trim().isEmpty()) {
                        FirebaseFirestore.getInstance()
                                .collection("birds")
                                .document(birdId)
                                .get()
                                .addOnSuccessListener(birdSnap -> {
                                    if (!birdSnap.exists()) return;

                                    String commonName = birdSnap.getString("commonName");
                                    String scientificName = birdSnap.getString("scientificName");

                                    Map<String, Object> updates = new HashMap<>();

                                    if (!isBlank(commonName)) {
                                        slot.setCommonName(commonName);
                                        updates.put("commonName", commonName);
                                    }

                                    if (!isBlank(scientificName)) {
                                        slot.setScientificName(scientificName);
                                        updates.put("scientificName", scientificName);
                                    }

                                    if (!updates.isEmpty()) {
                                        updateCollectionSlot(userId, slotDocId, updates);
                                        adapter.notifyItemChanged(idx);
                                    }
                                });
                    }

                    if (missingLocation && locationId != null && !locationId.trim().isEmpty()) {
                        FirebaseFirestore.getInstance()
                                .collection("locations")
                                .document(locationId)
                                .get()
                                .addOnSuccessListener(locationSnap -> {
                                    if (!locationSnap.exists()) return;

                                    String state = locationSnap.getString("state");
                                    String locality = locationSnap.getString("locality");

                                    Map<String, Object> updates = new HashMap<>();

                                    if (!isBlank(state)) {
                                        slot.setState(state);
                                        updates.put("state", state);
                                    }

                                    if (!isBlank(locality)) {
                                        slot.setLocality(locality);
                                        updates.put("locality", locality);
                                    }

                                    if (!updates.isEmpty()) {
                                        updateCollectionSlot(userId, slotDocId, updates);
                                        adapter.notifyItemChanged(idx);
                                    }
                                });
                    }
                });
    }

    private void updateCollectionSlot(String userId, String slotDocId, Map<String, Object> updates) {
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(userId)
                .collection("collectionSlot")
                .document(slotDocId)
                .update(updates);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void ensure15Slots() {
        slots.clear();
        for (int i = 0; i < 15; i++) {
            CollectionSlot s = new CollectionSlot();
            s.setSlotIndex(i);
            s.setImageUrl(null);
            s.setTimestamp(null);
            s.setState(null);
            s.setLocality(null);
            slots.add(s);
        }
    }
}