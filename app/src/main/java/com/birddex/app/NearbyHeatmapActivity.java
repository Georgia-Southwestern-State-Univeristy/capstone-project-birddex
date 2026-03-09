package com.birddex.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
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
public class NearbyHeatmapActivity extends AppCompatActivity
        implements OnMapReadyCallback, GoogleMap.OnCircleClickListener, GoogleMap.OnMarkerClickListener, ForumCommentAdapter.OnCommentInteractionListener {

    private static final String TAG = "NearbyHeatmapActivity";
    public static final String EXTRA_CENTER_LAT = "extra_center_lat";
    public static final String EXTRA_CENTER_LNG = "extra_center_lng";
    private static final String PREFS_NAME = "BirdDexPrefs";
    private static final String KEY_GRAPHIC_CONTENT = "show_graphic_content";
    private LatLngBounds currentVisibleBounds;
    private float lastAppliedZoom = -1f;
    private LatLng lastAppliedTarget;
    private static final float MIN_ZOOM_CHANGE_TO_REFRESH = 0.5f;
    private static final float MIN_CAMERA_MOVE_TO_REFRESH_METERS = 500f;

    private static final double DEFAULT_LAT = 32.6781;
    private static final double DEFAULT_LNG = -83.2220;
    private static final float DEFAULT_ZOOM = 7f;
    private static final float NEARBY_ZOOM = 10f;

    private static final double SEARCH_RADIUS_METERS = 50000d;
    private static final long SIGHTING_RECENCY_MS = 72L * 60 * 60 * 1000;

    private static final double HOTSPOT_BUCKET_SIZE = 0.02d;
    private static final double HOTSPOT_CIRCLE_RADIUS_METERS = 900d;

    private static final Gradient USER_GRADIENT = new Gradient(
            new int[]{ Color.argb(0, 255, 138, 0), Color.rgb(255, 195, 0), Color.rgb(255, 122, 0), Color.rgb(220, 38, 38) },
            new float[]{0.2f, 0.5f, 0.8f, 1f}
    );

    private static final Gradient EBIRD_GRADIENT = new Gradient(
            new int[]{ Color.argb(0, 37, 99, 235), Color.rgb(103, 232, 249), Color.rgb(59, 130, 246), Color.rgb(29, 78, 216) },
            new float[]{0.2f, 0.5f, 0.8f, 1f}
    );

    private GoogleMap googleMap;
    private FirebaseFirestore db;
    private FirebaseAuth mAuth;
    private TextView tvMapSubtitle;

    private TileOverlay userOverlay;
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
    private DocumentSnapshot lastPopupCommentVisible;
    private boolean isFetchingPopupComments = false;
    private boolean isLastPopupCommentsPage = false;
    private static final int POPUP_COMMENTS_PAGE_SIZE = 25;

    // --- FIXES ---
    private final Set<String> postLikeInFlight = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> commentLikeInFlight = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private boolean isNavigating = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nearby_heatmap);

        db = FirebaseFirestore.getInstance();
        mAuth = FirebaseAuth.getInstance();
        firebaseManager = new FirebaseManager(this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        tvMapSubtitle = findViewById(R.id.tvMapSubtitle);

        if (getIntent() != null && getIntent().hasExtra(EXTRA_CENTER_LAT)) {
            centerLat = getIntent().getDoubleExtra(EXTRA_CENTER_LAT, Double.NaN);
            centerLng = getIntent().getDoubleExtra(EXTRA_CENTER_LNG, Double.NaN);
        }

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) mapFragment.getMapAsync(this);
        else { Toast.makeText(this, "Map failed to load", Toast.LENGTH_SHORT).show(); finish(); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isNavigating = false;
    }

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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try { googleMap.setMyLocationEnabled(true); } catch (SecurityException ignored) {}
        }

        googleMap.setOnCameraIdleListener(() -> {
            if (googleMap == null) return;
            CameraPosition cp = googleMap.getCameraPosition();
            currentVisibleBounds = googleMap.getProjection().getVisibleRegion().latLngBounds;
            centerLat = cp.target.latitude; centerLng = cp.target.longitude;

            boolean shouldRefresh = false;
            if (lastAppliedTarget == null) shouldRefresh = true;
            else {
                float[] res = new float[1];
                android.location.Location.distanceBetween(lastAppliedTarget.latitude, lastAppliedTarget.longitude, cp.target.latitude, cp.target.longitude, res);
                if (res[0] >= MIN_CAMERA_MOVE_TO_REFRESH_METERS || Math.abs(cp.zoom - lastAppliedZoom) >= MIN_ZOOM_CHANGE_TO_REFRESH) shouldRefresh = true;
            }

            if (shouldRefresh) { lastAppliedTarget = cp.target; lastAppliedZoom = cp.zoom; fetchHeatmapData(); loadForumPins(); }
        });

        googleMap.setOnMapLoadedCallback(() -> {
            if (googleMap != null) {
                currentVisibleBounds = googleMap.getProjection().getVisibleRegion().latLngBounds;
                CameraPosition cp = googleMap.getCameraPosition();
                lastAppliedTarget = cp.target; lastAppliedZoom = cp.zoom;
                fetchHeatmapData(); loadForumPins();
            }
        });
    }

    private void fetchHeatmapData() {
        final int gen = ++fetchGeneration;
        pendingLoads = 4;
        userHeatPoints.clear(); eBirdHeatPoints.clear();
        userHotspotSightings.clear(); eBirdHotspotSightings.clear();
        hotspotBuckets.clear(); clearHotspotCircles();
        tvMapSubtitle.setText("Loading heatmap...");

        loadUserBirdSightings(gen);
        loadEbirdApiSightings(gen);
    }

    private void loadForumPins() {
        db.collection("forumThreads").whereEqualTo("showLocation", true).get(Source.CACHE).addOnSuccessListener(snap -> {
            if (snap != null && !snap.isEmpty()) processForumPins(snap);
            fetchForumPinsFromServer();
        }).addOnFailureListener(e -> fetchForumPinsFromServer());
    }

    private void fetchForumPinsFromServer() {
        db.collection("forumThreads").whereEqualTo("showLocation", true).get(Source.SERVER).addOnSuccessListener(this::processForumPins).addOnFailureListener(e -> Log.e(TAG, "Error", e));
    }

    private void processForumPins(com.google.firebase.firestore.QuerySnapshot snap) {
        clearForumMarkers();
        boolean showGraphic = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_GRAPHIC_CONTENT, false);
        for (DocumentSnapshot doc : snap.getDocuments()) {
            ForumPost p = doc.toObject(ForumPost.class);
            if (p != null && p.getLatitude() != null) {
                p.setId(doc.getId());
                boolean inBounds = currentVisibleBounds == null || currentVisibleBounds.contains(new LatLng(p.getLatitude(), p.getLongitude()));
                if (inBounds && (showGraphic || !p.isHunted())) addPinToMap(p);
            }
        }
    }

    private void addPinToMap(ForumPost p) {
        LatLng pos = new LatLng(p.getLatitude(), p.getLongitude());
        StringBuilder status = new StringBuilder();
        if (p.isSpotted()) status.append("Spotted ");
        if (p.isHunted()) status.append("Hunted ");
        String title = p.getUsername() + (status.length() > 0 ? " (" + status.toString().trim() + ")" : "");

        BitmapDescriptor icon;
        if (p.isSpotted() && p.isHunted()) icon = createDualColorPin();
        else if (p.isHunted()) icon = createColoredPin(Color.BLACK);
        else if (p.isSpotted()) icon = createColoredPin(Color.rgb(255, 165, 0));
        else icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE);

        Marker m = googleMap.addMarker(new MarkerOptions().position(pos).title(title).snippet(p.getMessage()).icon(icon));
        if (m != null) { m.setTag(p); forumMarkers.add(m); }
    }

    private BitmapDescriptor createColoredPin(int color) {
        int size = 80; Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(bm); Paint p = new Paint();
        p.setColor(color); p.setAntiAlias(true); c.drawCircle(size / 2f, size / 3f, size / 3f, p); c.drawRect(size / 2f - 4, size / 2f, size / 2f + 4, size * 0.9f, p);
        return BitmapDescriptorFactory.fromBitmap(bm);
    }

    private BitmapDescriptor createDualColorPin() {
        int size = 80; Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(bm); Paint p = new Paint(); p.setAntiAlias(true);
        p.setColor(Color.BLUE); c.drawArc(size/6f, 0, size*5/6f, size*2/3f, 90, 180, true, p);
        p.setColor(Color.BLACK); c.drawArc(size/6f, 0, size*5/6f, size*2/3f, 270, 180, true, p);
        p.setColor(Color.BLUE); c.drawRect(size/2f-4, size/2f, size/2f, size*0.9f, p);
        p.setColor(Color.BLACK); c.drawRect(size/2f, size/2f, size/2f+4, size*0.9f, p);
        return BitmapDescriptorFactory.fromBitmap(bm);
    }

    private void clearForumMarkers() { for (Marker m : forumMarkers) m.remove(); forumMarkers.clear(); }

    @Override public boolean onMarkerClick(@NonNull Marker m) { Object tag = m.getTag(); if (tag instanceof ForumPost) { showPostInBottomSheet((ForumPost) tag); return true; } return false; }

    private void showPostInBottomSheet(ForumPost p) {
        activePost = p; BottomSheetDialog dialog = new BottomSheetDialog(this); View view = getLayoutInflater().inflate(R.layout.bottom_sheet_post_view, null); dialog.setContentView(view);
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null && user.getUid().equals(p.getUserId()) && p.isNotificationSent()) db.collection("forumThreads").document(p.getId()).update("notificationSent", false);

        View content = view.findViewById(R.id.postContent);
        ((TextView) content.findViewById(R.id.tvPostUsername)).setText(p.getUsername());
        ((TextView) content.findViewById(R.id.tvPostMessage)).setText(p.isEdited() ? p.getMessage() + " (edited)" : p.getMessage());
        ((TextView) content.findViewById(R.id.tvLikeCount)).setText(String.valueOf(p.getLikeCount()));
        ((TextView) content.findViewById(R.id.tvCommentCount)).setText(String.valueOf(p.getCommentCount()));
        ((TextView) content.findViewById(R.id.tvViewCount)).setText(p.getViewCount() + " views");

        if (p.getTimestamp() != null) ((TextView) content.findViewById(R.id.tvPostTimestamp)).setText(DateUtils.getRelativeTimeSpanString(p.getTimestamp().toDate().getTime()));
        Glide.with(this).load(p.getUserProfilePictureUrl()).placeholder(R.drawable.ic_profile).into((ImageView) content.findViewById(R.id.ivPostUserProfilePicture));

        ImageView ivBird = content.findViewById(R.id.ivPostBirdImage);
        if (p.getBirdImageUrl() != null && !p.getBirdImageUrl().isEmpty()) { content.findViewById(R.id.cvPostImage).setVisibility(View.VISIBLE); Glide.with(this).load(p.getBirdImageUrl()).into(ivBird); }
        else content.findViewById(R.id.cvPostImage).setVisibility(View.GONE);

        ImageView ivLike = content.findViewById(R.id.ivLikeIcon);
        ivLike.setImageResource((user != null && p.getLikedBy() != null && p.getLikedBy().containsKey(user.getUid())) ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);

        content.findViewById(R.id.btnLike).setOnClickListener(v -> {
            if (user == null || postLikeInFlight.contains(p.getId())) return;
            postLikeInFlight.add(p.getId());

            String uid = user.getUid(); boolean liked = p.getLikedBy() != null && p.getLikedBy().containsKey(uid);
            int count = p.getLikeCount();
            if (liked) { p.setLikeCount(Math.max(0, count-1)); p.getLikedBy().remove(uid); ivLike.setImageResource(R.drawable.ic_favorite_border); }
            else { p.setLikeCount(count+1); if (p.getLikedBy() == null) p.setLikedBy(new HashMap<>()); p.getLikedBy().put(uid, true); ivLike.setImageResource(R.drawable.ic_favorite); }
            ((TextView) content.findViewById(R.id.tvLikeCount)).setText(String.valueOf(p.getLikeCount()));

            db.collection("forumThreads").document(p.getId()).update("likedBy." + uid, liked ? FieldValue.delete() : true)
                    .addOnCompleteListener(t -> {
                        postLikeInFlight.remove(p.getId());
                        if (!t.isSuccessful()) {
                            p.setLikeCount(count); if (liked) p.getLikedBy().put(uid, true); else p.getLikedBy().remove(uid);
                            ((TextView) content.findViewById(R.id.tvLikeCount)).setText(String.valueOf(count));
                            ivLike.setImageResource(liked ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
                        }
                    });
        });

        content.setOnClickListener(v -> {
            if (isNavigating) return;
            isNavigating = true;
            startActivity(new Intent(this, PostDetailActivity.class).putExtra(PostDetailActivity.EXTRA_POST_ID, p.getId()));
            dialog.dismiss();
        });
        content.findViewById(R.id.btnPostOptions).setOnClickListener(v -> showPostOptions(p, v, dialog));

        RecyclerView rv = view.findViewById(R.id.rvComments); popupCommentAdapter = new ForumCommentAdapter(this); LinearLayoutManager lm = new LinearLayoutManager(this); rv.setLayoutManager(lm); rv.setAdapter(popupCommentAdapter);
        popupCommentList.clear(); lastPopupCommentVisible = null; isLastPopupCommentsPage = false; fetchPopupComments(p.getId(), popupCommentAdapter);
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                if (dy > 0 && !isFetchingPopupComments && !isLastPopupCommentsPage) { if ((lm.getChildCount() + lm.findFirstVisibleItemPosition()) >= lm.getItemCount()) fetchPopupComments(p.getId(), popupCommentAdapter); }
            }
        });

        currentPopupEditText = view.findViewById(R.id.etComment);
        view.findViewById(R.id.btnSendComment).setOnClickListener(v -> {
            String text = currentPopupEditText.getText().toString().trim(); if (text.isEmpty() || user == null || ContentFilter.containsInappropriateContent(text)) return;
            db.collection("users").document(user.getUid()).get().addOnSuccessListener(udoc -> {
                ForumComment c = new ForumComment(p.getId(), user.getUid(), udoc.getString("username"), udoc.getString("profilePictureUrl"), text);
                if (replyingToComment != null) { c.setParentCommentId(replyingToComment.getId()); c.setParentUsername(replyingToComment.getUsername()); }
                db.collection("forumThreads").document(p.getId()).collection("comments").add(c).addOnSuccessListener(v2 -> { currentPopupEditText.setText(""); replyingToComment = null; refreshPopupComments(); });
            });
        });

        dialog.show();
        View bs = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bs != null) { BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bs); behavior.setState(BottomSheetBehavior.STATE_EXPANDED); behavior.setSkipCollapsed(true); }
    }

    private void refreshPopupComments() { if (activePost == null) return; popupCommentList.clear(); lastPopupCommentVisible = null; isLastPopupCommentsPage = false; fetchPopupComments(activePost.getId(), popupCommentAdapter); }

    private void fetchPopupComments(String postId, ForumCommentAdapter adapter) {
        if (isFetchingPopupComments || isLastPopupCommentsPage) return;
        isFetchingPopupComments = true;
        Query q = db.collection("forumThreads").document(postId).collection("comments").orderBy("timestamp", Query.Direction.ASCENDING).limit(POPUP_COMMENTS_PAGE_SIZE);
        if (lastPopupCommentVisible != null) q = q.startAfter(lastPopupCommentVisible);
        q.get().addOnSuccessListener(val -> {
            if (val != null && !val.isEmpty()) {
                lastPopupCommentVisible = val.getDocuments().get(val.size()-1);
                for (DocumentSnapshot d : val.getDocuments()) { ForumComment c = d.toObject(ForumComment.class); if (c != null) { c.setId(d.getId()); popupCommentList.add(c); } }
                adapter.setComments(new ArrayList<>(popupCommentList));
                if (val.size() < POPUP_COMMENTS_PAGE_SIZE) isLastPopupCommentsPage = true;
            } else isLastPopupCommentsPage = true;
            isFetchingPopupComments = false;
        }).addOnFailureListener(e -> isFetchingPopupComments = false);
    }

    private void showPostOptions(ForumPost p, View v, BottomSheetDialog dialog) {
        PopupMenu popup = new PopupMenu(this, v); FirebaseUser user = mAuth.getCurrentUser();
        if (user != null && p.getUserId().equals(user.getUid())) popup.getMenu().add("Delete");
        popup.getMenu().add("Report");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getTitle().equals("Delete")) showDeleteConfirmation(p, dialog);
            else if (item.getTitle().equals("Report")) showReportDialog("post", p.getId());
            return true;
        });
        popup.show();
    }

    private void showDeleteConfirmation(ForumPost p, BottomSheetDialog dialog) {
        new AlertDialog.Builder(this).setTitle("Delete Post").setMessage("Are you sure?").setPositiveButton("Delete", (d, w) -> archiveAndDeletePost(p, dialog)).setNegativeButton("Cancel", null).show();
    }

    private void archiveAndDeletePost(ForumPost post, BottomSheetDialog dialog) {
        FirebaseUser user = mAuth.getCurrentUser(); if (user == null) return;
        if (post.getBirdImageUrl() != null && !post.getBirdImageUrl().isEmpty()) {
            moveImageToArchive(user.getUid(), post.getId(), post.getBirdImageUrl(), new OnImageArchivedListener() {
                @Override public void onSuccess(String url) { post.setBirdImageUrl(url); handleCommentsArchiveAndDeletion(user.getUid(), post, dialog); }
                @Override public void onFailure(Exception e) { handleCommentsArchiveAndDeletion(user.getUid(), post, dialog); }
            });
        } else handleCommentsArchiveAndDeletion(user.getUid(), post, dialog);
    }

    private void handleCommentsArchiveAndDeletion(String uid, ForumPost post, BottomSheetDialog dialog) {
        db.collection("forumThreads").document(post.getId()).collection("comments").get().addOnSuccessListener(snap -> {
            WriteBatch b = db.batch();
            for (DocumentSnapshot d : snap) {
                Map<String, Object> c = new HashMap<>(); c.put("type", "comment_archived_with_post"); c.put("originalId", d.getId()); c.put("postId", post.getId()); c.put("data", d.getData()); c.put("deletedBy", uid); c.put("deletedAt", FieldValue.serverTimestamp());
                b.set(db.collection("deletedforum_backlog").document(), c); b.delete(d.getReference());
            }
            Map<String, Object> pb = new HashMap<>(); pb.put("type", "post"); pb.put("originalId", post.getId()); pb.put("data", post); pb.put("deletedBy", uid); pb.put("deletedAt", FieldValue.serverTimestamp());
            b.set(db.collection("deletedforum_backlog").document(), pb); b.delete(db.collection("forumThreads").document(post.getId()));
            b.commit().addOnSuccessListener(v -> { if (dialog != null) dialog.dismiss(); loadForumPins(); });
        }).addOnFailureListener(e -> savePostToBacklogAndFirestore(uid, post, dialog));
    }

    private interface OnImageArchivedListener { void onSuccess(String url); void onFailure(Exception e); }

    private void moveImageToArchive(String uid, String pid, String url, OnImageArchivedListener l) {
        FirebaseStorage s = FirebaseStorage.getInstance();
        try {
            StorageReference old = s.getReferenceFromUrl(url);
            StorageReference next = s.getReference().child("archive/forum_post_images/" + uid + "/" + pid + "_" + old.getName());
            old.getBytes(10 * 1024 * 1024).addOnSuccessListener(bytes -> next.putBytes(bytes).addOnSuccessListener(ts -> next.getDownloadUrl().addOnSuccessListener(uri -> old.delete().addOnCompleteListener(t -> l.onSuccess(uri.toString()))).addOnFailureListener(l::onFailure)).addOnFailureListener(l::onFailure)).addOnFailureListener(l::onFailure);
        } catch (Exception e) { l.onFailure(e); }
    }

    private void savePostToBacklogAndFirestore(String uid, ForumPost post, BottomSheetDialog dialog) {
        WriteBatch b = db.batch(); Map<String, Object> m = new HashMap<>();
        m.put("type", "post"); m.put("originalId", post.getId()); m.put("data", post); m.put("deletedBy", uid); m.put("deletedAt", FieldValue.serverTimestamp());
        b.set(db.collection("deletedforum_backlog").document(), m); b.delete(db.collection("forumThreads").document(post.getId()));
        b.commit().addOnSuccessListener(v -> { if (dialog != null) dialog.dismiss(); loadForumPins(); });
    }

    private void showReportDialog(String type, String id) {
        String[] rs = {"Inappropriate Language", "Spam", "Harassment", "Other"};
        new AlertDialog.Builder(this).setTitle("Report").setItems(rs, (d, w) -> { if (rs[w].equals("Other")) showOtherReportDialog(reason -> submitReport(type, id, reason)); else submitReport(type, id, rs[w]); }).show();
    }

    private void submitReport(String type, String id, String r) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser(); if (user == null) return;
        firebaseManager.addReport(new Report(type, id, user.getUid(), r), t -> { if (t.isSuccessful()) Toast.makeText(this, "Reported", Toast.LENGTH_SHORT).show(); });
    }

    private void showOtherReportDialog(OnReasonEnteredListener l) {
        AlertDialog.Builder b = new AlertDialog.Builder(this); b.setTitle("Reason"); final EditText i = new EditText(this); i.setHint("Specify..."); i.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        FrameLayout c = new FrameLayout(this); FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-1, -2); p.leftMargin = p.rightMargin = 40; i.setLayoutParams(p); c.addView(i); b.setView(c);
        b.setPositiveButton("Submit", (d, w) -> { String r = i.getText().toString().trim(); if (!r.isEmpty()) l.onReasonEntered(r); });
        b.setNegativeButton("Cancel", null); b.show();
    }

    private interface OnReasonEnteredListener { void onReasonEntered(String r); }

    @Override
    public void onCommentLikeClick(ForumComment c) {
        FirebaseUser user = mAuth.getCurrentUser(); if (user == null || activePost == null || commentLikeInFlight.contains(c.getId())) return;
        commentLikeInFlight.add(c.getId());

        String uid = user.getUid(); if (c.getLikedBy() == null) c.setLikedBy(new HashMap<>()); boolean liked = c.getLikedBy().containsKey(uid);
        int count = c.getLikeCount();
        if (liked) { c.setLikeCount(Math.max(0, count-1)); c.getLikedBy().remove(uid); }
        else { c.setLikeCount(count+1); c.getLikedBy().put(uid, true); }
        if (popupCommentAdapter != null) popupCommentAdapter.notifyDataSetChanged();

        db.collection("forumThreads").document(activePost.getId()).collection("comments").document(c.getId())
                .update("likedBy." + uid, liked ? FieldValue.delete() : true)
                .addOnCompleteListener(t -> {
                    commentLikeInFlight.remove(c.getId());
                    if (!t.isSuccessful()) {
                        c.setLikeCount(count); if (liked) c.getLikedBy().put(uid, true); else c.getLikedBy().remove(uid);
                        if (popupCommentAdapter != null) popupCommentAdapter.notifyDataSetChanged();
                    }
                });
    }

    @Override public void onCommentReplyClick(ForumComment c) { replyingToComment = c; if (currentPopupEditText != null) { currentPopupEditText.setHint("Replying to " + c.getUsername() + "..."); currentPopupEditText.requestFocus(); } }
    @Override public void onCommentOptionsClick(ForumComment c, View v) { PopupMenu p = new PopupMenu(this, v); FirebaseUser user = mAuth.getCurrentUser(); if (user != null && c.getUserId().equals(user.getUid())) p.getMenu().add("Delete"); p.getMenu().add("Report"); p.setOnMenuItemClickListener(item -> { if (item.getTitle().equals("Delete")) showCommentDeleteConfirmation(c); else if (item.getTitle().equals("Report")) showReportDialog("comment", c.getId()); return true; }); p.show(); }

    private void showCommentDeleteConfirmation(ForumComment c) {
        new AlertDialog.Builder(this).setTitle("Delete Comment").setMessage("Are you sure?").setPositiveButton("Delete", (d, w) -> deleteCommentAndReplies(c)).setNegativeButton("Cancel", null).show();
    }

    private void deleteCommentAndReplies(ForumComment c) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser(); if (user == null || activePost == null) return;
        if (c.getParentCommentId() == null) {
            db.collection("forumThreads").document(activePost.getId()).collection("comments").whereEqualTo("parentCommentId", c.getId()).get().addOnSuccessListener(snap -> {
                WriteBatch b = db.batch();
                for (DocumentSnapshot d : snap) { backlogByUserId(b, user.getUid(), "comment_reply", d.getId(), d.getData()); b.delete(d.getReference()); }
                backlogByUserId(b, user.getUid(), "comment", c.getId(), c); b.delete(db.collection("forumThreads").document(activePost.getId()).collection("comments").document(c.getId()));
                b.commit().addOnSuccessListener(v -> refreshPopupComments());
            });
        } else {
            WriteBatch b = db.batch(); Map<String, Object> m = new HashMap<>(); m.put("type", "reply"); m.put("originalId", c.getId()); m.put("data", c); m.put("deletedBy", user.getUid()); m.put("deletedAt", FieldValue.serverTimestamp());
            b.set(db.collection("deletedforum_backlog").document(), m); b.delete(db.collection("forumThreads").document(activePost.getId()).collection("comments").document(c.getId()));
            b.commit().addOnSuccessListener(v -> refreshPopupComments());
        }
    }

    private void backlogByUserId(WriteBatch b, String uid, String type, String oid, Object data) {
        Map<String, Object> m = new HashMap<>(); m.put("type", type); m.put("originalId", oid); m.put("data", data); m.put("deletedBy", uid); m.put("deletedAt", FieldValue.serverTimestamp());
        b.set(db.collection("deletedforum_backlog").document(), m);
    }

    @Override public void onUserClick(String uid) {
        if (isNavigating) return;
        isNavigating = true;
        startActivity(new Intent(this, UserSocialProfileActivity.class).putExtra(UserSocialProfileActivity.EXTRA_USER_ID, uid));
    }

    private void loadUserBirdSightings(int gen) {
        db.collection("userBirdSightings").limit(500).get(Source.CACHE).addOnSuccessListener(snap -> {
            if (snap != null && !snap.isEmpty()) processSightings(snap, true, gen); else onCollectionFinished(gen);
            fetchUserBirdSightingsFromServer(gen);
        }).addOnFailureListener(e -> { onCollectionFinished(gen); fetchUserBirdSightingsFromServer(gen); });
    }

    private void fetchUserBirdSightingsFromServer(int gen) {
        db.collection("userBirdSightings").limit(500).get(Source.SERVER).addOnSuccessListener(snap -> processSightings(snap, true, gen)).addOnFailureListener(e -> onCollectionFinished(gen));
    }

    private void loadEbirdApiSightings(int gen) {
        db.collection("eBirdApiSightings").limit(1000).get(Source.CACHE).addOnSuccessListener(snap -> {
            if (snap != null && !snap.isEmpty()) processSightings(snap, false, gen); else onCollectionFinished(gen);
            fetchEbirdApiSightingsFromServer(gen);
        }).addOnFailureListener(e -> { onCollectionFinished(gen); fetchEbirdApiSightingsFromServer(gen); });
    }

    private void fetchEbirdApiSightingsFromServer(int gen) {
        db.collection("eBirdApiSightings").limit(1000).get(Source.SERVER).addOnSuccessListener(snap -> processSightings(snap, false, gen)).addOnFailureListener(e -> onCollectionFinished(gen));
    }

    private void processSightings(com.google.firebase.firestore.QuerySnapshot snap, boolean user, int gen) {
        if (fetchGeneration != gen) return;
        List<WeightedLatLng> hl = user ? userHeatPoints : eBirdHeatPoints; List<HotspotSighting> hsl = user ? userHotspotSightings : eBirdHotspotSightings;
        hl.clear(); hsl.clear();
        for (DocumentSnapshot d : snap.getDocuments()) {
            Double lat = getAnyDouble(d, "location.latitude", "lastSeenLatitudeGeorgia", "latitude", "lat"), lng = getAnyDouble(d, "location.longitude", "lastSeenLongitudeGeorgia", "longitude", "lng");
            if (lat == null || lng == null || shouldBeFiltered(lat, lng, getAnyTimeMillis(d, user ? "timestamp" : "observationDate"))) continue;
            hl.add(new WeightedLatLng(new LatLng(lat, lng), user ? 1.8 : 1.0)); hsl.add(new HotspotSighting(lat, lng, extractBirdName(d), user));
        }
        rebuildHotspotBuckets(); onCollectionFinished(gen);
    }

    private void onCollectionFinished(int gen) {
        if (fetchGeneration != gen) return;
        pendingLoads--; if (pendingLoads <= 0) renderHeatmaps();
    }

    private void rebuildHotspotBuckets() {
        hotspotBuckets.clear();
        for (HotspotSighting s : userHotspotSightings) addToHotspotBucket(s.lat, s.lng, s.birdName, true);
        for (HotspotSighting s : eBirdHotspotSightings) addToHotspotBucket(s.lat, s.lng, s.birdName, false);
    }

    private void renderHeatmaps() {
        if (googleMap == null) return;
        if (eBirdOverlay != null) { eBirdOverlay.remove(); eBirdOverlay = null; }
        if (userOverlay != null) { userOverlay.remove(); userOverlay = null; }
        clearHotspotCircles();

        if (!eBirdHeatPoints.isEmpty()) eBirdOverlay = googleMap.addTileOverlay(new TileOverlayOptions().tileProvider(new HeatmapTileProvider.Builder().weightedData(eBirdHeatPoints).radius(45).opacity(0.65).gradient(EBIRD_GRADIENT).build()).zIndex(1f));
        if (!userHeatPoints.isEmpty()) userOverlay = googleMap.addTileOverlay(new TileOverlayOptions().tileProvider(new HeatmapTileProvider.Builder().weightedData(userHeatPoints).radius(45).opacity(0.70).gradient(USER_GRADIENT).build()).zIndex(2f));
        renderHotspotCircles();

        if (userHeatPoints.isEmpty() && eBirdHeatPoints.isEmpty()) tvMapSubtitle.setText("No recent sightings found.");
        else tvMapSubtitle.setText("Heatmap: " + userHeatPoints.size() + " unverified, " + eBirdHeatPoints.size() + " verified");
    }

    private void renderHotspotCircles() {
        for (HotspotBucket b : hotspotBuckets.values()) {
            if (b.pointCount == 0) continue;
            Circle c = googleMap.addCircle(new CircleOptions().center(new LatLng(b.getCenterLat(), b.getCenterLng())).radius(HOTSPOT_CIRCLE_RADIUS_METERS).strokeWidth(2f).strokeColor(Color.argb(110, 255, 255, 255)).fillColor(Color.argb(35, 255, 255, 255)).clickable(true).zIndex(3f));
            hotspotCircles.add(c); circleIdToBucket.put(c.getId(), b);
        }
    }

    private void clearHotspotCircles() { for (Circle c : hotspotCircles) c.remove(); hotspotCircles.clear(); circleIdToBucket.clear(); }

    @Override public void onCircleClick(@NonNull Circle c) { HotspotBucket b = circleIdToBucket.get(c.getId()); if (b != null) showBirdListBottomSheet(b); }

    private void showBirdListBottomSheet(@NonNull HotspotBucket b) {
        BottomSheetDialog dialog = new BottomSheetDialog(this); dialog.setContentView(R.layout.bottom_sheet_heatmap_birds);
        View bs = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bs != null) { bs.setBackgroundColor(Color.TRANSPARENT); ViewGroup.LayoutParams p = bs.getLayoutParams(); p.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.58f); bs.setLayoutParams(p); BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bs); behavior.setState(BottomSheetBehavior.STATE_EXPANDED); }
        ((TextView) dialog.findViewById(R.id.tvSheetSummary)).setText("Unverified: " + b.userCount + "  •  Verified: " + b.eBirdCount);
        LinearLayout container = dialog.findViewById(R.id.birdListContainer); container.removeAllViews();
        List<String> names = new ArrayList<>(b.birdCounts.keySet()); Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        LayoutInflater inf = LayoutInflater.from(this);
        for (String n : names) {
            View row = inf.inflate(R.layout.item_heatmap_bird_placeholder, container, false);
            ((TextView) row.findViewById(R.id.tvBirdName)).setText(n);
            ((TextView) row.findViewById(R.id.tvBirdCount)).setText(b.birdCounts.get(n) + " sightings");
            container.addView(row);
        }
        dialog.show();
    }

    private void addToHotspotBucket(double lat, double lng, String name, boolean user) {
        double blat = Math.round(lat / HOTSPOT_BUCKET_SIZE) * HOTSPOT_BUCKET_SIZE, blng = Math.round(lng / HOTSPOT_BUCKET_SIZE) * HOTSPOT_BUCKET_SIZE;
        String key = String.format(Locale.US, "%.4f,%.4f", blat, blng);
        HotspotBucket b = hotspotBuckets.get(key); if (b == null) { b = new HotspotBucket(); hotspotBuckets.put(key, b); }
        b.add(lat, lng, name, user);
    }

    private String extractBirdName(DocumentSnapshot d) { String n = getAnyString(d, "commonName", "comName", "birdName", "species", "scientificName", "sciName", "speciesCode", "birdId"); return (n == null || n.trim().isEmpty()) ? "Unknown bird" : n.trim().replace("_", " ").replace("-", " "); }

    private boolean shouldBeFiltered(double lat, double lng, Long time) {
        if (time != null && (System.currentTimeMillis() - time > SIGHTING_RECENCY_MS)) return true;
        if (currentVisibleBounds != null) return !currentVisibleBounds.contains(new LatLng(lat, lng));
        return false;
    }

    private Double getAnyDouble(DocumentSnapshot d, String... paths) { for (String p : paths) { Object v = d.get(p); if (v instanceof Number) return ((Number) v).doubleValue(); } return null; }
    private String getAnyString(DocumentSnapshot d, String... paths) { for (String p : paths) { Object v = d.get(p); if (v != null && !String.valueOf(v).trim().isEmpty()) return String.valueOf(v).trim(); } return null; }
    private Long getAnyTimeMillis(DocumentSnapshot d, String... paths) { for (String p : paths) { Object v = d.get(p); if (v instanceof Date) return ((Date) v).getTime(); if (v instanceof Number) return ((Number) v).longValue(); } return null; }

    private static class HotspotSighting { final double lat, lng; final String birdName; final boolean isUserSighting; HotspotSighting(double lat, double lng, String n, boolean u) { this.lat = lat; this.lng = lng; this.birdName = n; this.isUserSighting = u; } }
    private static class HotspotBucket { double latSum=0, lngSum=0; int pointCount=0, userCount=0, eBirdCount=0; final Map<String, Integer> birdCounts = new LinkedHashMap<>(); void add(double lat, double lng, String n, boolean u) { latSum+=lat; lngSum+=lng; pointCount++; if (u) userCount++; else eBirdCount++; String cn = (n==null || n.trim().isEmpty()) ? "Unknown bird" : n.trim(); Integer c = birdCounts.get(cn); birdCounts.put(cn, (c==null?0:c)+1); } double getCenterLat() { return pointCount==0?0:latSum/pointCount; } double getCenterLng() { return pointCount==0?0:lngSum/pointCount; } }
}
