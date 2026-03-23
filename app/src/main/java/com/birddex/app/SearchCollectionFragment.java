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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.Source;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SearchCollectionFragment: Search/browse screen for the user's saved bird cards.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class SearchCollectionFragment extends Fragment {

    private static final String TAG = "SearchCollectionFragment";

    private enum SortMode { DEFAULT, NAME_A_TO_Z, NAME_Z_TO_A, NEWEST, OLDEST }
    private enum ViewMode { SPECIES_CARDS, RECENT_PHOTOS }
    private enum RarityFilter {
        ALL,
        COMMON,
        UNCOMMON,
        RARE,
        EPIC,
        LEGENDARY,
        MYTHIC
    }

    private RecyclerView rvCollection;
    private EditText etSearch;
    private ImageButton btnFilter;
    private Button btnAddBird;
    private TextView tvCollectionEmpty;
    private BottomSheetDialog filterDialog;
    private boolean isFilterDialogOpening = false;
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
    private boolean favoritesOnly = false;
    private RarityFilter currentRarityFilter = RarityFilter.ALL;

    private boolean isNavigating = false;

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        imagePickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), this::handleSelectedImage);
    }

    /**
     * Android calls this to inflate the Fragment's XML and return the root view that will be shown
     * on screen.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        View v = inflater.inflate(R.layout.fragment_search_collection, container, false);
        rvCollection = v.findViewById(R.id.rvCollection);
        etSearch = v.findViewById(R.id.etSearch);
        btnFilter = v.findViewById(R.id.btnFilter);
        btnAddBird = v.findViewById(R.id.btnAddBird);
        tvCollectionEmpty = v.findViewById(R.id.tvCollectionEmpty);

        // Hook the data source to the list/grid adapter so model objects can render as UI rows/cards.
        cardAdapter = new CollectionCardAdapter(displayedSlots);
        recentPhotoAdapter = new RecentPhotoMemoriesAdapter(requireContext(), this::fetchRecentPhotos);
        applySpeciesCardMode();

        // Attach the user interaction that should run when this control is tapped.
        btnAddBird.setOnClickListener(view -> openImagePicker());
        btnFilter.setOnClickListener(view -> showFilterDialog());

        setupSearch();
        fetchUserCollection();
        return v;
    }

    /**
     * Runs when the screen returns to the foreground, so it often refreshes UI state or restarts
     * listeners.
     */
    @Override
    public void onResume() {
        super.onResume();
        isNavigating = false;
        if (cardAdapter != null) cardAdapter.setNavigating(false);
        fetchUserCollection();
        if (currentViewMode == ViewMode.RECENT_PHOTOS) fetchRecentPhotos();
    }

    /**
     * Main logic block for this part of the feature.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    private void applySpeciesCardMode() {
        GridLayoutManager layoutManager = new GridLayoutManager(getContext(), 3);
        rvCollection.setLayoutManager(layoutManager);
        // Hook the data source to the list/grid adapter so model objects can render as UI rows/cards.
        rvCollection.setAdapter(cardAdapter);

        if (favoritesOnly) {
            etSearch.setHint("Search favorites...");
        } else if (currentRarityFilter != RarityFilter.ALL) {
            etSearch.setHint("Search " + getRarityLabel(currentRarityFilter).toLowerCase(Locale.US) + " cards...");
        } else {
            etSearch.setHint("Search birds...");
        }
    }

    /**
     * Main logic block for this part of the feature.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    private void applyRecentPhotosMode() {
        GridLayoutManager layoutManager = new GridLayoutManager(getContext(), 3);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return recentPhotoAdapter.isHeader(position) ? 3 : 1;
            }
        });
        rvCollection.setLayoutManager(layoutManager);
        // Hook the data source to the list/grid adapter so model objects can render as UI rows/cards.
        rvCollection.setAdapter(recentPhotoAdapter);
        etSearch.setHint("Search birds...");
    }

    private void openImagePicker() {
        if (imagePickerLauncher != null) imagePickerLauncher.launch("image/*");
    }

    /**
     * Central handler that reacts to an event/input and decides what the next app action should
     * be.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     */
    private void handleSelectedImage(@Nullable Uri uri) {
        if (uri != null && getContext() != null) {
            if (isNavigating) return;
            isNavigating = true;
            // Move into the next screen and pass the identifiers/data that screen needs.
            startActivity(new Intent(getContext(), CropActivity.class)
                    .putExtra(CropActivity.EXTRA_IMAGE_URI, uri.toString())
                    .putExtra(CropActivity.EXTRA_AWARD_POINTS, false));
        }
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void showFilterDialog() {
        if (!isAdded() || getContext() == null) return;
        if (isFilterDialogOpening) return;
        if (filterDialog != null && filterDialog.isShowing()) return;

        isFilterDialogOpening = true;

        try {
            View sheetView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.bottom_sheet_collection_filter, null, false);

            filterDialog = new BottomSheetDialog(requireContext());
            BottomSheetDialog dialog = filterDialog;

            dialog.setContentView(sheetView);
            dialog.setOnShowListener(d -> {
                View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
                if (bottomSheet != null) {
                    bottomSheet.setBackgroundResource(android.R.color.transparent);
                    BottomSheetBehavior.from(bottomSheet).setState(BottomSheetBehavior.STATE_EXPANDED);
                }
            });

            dialog.setOnDismissListener(d -> {
                isFilterDialogOpening = false;
                filterDialog = null;
            });

            ChipGroup sortGroup = sheetView.findViewById(R.id.chipGroupSort);
            Chip chipSortDefault = sheetView.findViewById(R.id.chipSortDefault);
            Chip chipSortAz = sheetView.findViewById(R.id.chipSortAz);
            Chip chipSortZa = sheetView.findViewById(R.id.chipSortZa);
            Chip chipSortNewest = sheetView.findViewById(R.id.chipSortNewest);
            Chip chipSortOldest = sheetView.findViewById(R.id.chipSortOldest);

            ChipGroup showGroup = sheetView.findViewById(R.id.chipGroupShow);
            Chip chipShowAll = sheetView.findViewById(R.id.chipShowAll);
            Chip chipShowFavorites = sheetView.findViewById(R.id.chipShowFavorites);
            Chip chipShowRecent = sheetView.findViewById(R.id.chipShowRecent);

            TextView tvRarityTitle = sheetView.findViewById(R.id.tvRarityTitle);
            ChipGroup rarityGroup = sheetView.findViewById(R.id.chipGroupRarity);
            Chip chipRarityAll = sheetView.findViewById(R.id.chipRarityAll);
            Chip chipRarityCommon = sheetView.findViewById(R.id.chipRarityCommon);
            Chip chipRarityUncommon = sheetView.findViewById(R.id.chipRarityUncommon);
            Chip chipRarityRare = sheetView.findViewById(R.id.chipRarityRare);
            Chip chipRarityEpic = sheetView.findViewById(R.id.chipRarityEpic);
            Chip chipRarityLegendary = sheetView.findViewById(R.id.chipRarityLegendary);
            Chip chipRarityMythic = sheetView.findViewById(R.id.chipRarityMythic);

            Button btnReset = sheetView.findViewById(R.id.btnResetFilters);
            Button btnApply = sheetView.findViewById(R.id.btnApplyFilters);

            switch (currentSortMode) {
                case NAME_A_TO_Z:
                    chipSortAz.setChecked(true);
                    break;
                case NAME_Z_TO_A:
                    chipSortZa.setChecked(true);
                    break;
                case NEWEST:
                    chipSortNewest.setChecked(true);
                    break;
                case OLDEST:
                    chipSortOldest.setChecked(true);
                    break;
                case DEFAULT:
                default:
                    chipSortDefault.setChecked(true);
                    break;
            }

            if (currentViewMode == ViewMode.RECENT_PHOTOS) {
                chipShowRecent.setChecked(true);
            } else if (favoritesOnly) {
                chipShowFavorites.setChecked(true);
            } else {
                chipShowAll.setChecked(true);
            }

            switch (currentRarityFilter) {
                case COMMON:
                    chipRarityCommon.setChecked(true);
                    break;
                case UNCOMMON:
                    chipRarityUncommon.setChecked(true);
                    break;
                case RARE:
                    chipRarityRare.setChecked(true);
                    break;
                case EPIC:
                    chipRarityEpic.setChecked(true);
                    break;
                case LEGENDARY:
                    chipRarityLegendary.setChecked(true);
                    break;
                case MYTHIC:
                    chipRarityMythic.setChecked(true);
                    break;
                case ALL:
                default:
                    chipRarityAll.setChecked(true);
                    break;
            }

            updateRaritySectionEnabled(tvRarityTitle, rarityGroup, chipShowAll.isChecked());

            showGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
                boolean enableRarity = chipShowAll.isChecked();
                if (!enableRarity) {
                    chipRarityAll.setChecked(true);
                }
                updateRaritySectionEnabled(tvRarityTitle, rarityGroup, enableRarity);
            });

            btnReset.setOnClickListener(v -> {
                chipSortDefault.setChecked(true);
                chipShowAll.setChecked(true);
                chipRarityAll.setChecked(true);
                updateRaritySectionEnabled(tvRarityTitle, rarityGroup, true);
            });

            btnApply.setOnClickListener(v -> {
                int checkedSortId = sortGroup.getCheckedChipId();
                if (checkedSortId == R.id.chipSortAz) {
                    currentSortMode = SortMode.NAME_A_TO_Z;
                } else if (checkedSortId == R.id.chipSortZa) {
                    currentSortMode = SortMode.NAME_Z_TO_A;
                } else if (checkedSortId == R.id.chipSortNewest) {
                    currentSortMode = SortMode.NEWEST;
                } else if (checkedSortId == R.id.chipSortOldest) {
                    currentSortMode = SortMode.OLDEST;
                } else {
                    currentSortMode = SortMode.DEFAULT;
                }

                int checkedShowId = showGroup.getCheckedChipId();
                if (checkedShowId == R.id.chipShowRecent) {
                    currentViewMode = ViewMode.RECENT_PHOTOS;
                    favoritesOnly = false;
                    currentRarityFilter = RarityFilter.ALL;
                    applyRecentPhotosMode();
                    fetchRecentPhotos();
                } else {
                    currentViewMode = ViewMode.SPECIES_CARDS;
                    favoritesOnly = checkedShowId == R.id.chipShowFavorites;

                    if (favoritesOnly) {
                        currentRarityFilter = RarityFilter.ALL;
                    } else {
                        int checkedRarityId = rarityGroup.getCheckedChipId();
                        if (checkedRarityId == R.id.chipRarityCommon) {
                            currentRarityFilter = RarityFilter.COMMON;
                        } else if (checkedRarityId == R.id.chipRarityUncommon) {
                            currentRarityFilter = RarityFilter.UNCOMMON;
                        } else if (checkedRarityId == R.id.chipRarityRare) {
                            currentRarityFilter = RarityFilter.RARE;
                        } else if (checkedRarityId == R.id.chipRarityEpic) {
                            currentRarityFilter = RarityFilter.EPIC;
                        } else if (checkedRarityId == R.id.chipRarityLegendary) {
                            currentRarityFilter = RarityFilter.LEGENDARY;
                        } else if (checkedRarityId == R.id.chipRarityMythic) {
                            currentRarityFilter = RarityFilter.MYTHIC;
                        } else {
                            currentRarityFilter = RarityFilter.ALL;
                        }
                    }

                    applySpeciesCardMode();
                    applyCurrentFilter();
                }

                dialog.dismiss();
            });

            dialog.show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show filter dialog.", e);
            isFilterDialogOpening = false;
            filterDialog = null;
        }
    }

    private void updateRaritySectionEnabled(TextView titleView, ChipGroup rarityGroup, boolean enabled) {
        if (titleView != null) {
            titleView.setAlpha(enabled ? 1f : 0.45f);
        }

        if (rarityGroup == null) return;

        rarityGroup.setAlpha(enabled ? 1f : 0.45f);
        for (int i = 0; i < rarityGroup.getChildCount(); i++) {
            View child = rarityGroup.getChildAt(i);
            child.setEnabled(enabled);
            child.setClickable(enabled);
        }
    }

    @Override
    public void onDestroyView() {
        if (filterDialog != null) {
            filterDialog.setOnDismissListener(null);
            filterDialog.dismiss();
            filterDialog = null;
        }
        isFilterDialogOpening = false;
        super.onDestroyView();
    }

    /**
     * Updates object/screen state by storing a new value or reconfiguring a dependency.
     */
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

    /**
     * Main logic block for this part of the feature.
     */
    private void applyCurrentFilter() {
        String q = (etSearch != null && etSearch.getText() != null) ? etSearch.getText().toString() : "";
        if (currentViewMode == ViewMode.RECENT_PHOTOS) {
            filterRecentPhotos(q);
        } else {
            filterCollection(q);
        }
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    private void fetchUserCollection() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .collection("collectionSlot")
                .orderBy("slotIndex", Query.Direction.ASCENDING)
                .get(Source.CACHE)
                .addOnSuccessListener(snap -> {
                    if (snap != null && !snap.isEmpty()) processCollectionSnapshots(user.getUid(), snap);
                    fetchCollectionFromServer(user.getUid());
                })
                .addOnFailureListener(e -> fetchCollectionFromServer(user.getUid()));
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    private void fetchCollectionFromServer(String uid) {
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("collectionSlot")
                .orderBy("slotIndex", Query.Direction.ASCENDING)
                .get(Source.SERVER)
                .addOnSuccessListener(snap -> processCollectionSnapshots(uid, snap))
                .addOnFailureListener(e -> Log.e(TAG, "Error", e));
    }

    /**
     * Main logic block for this part of the feature.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
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
            slot.setFavorite(Boolean.TRUE.equals(document.getBoolean("isFavorite")));
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

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    private void fetchRecentPhotos() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .collection("userBirdImage")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get(Source.CACHE)
                .addOnSuccessListener(snap -> {
                    if (snap != null && !snap.isEmpty()) processRecentPhotoSnapshots(snap);
                    fetchRecentPhotosFromServer(user.getUid());
                })
                .addOnFailureListener(e -> fetchRecentPhotosFromServer(user.getUid()));
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    private void fetchRecentPhotosFromServer(String uid) {
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("userBirdImage")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get(Source.SERVER)
                .addOnSuccessListener(this::processRecentPhotoSnapshots)
                .addOnFailureListener(e -> Log.e(TAG, "Error", e));
    }

    /**
     * Main logic block for this part of the feature.
     */
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

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private void backfillFromUserBird(String userId, CollectionSlot slot, int gen, boolean missingBirdId, boolean missingNames, boolean missingLocation, boolean missingTimestamp) {
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        FirebaseFirestore.getInstance()
                .collection("userBirds")
                .document(slot.getUserBirdId())
                .get()
                .addOnSuccessListener(ubSnap -> {
                    if (fetchGeneration != gen || !ubSnap.exists()) return;

                    Map<String, Object> baseUpdates = new LinkedHashMap<>();
                    String birdId = ubSnap.getString("birdSpeciesId");
                    String locationId = ubSnap.getString("locationId");
                    Date timeSpotted = ubSnap.getDate("timeSpotted");

                    if (missingBirdId && !isBlank(birdId)) {
                        slot.setBirdId(birdId);
                        baseUpdates.put("birdId", birdId);
                    }

                    if (missingTimestamp && timeSpotted != null) {
                        slot.setTimestamp(timeSpotted);
                        baseUpdates.put("timestamp", timeSpotted);
                    }

                    if (!baseUpdates.isEmpty()) updateCollectionSlot(userId, slot.getId(), baseUpdates);

                    if (missingNames && !isBlank(birdId)) {
                        FirebaseFirestore.getInstance()
                                .collection("birds")
                                .document(birdId)
                                .get()
                                .addOnSuccessListener(birdSnap -> {
                                    if (fetchGeneration != gen || !birdSnap.exists()) return;
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

                                    if (!updates.isEmpty()) updateCollectionSlot(userId, slot.getId(), updates);
                                    rebuildSpeciesListAndFilter();
                                });
                    }

                    if (missingLocation && !isBlank(locationId)) {
                        FirebaseFirestore.getInstance()
                                .collection("locations")
                                .document(locationId)
                                .get()
                                .addOnSuccessListener(locationSnap -> {
                                    if (fetchGeneration != gen || !locationSnap.exists()) return;
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

                                    if (!updates.isEmpty()) updateCollectionSlot(userId, slot.getId(), updates);
                                    rebuildSpeciesListAndFilter();
                                });
                    }

                    rebuildSpeciesListAndFilter();
                });
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void rebuildSpeciesListAndFilter() {
        LinkedHashMap<String, CollectionSlot> grouped = new LinkedHashMap<>();
        for (CollectionSlot s : rawSlots) {
            if (s == null || isBlank(s.getImageUrl())) continue;
            String k = getSpeciesKey(s);
            if (!isBlank(k) && !grouped.containsKey(k)) grouped.put(k, s);
        }
        uniqueSpeciesSlots.clear();
        uniqueSpeciesSlots.addAll(grouped.values());
        if (currentViewMode == ViewMode.SPECIES_CARDS) applyCurrentFilter();
    }

    /**
     * Main logic block for this part of the feature.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    private void filterCollection(String query) {
        String text = query == null ? "" : query.trim().toLowerCase(Locale.US);
        displayedSlots.clear();

        for (CollectionSlot s : uniqueSpeciesSlots) {
            if (favoritesOnly && !s.isFavorite()) continue;
            if (!matchesRarityFilter(s)) continue;

            boolean matchesSearch = text.isEmpty()
                    || safeLower(s.getCommonName()).contains(text)
                    || safeLower(s.getScientificName()).contains(text);

            if (matchesSearch) {
                displayedSlots.add(s);
            }
        }

        sortDisplayedSlots();
        cardAdapter.notifyDataSetChanged();

        if (favoritesOnly) {
            updateEmptyState(displayedSlots.isEmpty(), "No favorite birds yet.");
        } else if (currentRarityFilter != RarityFilter.ALL) {
            updateEmptyState(displayedSlots.isEmpty(), "No " + getRarityLabel(currentRarityFilter).toLowerCase(Locale.US) + " cards found.");
        } else {
            updateEmptyState(displayedSlots.isEmpty(), "No birds collected yet.");
        }
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void filterRecentPhotos(String q) {
        String text = q == null ? "" : q.trim().toLowerCase(Locale.US);
        List<RecentPhotoMemoriesAdapter.MemoryItem> items = new ArrayList<>();
        SimpleDateFormat hF = new SimpleDateFormat("MMMM yyyy", Locale.US);
        SimpleDateFormat dF = new SimpleDateFormat("MMM d, yyyy", Locale.US);
        String lastH = null;

        for (RecentPhotoEntry e : recentPhotoEntries) {
            if (!text.isEmpty() && !safeLower(e.commonName).contains(text) && !safeLower(e.scientificName).contains(text)) continue;
            String hT = e.timestamp != null ? hF.format(e.timestamp) : "Unknown Date";
            if (!hT.equals(lastH)) {
                items.add(RecentPhotoMemoriesAdapter.MemoryItem.createHeader(hT));
                lastH = hT;
            }
            items.add(RecentPhotoMemoriesAdapter.MemoryItem.createPhoto(
                    e.imageUrl,
                    e.timestamp != null ? dF.format(e.timestamp) : "Unknown date",
                    e.documentId
            ));
        }

        recentPhotoAdapter.submitList(items);
        updateEmptyState(items.isEmpty(), "No bird photos match this search.");
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void sortDisplayedSlots() {
        switch (currentSortMode) {
            case NAME_A_TO_Z:
                displayedSlots.sort(Comparator.comparing(s -> safeLower(s.getCommonName())));
                break;
            case NAME_Z_TO_A:
                displayedSlots.sort((a, b) -> safeLower(b.getCommonName()).compareTo(safeLower(a.getCommonName())));
                break;
            case NEWEST:
                displayedSlots.sort((a, b) -> Long.compare(getTime(b.getTimestamp()), getTime(a.getTimestamp())));
                break;
            case OLDEST:
                displayedSlots.sort(Comparator.comparingLong(s -> getTime(s.getTimestamp())));
                break;
            case DEFAULT:
            default:
                displayedSlots.sort(Comparator.comparingInt(CollectionSlot::getSlotIndex));
                break;
        }
    }

    private boolean matchesRarityFilter(CollectionSlot slot) {
        if (currentRarityFilter == RarityFilter.ALL) return true;

        String rarity = CardRarityHelper.normalizeRarity(slot.getRarity());
        switch (currentRarityFilter) {
            case COMMON:
                return CardRarityHelper.COMMON.equals(rarity);
            case UNCOMMON:
                return CardRarityHelper.UNCOMMON.equals(rarity);
            case RARE:
                return CardRarityHelper.RARE.equals(rarity);
            case EPIC:
                return CardRarityHelper.EPIC.equals(rarity);
            case LEGENDARY:
                return CardRarityHelper.LEGENDARY.equals(rarity);
            case MYTHIC:
                return CardRarityHelper.MYTHIC.equals(rarity);
            case ALL:
            default:
                return true;
        }
    }

    private String getRarityLabel(RarityFilter filter) {
        switch (filter) {
            case COMMON:
                return "Common";
            case UNCOMMON:
                return "Uncommon";
            case RARE:
                return "Rare";
            case EPIC:
                return "Epic";
            case LEGENDARY:
                return "Legendary";
            case MYTHIC:
                return "Mythic";
            case ALL:
            default:
                return "All";
        }
    }

    private void updateEmptyState(boolean empty, String msg) {
        if (tvCollectionEmpty != null && rvCollection != null) {
            tvCollectionEmpty.setText(msg);
            tvCollectionEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            rvCollection.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    private String getSpeciesKey(CollectionSlot s) {
        if (!isBlank(s.getBirdId())) return "birdId:" + s.getBirdId().trim();
        if (!isBlank(s.getCommonName())) return "common:" + s.getCommonName().trim().toLowerCase(Locale.US);
        return null;
    }

    private void updateCollectionSlot(String userId, String id, Map<String, Object> updates) {
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(userId)
                .collection("collectionSlot")
                .document(id)
                .update(updates);
    }

    private long getTime(Date d) {
        return d == null ? 0L : d.getTime();
    }

    private String safeLower(String v) {
        return v == null ? "" : v.trim().toLowerCase(Locale.US);
    }

    private boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    private static class RecentPhotoEntry {
        String documentId;
        String imageUrl;
        String birdId;
        String commonName;
        String scientificName;
        Date timestamp;
    }
}