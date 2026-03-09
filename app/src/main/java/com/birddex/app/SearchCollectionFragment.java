package com.birddex.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.Source;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SearchCollectionFragment extends Fragment {

    private static final String TAG = "SearchCollectionFragment";

    private enum SortMode { DEFAULT, NAME_A_TO_Z, NAME_Z_TO_A, NEWEST, OLDEST }
    private enum ViewMode { SPECIES_CARDS, RECENT_PHOTOS }

    private RecyclerView rvCollection;
    private EditText etSearch;
    private ImageButton btnFilter;
    private Button btnAddBird;
    private TextView tvCollectionEmpty;

    private CollectionCardAdapter cardAdapter;
    private RecentPhotoMemoriesAdapter recentPhotoAdapter;

    private ActivityResultLauncher<String> imagePickerLauncher;

    private final List<CollectionSlot> rawSlots = new ArrayList<>();
    private final List<CollectionSlot> uniqueSpeciesSlots = new ArrayList<>();
    private final List<CollectionSlot> displayedSlots = new ArrayList<>();
    private final List<RecentPhotoEntry> recentPhotoEntries = new ArrayList<>();
    private int fetchGeneration = 0;
    private SortMode currentSortMode = SortMode.DEFAULT;
    private ViewMode currentViewMode = ViewMode.SPECIES_CARDS;

    private boolean isNavigating = false;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        imagePickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), this::handleSelectedImage);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_search_collection, container, false);
        rvCollection = v.findViewById(R.id.rvCollection);
        etSearch = v.findViewById(R.id.etSearch);
        btnFilter = v.findViewById(R.id.btnFilter);
        btnAddBird = v.findViewById(R.id.btnAddBird);
        tvCollectionEmpty = v.findViewById(R.id.tvCollectionEmpty);

        cardAdapter = new CollectionCardAdapter(displayedSlots);
        recentPhotoAdapter = new RecentPhotoMemoriesAdapter(requireContext(), this::fetchRecentPhotos);
        applySpeciesCardMode();

        btnAddBird.setOnClickListener(view -> openImagePicker());
        btnFilter.setOnClickListener(view -> showFilterDialog());

        setupSearch();
        fetchUserCollection();
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        isNavigating = false;
        if (cardAdapter != null) cardAdapter.setNavigating(false);
        fetchUserCollection();
        if (currentViewMode == ViewMode.RECENT_PHOTOS) fetchRecentPhotos();
    }

    private void applySpeciesCardMode() {
        GridLayoutManager layoutManager = new GridLayoutManager(getContext(), 3);
        rvCollection.setLayoutManager(layoutManager);
        rvCollection.setAdapter(cardAdapter);
        etSearch.setHint("Search birds...");
    }

    private void applyRecentPhotosMode() {
        GridLayoutManager layoutManager = new GridLayoutManager(getContext(), 3);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return recentPhotoAdapter.isHeader(position) ? 3 : 1; }
        });
        rvCollection.setLayoutManager(layoutManager);
        rvCollection.setAdapter(recentPhotoAdapter);
        etSearch.setHint("Search birds...");
    }

    private void openImagePicker() { if (imagePickerLauncher != null) imagePickerLauncher.launch("image/*"); }
    
    private void handleSelectedImage(@Nullable Uri uri) {
        if (uri != null && getContext() != null) {
            if (isNavigating) return;
            isNavigating = true;
            startActivity(new Intent(getContext(), CropActivity.class).putExtra(CropActivity.EXTRA_IMAGE_URI, uri.toString()));
        }
    }

    private void showFilterDialog() {
        final String[] options = {"Default", "Name A-Z", "Name Z-A", "Newest first", "Oldest first", "Recent Photos"};
        new AlertDialog.Builder(requireContext()).setTitle("Filter collection").setSingleChoiceItems(options, currentViewMode == ViewMode.RECENT_PHOTOS ? 5 : currentSortMode.ordinal(), (dialog, which) -> {
            if (which == 5) {
                currentViewMode = ViewMode.RECENT_PHOTOS;
                applyRecentPhotosMode();
                fetchRecentPhotos();
            } else {
                currentViewMode = ViewMode.SPECIES_CARDS;
                currentSortMode = SortMode.values()[which];
                applySpeciesCardMode();
                applyCurrentFilter();
            }
            dialog.dismiss();
        }).setNegativeButton("Cancel", null).show();
    }

    private void setupSearch() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { applyCurrentFilter(); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void applyCurrentFilter() {
        String q = (etSearch != null && etSearch.getText() != null) ? etSearch.getText().toString() : "";
        if (currentViewMode == ViewMode.RECENT_PHOTOS) filterRecentPhotos(q); else filterCollection(q);
    }

    private void fetchUserCollection() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        FirebaseFirestore.getInstance().collection("users").document(user.getUid()).collection("collectionSlot").orderBy("slotIndex", Query.Direction.ASCENDING).get(Source.CACHE)
                .addOnSuccessListener(snap -> {
                    if (snap != null && !snap.isEmpty()) processCollectionSnapshots(user.getUid(), snap);
                    fetchCollectionFromServer(user.getUid());
                })
                .addOnFailureListener(e -> fetchCollectionFromServer(user.getUid()));
    }

    private void fetchCollectionFromServer(String uid) {
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("collectionSlot").orderBy("slotIndex", Query.Direction.ASCENDING).get(Source.SERVER)
                .addOnSuccessListener(snap -> processCollectionSnapshots(uid, snap)).addOnFailureListener(e -> Log.e(TAG, "Error", e));
    }

    private void processCollectionSnapshots(String uid, com.google.firebase.firestore.QuerySnapshot queryDocumentSnapshots) {
        final int myGeneration = ++fetchGeneration;
        rawSlots.clear();
        for (QueryDocumentSnapshot document : queryDocumentSnapshots) {
            CollectionSlot slot = new CollectionSlot();
            slot.setId(document.getId());
            slot.setSlotIndex(document.getLong("slotIndex") != null ? document.getLong("slotIndex").intValue() : 0);
            slot.setUserBirdId(document.getString("userBirdId"));
            slot.setBirdId(document.getString("birdId"));
            slot.setImageUrl(document.getString("imageUrl"));
            slot.setTimestamp(document.getDate("timestamp"));
            slot.setCommonName(document.getString("commonName"));
            slot.setScientificName(document.getString("scientificName"));
            slot.setState(document.getString("state"));
            slot.setLocality(document.getString("locality"));
            slot.setRarity(document.getString("rarity"));
            rawSlots.add(slot);

            boolean missingBirdId = isBlank(slot.getBirdId());
            boolean missingNames = isBlank(slot.getCommonName()) && isBlank(slot.getScientificName());
            boolean missingLocation = isBlank(slot.getState()) && isBlank(slot.getLocality());
            boolean missingTimestamp = slot.getTimestamp() == null;

            if ((missingBirdId || missingNames || missingLocation || missingTimestamp) && !isBlank(slot.getUserBirdId())) {
                backfillFromUserBird(uid, slot, myGeneration, missingBirdId, missingNames, missingLocation, missingTimestamp);
            }
        }
        if (fetchGeneration == myGeneration) rebuildSpeciesListAndFilter();
    }

    private void fetchRecentPhotos() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        FirebaseFirestore.getInstance().collection("users").document(user.getUid()).collection("userBirdImage").orderBy("timestamp", Query.Direction.DESCENDING).get(Source.CACHE)
                .addOnSuccessListener(snap -> {
                    if (snap != null && !snap.isEmpty()) processRecentPhotoSnapshots(snap);
                    fetchRecentPhotosFromServer(user.getUid());
                })
                .addOnFailureListener(e -> fetchRecentPhotosFromServer(user.getUid()));
    }

    private void fetchRecentPhotosFromServer(String uid) {
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("userBirdImage").orderBy("timestamp", Query.Direction.DESCENDING).get(Source.SERVER)
                .addOnSuccessListener(this::processRecentPhotoSnapshots).addOnFailureListener(e -> Log.e(TAG, "Error", e));
    }

    private void processRecentPhotoSnapshots(com.google.firebase.firestore.QuerySnapshot snap) {
        List<CollectionSlot> slotsCopy = new ArrayList<>(rawSlots);
        Map<String, String> commonMap = new LinkedHashMap<>();
        Map<String, String> sciMap = new LinkedHashMap<>();
        for (CollectionSlot s : slotsCopy) {
            if (s == null || isBlank(s.getBirdId())) continue;
            if (!isBlank(s.getCommonName())) commonMap.put(s.getBirdId(), s.getCommonName());
            if (!isBlank(s.getScientificName())) sciMap.put(s.getBirdId(), s.getScientificName());
        }

        recentPhotoEntries.clear();
        for (QueryDocumentSnapshot document : snap) {
            String img = document.getString("imageUrl");
            if (isBlank(img) || Boolean.TRUE.equals(document.getBoolean("hiddenFromUser"))) continue;
            RecentPhotoEntry entry = new RecentPhotoEntry();
            entry.documentId = document.getId();
            entry.imageUrl = img;
            entry.timestamp = document.getDate("timestamp");
            entry.birdId = document.getString("birdId");
            entry.commonName = !isBlank(document.getString("commonName")) ? document.getString("commonName") : commonMap.get(entry.birdId);
            entry.scientificName = !isBlank(document.getString("scientificName")) ? document.getString("scientificName") : sciMap.get(entry.birdId);
            recentPhotoEntries.add(entry);
        }
        if (currentViewMode == ViewMode.RECENT_PHOTOS) applyCurrentFilter();
    }

    private void backfillFromUserBird(String userId, CollectionSlot slot, int gen, boolean missingBirdId, boolean missingNames, boolean missingLocation, boolean missingTimestamp) {
        FirebaseFirestore.getInstance().collection("userBirds").document(slot.getUserBirdId()).get().addOnSuccessListener(ubSnap -> {
            if (fetchGeneration != gen || !ubSnap.exists()) return;
            Map<String, Object> baseUpdates = new LinkedHashMap<>();
            String birdId = ubSnap.getString("birdSpeciesId");
            String locationId = ubSnap.getString("locationId");
            Date timeSpotted = ubSnap.getDate("timeSpotted");

            if (missingBirdId && !isBlank(birdId)) { slot.setBirdId(birdId); baseUpdates.put("birdId", birdId); }
            if (missingTimestamp && timeSpotted != null) { slot.setTimestamp(timeSpotted); baseUpdates.put("timestamp", timeSpotted); }
            if (!baseUpdates.isEmpty()) updateCollectionSlot(userId, slot.getId(), baseUpdates);

            if (missingNames && !isBlank(birdId)) {
                FirebaseFirestore.getInstance().collection("birds").document(birdId).get().addOnSuccessListener(birdSnap -> {
                    if (fetchGeneration != gen || !birdSnap.exists()) return;
                    Map<String, Object> updates = new LinkedHashMap<>();
                    String commonName = birdSnap.getString("commonName");
                    String scientificName = birdSnap.getString("scientificName");
                    if (!isBlank(commonName)) { slot.setCommonName(commonName); updates.put("commonName", commonName); }
                    if (!isBlank(scientificName)) { slot.setScientificName(scientificName); updates.put("scientificName", scientificName); }
                    if (!updates.isEmpty()) updateCollectionSlot(userId, slot.getId(), updates);
                    rebuildSpeciesListAndFilter();
                });
            }

            if (missingLocation && !isBlank(locationId)) {
                FirebaseFirestore.getInstance().collection("locations").document(locationId).get().addOnSuccessListener(locationSnap -> {
                    if (fetchGeneration != gen || !locationSnap.exists()) return;
                    Map<String, Object> updates = new LinkedHashMap<>();
                    String state = locationSnap.getString("state");
                    String locality = locationSnap.getString("locality");
                    if (!isBlank(state)) { slot.setState(state); updates.put("state", state); }
                    if (!isBlank(locality)) { slot.setLocality(locality); updates.put("locality", locality); }
                    if (!updates.isEmpty()) updateCollectionSlot(userId, slot.getId(), updates);
                    rebuildSpeciesListAndFilter();
                });
            }
            rebuildSpeciesListAndFilter();
        });
    }

    private void rebuildSpeciesListAndFilter() {
        LinkedHashMap<String, CollectionSlot> grouped = new LinkedHashMap<>();
        for (CollectionSlot s : rawSlots) {
            if (s == null || isBlank(s.getImageUrl())) continue;
            String k = getSpeciesKey(s); if (!isBlank(k) && !grouped.containsKey(k)) grouped.put(k, s);
        }
        uniqueSpeciesSlots.clear(); uniqueSpeciesSlots.addAll(grouped.values());
        if (currentViewMode == ViewMode.SPECIES_CARDS) applyCurrentFilter();
    }

    private void filterCollection(String query) {
        String text = query == null ? "" : query.trim().toLowerCase(Locale.US);
        displayedSlots.clear();
        if (text.isEmpty()) displayedSlots.addAll(uniqueSpeciesSlots);
        else for (CollectionSlot s : uniqueSpeciesSlots) if (safeLower(s.getCommonName()).contains(text) || safeLower(s.getScientificName()).contains(text)) displayedSlots.add(s);
        sortDisplayedSlots(); cardAdapter.notifyDataSetChanged(); updateEmptyState(displayedSlots.isEmpty(), "No birds collected yet.");
    }

    private void filterRecentPhotos(String q) {
        String text = q == null ? "" : q.trim().toLowerCase(Locale.US);
        List<RecentPhotoMemoriesAdapter.MemoryItem> items = new ArrayList<>();
        SimpleDateFormat hF = new SimpleDateFormat("MMMM yyyy", Locale.US), dF = new SimpleDateFormat("MMM d, yyyy", Locale.US);
        String lastH = null;
        for (RecentPhotoEntry e : recentPhotoEntries) {
            if (!text.isEmpty() && !safeLower(e.commonName).contains(text) && !safeLower(e.scientificName).contains(text)) continue;
            String hT = e.timestamp != null ? hF.format(e.timestamp) : "Unknown Date";
            if (!hT.equals(lastH)) { items.add(RecentPhotoMemoriesAdapter.MemoryItem.createHeader(hT)); lastH = hT; }
            items.add(RecentPhotoMemoriesAdapter.MemoryItem.createPhoto(e.imageUrl, e.timestamp != null ? dF.format(e.timestamp) : "Unknown date", e.documentId));
        }
        recentPhotoAdapter.submitList(items); updateEmptyState(items.isEmpty(), "No bird photos match this search.");
    }

    private void sortDisplayedSlots() {
        switch (currentSortMode) {
            case NAME_A_TO_Z: displayedSlots.sort(Comparator.comparing(s -> safeLower(s.getCommonName()))); break;
            case NAME_Z_TO_A: displayedSlots.sort((a, b) -> safeLower(b.getCommonName()).compareTo(safeLower(a.getCommonName()))); break;
            case NEWEST: displayedSlots.sort((a, b) -> Long.compare(getTime(b.getTimestamp()), getTime(a.getTimestamp()))); break;
            case OLDEST: displayedSlots.sort(Comparator.comparingLong(s -> getTime(s.getTimestamp()))); break;
            default: displayedSlots.sort(Comparator.comparingInt(CollectionSlot::getSlotIndex)); break;
        }
    }

    private void updateEmptyState(boolean empty, String msg) { if (tvCollectionEmpty != null && rvCollection != null) { tvCollectionEmpty.setText(msg); tvCollectionEmpty.setVisibility(empty ? View.VISIBLE : View.GONE); rvCollection.setVisibility(empty ? View.GONE : View.VISIBLE); } }
    private String getSpeciesKey(CollectionSlot s) { if (!isBlank(s.getBirdId())) return "birdId:" + s.getBirdId().trim(); if (!isBlank(s.getCommonName())) return "common:" + s.getCommonName().trim().toLowerCase(Locale.US); return null; }
    private void updateCollectionSlot(String userId, String id, Map<String, Object> updates) { FirebaseFirestore.getInstance().collection("users").document(userId).collection("collectionSlot").document(id).update(updates); }
    private long getTime(Date d) { return d == null ? 0L : d.getTime(); }
    private String safeLower(String v) { return v == null ? "" : v.trim().toLowerCase(Locale.US); }
    private boolean isBlank(String v) { return v == null || v.trim().isEmpty(); }
    private static class RecentPhotoEntry { String documentId, imageUrl, birdId, commonName, scientificName; Date timestamp; }
}
