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

    private enum SortMode {
        DEFAULT,
        NAME_A_TO_Z,
        NAME_Z_TO_A,
        NEWEST,
        OLDEST
    }

    private enum ViewMode {
        SPECIES_CARDS,
        RECENT_PHOTOS
    }

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

    private SortMode currentSortMode = SortMode.DEFAULT;
    private ViewMode currentViewMode = ViewMode.SPECIES_CARDS;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                this::handleSelectedImage
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
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
        fetchUserCollection();

        if (currentViewMode == ViewMode.RECENT_PHOTOS) {
            fetchRecentPhotos();
        }
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
            @Override
            public int getSpanSize(int position) {
                return recentPhotoAdapter.isHeader(position) ? 3 : 1;
            }
        });

        rvCollection.setLayoutManager(layoutManager);
        rvCollection.setAdapter(recentPhotoAdapter);
        etSearch.setHint("Search birds...");
    }

    private void openImagePicker() {
        if (imagePickerLauncher == null) {
            Toast.makeText(getContext(), "Image picker is not ready yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        imagePickerLauncher.launch("image/*");
    }

    private void handleSelectedImage(@Nullable Uri imageUri) {
        if (imageUri == null || getContext() == null) {
            return;
        }

        Intent cropIntent = new Intent(getContext(), CropActivity.class);
        cropIntent.putExtra(CropActivity.EXTRA_IMAGE_URI, imageUri.toString());
        startActivity(cropIntent);
    }

    private void showFilterDialog() {
        if (getContext() == null) return;

        final String[] options = {
                "Default",
                "Name A-Z",
                "Name Z-A",
                "Newest first",
                "Oldest first",
                "Recent Photos"
        };

        final int checkedItem = (currentViewMode == ViewMode.RECENT_PHOTOS)
                ? 5
                : currentSortMode.ordinal();

        new AlertDialog.Builder(requireContext())
                .setTitle("Filter collection")
                .setSingleChoiceItems(options, checkedItem, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            currentViewMode = ViewMode.SPECIES_CARDS;
                            currentSortMode = SortMode.DEFAULT;
                            applySpeciesCardMode();
                            applyCurrentFilter();
                            break;

                        case 1:
                            currentViewMode = ViewMode.SPECIES_CARDS;
                            currentSortMode = SortMode.NAME_A_TO_Z;
                            applySpeciesCardMode();
                            applyCurrentFilter();
                            break;

                        case 2:
                            currentViewMode = ViewMode.SPECIES_CARDS;
                            currentSortMode = SortMode.NAME_Z_TO_A;
                            applySpeciesCardMode();
                            applyCurrentFilter();
                            break;

                        case 3:
                            currentViewMode = ViewMode.SPECIES_CARDS;
                            currentSortMode = SortMode.NEWEST;
                            applySpeciesCardMode();
                            applyCurrentFilter();
                            break;

                        case 4:
                            currentViewMode = ViewMode.SPECIES_CARDS;
                            currentSortMode = SortMode.OLDEST;
                            applySpeciesCardMode();
                            applyCurrentFilter();
                            break;

                        case 5:
                            currentViewMode = ViewMode.RECENT_PHOTOS;
                            applyRecentPhotosMode();
                            fetchRecentPhotos();
                            break;
                    }

                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setupSearch() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyCurrentFilter();
            }

            @Override
            public void afterTextChanged(Editable s) { }
        });
    }

    private void applyCurrentFilter() {
        String query = etSearch != null && etSearch.getText() != null
                ? etSearch.getText().toString()
                : "";

        if (currentViewMode == ViewMode.RECENT_PHOTOS) {
            filterRecentPhotos(query);
        } else {
            filterCollection(query);
        }
    }

    private void fetchUserCollection() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .collection("collectionSlot")
                .orderBy("slotIndex", Query.Direction.ASCENDING)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    rawSlots.clear();

                    for (QueryDocumentSnapshot document : queryDocumentSnapshots) {
                        CollectionSlot slot = new CollectionSlot();
                        slot.setId(document.getId());
                        slot.setSlotIndex(document.getLong("slotIndex") != null
                                ? document.getLong("slotIndex").intValue() : 0);
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

                        String userBirdId = slot.getUserBirdId();
                        if ((missingBirdId || missingNames || missingLocation || missingTimestamp)
                                && !isBlank(userBirdId)) {
                            backfillFromUserBird(user.getUid(), slot, missingBirdId, missingNames, missingLocation, missingTimestamp);
                        }
                    }

                    rebuildSpeciesListAndFilter();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error fetching collection", e);
                    Toast.makeText(getContext(), "Failed to load collection.", Toast.LENGTH_SHORT).show();
                });
    }

    private void fetchRecentPhotos() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        // Build a lookup from birdId -> bird names using already-loaded collection data
        Map<String, String> commonNameByBirdId = new LinkedHashMap<>();
        Map<String, String> scientificNameByBirdId = new LinkedHashMap<>();

        for (CollectionSlot slot : rawSlots) {
            if (slot == null) continue;

            String birdId = slot.getBirdId();
            if (isBlank(birdId)) continue;

            if (!isBlank(slot.getCommonName()) && !commonNameByBirdId.containsKey(birdId)) {
                commonNameByBirdId.put(birdId, slot.getCommonName());
            }

            if (!isBlank(slot.getScientificName()) && !scientificNameByBirdId.containsKey(birdId)) {
                scientificNameByBirdId.put(birdId, slot.getScientificName());
            }
        }

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .collection("userBirdImage")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    recentPhotoEntries.clear();

                    for (QueryDocumentSnapshot document : queryDocumentSnapshots) {
                        String imageUrl = document.getString("imageUrl");
                        Date timestamp = document.getDate("timestamp");
                        Boolean hiddenFromUser = document.getBoolean("hiddenFromUser");
                        String birdId = document.getString("birdId");
                        String commonName = document.getString("commonName");
                        String scientificName = document.getString("scientificName");

                        if (Boolean.TRUE.equals(hiddenFromUser)) {
                            continue;
                        }

                        if (isBlank(imageUrl)) continue;

                        RecentPhotoEntry entry = new RecentPhotoEntry();
                        entry.documentId = document.getId();
                        entry.imageUrl = imageUrl;
                        entry.timestamp = timestamp;
                        entry.birdId = birdId;

                        // Prefer names saved directly on the image doc, otherwise fall back to collection lookup
                        entry.commonName = !isBlank(commonName)
                                ? commonName
                                : commonNameByBirdId.get(birdId);

                        entry.scientificName = !isBlank(scientificName)
                                ? scientificName
                                : scientificNameByBirdId.get(birdId);

                        recentPhotoEntries.add(entry);
                    }

                    if (currentViewMode == ViewMode.RECENT_PHOTOS) {
                        filterRecentPhotos(etSearch != null && etSearch.getText() != null
                                ? etSearch.getText().toString()
                                : "");
                    }
                })
                .addOnFailureListener(e ->
                        Toast.makeText(getContext(), "Failed to load recent photos.", Toast.LENGTH_SHORT).show()
                );
    }

    private void backfillFromUserBird(String userId,
                                      CollectionSlot slot,
                                      boolean missingBirdId,
                                      boolean missingNames,
                                      boolean missingLocation,
                                      boolean missingTimestamp) {

        FirebaseFirestore.getInstance()
                .collection("userBirds")
                .document(slot.getUserBirdId())
                .get()
                .addOnSuccessListener(userBirdSnap -> {
                    if (!userBirdSnap.exists()) return;

                    Map<String, Object> baseUpdates = new LinkedHashMap<>();

                    String birdId = userBirdSnap.getString("birdSpeciesId");
                    String locationId = userBirdSnap.getString("locationId");
                    Date timeSpotted = userBirdSnap.getDate("timeSpotted");

                    if (missingBirdId && !isBlank(birdId)) {
                        slot.setBirdId(birdId);
                        baseUpdates.put("birdId", birdId);
                    }

                    if (missingTimestamp && timeSpotted != null) {
                        slot.setTimestamp(timeSpotted);
                        baseUpdates.put("timestamp", timeSpotted);
                    }

                    if (!baseUpdates.isEmpty()) {
                        updateCollectionSlot(userId, slot.getId(), baseUpdates);
                    }

                    if (missingNames && !isBlank(birdId)) {
                        FirebaseFirestore.getInstance()
                                .collection("birds")
                                .document(birdId)
                                .get()
                                .addOnSuccessListener(birdSnap -> {
                                    if (!birdSnap.exists()) return;

                                    Map<String, Object> updates = new LinkedHashMap<>();
                                    String commonName = birdSnap.getString("commonName");
                                    String scientificName = birdSnap.getString("scientificName");

                                    if (!isBlank(commonName)) {
                                        slot.setCommonName(commonName);
                                        updates.put("commonName", commonName);
                                    }

                                    if (!isBlank(scientificName)) {
                                        slot.setScientificName(scientificName);
                                        updates.put("scientificName", scientificName);
                                    }

                                    if (!updates.isEmpty()) {
                                        updateCollectionSlot(userId, slot.getId(), updates);
                                    }

                                    rebuildSpeciesListAndFilter();
                                });
                    }

                    if (missingLocation && !isBlank(locationId)) {
                        FirebaseFirestore.getInstance()
                                .collection("locations")
                                .document(locationId)
                                .get()
                                .addOnSuccessListener(locationSnap -> {
                                    if (!locationSnap.exists()) return;

                                    Map<String, Object> updates = new LinkedHashMap<>();
                                    String state = locationSnap.getString("state");
                                    String locality = locationSnap.getString("locality");

                                    if (!isBlank(state)) {
                                        slot.setState(state);
                                        updates.put("state", state);
                                    }

                                    if (!isBlank(locality)) {
                                        slot.setLocality(locality);
                                        updates.put("locality", locality);
                                    }

                                    if (!updates.isEmpty()) {
                                        updateCollectionSlot(userId, slot.getId(), updates);
                                    }

                                    rebuildSpeciesListAndFilter();
                                });
                    }

                    rebuildSpeciesListAndFilter();
                });
    }

    private void rebuildSpeciesListAndFilter() {
        LinkedHashMap<String, CollectionSlot> grouped = new LinkedHashMap<>();

        for (CollectionSlot slot : rawSlots) {
            if (slot == null) continue;
            if (isBlank(slot.getImageUrl())) continue;

            String key = getSpeciesKey(slot);
            if (isBlank(key)) continue;

            if (!grouped.containsKey(key)) {
                grouped.put(key, slot);
            }
        }

        uniqueSpeciesSlots.clear();
        uniqueSpeciesSlots.addAll(grouped.values());

        if (currentViewMode == ViewMode.SPECIES_CARDS) {
            applyCurrentFilter();
        }
    }

    private void filterCollection(String query) {
        String searchText = query == null ? "" : query.trim().toLowerCase(Locale.US);

        displayedSlots.clear();

        if (searchText.isEmpty()) {
            displayedSlots.addAll(uniqueSpeciesSlots);
        } else {
            for (CollectionSlot slot : uniqueSpeciesSlots) {
                String common = slot.getCommonName();
                String scientific = slot.getScientificName();

                boolean commonMatch = common != null && common.toLowerCase(Locale.US).contains(searchText);
                boolean scientificMatch = scientific != null && scientific.toLowerCase(Locale.US).contains(searchText);

                if (commonMatch || scientificMatch) {
                    displayedSlots.add(slot);
                }
            }
        }

        sortDisplayedSlots();
        cardAdapter.notifyDataSetChanged();
        updateEmptyState(displayedSlots.isEmpty(), "No birds match this filter.");
    }

    private void filterRecentPhotos(String query) {
        String searchText = query == null ? "" : query.trim().toLowerCase(Locale.US);

        List<RecentPhotoMemoriesAdapter.MemoryItem> memoryItems = new ArrayList<>();
        SimpleDateFormat headerFormat = new SimpleDateFormat("MMMM yyyy", Locale.US);
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy", Locale.US);

        String lastHeader = null;

        for (RecentPhotoEntry entry : recentPhotoEntries) {
            String common = entry.commonName == null ? "" : entry.commonName.toLowerCase(Locale.US);
            String scientific = entry.scientificName == null ? "" : entry.scientificName.toLowerCase(Locale.US);

            boolean matchesBird = searchText.isEmpty()
                    || common.contains(searchText)
                    || scientific.contains(searchText);

            if (!matchesBird) {
                continue;
            }

            String headerTitle = entry.timestamp != null
                    ? headerFormat.format(entry.timestamp)
                    : "Unknown Date";

            String dateLabel = entry.timestamp != null
                    ? dateFormat.format(entry.timestamp)
                    : "Unknown date";

            if (!headerTitle.equals(lastHeader)) {
                memoryItems.add(RecentPhotoMemoriesAdapter.MemoryItem.createHeader(headerTitle));
                lastHeader = headerTitle;
            }

            memoryItems.add(RecentPhotoMemoriesAdapter.MemoryItem.createPhoto(
                    entry.imageUrl,
                    dateLabel,
                    entry.documentId
            ));
        }

        recentPhotoAdapter.submitList(memoryItems);
        updateEmptyState(memoryItems.isEmpty(), "No bird photos match this search.");
    }

    private void sortDisplayedSlots() {
        switch (currentSortMode) {
            case NAME_A_TO_Z:
                Collections.sort(displayedSlots, Comparator.comparing(
                        slot -> safeLower(slot.getCommonName())
                ));
                break;

            case NAME_Z_TO_A:
                Collections.sort(displayedSlots, (a, b) ->
                        safeLower(b.getCommonName()).compareTo(safeLower(a.getCommonName()))
                );
                break;

            case NEWEST:
                Collections.sort(displayedSlots, (a, b) ->
                        Long.compare(getTime(b.getTimestamp()), getTime(a.getTimestamp()))
                );
                break;

            case OLDEST:
                Collections.sort(displayedSlots, Comparator.comparingLong(
                        slot -> getTime(slot.getTimestamp())
                ));
                break;

            case DEFAULT:
            default:
                Collections.sort(displayedSlots, Comparator.comparingInt(CollectionSlot::getSlotIndex));
                break;
        }
    }

    private void updateEmptyState(boolean isEmpty, String emptyMessage) {
        if (tvCollectionEmpty == null || rvCollection == null) return;

        tvCollectionEmpty.setText(emptyMessage);
        tvCollectionEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        rvCollection.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private String getSpeciesKey(CollectionSlot slot) {
        if (!isBlank(slot.getBirdId())) {
            return "birdId:" + slot.getBirdId().trim();
        }

        if (!isBlank(slot.getCommonName())) {
            return "common:" + slot.getCommonName().trim().toLowerCase(Locale.US);
        }

        if (!isBlank(slot.getScientificName())) {
            return "sci:" + slot.getScientificName().trim().toLowerCase(Locale.US);
        }

        return null;
    }

    private void updateCollectionSlot(String userId, String slotDocId, Map<String, Object> updates) {
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(userId)
                .collection("collectionSlot")
                .document(slotDocId)
                .update(updates);
    }

    private long getTime(Date date) {
        return date == null ? 0L : date.getTime();
    }

    private String safeLower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class RecentPhotoEntry {
        String documentId;
        String imageUrl;
        Date timestamp;
        String birdId;
        String commonName;
        String scientificName;
    }
}