package com.birddex.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.maps.android.heatmaps.Gradient;
import com.google.maps.android.heatmaps.HeatmapTileProvider;
import com.google.maps.android.heatmaps.WeightedLatLng;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class NearbyHeatmapActivity extends AppCompatActivity implements OnMapReadyCallback {

    public static final String EXTRA_CENTER_LAT = "extra_center_lat";
    public static final String EXTRA_CENTER_LNG = "extra_center_lng";

    private static final double DEFAULT_LAT = 32.6781;
    private static final double DEFAULT_LNG = -83.2220;
    private static final float DEFAULT_ZOOM = 7f;
    private static final float NEARBY_ZOOM = 10f;

    private static final double SEARCH_RADIUS_METERS = 50000d;
    private static final long SIGHTING_RECENCY_MS = 72L * 60 * 60 * 1000;

    private static final Gradient USER_GRADIENT = new Gradient(
            new int[]{
                    Color.argb(0, 255, 138, 0),
                    Color.rgb(255, 195, 0),
                    Color.rgb(255, 122, 0),
                    Color.rgb(220, 38, 38)
            },
            new float[]{0.2f, 0.5f, 0.8f, 1f}
    );

    private static final Gradient EBIRD_GRADIENT = new Gradient(
            new int[]{
                    Color.argb(0, 37, 99, 235),
                    Color.rgb(103, 232, 249),
                    Color.rgb(59, 130, 246),
                    Color.rgb(29, 78, 216)
            },
            new float[]{0.2f, 0.5f, 0.8f, 1f}
    );

    private GoogleMap googleMap;
    private FirebaseFirestore db;
    private TextView tvMapSubtitle;

    private TileOverlay userOverlay;
    private TileOverlay eBirdOverlay;

    private final List<WeightedLatLng> userHeatPoints = new ArrayList<>();
    private final List<WeightedLatLng> eBirdHeatPoints = new ArrayList<>();

    private double centerLat = Double.NaN;
    private double centerLng = Double.NaN;

    private int pendingLoads = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nearby_heatmap);

        db = FirebaseFirestore.getInstance();

        ImageButton btnBack = findViewById(R.id.btnBack);
        tvMapSubtitle = findViewById(R.id.tvMapSubtitle);

        btnBack.setOnClickListener(v -> finish());

        if (getIntent() != null
                && getIntent().hasExtra(EXTRA_CENTER_LAT)
                && getIntent().hasExtra(EXTRA_CENTER_LNG)) {
            centerLat = getIntent().getDoubleExtra(EXTRA_CENTER_LAT, Double.NaN);
            centerLng = getIntent().getDoubleExtra(EXTRA_CENTER_LNG, Double.NaN);
        }

        SupportMapFragment mapFragment =
                (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);

        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        } else {
            Toast.makeText(this, R.string.map_failed_to_load, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap map) {
        googleMap = map;

        googleMap.getUiSettings().setZoomControlsEnabled(true);
        googleMap.getUiSettings().setCompassEnabled(true);
        googleMap.getUiSettings().setMyLocationButtonEnabled(true);

        LatLng initialCenter = hasNearbyCenter()
                ? new LatLng(centerLat, centerLng)
                : new LatLng(DEFAULT_LAT, DEFAULT_LNG);

        float initialZoom = hasNearbyCenter() ? NEARBY_ZOOM : DEFAULT_ZOOM;
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialCenter, initialZoom));

        if (hasLocationPermission()) {
            try {
                googleMap.setMyLocationEnabled(true);
            } catch (SecurityException ignored) {
            }
        }

        fetchHeatmapData();
    }

    private void fetchHeatmapData() {
        pendingLoads = 2;
        userHeatPoints.clear();
        eBirdHeatPoints.clear();

        tvMapSubtitle.setText(R.string.loading_heatmap);

        loadUserBirdSightings();
        loadEbirdApiSightings();
    }

    private void loadUserBirdSightings() {
        db.collection("userBirdSightings")
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        Double lat = getAnyDouble(doc,
                                "location.latitude",
                                "lastSeenLatitudeGeorgia",
                                "latitude",
                                "lat");

                        Double lng = getAnyDouble(doc,
                                "location.longitude",
                                "lastSeenLongitudeGeorgia",
                                "longitude",
                                "lng");

                        Long timeMillis = getAnyTimeMillis(doc, "timestamp");

                        if (lat == null || lng == null) continue;
                        if (shouldBeFiltered(lat, lng, timeMillis)) continue;

                        userHeatPoints.add(new WeightedLatLng(new LatLng(lat, lng), 1.8));
                    }

                    onCollectionFinished();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, R.string.failed_to_load_user_sightings, Toast.LENGTH_SHORT).show();
                    onCollectionFinished();
                });
    }

    private void loadEbirdApiSightings() {
        db.collection("eBirdApiSightings")
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        Double lat = getAnyDouble(doc,
                                "location.latitude",
                                "lastSeenLatitudeGeorgia",
                                "latitude",
                                "lat");

                        Double lng = getAnyDouble(doc,
                                "location.longitude",
                                "lastSeenLongitudeGeorgia",
                                "longitude",
                                "lng");

                        Long timeMillis = getAnyTimeMillis(doc, "observationDate", "timestamp");

                        if (lat == null || lng == null) continue;
                        if (shouldBeFiltered(lat, lng, timeMillis)) continue;

                        eBirdHeatPoints.add(new WeightedLatLng(new LatLng(lat, lng), 1.0));
                    }

                    onCollectionFinished();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, R.string.failed_to_load_ebird_sightings, Toast.LENGTH_SHORT).show();
                    onCollectionFinished();
                });
    }

    private void onCollectionFinished() {
        pendingLoads--;

        if (pendingLoads <= 0) {
            renderHeatmaps();
        }
    }

    private void renderHeatmaps() {
        if (googleMap == null) return;

        if (eBirdOverlay != null) {
            eBirdOverlay.remove();
            eBirdOverlay = null;
        }

        if (userOverlay != null) {
            userOverlay.remove();
            userOverlay = null;
        }

        if (!eBirdHeatPoints.isEmpty()) {
            HeatmapTileProvider eBirdProvider = new HeatmapTileProvider.Builder()
                    .weightedData(eBirdHeatPoints)
                    .radius(45)
                    .opacity(0.65)
                    .gradient(EBIRD_GRADIENT)
                    .build();

            eBirdOverlay = googleMap.addTileOverlay(
                    new TileOverlayOptions()
                            .tileProvider(eBirdProvider)
                            .zIndex(1f)
            );
        }

        if (!userHeatPoints.isEmpty()) {
            HeatmapTileProvider userProvider = new HeatmapTileProvider.Builder()
                    .weightedData(userHeatPoints)
                    .radius(45)
                    .opacity(0.70)
                    .gradient(USER_GRADIENT)
                    .build();

            userOverlay = googleMap.addTileOverlay(
                    new TileOverlayOptions()
                            .tileProvider(userProvider)
                            .zIndex(2f)
            );
        }

        if (userHeatPoints.isEmpty() && eBirdHeatPoints.isEmpty()) {
            tvMapSubtitle.setText(hasNearbyCenter()
                    ? R.string.no_nearby_sightings
                    : R.string.no_recent_sightings);
            Toast.makeText(this, R.string.no_heatmap_data_found, Toast.LENGTH_SHORT).show();
            return;
        }

        String scopeText = getString(hasNearbyCenter() ? R.string.heatmap_scope_72h_50km : R.string.heatmap_scope_72h);
        tvMapSubtitle.setText(getString(R.string.heatmap_stats, scopeText, userHeatPoints.size(), eBirdHeatPoints.size()));
    }

    private boolean shouldBeFiltered(double lat, double lng, Long timeMillis) {
        if (timeMillis != null) {
            long age = System.currentTimeMillis() - timeMillis;
            if (age > SIGHTING_RECENCY_MS) {
                return true;
            }
        }

        if (hasNearbyCenter()) {
            float[] results = new float[1];
            android.location.Location.distanceBetween(
                    centerLat,
                    centerLng,
                    lat,
                    lng,
                    results
            );

            return results[0] > SEARCH_RADIUS_METERS;
        }

        return false;
    }

    private boolean hasNearbyCenter() {
        return !Double.isNaN(centerLat) && !Double.isNaN(centerLng);
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private Double getAnyDouble(DocumentSnapshot doc, String... fieldPaths) {
        for (String fieldPath : fieldPaths) {
            Object value = doc.get(fieldPath);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
        }
        return null;
    }

    private Long getAnyTimeMillis(DocumentSnapshot doc, String... fieldPaths) {
        for (String fieldPath : fieldPaths) {
            Object raw = doc.get(fieldPath);
            if (raw instanceof Date) {
                return ((Date) raw).getTime();
            }
            if (raw instanceof Number) {
                return ((Number) raw).longValue();
            }
        }
        return null;
    }
}
