package com.birddex.app;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

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
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SearchCollectionFragment extends Fragment {

    private static final String TAG = "SearchCollectionFragment";

    private RecyclerView rvCollection;
    private EditText etSearch;
    private CollectionCardAdapter adapter;
    private final List<CollectionSlot> slots = new ArrayList<>();
    private FirebaseFirestore db; // Added Firestore instance
    private FirebaseUser currentUser; // Added currentUser instance

    // Full collection (always keeps the full 15 slots)
    private final List<CollectionSlot> allSlots = new ArrayList<>();

    // What RecyclerView is currently showing
    private final List<CollectionSlot> displayedSlots = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_search_collection, container, false);

        rvCollection = v.findViewById(R.id.rvCollection);
        etSearch = v.findViewById(R.id.etSearch);

        rvCollection.setHasFixedSize(true);
        rvCollection.setLayoutManager(new GridLayoutManager(getContext(), 3));

        ensure15Slots(allSlots);

        db = FirebaseFirestore.getInstance(); // Initialize Firestore
        currentUser = FirebaseAuth.getInstance().getCurrentUser(); // Initialize currentUser

        // Initialize adapter with db and currentUser UID
        // Pass userId if currentUser is not null, otherwise null
        adapter = new CollectionCardAdapter(slots, db, currentUser != null ? currentUser.getUid() : null);
        displayedSlots.clear();
        displayedSlots.addAll(allSlots);

        adapter = new CollectionCardAdapter(displayedSlots);
        rvCollection.setAdapter(adapter);

        setupSearch();
        fetchUserCollection();

        return v;
    }

    private void setupSearch() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterCollection(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void filterCollection(String query) {
        String searchText = query == null ? "" : query.trim().toLowerCase(Locale.US);

        displayedSlots.clear();

        // If search is empty, show normal full collection page again
        if (searchText.isEmpty()) {
            displayedSlots.addAll(allSlots);
            adapter.notifyDataSetChanged();
            return;
        }

        // Only show captured birds whose COMMON NAME matches the search
        for (CollectionSlot slot : allSlots) {
            if (slot == null) continue;

            String imageUrl = slot.getImageUrl();
            String commonName = slot.getCommonName();

            boolean hasImage = imageUrl != null && !imageUrl.trim().isEmpty();
            boolean commonMatches = commonName != null
                    && commonName.toLowerCase(Locale.US).contains(searchText);

            if (hasImage && commonMatches) {
                displayedSlots.add(slot);
            }
        }

        adapter.notifyDataSetChanged();
    }

    private void fetchUserCollection() {
        if (currentUser == null) {
            adapter.notifyDataSetChanged(); // Notify even if no user, to clear/show placeholders
            return;
        }

        db.collection("users")
                .document(currentUser.getUid())
                .collection("collectionSlot")
                .whereLessThan("slotIndex", 15)
                .orderBy("slotIndex", Query.Direction.ASCENDING)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    ensure15Slots(allSlots);

                    for (QueryDocumentSnapshot document : queryDocumentSnapshots) {
                        Long idxL = document.getLong("slotIndex");
                        int idx = idxL != null ? idxL.intValue() : -1;
                        if (idx < 0 || idx >= 15) continue;

                        CollectionSlot slot = allSlots.get(idx);
                        slot.setSlotIndex(idx);
                        slot.setRarity(document.getString("rarity"));
                        slot.setImageUrl(document.getString("imageUrl"));
                        slot.setTimestamp(document.getDate("timestamp"));
                        slot.setCommonName(document.getString("commonName"));
                        slot.setScientificName(document.getString("scientificName"));
                        slot.setState(document.getString("state"));
                        slot.setLocality(document.getString("locality"));
                        slot.setRarity(document.getString("rarity"));

                        String userBirdId = document.getString("userBirdId");
                        String slotDocId = document.getId();

                        // *** Crucial Fix: Set the userBirdId on the CollectionSlot object ***
                        slot.setUserBirdId(userBirdId);

                        boolean missingNames = (docCommon == null || docCommon.trim().isEmpty())
                                && (docSci == null || docSci.trim().isEmpty());

                        if (missingNames && userBirdId != null && !userBirdId.trim().isEmpty()) {
                            db.collection("userBirds")
                                    .document(userBirdId)
                                    .get()
                                    .addOnSuccessListener(userBirdSnap -> {
                                        String birdId = userBirdSnap.getString("birdSpeciesId");
                                        if (birdId == null || birdId.trim().isEmpty()) return;

                                        db.collection("birds")
                                                .document(birdId)
                                                .get()
                                                .addOnSuccessListener(birdSnap -> {
                                                    String commonName = birdSnap.getString("commonName");
                                                    String scientificName = birdSnap.getString("scientificName");

                                                    // Update UI slot
                                                    if (commonName != null) slot.setCommonName(commonName);
                                                    if (scientificName != null) slot.setScientificName(scientificName);
                                                    // Notify adapter for this specific item change
                                                    adapter.notifyItemChanged(idx); 

                                                    // Persist back into collectionSlot doc so it’s fixed permanently
                                                    Map<String, Object> updates = new HashMap<>();
                                                    if (commonName != null) updates.put("commonName", commonName);
                                                    if (scientificName != null) updates.put("scientificName", scientificName);

                                                    if (!updates.isEmpty()) {
                                                        db.collection("users")
                                                                .document(currentUser.getUid())
                                                                .collection("collectionSlot")
                                                                .document(slotDocId)
                                                                .update(updates);
                                                    }
                                                });
                                    });
                        }
                    }

                    // After all CollectionSlots are processed, notify the adapter
                    adapter.notifyDataSetChanged();
                        boolean missingNames = isBlank(slot.getCommonName()) && isBlank(slot.getScientificName());
                        boolean missingLocation = isBlank(slot.getState()) && isBlank(slot.getLocality());
                        boolean missingTimestamp = slot.getTimestamp() == null;

                        if ((missingNames || missingLocation || missingTimestamp) &&
                                userBirdId != null && !userBirdId.trim().isEmpty()) {
                            backfillFromUserBird(user.getUid(), slotDocId, idx, slot, userBirdId,
                                    missingNames, missingLocation, missingTimestamp);
                        }
                    }

                    // Re-apply whatever is in the search bar after loading data
                    filterCollection(etSearch.getText() == null ? "" : etSearch.getText().toString());
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error fetching collection", e);
                    Toast.makeText(getContext(), "Failed to load collection.", Toast.LENGTH_SHORT).show();
                    adapter.notifyDataSetChanged(); // Notify even on failure to show placeholders
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
                                    }

                                    filterCollection(etSearch.getText() == null ? "" : etSearch.getText().toString());
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
                                    }

                                    filterCollection(etSearch.getText() == null ? "" : etSearch.getText().toString());
                                });
                    }

                    filterCollection(etSearch.getText() == null ? "" : etSearch.getText().toString());
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

    private void ensure15Slots(List<CollectionSlot> targetList) {
        targetList.clear();
        for (int i = 0; i < 15; i++) {
            CollectionSlot s = new CollectionSlot();
            s.setSlotIndex(i);
            s.setRarity("common"); // Default to common
            slots.add(s);
            s.setImageUrl(null);
            s.setTimestamp(null);
            s.setState(null);
            s.setLocality(null);
            s.setCommonName(null);
            s.setScientificName(null);
            s.setRarity(null);
            targetList.add(s);
        }
    }
}