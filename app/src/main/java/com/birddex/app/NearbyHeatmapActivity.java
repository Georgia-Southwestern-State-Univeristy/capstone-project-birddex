package com.birddex.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Window;
import androidx.annotation.Nullable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import java.util.HashSet;
import java.util.Iterator;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.Source;
import com.google.firebase.firestore.WriteBatch;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.maps.android.heatmaps.Gradient;
import com.google.maps.android.heatmaps.HeatmapTileProvider;
import com.google.maps.android.heatmaps.WeightedLatLng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NearbyHeatmapActivity shows sightings on a map.
 * Fixed Race Conditions:
 * 1. Navigation Flooding: Added isNavigating guard.
 * 2. Stale Fetch: Added fetchGeneration counter.
 * 3. Like Spam: Added postLikeInFlight and commentLikeInFlight guards.
 */
/**
 * NearbyHeatmapActivity: Map screen that draws heatmap-style sightings and supports map/filter interactions.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class NearbyHeatmapActivity extends AppCompatActivity
        implements OnMapReadyCallback, GoogleMap.OnCircleClickListener, GoogleMap.OnMarkerClickListener, ForumCommentAdapter.OnCommentInteractionListener {

    private static final String TAG = "NearbyHeatmapActivity";
    public static final String EXTRA_CENTER_LAT = "extra_center_lat";
    public static final String EXTRA_CENTER_LNG = "extra_center_lng";
    public static final String EXTRA_OPEN_POST_ID = "extra_post_id";
    private static final String PREFS_NAME = "BirdDexPrefs";
    public static final String EXTRA_TRACKED_SIGHTING_ID = "extra_tracked_sighting_id";
    public static final String EXTRA_TRACKED_BIRD_ID = "extra_tracked_bird_id";
    public static final String EXTRA_TRACKED_BIRD_NAME = "extra_tracked_bird_name";
    private String trackedSightingIdFromNotification;
    private String trackedBirdIdFromNotification;
    private String trackedBirdNameFromNotification;
    private static final String KEY_GRAPHIC_CONTENT = "show_graphic_content";
    private static final int VOTE_NONE = 0;
    private final Map<String, Boolean> savedPostStateCache = new ConcurrentHashMap<>();
    private final Set<String> savedPostStateInFlight = ConcurrentHashMap.newKeySet();
    private static final int VOTE_UP = 1;
    private static final int VOTE_DOWN = -1;
    private View legendHeader;
    private View legendContent;
    private TextView tvLegendToggle;
    private boolean isLegendExpanded = true;
    private LatLngBounds currentVisibleBounds;
    private float lastAppliedZoom = -1f;
    private LatLng lastAppliedTarget;
    private static final float MIN_ZOOM_CHANGE_TO_REFRESH = 0.5f;
    private static final float MIN_CAMERA_MOVE_TO_REFRESH_METERS = 500f;
    /** Wait until the camera stops before reloading tiles — avoids flicker/jitter while panning or zooming. */
    private static final long CAMERA_IDLE_DEBOUNCE_MS = 420L;

    private final Handler heatmapCameraHandler = new Handler(Looper.getMainLooper());
    private final Runnable debouncedHeatmapReloadRunnable = this::onDebouncedCameraIdleForHeatmap;

    private static final double DEFAULT_LAT = 32.6781;
    private static final double DEFAULT_LNG = -83.2220;
    private static final float DEFAULT_ZOOM = 7f;
    private static final float NEARBY_ZOOM = 10f;
    private static final float POST_FOCUS_ZOOM = 16f;

    private static final double SEARCH_RADIUS_METERS = 50000d;
    private static final long SIGHTING_RECENCY_MS = 72L * 60 * 60 * 1000;

    private static final double HOTSPOT_BUCKET_SIZE = 0.02d;
    private static final double HOTSPOT_CIRCLE_RADIUS_METERS = 900d;

    private static final Gradient USER_UNVERIFIED_GRADIENT = new Gradient(
            new int[]{Color.argb(0, 255, 138, 0), Color.rgb(255, 195, 0), Color.rgb(255, 138, 0), Color.rgb(255, 122, 0)},
            new float[]{0.2f, 0.5f, 0.8f, 1f}
    );

    private static final Gradient USER_MIXED_GRADIENT = new Gradient(
            new int[]{Color.argb(0, 255, 105, 180), Color.rgb(255, 145, 214), Color.rgb(244, 114, 182), Color.rgb(236, 72, 153)},
            new float[]{0.2f, 0.5f, 0.8f, 1f}
    );

    private static final Gradient USER_VERIFIED_GRADIENT = new Gradient(
            new int[]{Color.argb(0, 109, 40, 217), Color.rgb(196, 181, 253), Color.rgb(139, 92, 246), Color.rgb(109, 40, 217)},
            new float[]{0.2f, 0.5f, 0.8f, 1f}
    );

    private static final Gradient EBIRD_GRADIENT = new Gradient(
            new int[]{Color.argb(0, 37, 99, 235), Color.rgb(103, 232, 249), Color.rgb(59, 130, 246), Color.rgb(29, 78, 216)},
            new float[]{0.2f, 0.5f, 0.8f, 1f}
    );

    private GoogleMap googleMap;
    private FirebaseFirestore db;
    private FirebaseAuth mAuth;
    private TextView tvMapSubtitle;
    private ImageButton btnBirdSearch;
    private ImageButton btnPinFilter;

    private final Set<String> followedUserIds = new HashSet<>();
    private boolean showFollowingPinsOnly = false;

    private final List<Bird> searchableBirds = new ArrayList<>();
    private boolean isSearchDataLoading = false;
    private String selectedBirdId;
    private String selectedBirdCommonName;
    private String selectedBirdScientificName;
    private String selectedBirdLabel;

    private TileOverlay userUnverifiedOverlay;
    private TileOverlay userMixedOverlay;
    private TileOverlay userVerifiedOverlay;
    private TileOverlay eBirdOverlay;

    private final List<WeightedLatLng> userHeatPoints = new ArrayList<>();
    private final List<WeightedLatLng> eBirdHeatPoints = new ArrayList<>();
    private final List<HotspotSighting> userHotspotSightings = new ArrayList<>();
    private final List<HotspotSighting> eBirdHotspotSightings = new ArrayList<>();

    private final Map<String, HotspotBucket> hotspotBuckets = new LinkedHashMap<>();
    private final List<Circle> hotspotCircles = new ArrayList<>();
    private final Map<String, HotspotBucket> circleIdToBucket = new HashMap<>();

    private final List<Marker> forumMarkers = new ArrayList<>();

    private double centerLat = Double.NaN;
    private double centerLng = Double.NaN;

    private int pendingLoads = 0;
    private int fetchGeneration = 0;

    private ForumComment replyingToComment = null;
    private EditText currentPopupEditText;
    private ForumPost activePost;
    private FirebaseManager firebaseManager;
    private ForumCommentAdapter popupCommentAdapter;

    private List<ForumComment> popupCommentList = new ArrayList<>();
    private final List<ListenerRegistration> activeListeners = new ArrayList<>();
    private DocumentSnapshot lastPopupCommentVisible;
    private boolean isFetchingPopupComments = false;
    private boolean isLastPopupCommentsPage = false;
    private boolean trackedBirdNotificationHandled = false;
    private String pendingOpenPostId;
    private boolean pendingOpenPostHandled = false;
    private static final int POPUP_COMMENTS_PAGE_SIZE = 25;

    // --- FIXES ---
    private final Set<String> postLikeInFlight = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> commentLikeInFlight = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private boolean isNavigating = false;

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nearby_heatmap);

        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db = FirebaseFirestore.getInstance();
        mAuth = FirebaseAuth.getInstance();
        firebaseManager = new FirebaseManager(this);

        // Bind or inflate the UI pieces this method needs before it can update the screen.
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        tvMapSubtitle = findViewById(R.id.tvMapSubtitle);
        btnBirdSearch = findViewById(R.id.btnBirdSearch);
        btnPinFilter = findViewById(R.id.btnPinFilter);

        if (btnBirdSearch != null) {
            btnBirdSearch.setOnClickListener(v -> openBirdSearchDialog());
            btnBirdSearch.setOnLongClickListener(v -> {
                if (hasSelectedBirdFilter()) {
                    clearSelectedBirdFilter();
                    MessagePopupHelper.showBrief(this, "Bird filter cleared");
                    return true;
                }
                return false;
            });
        }

        if (btnPinFilter != null) {
            btnPinFilter.setOnClickListener(this::showPinFilterMenu);
        }

        updateBirdSearchUi();
        updatePinFilterUi();
        primeSearchableBirds();

        legendHeader = findViewById(R.id.legendHeader);
        legendContent = findViewById(R.id.legendContent);
        tvLegendToggle = findViewById(R.id.tvLegendToggle);

        legendHeader.setOnClickListener(v -> toggleLegend());
        updateLegendUi();

        if (getIntent() != null && getIntent().hasExtra(EXTRA_CENTER_LAT)) {
            centerLat = getIntent().getDoubleExtra(EXTRA_CENTER_LAT, Double.NaN);
            centerLng = getIntent().getDoubleExtra(EXTRA_CENTER_LNG, Double.NaN);
        }
        if (getIntent() != null) {
            trackedSightingIdFromNotification = getIntent().getStringExtra(EXTRA_TRACKED_SIGHTING_ID);
            trackedBirdIdFromNotification = getIntent().getStringExtra(EXTRA_TRACKED_BIRD_ID);
            trackedBirdNameFromNotification = getIntent().getStringExtra(EXTRA_TRACKED_BIRD_NAME);
            pendingOpenPostId = getIntent().getStringExtra(EXTRA_OPEN_POST_ID);
        }

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) mapFragment.getMapAsync(this);
            // Give the user immediate feedback about the result of this action.
        else {
            MessagePopupHelper.showBrief(this, "Map failed to load");
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        heatmapCameraHandler.removeCallbacks(debouncedHeatmapReloadRunnable);
        super.onDestroy();
        for (ListenerRegistration lr : activeListeners) {
            if (lr != null) lr.remove();
        }
        activeListeners.clear();
    }

    private void toggleLegend() {
        isLegendExpanded = !isLegendExpanded;
        updateLegendUi();
    }

    private void updateLegendUi() {
        if (legendContent == null || tvLegendToggle == null) return;

        legendContent.setVisibility(isLegendExpanded ? View.VISIBLE : View.GONE);
        tvLegendToggle.setText(isLegendExpanded ? "▲" : "▼");
    }

    private void updateBirdSearchUi() {
        if (btnBirdSearch == null) return;

        boolean hasFilter = hasSelectedBirdFilter();
        btnBirdSearch.setAlpha(hasFilter ? 1f : 0.82f);
        btnBirdSearch.setContentDescription(
                hasFilter
                        ? "Bird filter: " + safeSelectedBirdLabel() + ". Long press to clear."
                        : "Search birds"
        );
    }

    private void updatePinFilterUi() {
        if (btnPinFilter == null) return;

        btnPinFilter.setAlpha(showFollowingPinsOnly ? 1f : 0.82f);
        btnPinFilter.setContentDescription(
                showFollowingPinsOnly
                        ? "Pin filter: following only"
                        : "Pin filter: all pins"
        );
    }

    private void showPinFilterMenu(View anchor) {
        PopupMenu popupMenu = new PopupMenu(this, anchor);

        popupMenu.getMenu().add(
                0,
                1,
                0,
                showFollowingPinsOnly ? "All pins" : "✓ All pins"
        );

        popupMenu.getMenu().add(
                0,
                2,
                1,
                showFollowingPinsOnly ? "✓ Following only" : "Following only"
        );

        popupMenu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                setFollowingPinsOnly(false);
                return true;
            } else if (item.getItemId() == 2) {
                setFollowingPinsOnly(true);
                return true;
            }
            return false;
        });

        popupMenu.show();
    }

    private void setFollowingPinsOnly(boolean enabled) {
        if (!enabled) {
            showFollowingPinsOnly = false;
            updatePinFilterUi();
            loadForumPins();
            return;
        }

        ensureFollowedUserIdsLoaded(() -> {
            showFollowingPinsOnly = true;
            updatePinFilterUi();
            loadForumPins();

            if (followedUserIds.isEmpty()) {
                MessagePopupHelper.showBrief(this, "You are not following anyone yet.");
            }
        });
    }

    private void ensureFollowedUserIdsLoaded(Runnable onComplete) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            followedUserIds.clear();
            if (onComplete != null) onComplete.run();
            return;
        }

        db.collection("users")
                .document(currentUser.getUid())
                .collection("following")
                .get()
                .addOnSuccessListener(snap -> {
                    followedUserIds.clear();
                    for (DocumentSnapshot doc : snap.getDocuments()) {
                        followedUserIds.add(doc.getId());
                    }
                    if (onComplete != null) onComplete.run();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to load followed users for heatmap pin filter", e);
                    followedUserIds.clear();
                    if (onComplete != null) onComplete.run();
                });
    }

    private void primeSearchableBirds() {
        if (isSearchDataLoading || !searchableBirds.isEmpty()) return;

        isSearchDataLoading = true;
        firebaseManager.getAllBirds(task -> {
            try {
                if (task.isSuccessful() && task.getResult() != null) {
                    List<Bird> loaded = new ArrayList<>();
                    for (DocumentSnapshot d : task.getResult().getDocuments()) {
                        Bird bird = d.toObject(Bird.class);
                        if (bird == null) continue;
                        if (bird.getId() == null || bird.getId().trim().isEmpty()) {
                            bird.setId(d.getId());
                        }
                        loaded.add(bird);
                    }

                    loaded.sort((left, right) -> safeBirdLabel(left).compareToIgnoreCase(safeBirdLabel(right)));
                    searchableBirds.clear();
                    searchableBirds.addAll(loaded);
                }
            } finally {
                isSearchDataLoading = false;
            }
        });
    }

    private void openBirdSearchDialog() {
        if (searchableBirds.isEmpty()) {
            primeSearchableBirds();
            MessagePopupHelper.showBrief(this, isSearchDataLoading ? "Loading birds..." : "Bird list is not ready yet.");
            return;
        }

        List<Bird> birds = buildNearbySearchBirdList();
        if (birds.isEmpty()) {
            MessagePopupHelper.showBrief(this, "No birds available yet.");
            return;
        }

        View content = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_nearby_bird_search, null, false);
        EditText etSearch = content.findViewById(R.id.etBirdSearch);
        RecyclerView rvBirdSearch = content.findViewById(R.id.rvBirdSearch);
        TextView tvEmpty = content.findViewById(R.id.tvBirdSearchEmpty);
        ImageButton btnClearBirdSearchSheet = content.findViewById(R.id.btnClearBirdSearchSheet);

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(content);

        dialog.setOnShowListener(d -> {
            View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(android.R.color.transparent);
                BottomSheetBehavior.from(bottomSheet).setState(BottomSheetBehavior.STATE_EXPANDED);
            }

            Window window = dialog.getWindow();
            if (window != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.setNavigationBarContrastEnforced(false);
                }

                WindowInsetsControllerCompat controller =
                        WindowCompat.getInsetsController(window, window.getDecorView());

                window.setNavigationBarColor(
                        ContextCompat.getColor(this, R.color.nav_brown)
                );

                if (controller != null) {
                    controller.setAppearanceLightNavigationBars(false);
                }
            }
        });

        NearbyBirdSearchAdapter searchAdapter = new NearbyBirdSearchAdapter(birds, bird -> {
            dialog.dismiss();
            applySelectedBirdFilter(bird);
        });

        rvBirdSearch.setLayoutManager(new LinearLayoutManager(this));
        rvBirdSearch.setAdapter(searchAdapter);

        String initialQuery = hasSelectedBirdFilter() ? safeSelectedBirdLabel() : "";
        etSearch.setText(initialQuery);
        etSearch.setSelection(etSearch.getText().length());

        searchAdapter.filter(initialQuery);
        tvEmpty.setVisibility(searchAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        btnClearBirdSearchSheet.setVisibility(
                (hasSelectedBirdFilter() || !initialQuery.trim().isEmpty()) ? View.VISIBLE : View.GONE
        );

        btnClearBirdSearchSheet.setOnClickListener(v -> {
            etSearch.setText("");
            clearSelectedBirdFilter();
            searchAdapter.filter("");
            tvEmpty.setVisibility(searchAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
            btnClearBirdSearchSheet.setVisibility(View.GONE);
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s == null ? "" : s.toString();
                searchAdapter.filter(query);
                tvEmpty.setVisibility(searchAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
                btnClearBirdSearchSheet.setVisibility(
                        (hasSelectedBirdFilter() || !query.trim().isEmpty()) ? View.VISIBLE : View.GONE
                );
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        dialog.show();
    }

    private void applySelectedBirdFilter(@NonNull Bird bird) {
        selectedBirdId = safeTrim(bird.getId());
        selectedBirdCommonName = safeTrim(cleanBirdText(bird.getCommonName()));
        selectedBirdScientificName = safeTrim(cleanBirdText(bird.getScientificName()));
        selectedBirdLabel = firstNonBlank(selectedBirdCommonName, selectedBirdScientificName, null, "Unknown bird");

        updateBirdSearchUi();
        renderHeatmaps();
        focusCameraOnSelectedBirdHotspots();
    }

    private void clearSelectedBirdFilter() {
        if (!hasSelectedBirdFilter()) return;

        selectedBirdId = null;
        selectedBirdCommonName = null;
        selectedBirdScientificName = null;
        selectedBirdLabel = null;

        updateBirdSearchUi();
        renderHeatmaps();
    }

    private boolean hasSelectedBirdFilter() {
        return safeTrim(selectedBirdId) != null
                || safeTrim(selectedBirdCommonName) != null
                || safeTrim(selectedBirdScientificName) != null
                || safeTrim(selectedBirdLabel) != null;
    }

    private String safeSelectedBirdLabel() {
        return firstNonBlank(selectedBirdLabel, selectedBirdCommonName, selectedBirdScientificName, "Selected bird");
    }

    private List<Bird> buildNearbySearchBirdList() {
        LinkedHashMap<String, Bird> deduped = new LinkedHashMap<>();
        for (Bird bird : searchableBirds) {
            if (bird == null || bird.getId() == null || bird.getId().trim().isEmpty()) continue;

            String key = bird.getId().trim();
            Bird existing = deduped.get(key);
            if (existing == null) {
                deduped.put(key, bird);
            } else {
                if ((existing.getCommonName() == null || existing.getCommonName().trim().isEmpty())
                        && bird.getCommonName() != null && !bird.getCommonName().trim().isEmpty()) {
                    existing.setCommonName(bird.getCommonName());
                }
                if ((existing.getScientificName() == null || existing.getScientificName().trim().isEmpty())
                        && bird.getScientificName() != null && !bird.getScientificName().trim().isEmpty()) {
                    existing.setScientificName(bird.getScientificName());
                }
            }
        }

        List<Bird> birds = new ArrayList<>(deduped.values());
        birds.sort((left, right) -> safeBirdLabel(left).compareToIgnoreCase(safeBirdLabel(right)));
        return birds;
    }

    private String safeBirdLabel(@Nullable Bird bird) {
        if (bird == null) return "";
        if (bird.getCommonName() != null && !bird.getCommonName().trim().isEmpty()) return bird.getCommonName().trim();
        if (bird.getScientificName() != null && !bird.getScientificName().trim().isEmpty()) return bird.getScientificName().trim();
        return "Unknown Bird";
    }


    /**
     * Runs when the screen returns to the foreground, so it often refreshes UI state or restarts
     * listeners.
     */
    @Override
    protected void onResume() {
        super.onResume();
        isNavigating = false;
    }

    /**
     * Main logic block for this part of the feature.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    @Override
    public void onMapReady(@NonNull GoogleMap map) {
        googleMap = map;
        googleMap.getUiSettings().setZoomControlsEnabled(true);
        googleMap.getUiSettings().setCompassEnabled(true);
        googleMap.getUiSettings().setMyLocationButtonEnabled(true);
        googleMap.setOnCircleClickListener(this);
        googleMap.setOnMarkerClickListener(this);

        LatLng initialCenter = !Double.isNaN(centerLat) ? new LatLng(centerLat, centerLng) : new LatLng(DEFAULT_LAT, DEFAULT_LNG);
        float initialZoom = !Double.isNaN(centerLat) ? NEARBY_ZOOM : DEFAULT_ZOOM;
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialCenter, initialZoom));

        if (trackedBirdNameFromNotification != null && !trackedBirdNameFromNotification.trim().isEmpty()) {
            MessagePopupHelper.showBrief(this,
                    "Showing tracked bird location for " + trackedBirdNameFromNotification);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                googleMap.setMyLocationEnabled(true);
            } catch (SecurityException ignored) {
            }
        }

        googleMap.setOnCameraIdleListener(() -> {
            if (googleMap == null) return;
            CameraPosition cp = googleMap.getCameraPosition();
            currentVisibleBounds = googleMap.getProjection().getVisibleRegion().latLngBounds;
            centerLat = cp.target.latitude;
            centerLng = cp.target.longitude;

            heatmapCameraHandler.removeCallbacks(debouncedHeatmapReloadRunnable);
            heatmapCameraHandler.postDelayed(debouncedHeatmapReloadRunnable, CAMERA_IDLE_DEBOUNCE_MS);
        });

        googleMap.setOnMapLoadedCallback(() -> {
            if (googleMap != null) {
                currentVisibleBounds = googleMap.getProjection().getVisibleRegion().latLngBounds;
                CameraPosition cp = googleMap.getCameraPosition();
                lastAppliedTarget = cp.target;
                lastAppliedZoom = cp.zoom;
                fetchHeatmapData();
                loadForumPins();
            }
        });
    }

    /**
     * Runs shortly after the camera stops moving. Reloading heatmap tiles on every intermediate
     * {@link GoogleMap.OnCameraIdleListener} frame caused visible flicker; debouncing keeps the map stable while panning/zooming.
     */
    private void onDebouncedCameraIdleForHeatmap() {
        if (googleMap == null || isFinishing()) return;
        CameraPosition cp = googleMap.getCameraPosition();
        currentVisibleBounds = googleMap.getProjection().getVisibleRegion().latLngBounds;
        centerLat = cp.target.latitude;
        centerLng = cp.target.longitude;

        boolean shouldRefresh = false;
        if (lastAppliedTarget == null) {
            shouldRefresh = true;
        } else {
            float[] res = new float[1];
            android.location.Location.distanceBetween(
                    lastAppliedTarget.latitude, lastAppliedTarget.longitude,
                    cp.target.latitude, cp.target.longitude, res);
            if (res[0] >= MIN_CAMERA_MOVE_TO_REFRESH_METERS
                    || Math.abs(cp.zoom - lastAppliedZoom) >= MIN_ZOOM_CHANGE_TO_REFRESH) {
                shouldRefresh = true;
            }
        }

        if (shouldRefresh) {
            lastAppliedTarget = cp.target;
            lastAppliedZoom = cp.zoom;
            fetchHeatmapData();
            loadForumPins();
        }
    }

    private void markPostViewedFromHeatmap(String userId, ForumPost post, TextView tvViewCount) {
        if (post == null || post.getId() == null) return;

        if (post.getViewedBy() != null && post.getViewedBy().containsKey(userId)) {
            return;
        }

        if (post.getViewedBy() == null) {
            post.setViewedBy(new HashMap<>());
        }

        post.getViewedBy().put(userId, true);
        firebaseManager.recordForumPostView(post.getId());
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     */
    private void fetchHeatmapData() {
        final int gen = ++fetchGeneration;
        pendingLoads = 4;
        tvMapSubtitle.setText("Updating heatmap...");

        loadUserBirdSightings(gen);
        loadEbirdApiSightings(gen);
    }

    private boolean isHeatmapPinVisible(ForumPost post) {
        if (post == null) return false;
        String status = post.getModerationStatus();
        return (status == null || status.isEmpty() || "visible".equalsIgnoreCase(status))
                && post.isShowLocation()
                && post.getLatitude() != null
                && post.getLongitude() != null;
    }

    private boolean isForumCommentVisible(ForumComment comment) {
        if (comment == null) return false;
        String status = comment.getModerationStatus();
        return status == null
                || status.isEmpty()
                || "visible".equalsIgnoreCase(status)
                || "under_review".equalsIgnoreCase(status);
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private void loadForumPins() {
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("forumThreads").whereEqualTo("showLocation", true).get(Source.CACHE).addOnSuccessListener(snap -> {
            if (snap != null && !snap.isEmpty()) processForumPins(snap);
            fetchForumPinsFromServer();
        }).addOnFailureListener(e -> fetchForumPinsFromServer());
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private void fetchForumPinsFromServer() {
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("forumThreads").whereEqualTo("showLocation", true).get(Source.SERVER).addOnSuccessListener(this::processForumPins).addOnFailureListener(e -> Log.e(TAG, "Error", e));
    }

    /**
     * Main logic block for this part of the feature.
     * This method also reads or writes local device preferences so some state survives app
     * restarts.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private final Map<Integer, BitmapDescriptor> pinCache = new java.util.HashMap<>();
    private BitmapDescriptor dualColorPinCache = null;
    private final Map<String, Marker> forumMarkerMap = new java.util.HashMap<>();

    private void processForumPins(com.google.firebase.firestore.QuerySnapshot snap) {
        new Thread(() -> {
            boolean showGraphic = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_GRAPHIC_CONTENT, false);
            List<ForumPost> visiblePosts = new ArrayList<>();

            for (DocumentSnapshot doc : snap.getDocuments()) {
                ForumPost p = doc.toObject(ForumPost.class);
                if (p == null) continue;
                p.setId(doc.getId());

                if (!isHeatmapPinVisible(p)) continue;
                if (showFollowingPinsOnly) {
                    String postUserId = p.getUserId();
                    if (postUserId == null || !followedUserIds.contains(postUserId)) continue;
                }

                boolean inBounds = currentVisibleBounds == null
                        || currentVisibleBounds.contains(new LatLng(p.getLatitude(), p.getLongitude()));

                if (inBounds && (showGraphic || !p.isHunted())) {
                    visiblePosts.add(p);
                }
            }

            runOnUiThread(() -> {
                if (isFinishing()) return;
                updateForumMarkers(visiblePosts);
            });
        }).start();
    }

    private void updateForumMarkers(List<ForumPost> visiblePosts) {
        Set<String> newPostIds = new HashSet<>();
        for (ForumPost p : visiblePosts) newPostIds.add(p.getId());

        // Remove markers no longer visible
        Iterator<Map.Entry<String, Marker>> it = forumMarkerMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Marker> entry = it.next();
            if (!newPostIds.contains(entry.getKey())) {
                entry.getValue().remove();
                it.remove();
            }
        }

        // Add or update markers
        for (ForumPost p : visiblePosts) {
            if (!forumMarkerMap.containsKey(p.getId())) {
                addPinToMap(p);
            }
        }

        // Handle pending post open if requested
        if (!pendingOpenPostHandled && pendingOpenPostId != null) {
            for (ForumPost p : visiblePosts) {
                if (pendingOpenPostId.equals(p.getId())) {
                    maybeOpenRequestedPost(p);
                    break;
                }
            }
        }
    }

    /**
     * Main logic block for this part of the feature.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     * Bitmap/rendering work happens here, so this block is shaping the final card/image output
     * rather than just text data.
     */
    private void addPinToMap(ForumPost p) {
        LatLng pos = new LatLng(p.getLatitude(), p.getLongitude());
        StringBuilder status = new StringBuilder();
        if (p.isSpotted()) status.append("Spotted ");
        if (p.isHunted()) status.append("Hunted ");
        String title = p.getUsername() + (status.length() > 0 ? " (" + status.toString().trim() + ")" : "");

        BitmapDescriptor icon;
        if (p.isSpotted() && p.isHunted()) {
            icon = getCachedDualColorPin();
        } else if (p.isHunted()) {
            icon = getCachedColoredPin(Color.parseColor("#F44336")); // red
        } else if (p.isSpotted()) {
            icon = getCachedColoredPin(Color.parseColor("#4CAF50")); // green
        } else {
            icon = getCachedColoredPin(Color.parseColor("#8A6240")); // brown
        }

        Marker m = googleMap.addMarker(
                new MarkerOptions()
                        .position(pos)
                        .title(title)
                        .snippet(p.getMessage())
                        .icon(icon)
        );
        if (m != null) {
            m.setTag(p);
            forumMarkerMap.put(p.getId(), m);
        }
    }

    private BitmapDescriptor getCachedColoredPin(int color) {
        if (!pinCache.containsKey(color)) {
            pinCache.put(color, createColoredPin(color));
        }
        return pinCache.get(color);
    }

    private BitmapDescriptor getCachedDualColorPin() {
        if (dualColorPinCache == null) {
            dualColorPinCache = createDualColorPin();
        }
        return dualColorPinCache;
    }

    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     * Bitmap/rendering work happens here, so this block is shaping the final card/image output
     * rather than just text data.
     */
    private BitmapDescriptor createColoredPin(int color) {
        int size = 80;
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint p = new Paint();
        p.setColor(color);
        p.setAntiAlias(true);
        c.drawCircle(size / 2f, size / 3f, size / 3f, p);
        c.drawRect(size / 2f - 4, size / 2f, size / 2f + 4, size * 0.9f, p);
        return BitmapDescriptorFactory.fromBitmap(bm);
    }

    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     * Bitmap/rendering work happens here, so this block is shaping the final card/image output
     * rather than just text data.
     */
    private BitmapDescriptor createDualColorPin() {
        int size = 80;
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint p = new Paint();
        p.setAntiAlias(true);

        p.setColor(Color.parseColor("#4CAF50")); // green = spotted
        c.drawArc(size / 6f, 0, size * 5 / 6f, size * 2 / 3f, 90, 180, true, p);

        p.setColor(Color.parseColor("#F44336")); // red = hunted
        c.drawArc(size / 6f, 0, size * 5 / 6f, size * 2 / 3f, 270, 180, true, p);

        p.setColor(Color.parseColor("#4CAF50"));
        c.drawRect(size / 2f - 4, size / 2f, size / 2f, size * 0.9f, p);

        p.setColor(Color.parseColor("#F44336"));
        c.drawRect(size / 2f, size / 2f, size / 2f + 4, size * 0.9f, p);

        return BitmapDescriptorFactory.fromBitmap(bm);
    }

    private void clearForumMarkers() {
        for (Marker m : forumMarkerMap.values()) m.remove();
        forumMarkerMap.clear();
    }

    private void maybeOpenRequestedPost(@Nullable ForumPost post) {
        if (pendingOpenPostHandled || post == null || googleMap == null) return;
        if (post.getLatitude() == null || post.getLongitude() == null) return;

        pendingOpenPostHandled = true;

        LatLng target = new LatLng(post.getLatitude(), post.getLongitude());
        googleMap.animateCamera(
                CameraUpdateFactory.newLatLngZoom(target, POST_FOCUS_ZOOM),
                new GoogleMap.CancelableCallback() {
                    @Override
                    public void onFinish() {
                        showPostInBottomSheet(post);
                    }

                    @Override
                    public void onCancel() {
                        showPostInBottomSheet(post);
                    }
                }
        );
    }

    @Override
    public boolean onMarkerClick(@NonNull Marker m) {
        Object tag = m.getTag();
        if (tag instanceof ForumPost) {
            showPostInBottomSheet((ForumPost) tag);
            return true;
        }
        return false;
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    private void showPostInBottomSheet(ForumPost p) {
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        activePost = p;
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.bottom_sheet_post_view, null);
        dialog.setContentView(view);
        FirebaseUser user = mAuth.getCurrentUser();

        // Count a heatmap view when the user presses the post's pin and opens the post bottom sheet.
        // Because this writes to the same viewedBy map used by the forum post screen, the same user
        // will only count once for the same post across both places.
        TextView tvViewCount = view.findViewById(R.id.tvViewCount);
        if (user != null) {
            markPostViewedFromHeatmap(user.getUid(), p, tvViewCount);
        }

        // Keep the existing owner notification reset behavior as-is.
        if (user != null && user.getUid().equals(p.getUserId()) && p.isNotificationSent()) {
            p.setNotificationSent(false);
        }
        View content = view.findViewById(R.id.postContent);
        ((TextView) content.findViewById(R.id.tvPostUsername)).setText(p.getUsername());
        ((TextView) content.findViewById(R.id.tvPostMessage)).setText(p.isEdited() ? p.getMessage() + " (edited)" : p.getMessage());
        ((TextView) content.findViewById(R.id.tvLikeCount)).setText(String.valueOf(p.getLikeCount()));
        ((TextView) content.findViewById(R.id.tvCommentCount)).setText(String.valueOf(p.getCommentCount()));
        ((TextView) content.findViewById(R.id.tvViewCount)).setText(p.getViewCount() + " views");

        if (p.getTimestamp() != null)
            ((TextView) content.findViewById(R.id.tvPostTimestamp)).setText(DateUtils.getRelativeTimeSpanString(p.getTimestamp().toDate().getTime()));
        // Load the image asynchronously so the UI can show remote/local media without blocking the main thread.
        Glide.with(this).load(p.getUserProfilePictureUrl()).placeholder(R.drawable.ic_profile).into((ImageView) content.findViewById(R.id.ivPostUserProfilePicture));

        ImageView ivBird = content.findViewById(R.id.ivPostBirdImage);
        if (p.getBirdImageUrl() != null && !p.getBirdImageUrl().isEmpty()) {
            content.findViewById(R.id.cvPostImage).setVisibility(View.VISIBLE);
            Glide.with(this).load(p.getBirdImageUrl()).into(ivBird);
        } else content.findViewById(R.id.cvPostImage).setVisibility(View.GONE);

        ImageView ivLike = content.findViewById(R.id.ivLikeIcon);
        ivLike.setImageResource((user != null && p.getLikedBy() != null && p.getLikedBy().containsKey(user.getUid())) ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);

        // Attach the user interaction that should run when this control is tapped.
        content.findViewById(R.id.btnLike).setOnClickListener(v -> {
            if (user == null || postLikeInFlight.contains(p.getId())) return;
            postLikeInFlight.add(p.getId());

            String uid = user.getUid();
            boolean liked = p.getLikedBy() != null && p.getLikedBy().containsKey(uid);
            int count = p.getLikeCount();
            if (liked) {
                p.setLikeCount(Math.max(0, count - 1));
                p.getLikedBy().remove(uid);
                ivLike.setImageResource(R.drawable.ic_favorite_border);
            } else {
                p.setLikeCount(count + 1);
                if (p.getLikedBy() == null) p.setLikedBy(new HashMap<>());
                p.getLikedBy().put(uid, true);
                ivLike.setImageResource(R.drawable.ic_favorite);
            }
            ((TextView) content.findViewById(R.id.tvLikeCount)).setText(String.valueOf(p.getLikeCount()));

            firebaseManager.toggleForumPostLike(p.getId(), !liked, new FirebaseManager.ActionListener() {
                @Override
                public void onSuccess() {
                    postLikeInFlight.remove(p.getId());
                }

                @Override
                public void onFailure(String errorMessage) {
                    postLikeInFlight.remove(p.getId());
                    p.setLikeCount(count);
                    if (liked) p.getLikedBy().put(uid, true);
                    else p.getLikedBy().remove(uid);
                    ((TextView) content.findViewById(R.id.tvLikeCount)).setText(String.valueOf(count));
                    ivLike.setImageResource(liked ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
                    if (errorMessage != null && !errorMessage.trim().isEmpty()) {
                        MessagePopupHelper.showBrief(NearbyHeatmapActivity.this, errorMessage);
                    }
                }
            });
        });

        content.setOnClickListener(v -> {
            if (isNavigating) return;
            isNavigating = true;
            startActivity(new Intent(this, PostDetailActivity.class).putExtra(PostDetailActivity.EXTRA_POST_ID, p.getId()));
            dialog.dismiss();
        });
        primeSavedPostState(p);
        content.findViewById(R.id.btnPostOptions).setOnClickListener(v -> showPostOptions(p, v, dialog));

        RecyclerView rv = view.findViewById(R.id.rvComments);
        popupCommentAdapter = new ForumCommentAdapter(this);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        rv.setLayoutManager(lm);
        rv.setAdapter(popupCommentAdapter);
        popupCommentList.clear();
        lastPopupCommentVisible = null;
        isLastPopupCommentsPage = false;
        fetchPopupComments(p.getId(), popupCommentAdapter);
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                if (dy > 0 && !isFetchingPopupComments && !isLastPopupCommentsPage) {
                    if ((lm.getChildCount() + lm.findFirstVisibleItemPosition()) >= lm.getItemCount())
                        fetchPopupComments(p.getId(), popupCommentAdapter);
                }
            }
        });

        currentPopupEditText = view.findViewById(R.id.etComment);
        ImageView ivCurrentUserPfp = view.findViewById(R.id.ivCurrentUserPfp);
        if (user != null) {
            db.collection("users").document(user.getUid()).get()
                    .addOnSuccessListener(doc -> {
                        if (isFinishing() || isDestroyed()) return;
                        String profilePictureUrl = doc != null ? doc.getString("profilePictureUrl") : null;
                        Glide.with(NearbyHeatmapActivity.this)
                                .load(profilePictureUrl)
                                .placeholder(R.drawable.ic_profile)
                                .into(ivCurrentUserPfp);
                    })
                    .addOnFailureListener(e -> Glide.with(NearbyHeatmapActivity.this)
                            .load((Object) null)
                            .placeholder(R.drawable.ic_profile)
                            .into(ivCurrentUserPfp));
        } else {
            Glide.with(this).load((Object) null).placeholder(R.drawable.ic_profile).into(ivCurrentUserPfp);
        }

        View sendCommentButton = view.findViewById(R.id.btnSendComment);
        sendCommentButton.setOnClickListener(v -> {
            String text = currentPopupEditText.getText().toString().trim();
            if (text.isEmpty() || user == null)
                return;
            if (!ContentFilter.isSafe(this, text, replyingToComment != null ? "Reply" : "Comment")) {
                firebaseManager.logFilteredContentAttempt(
                        replyingToComment != null ? "forum_reply_create_client_block" : "forum_comment_create_client_block",
                        replyingToComment != null ? "reply" : "comment",
                        text,
                        p.getId(),
                        null
                );
                return;
            }
            if (ForumSubmissionCooldownHelper.isCoolingDown(this)) {
                MessagePopupHelper.showBrief(this, ForumSubmissionCooldownHelper.buildCooldownMessage(this));
                return;
            }
            sendCommentButton.setEnabled(false);

            String commentId = db.collection("forumThreads").document(p.getId()).collection("comments").document().getId();
            String parentCommentId = replyingToComment != null ? replyingToComment.getId() : "";

            firebaseManager.createForumComment(p.getId(), commentId, text, parentCommentId, new FirebaseManager.ForumWriteListener() {
                @Override
                public void onSuccess() {
                    if (isFinishing() || isDestroyed()) return;
                    ForumSubmissionCooldownHelper.markSubmissionSuccess(NearbyHeatmapActivity.this);
                    currentPopupEditText.setText("");
                    currentPopupEditText.setHint("Write a comment...");
                    replyingToComment = null;
                    sendCommentButton.setEnabled(true);
                    refreshPopupComments();
                }

                @Override
                public void onFailure(String errorMessage) {
                    if (isFinishing() || isDestroyed()) return;
                    sendCommentButton.setEnabled(true);
                    MessagePopupHelper.showBrief(NearbyHeatmapActivity.this, errorMessage);
                }
            });
        });

        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            SystemBarHelper.applyDialogNavBar(this, window);
        }

        View bs = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bs != null) {
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bs);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
        }
    }

    private void refreshPopupComments() {
        if (activePost == null) return;
        popupCommentList.clear();
        lastPopupCommentVisible = null;
        isLastPopupCommentsPage = false;
        fetchPopupComments(activePost.getId(), popupCommentAdapter);
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    private void fetchPopupComments(String postId, ForumCommentAdapter adapter) {
        if (isFetchingPopupComments || isLastPopupCommentsPage) return;
        isFetchingPopupComments = true;
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        Query q = db.collection("forumThreads").document(postId).collection("comments").orderBy("timestamp", Query.Direction.ASCENDING).limit(POPUP_COMMENTS_PAGE_SIZE);
        if (lastPopupCommentVisible != null) q = q.startAfter(lastPopupCommentVisible);
        q.get().addOnSuccessListener(val -> {
            if (val != null && !val.isEmpty()) {
                lastPopupCommentVisible = val.getDocuments().get(val.size() - 1);
                for (DocumentSnapshot d : val.getDocuments()) {
                    ForumComment c = d.toObject(ForumComment.class);
                    if (c != null) {
                        c.setId(d.getId());
                        if (isForumCommentVisible(c)) popupCommentList.add(c);
                    }
                }
                adapter.setComments(new ArrayList<>(popupCommentList));
                if (val.size() < POPUP_COMMENTS_PAGE_SIZE) isLastPopupCommentsPage = true;
            } else isLastPopupCommentsPage = true;
            isFetchingPopupComments = false;
        }).addOnFailureListener(e -> isFetchingPopupComments = false);
    }

    private void primeSavedPostState(@Nullable ForumPost post) {
        if (post == null || post.getId() == null) return;

        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            savedPostStateCache.put(post.getId(), false);
            return;
        }

        if (savedPostStateCache.containsKey(post.getId())) return;
        if (!savedPostStateInFlight.add(post.getId())) return;

        firebaseManager.isForumPostSaved(post.getId(), task -> {
            savedPostStateInFlight.remove(post.getId());
            if (isFinishing() || isDestroyed()) return;

            boolean isSaved = task.isSuccessful()
                    && task.getResult() != null
                    && task.getResult();

            savedPostStateCache.put(post.getId(), isSaved);
        });
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void showPostOptions(ForumPost p, View v, BottomSheetDialog dialog) {
        showInstantPostOptions(p, v, dialog);
    }

    private void showInstantPostOptions(ForumPost p, View v, BottomSheetDialog dialog) {
        PopupMenu popup = new PopupMenu(this, v);
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null && p.getUserId().equals(user.getUid())) popup.getMenu().add("Delete");
        boolean isSaved = Boolean.TRUE.equals(savedPostStateCache.get(p.getId()));
        popup.getMenu().add(isSaved ? "Unsave Post" : "Save Post");
        if (user != null && !p.getUserId().equals(user.getUid())) popup.getMenu().add("Report");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getTitle().equals("Delete")) showDeleteConfirmation(p, dialog);
            else if (item.getTitle().equals("Save Post")) savePostForLater(p);
            else if (item.getTitle().equals("Unsave Post")) unsavePost(p);
            else if (item.getTitle().equals("Report")) showReportDialog("post", p.getId(), p.getId());
            return true;
        });
        popup.show();
    }

    private void savePostForLater(ForumPost p) {
        firebaseManager.saveForumPost(p.getId(), new FirebaseManager.ForumWriteListener() {
            @Override
            public void onSuccess() {
                if (isFinishing() || isDestroyed()) return;
                savedPostStateCache.put(p.getId(), true);
                MessagePopupHelper.showBrief(NearbyHeatmapActivity.this, "Post saved");
            }

            @Override
            public void onFailure(String errorMessage) {
                if (isFinishing() || isDestroyed()) return;
                MessagePopupHelper.showBrief(NearbyHeatmapActivity.this, errorMessage != null ? errorMessage : "Failed to save post.");
            }
        });
    }

    private void unsavePost(ForumPost p) {
        firebaseManager.unsaveForumPost(p.getId(), new FirebaseManager.ForumWriteListener() {
            @Override
            public void onSuccess() {
                if (isFinishing() || isDestroyed()) return;
                savedPostStateCache.put(p.getId(), false);
                MessagePopupHelper.showBrief(NearbyHeatmapActivity.this, "Post unsaved");
            }

            @Override
            public void onFailure(String errorMessage) {
                if (isFinishing() || isDestroyed()) return;
                MessagePopupHelper.showBrief(NearbyHeatmapActivity.this, errorMessage != null ? errorMessage : "Failed to unsave post.");
            }
        });
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void showDeleteConfirmation(ForumPost p, BottomSheetDialog dialog) {
        new AlertDialog.Builder(this).setTitle("Delete Post").setMessage("Are you sure?").setPositiveButton("Delete", (d, w) -> archiveAndDeletePost(p, dialog)).setNegativeButton("Cancel", null).show();
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void archiveAndDeletePost(ForumPost post, BottomSheetDialog dialog) {
        firebaseManager.deleteForumPost(post.getId(), task -> {
            if (isFinishing() || isDestroyed()) return;
            if (task.isSuccessful()) {
                if (dialog != null) dialog.dismiss();
                loadForumPins();
            } else {
                String error = task.getException() != null && task.getException().getMessage() != null
                        ? task.getException().getMessage()
                        : "Failed to delete post.";
                MessagePopupHelper.showBrief(this, error);
            }
        });
    }

    /**
     * Central handler that reacts to an event/input and decides what the next app action should
     * be.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void handleCommentsArchiveAndDeletion(String uid, ForumPost post, BottomSheetDialog dialog) {
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("forumThreads").document(post.getId()).collection("comments").get().addOnSuccessListener(snap -> {
            WriteBatch b = db.batch();
            for (DocumentSnapshot d : snap) {
                Map<String, Object> c = new HashMap<>();
                c.put("type", "comment_archived_with_post");
                c.put("originalId", d.getId());
                c.put("postId", post.getId());
                c.put("data", d.getData());
                c.put("deletedBy", uid);
                c.put("deletedAt", FieldValue.serverTimestamp());
                // Persist the new state so the action is saved outside the current screen.
                b.set(db.collection("deletedforum_backlog").document(), c);
                b.delete(d.getReference());
            }
            Map<String, Object> pb = new HashMap<>();
            pb.put("type", "post");
            pb.put("originalId", post.getId());
            pb.put("data", post);
            pb.put("deletedBy", uid);
            pb.put("deletedAt", FieldValue.serverTimestamp());
            b.set(db.collection("deletedforum_backlog").document(), pb);
            b.delete(db.collection("forumThreads").document(post.getId()));
            b.commit().addOnSuccessListener(v -> {
                if (dialog != null) dialog.dismiss();
                loadForumPins();
            });
        }).addOnFailureListener(e -> savePostToBacklogAndFirestore(uid, post, dialog));
    }

    private interface OnImageArchivedListener {
        void onSuccess(String url);

        void onFailure(Exception e);
    }

    /**
     * Main logic block for this part of the feature.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void moveImageToArchive(String uid, String pid, String url, OnImageArchivedListener l) {
        FirebaseStorage s = FirebaseStorage.getInstance();
        try {
            StorageReference old = s.getReferenceFromUrl(url);
            StorageReference next = s.getReference().child("archive/forum_post_images/" + uid + "/" + pid + "_" + old.getName());
            // Persist the new state so the action is saved outside the current screen.
            old.getBytes(10 * 1024 * 1024).addOnSuccessListener(bytes -> next.putBytes(bytes).addOnSuccessListener(ts -> next.getDownloadUrl().addOnSuccessListener(uri -> old.delete().addOnCompleteListener(t -> l.onSuccess(uri.toString()))).addOnFailureListener(l::onFailure)).addOnFailureListener(l::onFailure)).addOnFailureListener(l::onFailure);
        } catch (Exception e) {
            l.onFailure(e);
        }
    }

    /**
     * Builds data from the current screen/object state and writes it out to storage, Firebase, or
     * another service.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void savePostToBacklogAndFirestore(String uid, ForumPost post, BottomSheetDialog dialog) {
        WriteBatch b = db.batch();
        Map<String, Object> m = new HashMap<>();
        m.put("type", "post");
        m.put("originalId", post.getId());
        m.put("data", post);
        m.put("deletedBy", uid);
        m.put("deletedAt", FieldValue.serverTimestamp());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        b.set(db.collection("deletedforum_backlog").document(), m);
        b.delete(db.collection("forumThreads").document(post.getId()));
        b.commit().addOnSuccessListener(v -> {
            if (dialog != null) dialog.dismiss();
            loadForumPins();
        });
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void showReportDialog(String type, String id, String threadId) {
        String[] rs = {"Inappropriate Language", "Spam", "Harassment", "Other"};
        new AlertDialog.Builder(this).setTitle("Report").setItems(rs, (d, w) -> {
            if (rs[w].equals("Other"))
                showOtherReportDialog(reason -> submitReport(type, id, threadId, "heatmap", reason));
            else submitReport(type, id, threadId, "heatmap", rs[w]);
        }).show();
    }

    /**
     * Main logic block for this part of the feature.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void submitReport(String type, String id, String threadId, String sourceContext, String r) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        // Give the user immediate feedback about the result of this action.
        firebaseManager.addReport(new Report(type, id, user.getUid(), r, sourceContext, threadId), t -> {
            if (t.isSuccessful()) MessagePopupHelper.showBrief(this, "Reported");
        });
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void showOtherReportDialog(OnReasonEnteredListener l) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Reason");
        final EditText i = new EditText(this);
        i.setHint("Specify...");
        i.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        FrameLayout c = new FrameLayout(this);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-1, -2);
        p.leftMargin = p.rightMargin = 40;
        i.setLayoutParams(p);
        c.addView(i);
        b.setView(c);
        b.setPositiveButton("Submit", (d, w) -> {
            String r = i.getText().toString().trim();
            if (!r.isEmpty()) l.onReasonEntered(r);
        });
        b.setNegativeButton("Cancel", null);
        b.show();
    }

    private interface OnReasonEnteredListener {
        void onReasonEntered(String r);
    }

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    @Override
    public void onCommentLikeClick(ForumComment c) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null || activePost == null || commentLikeInFlight.contains(c.getId())) return;
        commentLikeInFlight.add(c.getId());

        String uid = user.getUid();
        if (c.getLikedBy() == null) c.setLikedBy(new HashMap<>());
        boolean liked = c.getLikedBy().containsKey(uid);
        int count = c.getLikeCount();
        if (liked) {
            c.setLikeCount(Math.max(0, count - 1));
            c.getLikedBy().remove(uid);
        } else {
            c.setLikeCount(count + 1);
            c.getLikedBy().put(uid, true);
        }
        if (popupCommentAdapter != null) popupCommentAdapter.notifyDataSetChanged();

        firebaseManager.toggleForumCommentLike(activePost.getId(), c.getId(), !liked, new FirebaseManager.ActionListener() {
            @Override
            public void onSuccess() {
                commentLikeInFlight.remove(c.getId());
            }

            @Override
            public void onFailure(String errorMessage) {
                commentLikeInFlight.remove(c.getId());
                c.setLikeCount(count);
                if (liked) c.getLikedBy().put(uid, true);
                else c.getLikedBy().remove(uid);
                if (popupCommentAdapter != null) popupCommentAdapter.notifyDataSetChanged();
                if (errorMessage != null && !errorMessage.trim().isEmpty()) {
                    MessagePopupHelper.showBrief(NearbyHeatmapActivity.this, errorMessage);
                }
            }
        });
    }

    @Override
    public void onCommentReplyClick(ForumComment c) {
        replyingToComment = c;
        if (currentPopupEditText != null) {
            currentPopupEditText.setHint("Replying to " + c.getUsername() + "...");
            currentPopupEditText.requestFocus();
        }
    }

    @Override
    public void onCommentOptionsClick(ForumComment c, View v) {
        PopupMenu p = new PopupMenu(this, v);
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null && c.getUserId().equals(user.getUid())) p.getMenu().add("Delete");
        if (user != null && !c.getUserId().equals(user.getUid())) p.getMenu().add("Report");
        p.setOnMenuItemClickListener(item -> {
            if (item.getTitle().equals("Delete")) showCommentDeleteConfirmation(c);
            else if (item.getTitle().equals("Report")) showReportDialog(c.getParentCommentId() != null ? "reply" : "comment", c.getId(), activePost != null ? activePost.getId() : null);
            return true;
        });
        p.show();
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     */
    private void showCommentDeleteConfirmation(ForumComment c) {
        new AlertDialog.Builder(this).setTitle("Delete Comment").setMessage("Are you sure?").setPositiveButton("Delete", (d, w) -> deleteCommentAndReplies(c)).setNegativeButton("Cancel", null).show();
    }

    /**
     * Removes data/listeners/items and then updates local state so the UI matches the deletion.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void deleteCommentAndReplies(ForumComment c) {
        if (activePost == null) return;
        firebaseManager.deleteForumComment(activePost.getId(), c.getId(), task -> {
            if (isFinishing() || isDestroyed()) return;
            if (task.isSuccessful()) {
                refreshPopupComments();
            } else {
                String error = task.getException() != null && task.getException().getMessage() != null
                        ? task.getException().getMessage()
                        : "Failed to delete comment.";
                MessagePopupHelper.showBrief(this, error);
            }
        });
    }

    /**
     * Main logic block for this part of the feature.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * Part of this method writes changes back to Firestore/storage, so this is where app actions
     * become permanent.
     */
    private void backlogByUserId(WriteBatch b, String uid, String type, String oid, Object data) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("originalId", oid);
        m.put("data", data);
        m.put("deletedBy", uid);
        m.put("deletedAt", FieldValue.serverTimestamp());
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        b.set(db.collection("deletedforum_backlog").document(), m);
    }

    @Override
    public void onUserClick(String uid) {
        if (isNavigating) return;
        isNavigating = true;
        startActivity(new Intent(this, UserSocialProfileActivity.class).putExtra(UserSocialProfileActivity.EXTRA_USER_ID, uid));
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     */
    private void loadUserBirdSightings(int gen) {
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("userBirdSightings").get(Source.CACHE).addOnSuccessListener(snap -> {
            if (snap != null && !snap.isEmpty()) processSightings(snap, true, gen);
            else onCollectionFinished(gen);
            fetchUserBirdSightingsFromServer(gen);
        }).addOnFailureListener(e -> {
            onCollectionFinished(gen);
            fetchUserBirdSightingsFromServer(gen);
        });
    }

    private void fetchUserBirdSightingsFromServer(int gen) {
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("userBirdSightings").get(Source.SERVER).addOnSuccessListener(snap -> processSightings(snap, true, gen)).addOnFailureListener(e -> onCollectionFinished(gen));
    }

    private void loadEbirdApiSightings(int gen) {
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("eBirdApiSightings").get(Source.CACHE).addOnSuccessListener(snap -> {
            if (snap != null && !snap.isEmpty()) processSightings(snap, false, gen);
            else onCollectionFinished(gen);
            fetchEbirdApiSightingsFromServer(gen);
        }).addOnFailureListener(e -> {
            onCollectionFinished(gen);
            fetchEbirdApiSightingsFromServer(gen);
        });
    }

    private void fetchEbirdApiSightingsFromServer(int gen) {
        // Set up or query the Firebase layer that supplies/stores this feature's data.
        db.collection("eBirdApiSightings").get(Source.SERVER).addOnSuccessListener(snap -> processSightings(snap, false, gen)).addOnFailureListener(e -> onCollectionFinished(gen));
    }

    /**
     * Main logic block for this part of the feature.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private void processSightings(com.google.firebase.firestore.QuerySnapshot snap, boolean user, int gen) {
        if (fetchGeneration != gen) return;

        // Run processing in background to prevent UI stutter
        new Thread(() -> {
            List<WeightedLatLng> hl = new ArrayList<>();
            List<HotspotSighting> hsl = new ArrayList<>();

            for (DocumentSnapshot d : snap.getDocuments()) {
                if (fetchGeneration != gen) return;

                Boolean suspicious = d.getBoolean("suspicious");
                if (suspicious != null && suspicious) continue;

                Double lat = getAnyDouble(d, "location.latitude", "lastSeenLatitudeGeorgia", "latitude", "lat");
                Double lng = getAnyDouble(d, "location.longitude", "lastSeenLongitudeGeorgia", "longitude", "lng");

                if (lat == null || lng == null || shouldBeFiltered(lat, lng, getAnyTimeMillis(d, user ? "timestamp" : "observationDate")))
                    continue;

                hl.add(new WeightedLatLng(new LatLng(lat, lng), user ? 1.8 : 1.0));
                hsl.add(buildHotspotSighting(d, lat, lng, user));
            }

            runOnUiThread(() -> {
                if (fetchGeneration != gen) return;
                List<WeightedLatLng> targetHl = user ? userHeatPoints : eBirdHeatPoints;
                List<HotspotSighting> targetHsl = user ? userHotspotSightings : eBirdHotspotSightings;

                targetHl.clear();
                targetHl.addAll(hl);
                targetHsl.clear();
                targetHsl.addAll(hsl);

                rebuildHotspotBuckets(gen);
            });
        }).start();
    }

    private void onCollectionFinished(int gen) {
        if (fetchGeneration != gen) return;
        pendingLoads--;
        if (pendingLoads <= 0) renderHeatmaps();
    }

    /**
     * Main logic block for this part of the feature.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private HotspotSighting buildHotspotSighting(DocumentSnapshot d, double lat, double lng, boolean user) {
        String sightingId = d.getId();
        String birdId = getAnyString(d, "birdId", "speciesCode", "speciesCodeClean", "species_code");
        String userBirdId = getAnyString(d, "userBirdId", "userBirdRefId");
        String commonName = cleanBirdText(getAnyString(d, "commonName", "comName", "birdName"));
        String scientificName = cleanBirdText(getAnyString(d, "scientificName", "sciName"));
        String displayName = firstNonBlank(commonName, scientificName, cleanBirdText(getAnyString(d, "species", "birdId", "speciesCode")), "Unknown bird");
        return new HotspotSighting(sightingId, lat, lng, birdId, userBirdId, commonName, scientificName, displayName, user);
    }

    /**
     * Main logic block for this part of the feature.
     */
    private void renderHeatmaps() {
        if (googleMap == null) return;

        List<WeightedLatLng> displayEBirdHeatPoints = buildDisplayHeatPoints(false);
        List<WeightedLatLng> displayUserUnverifiedHeatPoints = buildUserHeatPointsForStatus(HotspotVerificationState.UNVERIFIED);
        List<WeightedLatLng> displayUserMixedHeatPoints = buildUserHeatPointsForStatus(HotspotVerificationState.MIXED);
        List<WeightedLatLng> displayUserVerifiedHeatPoints = buildUserHeatPointsForStatus(HotspotVerificationState.VERIFIED);

        TileOverlay previousEBirdOverlay = eBirdOverlay;
        TileOverlay previousUserUnverifiedOverlay = userUnverifiedOverlay;
        TileOverlay previousUserMixedOverlay = userMixedOverlay;
        TileOverlay previousUserVerifiedOverlay = userVerifiedOverlay;

        eBirdOverlay = buildHeatOverlay(displayEBirdHeatPoints, EBIRD_GRADIENT, 45, 0.65, 1f);
        userUnverifiedOverlay = buildHeatOverlay(displayUserUnverifiedHeatPoints, USER_UNVERIFIED_GRADIENT, 45, 0.70, 2f);
        userMixedOverlay = buildHeatOverlay(displayUserMixedHeatPoints, USER_MIXED_GRADIENT, 45, 0.70, 2.1f);
        userVerifiedOverlay = buildHeatOverlay(displayUserVerifiedHeatPoints, USER_VERIFIED_GRADIENT, 45, 0.72, 2.2f);

        removeOverlayIfPresent(previousEBirdOverlay);
        removeOverlayIfPresent(previousUserUnverifiedOverlay);
        removeOverlayIfPresent(previousUserMixedOverlay);
        removeOverlayIfPresent(previousUserVerifiedOverlay);

        clearHotspotCircles();
        renderHotspotCircles();

        int userUnverifiedHotspots = countUserHotspotsByState(HotspotVerificationState.UNVERIFIED);
        int userMixedHotspots = countUserHotspotsByState(HotspotVerificationState.MIXED);
        int userVerifiedHotspots = countUserHotspotsByState(HotspotVerificationState.VERIFIED);

        if (displayUserUnverifiedHeatPoints.isEmpty()
                && displayUserMixedHeatPoints.isEmpty()
                && displayUserVerifiedHeatPoints.isEmpty()
                && displayEBirdHeatPoints.isEmpty()) {
            if (hasSelectedBirdFilter()) {
                tvMapSubtitle.setText("No nearby sightings for " + safeSelectedBirdLabel() + ".");
            } else {
                tvMapSubtitle.setText("No recent sightings found.");
            }
        } else if (hasSelectedBirdFilter()) {
            tvMapSubtitle.setText(
                    safeSelectedBirdLabel()
                            + ": "
                            + userUnverifiedHotspots
                            + " unverified, "
                            + userMixedHotspots
                            + " mixed, "
                            + userVerifiedHotspots
                            + " verified user hotspots, "
                            + displayEBirdHeatPoints.size()
                            + " verified nearby"
            );
        } else {
            tvMapSubtitle.setText(
                    "Heatmap: "
                            + userUnverifiedHotspots
                            + " unverified, "
                            + userMixedHotspots
                            + " mixed, "
                            + userVerifiedHotspots
                            + " verified user hotspots, "
                            + displayEBirdHeatPoints.size()
                            + " verified"
            );
        }

        if (!hasSelectedBirdFilter()) {
            maybeOpenTrackedBirdHotspot();
        }
    }

    @Nullable
    private TileOverlay buildHeatOverlay(@NonNull List<WeightedLatLng> points,
                                         @NonNull Gradient gradient,
                                         int radius,
                                         double opacity,
                                         float zIndex) {
        if (googleMap == null || points.isEmpty()) return null;

        HeatmapTileProvider provider = new HeatmapTileProvider.Builder()
                .weightedData(points)
                .radius(radius)
                .opacity(opacity)
                .gradient(gradient)
                .build();

        return googleMap.addTileOverlay(
                new TileOverlayOptions()
                        .tileProvider(provider)
                        .zIndex(zIndex)
        );
    }

    private void removeOverlayIfPresent(@Nullable TileOverlay overlay) {
        if (overlay != null) {
            overlay.remove();
        }
    }


    private void maybeOpenTrackedBirdHotspot() {
        if (trackedBirdNotificationHandled) return;

        String targetSightingId = safeTrim(trackedSightingIdFromNotification);
        String targetBirdId = safeTrim(trackedBirdIdFromNotification);
        String targetBirdName = safeTrim(trackedBirdNameFromNotification);

        // 1. Exact match first: find the hotspot bucket that contains the exact sighting doc ID
        if (targetSightingId != null) {
            for (HotspotBucket bucket : hotspotBuckets.values()) {
                if (bucket.sightingIds.contains(targetSightingId)) {
                    trackedBirdNotificationHandled = true;
                    showBirdListBottomSheet(bucket);
                    return;
                }
            }
        }

        // 2. Fallback: if exact sighting is not found in the current loaded map data,
        // fall back to the nearest hotspot containing that bird.
        if (targetBirdId == null && targetBirdName == null) {
            return;
        }

        HotspotBucket bestBucket = null;
        float bestDistanceMeters = Float.MAX_VALUE;

        for (HotspotBucket bucket : hotspotBuckets.values()) {
            if (!bucketContainsTrackedBird(bucket, targetBirdId, targetBirdName)) {
                continue;
            }

            float distance = distanceMeters(
                    centerLat,
                    centerLng,
                    bucket.getCenterLat(),
                    bucket.getCenterLng()
            );

            if (bestBucket == null || distance < bestDistanceMeters) {
                bestBucket = bucket;
                bestDistanceMeters = distance;
            }
        }

        if (bestBucket != null) {
            trackedBirdNotificationHandled = true;
            showBirdListBottomSheet(bestBucket);
        }
    }

    private boolean bucketContainsTrackedBird(@NonNull HotspotBucket bucket,
                                              @Nullable String targetBirdId,
                                              @Nullable String targetBirdName) {
        for (BirdSheetRow row : bucket.birdRows.values()) {
            String rowBirdId = safeTrim(row.birdId);
            String rowCommonName = safeTrim(row.commonName);
            String rowDisplayName = safeTrim(row.displayName);

            if (targetBirdId != null && targetBirdId.equalsIgnoreCase(rowBirdId)) {
                return true;
            }

            if (targetBirdName != null) {
                if (targetBirdName.equalsIgnoreCase(rowCommonName) ||
                        targetBirdName.equalsIgnoreCase(rowDisplayName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private float distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        float[] results = new float[1];
        android.location.Location.distanceBetween(lat1, lng1, lat2, lng2, results);
        return results[0];
    }

    /**
     * Main logic block for this part of the feature.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private void renderHotspotCircles() {
        boolean filterActive = hasSelectedBirdFilter();

        for (HotspotBucket b : hotspotBuckets.values()) {
            if (b.pointCount == 0) continue;
            if (filterActive && !bucketMatchesSelectedBird(b)) continue;

            Circle c = googleMap.addCircle(
                    new CircleOptions()
                            .center(new LatLng(b.getCenterLat(), b.getCenterLng()))
                            .radius(HOTSPOT_CIRCLE_RADIUS_METERS)
                            .strokeWidth(filterActive ? 3.5f : 2f)
                            .strokeColor(filterActive ? Color.argb(210, 255, 191, 0) : Color.argb(110, 255, 255, 255))
                            .fillColor(filterActive ? Color.argb(78, 255, 191, 0) : Color.argb(35, 255, 255, 255))
                            .clickable(true)
                            .zIndex(filterActive ? 3.5f : 3f)
            );
            hotspotCircles.add(c);
            circleIdToBucket.put(c.getId(), b);
        }
    }

    private void clearHotspotCircles() {
        for (Circle c : hotspotCircles) c.remove();
        hotspotCircles.clear();
        circleIdToBucket.clear();
    }

    @Override
    public void onCircleClick(@NonNull Circle c) {
        HotspotBucket b = circleIdToBucket.get(c.getId());
        if (b != null) showBirdListBottomSheet(b);
    }

    /**
     * Takes prepared data and presents it on screen or in a dialog/menu.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     */
    private void showBirdListBottomSheet(@NonNull HotspotBucket b) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(R.layout.bottom_sheet_heatmap_birds);

        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window != null) {
                SystemBarHelper.applyDialogNavBar(this, window);
            }

            View shownBottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (shownBottomSheet != null) {
                shownBottomSheet.setBackgroundColor(Color.TRANSPARENT);
                ViewGroup.LayoutParams params = shownBottomSheet.getLayoutParams();
                params.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.58f);
                shownBottomSheet.setLayoutParams(params);

                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(shownBottomSheet);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });

        TextView tvSheetSummary = dialog.findViewById(R.id.tvSheetSummary);
        TextView tvSheetStatus = dialog.findViewById(R.id.tvSheetStatus);
        LinearLayout container = dialog.findViewById(R.id.birdListContainer);
        TextView tvEmptyBirds = dialog.findViewById(R.id.tvEmptyBirds);

        if (container == null) {
            dialog.show();
            return;
        }

        Runnable refreshSummary = () -> {
            if (tvSheetSummary != null) {
                tvSheetSummary.setText("Thumbs Up or Thumbs Down A Bird If You Have or Haven't Seen It");
            }

            if (tvSheetStatus != null) {
                if (b.userCount == 0 && b.eBirdCount > 0) {
                    tvSheetStatus.setText("Hotspot status: Verified Sighting");
                    tvSheetStatus.setTextColor(Color.parseColor("#2563EB")); // eBird = blue
                } else {
                    HotspotVerificationState state = classifyBucketVerificationState(b);
                    if (state == HotspotVerificationState.VERIFIED) {
                        tvSheetStatus.setText("Hotspot status: Verified Sighting");
                        tvSheetStatus.setTextColor(Color.parseColor("#6D28D9")); // user verified = purple
                    } else if (state == HotspotVerificationState.MIXED) {
                        tvSheetStatus.setText("Hotspot status: Mixed Sighting");
                        tvSheetStatus.setTextColor(Color.parseColor("#EC4899")); // pink
                    } else {
                        tvSheetStatus.setText("Hotspot status: Unverified Sighting");
                        tvSheetStatus.setTextColor(Color.parseColor("#FF8A00")); // orange
                    }
                }
            }
        };

        dialog.setOnDismissListener(d -> {
            for (ListenerRegistration lr : activeListeners) {
                if (lr != null) lr.remove();
            }
            activeListeners.clear();
        });

        container.removeAllViews();

        List<BirdSheetRow> rows = new ArrayList<>(b.birdRows.values());
        Collections.sort(rows, (a, c) -> String.CASE_INSENSITIVE_ORDER.compare(a.displayName, c.displayName));

        if (tvEmptyBirds != null) {
            tvEmptyBirds.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        }

        LayoutInflater inf = LayoutInflater.from(this);
        for (BirdSheetRow item : rows) {
            View row = inf.inflate(R.layout.item_heatmap_bird_placeholder, container, false);

            ((TextView) row.findViewById(R.id.tvBirdName)).setText(item.displayName);

            TextView tvBirdCount = row.findViewById(R.id.tvBirdCount);
            if (item.userCount > 0 && item.eBirdCount > 0) {
                tvBirdCount.setText(item.userCount + " user  •  " + item.eBirdCount + " verified");
            } else if (item.userCount > 0) {
                tvBirdCount.setText(item.userCount + " user sightings");
            } else {
                tvBirdCount.setText(item.eBirdCount + " verified sightings");
            }

            ImageView ivBird = row.findViewById(R.id.ivBirdPlaceholder);
            BirdImageLoader.loadBirdImageInto(ivBird, item.birdId, item.commonName, item.scientificName);

            TextView tvVoteStatus = row.findViewById(R.id.tvVoteStatus);
            TextView btnThumbUp = row.findViewById(R.id.btnThumbUp);
            TextView btnThumbDown = row.findViewById(R.id.btnThumbDown);
            bindVoteUi(b, item, tvVoteStatus, btnThumbUp, btnThumbDown, refreshSummary);

            View clickArea = row.findViewById(R.id.rowContent);
            if (clickArea == null) {
                clickArea = row;
            }
            clickArea.setClickable(true);
            clickArea.setFocusable(true);
            clickArea.setOnClickListener(v -> openBirdWikiFromHeatmapRow(item, dialog));

            container.addView(row);
        }

        refreshSummary.run();
        dialog.show();
    }

    private void openBirdWikiFromHeatmapRow(@NonNull BirdSheetRow item, @NonNull BottomSheetDialog dialog) {
        String birdId = safeTrim(item.birdId);
        if (birdId != null) {
            launchBirdWiki(birdId, dialog);
            return;
        }

        String commonName = safeTrim(item.commonName);
        String scientificName = safeTrim(item.scientificName);

        if (commonName == null && scientificName == null) {
            MessagePopupHelper.showBrief(this, "Bird info unavailable for this hotspot entry.");
            return;
        }

        resolveBirdIdAndOpen(commonName, scientificName, dialog);
    }

    private void resolveBirdIdAndOpen(@Nullable String commonName,
                                      @Nullable String scientificName,
                                      @NonNull BottomSheetDialog dialog) {
        if (commonName != null) {
            db.collection("birds")
                    .whereEqualTo("commonName", commonName)
                    .limit(1)
                    .get(Source.DEFAULT)
                    .addOnSuccessListener(querySnapshot -> {
                        if (querySnapshot != null && !querySnapshot.isEmpty()) {
                            launchBirdWiki(querySnapshot.getDocuments().get(0).getId(), dialog);
                        } else if (scientificName != null) {
                            resolveBirdIdByScientificName(scientificName, dialog);
                        } else {
                            MessagePopupHelper.showBrief(this, "Could not open bird info for this entry.");
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to resolve bird by commonName=" + commonName, e);
                        if (scientificName != null) {
                            resolveBirdIdByScientificName(scientificName, dialog);
                        } else {
                            MessagePopupHelper.showBrief(this, "Could not open bird info for this entry.");
                        }
                    });
            return;
        }

        resolveBirdIdByScientificName(scientificName, dialog);
    }

    private void resolveBirdIdByScientificName(@Nullable String scientificName,
                                               @NonNull BottomSheetDialog dialog) {
        if (scientificName == null) {
            MessagePopupHelper.showBrief(this, "Could not open bird info for this entry.");
            return;
        }

        db.collection("birds")
                .whereEqualTo("scientificName", scientificName)
                .limit(1)
                .get(Source.DEFAULT)
                .addOnSuccessListener(querySnapshot -> {
                    if (querySnapshot != null && !querySnapshot.isEmpty()) {
                        launchBirdWiki(querySnapshot.getDocuments().get(0).getId(), dialog);
                    } else {
                        MessagePopupHelper.showBrief(this, "Could not open bird info for this entry.");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to resolve bird by scientificName=" + scientificName, e);
                    MessagePopupHelper.showBrief(this, "Could not open bird info for this entry.");
                });
    }

    private void launchBirdWiki(@NonNull String birdId, @NonNull BottomSheetDialog dialog) {
        dialog.dismiss();
        Intent intent = new Intent(this, BirdWikiActivity.class);
        intent.putExtra(BirdWikiActivity.EXTRA_BIRD_ID, birdId);
        startActivity(intent);
    }

    @Nullable
    private String safeTrim(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Main logic block for this part of the feature.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */
    private void addToHotspotBucket(@NonNull HotspotSighting sighting, boolean user) {
        double blat = (Math.round(sighting.lat / HOTSPOT_BUCKET_SIZE) * HOTSPOT_BUCKET_SIZE);
        double blng = (Math.round(sighting.lng / HOTSPOT_BUCKET_SIZE) * HOTSPOT_BUCKET_SIZE);
        String key = String.format(Locale.US, "%.4f,%.4f", blat, blng);
        HotspotBucket b = hotspotBuckets.get(key);
        if (b == null) {
            HotspotBucket newB = new HotspotBucket();
            hotspotBuckets.put(key, newB);
            activeListeners.add(firebaseManager.listenToHotspotSummary(key, (snapshot, e) -> {
                if (isFinishing() || isDestroyed()) return;
                if (e != null || snapshot == null) return;

                Map<String, Object> birds = (Map<String, Object>) snapshot.get("birds");
                if (birds != null) {
                    for (Map.Entry<String, Object> entry : birds.entrySet()) {
                        String birdKey = entry.getKey();
                        Map<String, Object> birdData = (Map<String, Object>) entry.getValue();
                        if (birdData != null) {
                            Long upVoteCount = (Long) birdData.get("upVoteCount");
                            Boolean isVerified = (Boolean) birdData.get("isVerified");
                            newB.verifiedCounts.put(birdKey, upVoteCount != null ? upVoteCount.intValue() : 0);
                            newB.isVerifiedMap.put(birdKey, isVerified != null && isVerified);
                        }
                    }
                }
                renderHeatmaps();
            }));
            b = newB;
        }
        b.add(sighting, user);
    }
    private void rebuildHotspotBuckets(int gen) {
        new Thread(() -> {
            Map<String, HotspotBucket> newBuckets = new LinkedHashMap<>();
            List<ListenerRegistration> newListeners = new ArrayList<>();

            // We use local copies of the sightings lists to avoid ConcurrentModificationException
            List<HotspotSighting> userList = new ArrayList<>(userHotspotSightings);
            List<HotspotSighting> eBirdList = new ArrayList<>(eBirdHotspotSightings);

            for (HotspotSighting sighting : userList) {
                if (fetchGeneration != gen) return;
                addToTempBuckets(newBuckets, sighting, true);
            }

            for (HotspotSighting sighting : eBirdList) {
                if (fetchGeneration != gen) return;
                addToTempBuckets(newBuckets, sighting, false);
            }

            runOnUiThread(() -> {
                if (fetchGeneration != gen) return;

                for (ListenerRegistration lr : activeListeners) {
                    if (lr != null) lr.remove();
                }
                activeListeners.clear();

                hotspotBuckets.clear();
                hotspotBuckets.putAll(newBuckets);
                onCollectionFinished(gen);
            });
        }).start();
    }

    private void addToTempBuckets(Map<String, HotspotBucket> buckets, HotspotSighting sighting, boolean user) {
        double bucketLat = Math.floor(sighting.lat / HOTSPOT_BUCKET_SIZE) * HOTSPOT_BUCKET_SIZE;
        double bucketLng = Math.floor(sighting.lng / HOTSPOT_BUCKET_SIZE) * HOTSPOT_BUCKET_SIZE;
        String key = String.format(java.util.Locale.US, "%.4f_%.4f", bucketLat, bucketLng);

        HotspotBucket b = buckets.get(key);
        if (b == null) {
            b = new HotspotBucket();
            buckets.put(key, b);
        }
        b.add(sighting, user);
    }

    private String cleanBirdText(String value) {
        return value == null ? null : value.trim().replace("_", " ").replace("-", " ");
    }

    private String firstNonBlank(String a, String b, String c, String fallback) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        if (b != null && !b.trim().isEmpty()) return b.trim();
        if (c != null && !c.trim().isEmpty()) return c.trim();
        return fallback;
    }

    /**
     * Main logic block for this part of the feature.
     * Location values are handled here, so this is part of the logic that decides what area/bird
     * sightings the user sees.
     */


    private List<WeightedLatLng> buildUserHeatPointsForStatus(@NonNull HotspotVerificationState targetState) {
        List<WeightedLatLng> filtered = new ArrayList<>();

        for (HotspotBucket bucket : hotspotBuckets.values()) {
            if (bucket.userCount <= 0) continue;
            if (classifyBucketVerificationState(bucket) != targetState) continue;

            double weight = hasSelectedBirdFilter()
                    ? bucket.getMatchingUserSightingCount(this::matchesSelectedBird)
                    : bucket.userCount;

            if (weight <= 0d) continue;

            filtered.add(new WeightedLatLng(
                    new LatLng(bucket.getCenterLat(), bucket.getCenterLng()),
                    Math.max(1.8d, weight * 1.8d)
            ));
        }

        return filtered;
    }

    private int countUserHotspotsByState(@NonNull HotspotVerificationState targetState) {
        int count = 0;
        for (HotspotBucket bucket : hotspotBuckets.values()) {
            if (bucket.userCount <= 0) continue;
            if (hasSelectedBirdFilter() && bucket.getMatchingUserSightingCount(this::matchesSelectedBird) <= 0) continue;
            if (classifyBucketVerificationState(bucket) == targetState) {
                count++;
            }
        }
        return count;
    }

    private HotspotVerificationState classifyBucketVerificationState(@NonNull HotspotBucket bucket) {
        int userBirdRows = 0;
        int verifiedBirdRows = 0;

        for (BirdSheetRow row : bucket.birdRows.values()) {
            if (row.userCount <= 0) continue;
            userBirdRows++;

            String birdKey = buildHotspotBirdKey(row.birdId, row.commonName, row.userBirdId);
            Boolean isVerified = bucket.isVerifiedMap.get(birdKey);
            if (isVerified != null && isVerified) {
                verifiedBirdRows++;
            }
        }

        if (userBirdRows == 0) return HotspotVerificationState.UNVERIFIED;
        if (verifiedBirdRows == 0) return HotspotVerificationState.UNVERIFIED;
        if (verifiedBirdRows == userBirdRows) return HotspotVerificationState.VERIFIED;
        return HotspotVerificationState.MIXED;
    }

    private int getBirdVote(@NonNull HotspotBucket bucket, @NonNull BirdSheetRow row) {
        String birdKey = buildHotspotBirdKey(row.birdId, row.commonName, row.userBirdId);
        Integer vote = bucket.userVotes.get(birdKey);
        return vote != null ? vote : VOTE_NONE;
    }

    private String sanitizeText(String text, int maxLength) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.length() > maxLength) trimmed = trimmed.substring(0, maxLength);
        return trimmed.replaceAll("<[^>]*>", "");
    }

    private String normalizeHotspotBirdKeySegment(String value, int maxLength) {
        String safe = sanitizeText(value != null ? value : "", maxLength).toLowerCase();
        return safe.replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .substring(0, Math.min(safe.length(), maxLength));
    }

    private String buildHotspotBirdKey(String birdId, String commonName, String userBirdId) {
        String normalizedBirdId = normalizeHotspotBirdKeySegment(birdId, 120);
        if (!normalizedBirdId.isEmpty()) return "bird_" + normalizedBirdId;

        String normalizedUserBirdId = normalizeHotspotBirdKeySegment(userBirdId, 120);
        if (!normalizedUserBirdId.isEmpty()) return "userbird_" + normalizedUserBirdId;

        String normalizedCommonName = sanitizeText(commonName != null ? commonName : "", 200).toLowerCase();
        if (!normalizedCommonName.isEmpty()) {
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
                byte[] bytes = digest.digest(normalizedCommonName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(bytes.length, 8); i++) {
                    sb.append(String.format("%02x", bytes[i]));
                }
                return "name_" + sb.toString();
            } catch (Exception e) {
                return "name_error";
            }
        }
        return "bird_unknown";
    }

    private void bindVoteUi(@NonNull HotspotBucket bucket,
                            @NonNull BirdSheetRow item,
                            @NonNull TextView tvVoteStatus,
                            @NonNull TextView btnThumbUp,
                            @NonNull TextView btnThumbDown,
                            @NonNull Runnable refreshBottomSheetUi) {
        if (item.userCount <= 0) {
            tvVoteStatus.setText("Verified eBird entry");
            tvVoteStatus.setTextColor(Color.parseColor("#2563EB"));
            btnThumbUp.setVisibility(View.GONE);
            btnThumbDown.setVisibility(View.GONE);
            return;
        }

        btnThumbUp.setVisibility(View.VISIBLE);
        btnThumbDown.setVisibility(View.VISIBLE);

        String hotspotId = String.format(Locale.US, "%.4f,%.4f",
                Math.round(bucket.getCenterLat() / HOTSPOT_BUCKET_SIZE) * HOTSPOT_BUCKET_SIZE,
                Math.round(bucket.getCenterLng() / HOTSPOT_BUCKET_SIZE) * HOTSPOT_BUCKET_SIZE);
        String birdKey = buildHotspotBirdKey(item.birdId, item.commonName, item.userBirdId);

        activeListeners.add(firebaseManager.listenToMyHotspotBirdVote(hotspotId, birdKey, (snapshot, e) -> {
            if (isFinishing() || isDestroyed()) return;
            if (e != null || snapshot == null) return;
            String voteVal = snapshot.getString("vote");
            int vote = VOTE_NONE;
            if ("up".equals(voteVal)) vote = VOTE_UP;
            else if ("down".equals(voteVal)) vote = VOTE_DOWN;
            bucket.userVotes.put(birdKey, vote);

            if (vote == VOTE_UP) {
                tvVoteStatus.setText("Verified");
                tvVoteStatus.setTextColor(Color.parseColor("#6D28D9"));
            } else if (vote == VOTE_DOWN) {
                tvVoteStatus.setText("Marked unverified");
                tvVoteStatus.setTextColor(Color.parseColor("#DC2626"));
            } else {
                tvVoteStatus.setText("Unverified");
                tvVoteStatus.setTextColor(Color.parseColor("#FF8A00"));
            }

            btnThumbUp.setAlpha(vote == VOTE_UP ? 1f : 0.45f);
            btnThumbDown.setAlpha(vote == VOTE_DOWN ? 1f : 0.45f);
        }));

        activeListeners.add(firebaseManager.listenToHotspotBirdSummary(hotspotId, birdKey, (snapshot, e) -> {
            if (isFinishing() || isDestroyed()) return;
            if (e != null || snapshot == null) return;
            Long upVoteCount = snapshot.getLong("upVoteCount");
            Boolean isVerified = snapshot.getBoolean("isVerified");
            bucket.verifiedCounts.put(birdKey, upVoteCount != null ? upVoteCount.intValue() : 0);
            bucket.isVerifiedMap.put(birdKey, isVerified != null && isVerified);
            refreshBottomSheetUi.run();
            renderHeatmaps();
        }));

        btnThumbUp.setOnClickListener(v -> {
            int currentVote = getBirdVote(bucket, item);
            if (currentVote == VOTE_UP) {
                firebaseManager.clearHotspotBirdVote(hotspotId, birdKey, null);
            } else {
                firebaseManager.voteOnHotspotBird(hotspotId, birdKey, "up", null);
            }
        });

        btnThumbDown.setOnClickListener(v -> {
            int currentVote = getBirdVote(bucket, item);
            if (currentVote == VOTE_DOWN) {
                firebaseManager.clearHotspotBirdVote(hotspotId, birdKey, null);
            } else {
                firebaseManager.voteOnHotspotBird(hotspotId, birdKey, "down", null);
            }
        });
    }

    private List<WeightedLatLng> buildDisplayHeatPoints(boolean user) {
        if (!hasSelectedBirdFilter()) {
            return new ArrayList<>(user ? userHeatPoints : eBirdHeatPoints);
        }

        List<WeightedLatLng> filtered = new ArrayList<>();
        List<HotspotSighting> source = user ? userHotspotSightings : eBirdHotspotSightings;
        double intensity = user ? 1.8d : 1.0d;

        for (HotspotSighting sighting : source) {
            if (!matchesSelectedBird(sighting.birdId, sighting.commonName, sighting.scientificName, sighting.displayName)) {
                continue;
            }
            filtered.add(new WeightedLatLng(new LatLng(sighting.lat, sighting.lng), intensity));
        }

        return filtered;
    }

    private boolean bucketMatchesSelectedBird(@NonNull HotspotBucket bucket) {
        for (BirdSheetRow row : bucket.birdRows.values()) {
            if (matchesSelectedBird(row.birdId, row.commonName, row.scientificName, row.displayName)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSelectedBird(@Nullable String birdId,
                                        @Nullable String commonName,
                                        @Nullable String scientificName,
                                        @Nullable String displayName) {
        if (!hasSelectedBirdFilter()) return true;

        String normalizedBirdId = normalizeBirdValue(birdId);
        String normalizedCommonName = normalizeBirdValue(commonName);
        String normalizedScientificName = normalizeBirdValue(scientificName);
        String normalizedDisplayName = normalizeBirdValue(displayName);

        String normalizedSelectedId = normalizeBirdValue(selectedBirdId);
        String normalizedSelectedCommon = normalizeBirdValue(selectedBirdCommonName);
        String normalizedSelectedScientific = normalizeBirdValue(selectedBirdScientificName);
        String normalizedSelectedLabel = normalizeBirdValue(selectedBirdLabel);

        return (normalizedSelectedId != null && normalizedSelectedId.equals(normalizedBirdId))
                || (normalizedSelectedCommon != null && (normalizedSelectedCommon.equals(normalizedCommonName)
                || normalizedSelectedCommon.equals(normalizedScientificName)
                || normalizedSelectedCommon.equals(normalizedDisplayName)))
                || (normalizedSelectedScientific != null && (normalizedSelectedScientific.equals(normalizedScientificName)
                || normalizedSelectedScientific.equals(normalizedCommonName)
                || normalizedSelectedScientific.equals(normalizedDisplayName)))
                || (normalizedSelectedLabel != null && (normalizedSelectedLabel.equals(normalizedDisplayName)
                || normalizedSelectedLabel.equals(normalizedCommonName)
                || normalizedSelectedLabel.equals(normalizedScientificName)));
    }

    @Nullable
    private String normalizeBirdValue(@Nullable String value) {
        if (value == null) return null;

        String normalized = value.trim()
                .replace("_", " ")
                .replace("-", " ")
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.US);

        return normalized.isEmpty() ? null : normalized;
    }

    private void focusCameraOnSelectedBirdHotspots() {
        if (googleMap == null || !hasSelectedBirdFilter()) return;

        LatLng onlyTarget = null;
        LatLngBounds.Builder boundsBuilder = new LatLngBounds.Builder();
        int matches = 0;

        for (HotspotBucket bucket : hotspotBuckets.values()) {
            if (!bucketMatchesSelectedBird(bucket)) continue;

            LatLng point = new LatLng(bucket.getCenterLat(), bucket.getCenterLng());
            boundsBuilder.include(point);
            onlyTarget = point;
            matches++;
        }

        if (matches == 0) {
            MessagePopupHelper.showBrief(this, "No nearby sightings for " + safeSelectedBirdLabel() + " in this map area.");
            return;
        }

        if (matches == 1 && onlyTarget != null) {
            float targetZoom = Math.max(googleMap.getCameraPosition().zoom, 11f);
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(onlyTarget, targetZoom));
        } else {
            googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), dpToPx(72)));
        }
    }

    private int dpToPx(int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics()
        ));
    }

    private boolean shouldBeFiltered(double lat, double lng, Long time) {
        if (time != null && (System.currentTimeMillis() - time > SIGHTING_RECENCY_MS)) return true;
        if (currentVisibleBounds != null)
            return !currentVisibleBounds.contains(new LatLng(lat, lng));
        return false;
    }

    private Double getAnyDouble(DocumentSnapshot d, String... paths) {
        for (String p : paths) {
            Object v = d.get(p);
            if (v instanceof Number) return ((Number) v).doubleValue();
        }
        return null;
    }

    private String getAnyString(DocumentSnapshot d, String... paths) {
        for (String p : paths) {
            Object v = d.get(p);
            if (v != null && !String.valueOf(v).trim().isEmpty()) return String.valueOf(v).trim();
        }
        return null;
    }

    private Long getAnyTimeMillis(DocumentSnapshot d, String... paths) {
        for (String p : paths) {
            Object v = d.get(p);
            if (v instanceof Date) return ((Date) v).getTime();
            if (v instanceof Number) return ((Number) v).longValue();
        }
        return null;
    }

    private static class HotspotSighting {
        final String sightingId;
        final double lat, lng;
        final String birdId;
        final String userBirdId;
        final String commonName;
        final String scientificName;
        final String displayName;
        final boolean isUserSighting;

        HotspotSighting(String sightingId,
                        double lat,
                        double lng,
                        String birdId,
                        String userBirdId,
                        String commonName,
                        String scientificName,
                        String displayName,
                        boolean u) {
            this.sightingId = sightingId;
            this.lat = lat;
            this.lng = lng;
            this.birdId = birdId;
            this.userBirdId = userBirdId;
            this.commonName = commonName;
            this.scientificName = scientificName;
            this.displayName = displayName;
            this.isUserSighting = u;
        }
    }

    private interface BirdMatchPredicate {
        boolean matches(@Nullable String birdId,
                        @Nullable String commonName,
                        @Nullable String scientificName,
                        @Nullable String displayName);
    }

    private enum HotspotVerificationState {
        UNVERIFIED,
        MIXED,
        VERIFIED
    }

    private static class BirdSheetRow {
        final String displayName;
        String birdId;
        String userBirdId;
        String commonName;
        String scientificName;
        int count;
        int userCount;
        int eBirdCount;

        BirdSheetRow(String displayName, String birdId, String userBirdId, String commonName, String scientificName) {
            this.displayName = displayName;
            this.birdId = birdId;
            this.userBirdId = userBirdId;
            this.commonName = commonName;
            this.scientificName = scientificName;
            this.count = 0;
            this.userCount = 0;
            this.eBirdCount = 0;
        }

        void mergeIdentifiers(HotspotSighting sighting) {
            if ((birdId == null || birdId.trim().isEmpty()) && sighting.birdId != null && !sighting.birdId.trim().isEmpty()) {
                birdId = sighting.birdId;
            }
            if ((userBirdId == null || userBirdId.trim().isEmpty()) && sighting.userBirdId != null && !sighting.userBirdId.trim().isEmpty()) {
                userBirdId = sighting.userBirdId;
            }
            if ((commonName == null || commonName.trim().isEmpty()) && sighting.commonName != null && !sighting.commonName.trim().isEmpty()) {
                commonName = sighting.commonName;
            }
            if ((scientificName == null || scientificName.trim().isEmpty()) && sighting.scientificName != null && !sighting.scientificName.trim().isEmpty()) {
                scientificName = sighting.scientificName;
            }
        }
    }

    private static class HotspotBucket {
        double latSum = 0, lngSum = 0;
        int pointCount = 0, userCount = 0, eBirdCount = 0;
        final Map<String, BirdSheetRow> birdRows = new LinkedHashMap<>();
        final Set<String> sightingIds = new HashSet<>();
        final Map<String, Integer> userVotes = new HashMap<>();
        final Map<String, Integer> verifiedCounts = new HashMap<>();
        final Map<String, Boolean> isVerifiedMap = new HashMap<>();

        void add(HotspotSighting sighting, boolean user) {
            latSum += sighting.lat;
            lngSum += sighting.lng;
            pointCount++;
            if (user) userCount++;
            else eBirdCount++;

            if (sighting.sightingId != null && !sighting.sightingId.trim().isEmpty()) {
                sightingIds.add(sighting.sightingId);
            }

            String key = (sighting.displayName == null || sighting.displayName.trim().isEmpty())
                    ? "Unknown bird"
                    : sighting.displayName.trim();

            BirdSheetRow row = birdRows.get(key);
            if (row == null) {
                row = new BirdSheetRow(key, sighting.birdId, sighting.userBirdId, sighting.commonName, sighting.scientificName);
                birdRows.put(key, row);
            } else {
                row.mergeIdentifiers(sighting);
            }
            row.count++;
            if (user) {
                row.userCount++;
            } else {
                row.eBirdCount++;
            }
        }

        int getMatchingUserSightingCount(@NonNull BirdMatchPredicate predicate) {
            int matches = 0;
            for (BirdSheetRow row : birdRows.values()) {
                if (row.userCount <= 0) continue;
                if (predicate.matches(row.birdId, row.commonName, row.scientificName, row.displayName)) {
                    matches += row.userCount;
                }
            }
            return matches;
        }

        double getCenterLat() {
            return pointCount == 0 ? 0 : latSum / pointCount;
        }

        double getCenterLng() {
            return pointCount == 0 ? 0 : lngSum / pointCount;
        }
    }
}
