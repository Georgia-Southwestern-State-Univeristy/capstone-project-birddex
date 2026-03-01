package com.birddex.app;

import android.Manifest;
import android.content.Intent;
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
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.maps.android.heatmaps.Gradient;
import com.google.maps.android.heatmaps.HeatmapTileProvider;
import com.google.maps.android.heatmaps.WeightedLatLng;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class NearbyHeatmapActivity extends AppCompatActivity
        implements OnMapReadyCallback, GoogleMap.OnCircleClickListener, GoogleMap.OnMarkerClickListener, ForumCommentAdapter.OnCommentInteractionListener {

    private static final String TAG = "NearbyHeatmapActivity";
    public static final String EXTRA_CENTER_LAT = "extra_center_lat";
    public static final String EXTRA_CENTER_LNG = "extra_center_lng";

    private static final double DEFAULT_LAT = 32.6781;
    private static final double DEFAULT_LNG = -83.2220;
    private static final float DEFAULT_ZOOM = 7f;
    private static final float NEARBY_ZOOM = 10f;

    private static final double SEARCH_RADIUS_METERS = 50000d;
    private static final long SIGHTING_RECENCY_MS = 72L * 60 * 60 * 1000;

    private static final double HOTSPOT_BUCKET_SIZE = 0.02d;
    private static final double HOTSPOT_CIRCLE_RADIUS_METERS = 900d;

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

    private final Map<String, HotspotBucket> hotspotBuckets = new LinkedHashMap<>();
    private final List<Circle> hotspotCircles = new ArrayList<>();
    private final Map<String, HotspotBucket> circleIdToBucket = new HashMap<>();
    
    private final List<Marker> forumMarkers = new ArrayList<>();

    private double centerLat = Double.NaN;
    private double centerLng = Double.NaN;

    private int pendingLoads = 0;
    
    private ForumComment replyingToComment = null;
    private ListenerRegistration popupCommentsListener;
    private EditText currentPopupEditText;
    private ForumPost activePost;
    private FirebaseManager firebaseManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nearby_heatmap);

        db = FirebaseFirestore.getInstance();
        firebaseManager = new FirebaseManager(this);

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
        googleMap.setOnCircleClickListener(this);
        googleMap.setOnMarkerClickListener(this);

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
        loadForumPins();
    }

    private void fetchHeatmapData() {
        pendingLoads = 2;
        userHeatPoints.clear();
        eBirdHeatPoints.clear();
        hotspotBuckets.clear();
        clearHotspotCircles();

        tvMapSubtitle.setText(R.string.loading_heatmap);

        loadUserBirdSightings();
        loadEbirdApiSightings();
    }

    private void loadForumPins() {
        db.collection("forumThreads")
                .whereEqualTo("showLocation", true)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    clearForumMarkers();
                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        ForumPost post = doc.toObject(ForumPost.class);
                        if (post != null && post.getLatitude() != null && post.getLongitude() != null) {
                            post.setId(doc.getId());
                            addPinToMap(post);
                        }
                    }
                })
                .addOnFailureListener(e -> Log.e(TAG, "Error loading forum pins", e));
    }

    private void addPinToMap(ForumPost post) {
        LatLng pos = new LatLng(post.getLatitude(), post.getLongitude());
        
        StringBuilder status = new StringBuilder();
        if (post.isSpotted()) status.append("Spotted ");
        if (post.isHunted()) status.append("Hunted ");
        
        String title = post.getUsername() + (status.length() > 0 ? " (" + status.toString().trim() + ")" : "");

        BitmapDescriptor pinIcon;
        if (post.isSpotted() && post.isHunted()) {
            pinIcon = createDualColorPin();
        } else if (post.isHunted()) {
            pinIcon = createColoredPin(Color.BLACK);
        } else if (post.isSpotted()) {
            pinIcon = createColoredPin(Color.rgb(255, 165, 0)); // Orange
        } else {
            pinIcon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE);
        }

        MarkerOptions options = new MarkerOptions()
                .position(pos)
                .title(title)
                .snippet(post.getMessage())
                .icon(pinIcon);

        Marker marker = googleMap.addMarker(options);
        if (marker != null) {
            marker.setTag(post);
            forumMarkers.add(marker);
        }
    }

    private BitmapDescriptor createColoredPin(int color) {
        int size = 80;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(color);
        paint.setAntiAlias(true);
        
        canvas.drawCircle(size / 2f, size / 3f, size / 3f, paint);
        canvas.drawRect(size / 2f - 4, size / 2f, size / 2f + 4, size * 0.9f, paint);
        
        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }

    private BitmapDescriptor createDualColorPin() {
        int size = 80;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setAntiAlias(true);

        paint.setColor(Color.BLUE);
        canvas.drawArc(size / 6f, 0, size * 5/6f, size * 2/3f, 90, 180, true, paint);
        
        paint.setColor(Color.BLACK);
        canvas.drawArc(size / 6f, 0, size * 5/6f, size * 2/3f, 270, 180, true, paint);

        paint.setColor(Color.BLUE);
        canvas.drawRect(size / 2f - 4, size / 2f, size / 2f, size * 0.9f, paint);
        paint.setColor(Color.BLACK);
        canvas.drawRect(size / 2f, size / 2f, size / 2f + 4, size * 0.9f, paint);

        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }

    private void clearForumMarkers() {
        for (Marker m : forumMarkers) {
            m.remove();
        }
        forumMarkers.clear();
    }

    @Override
    public boolean onMarkerClick(@NonNull Marker marker) {
        Object tag = marker.getTag();
        if (tag instanceof ForumPost) {
            showPostInBottomSheet((ForumPost) tag);
            return true;
        }
        return false;
    }

    private void showPostInBottomSheet(ForumPost post) {
        activePost = post;
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.bottom_sheet_post_view, null);
        dialog.setContentView(view);

        // Bind Post Data
        View postContent = view.findViewById(R.id.postContent);
        TextView tvUsername = postContent.findViewById(R.id.tvPostUsername);
        TextView tvTimestamp = postContent.findViewById(R.id.tvPostTimestamp);
        TextView tvMessage = postContent.findViewById(R.id.tvPostMessage);
        ImageView ivPfp = postContent.findViewById(R.id.ivPostUserProfilePicture);
        ImageView ivBird = postContent.findViewById(R.id.ivPostBirdImage);
        View cvImage = postContent.findViewById(R.id.cvPostImage);
        TextView tvLikes = postContent.findViewById(R.id.tvLikeCount);
        TextView tvComments = postContent.findViewById(R.id.tvCommentCount);
        TextView tvViews = postContent.findViewById(R.id.tvViewCount);
        View btnPostOptions = postContent.findViewById(R.id.btnPostOptions);
        
        TextView tvSpottedBadge = postContent.findViewById(R.id.tvSpottedBadge);
        TextView tvHuntedBadge = postContent.findViewById(R.id.tvHuntedBadge);

        tvUsername.setText(post.getUsername());
        tvMessage.setText(post.isEdited() ? post.getMessage() + " (edited)" : post.getMessage());
        tvLikes.setText(String.valueOf(post.getLikeCount()));
        tvComments.setText(String.valueOf(post.getCommentCount()));
        tvViews.setText(post.getViewCount() + " views");
        
        if (tvSpottedBadge != null) tvSpottedBadge.setVisibility(post.isSpotted() ? View.VISIBLE : View.GONE);
        if (tvHuntedBadge != null) tvHuntedBadge.setVisibility(post.isHunted() ? View.VISIBLE : View.GONE);

        if (post.getTimestamp() != null) {
            tvTimestamp.setText(DateUtils.getRelativeTimeSpanString(post.getTimestamp().toDate().getTime()));
        }

        Glide.with(this).load(post.getUserProfilePictureUrl()).placeholder(R.drawable.ic_profile).into(ivPfp);
        
        if (post.getBirdImageUrl() != null && !post.getBirdImageUrl().isEmpty()) {
            cvImage.setVisibility(View.VISIBLE);
            Glide.with(this).load(post.getBirdImageUrl()).into(ivBird);
        } else {
            cvImage.setVisibility(View.GONE);
        }

        // Navigate to forum post on click
        postContent.setOnClickListener(v -> {
            Intent intent = new Intent(this, PostDetailActivity.class);
            intent.putExtra(PostDetailActivity.EXTRA_POST_ID, post.getId());
            startActivity(intent);
            dialog.dismiss();
        });

        btnPostOptions.setOnClickListener(v -> showPostOptions(post, v));

        // Setup Comments
        RecyclerView rvComments = view.findViewById(R.id.rvComments);
        ForumCommentAdapter adapter = new ForumCommentAdapter(this);
        rvComments.setLayoutManager(new LinearLayoutManager(this));
        rvComments.setAdapter(adapter);

        if (popupCommentsListener != null) popupCommentsListener.remove();
        popupCommentsListener = db.collection("forumThreads").document(post.getId()).collection("comments")
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .addSnapshotListener((value, error) -> {
                    if (error != null) return;
                    if (value != null) {
                        List<ForumComment> comments = new ArrayList<>();
                        for (DocumentSnapshot doc : value.getDocuments()) {
                            ForumComment comment = doc.toObject(ForumComment.class);
                            if (comment != null) {
                                comment.setId(doc.getId());
                                comments.add(comment);
                            }
                        }
                        adapter.setComments(comments);
                    }
                });

        // Setup Current User PFP in comment input
        ImageView ivCurrentUser = view.findViewById(R.id.ivCurrentUserPfp);
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            db.collection("users").document(user.getUid()).get().addOnSuccessListener(doc -> {
                String pfp = doc.getString("profilePictureUrl");
                Glide.with(this).load(pfp).placeholder(R.drawable.ic_profile).into(ivCurrentUser);
            });
        }

        // Setup Sending
        currentPopupEditText = view.findViewById(R.id.etComment);
        View btnSend = view.findViewById(R.id.btnSendComment);
        btnSend.setOnClickListener(v -> {
            String text = currentPopupEditText.getText().toString().trim();
            if (text.isEmpty()) return;
            if (user == null) return;

            if (ContentFilter.containsInappropriateContent(text)) {
                Toast.makeText(this, "Comment contains inappropriate language.", Toast.LENGTH_SHORT).show();
                return;
            }

            db.collection("users").document(user.getUid()).get().addOnSuccessListener(userDoc -> {
                String name = userDoc.getString("username");
                String pfp = userDoc.getString("profilePictureUrl");
                ForumComment comment = new ForumComment(post.getId(), user.getUid(), name, pfp, text);
                if (replyingToComment != null) {
                    comment.setParentCommentId(replyingToComment.getId());
                    comment.setParentUsername(replyingToComment.getUsername());
                }
                db.collection("forumThreads").document(post.getId()).collection("comments").add(comment)
                        .addOnSuccessListener(ref -> {
                            currentPopupEditText.setText("");
                            currentPopupEditText.setHint("Write a comment...");
                            replyingToComment = null;
                            db.collection("forumThreads").document(post.getId()).update("commentCount", FieldValue.increment(1));
                        });
            });
        });

        dialog.show();
        
        View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) {
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottomSheet);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
        }
    }

    private void showPostOptions(ForumPost post, View view) {
        PopupMenu popup = new PopupMenu(this, view);
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        
        if (user != null && post.getUserId().equals(user.getUid())) {
            popup.getMenu().add("Delete");
        }
        popup.getMenu().add("Report");

        popup.setOnMenuItemClickListener(item -> {
            if (item.getTitle().equals("Delete")) {
                showDeleteConfirmation(post);
            } else if (item.getTitle().equals("Report")) {
                showReportDialog("post", post.getId());
            }
            return true;
        });
        popup.show();
    }

    private void showDeleteConfirmation(ForumPost post) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Post")
                .setMessage("Are you sure you want to delete this post?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    firebaseManager.deleteForumPost(post.getId(), task -> {
                        if (task.isSuccessful()) {
                            Toast.makeText(this, "Post deleted", Toast.LENGTH_SHORT).show();
                            loadForumPins(); // Refresh
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showReportDialog(String type, String targetId) {
        String[] reasons = {"Inappropriate Language", "Spam", "Harassment", "Other"};
        new AlertDialog.Builder(this)
                .setTitle("Report " + type)
                .setItems(reasons, (dialog, which) -> {
                    String selectedReason = reasons[which];
                    if (selectedReason.equals("Other")) {
                        showOtherReportDialog(reason -> submitReport(type, targetId, reason));
                    } else {
                        submitReport(type, targetId, selectedReason);
                    }
                })
                .show();
    }

    private void submitReport(String type, String targetId, String reason) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        Report report = new Report(type, targetId, user.getUid(), reason);
        firebaseManager.addReport(report, task -> {
            if (task.isSuccessful()) {
                Toast.makeText(this, "Report submitted.", Toast.LENGTH_LONG).show();
            } else {
                String error = task.getException() != null ? task.getException().getMessage() : "Unknown error";
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showOtherReportDialog(OnReasonEnteredListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Report Reason");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setHint("Please specify the reason (max 200 chars)...");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        input.setSingleLine(false);
        input.setHorizontallyScrolling(false);
        input.setLines(5);

        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = params.rightMargin = 40;
        input.setLayoutParams(params);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("Submit", (dialog, which) -> {
            String reason = input.getText().toString().trim();
            if (reason.isEmpty()) {
                Toast.makeText(this, "Please enter a reason", Toast.LENGTH_SHORT).show();
                return;
            }
            if (ContentFilter.containsInappropriateContent(reason)) {
                Toast.makeText(this, "Inappropriate language detected in your report.", Toast.LENGTH_SHORT).show();
                return;
            }
            listener.onReasonEntered(reason);
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private interface OnReasonEnteredListener {
        void onReasonEntered(String reason);
    }

    @Override
    public void onCommentLikeClick(ForumComment comment) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || activePost == null) return;

        String userId = user.getUid();
        boolean liked = comment.getLikedBy() != null && comment.getLikedBy().containsKey(userId);

        if (liked) {
            db.collection("forumThreads").document(activePost.getId()).collection("comments").document(comment.getId())
                    .update("likeCount", FieldValue.increment(-1),
                            "likedBy." + userId, FieldValue.delete());
        } else {
            db.collection("forumThreads").document(activePost.getId()).collection("comments").document(comment.getId())
                    .update("likeCount", FieldValue.increment(1),
                            "likedBy." + userId, true);
        }
    }

    @Override
    public void onCommentReplyClick(ForumComment comment) {
        replyingToComment = comment;
        if (currentPopupEditText != null) {
            currentPopupEditText.setHint("Replying to " + comment.getUsername() + "...");
            currentPopupEditText.requestFocus();
        }
    }

    @Override
    public void onCommentOptionsClick(ForumComment comment, View view) {
        PopupMenu popup = new PopupMenu(this, view);
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        
        boolean isCommentAuthor = user != null && comment.getUserId().equals(user.getUid());
        boolean isPostAuthor = user != null && activePost != null && activePost.getUserId().equals(user.getUid());

        if (isCommentAuthor || isPostAuthor) {
            popup.getMenu().add("Delete");
        }
        popup.getMenu().add("Report");

        popup.setOnMenuItemClickListener(item -> {
            if (item.getTitle().equals("Delete")) {
                db.collection("forumThreads").document(activePost.getId()).collection("comments").document(comment.getId())
                        .delete().addOnSuccessListener(aVoid -> {
                            Toast.makeText(this, "Comment deleted", Toast.LENGTH_SHORT).show();
                            db.collection("forumThreads").document(activePost.getId()).update("commentCount", FieldValue.increment(-1));
                        });
            } else if (item.getTitle().equals("Report")) {
                showReportDialog("comment", comment.getId());
            }
            return true;
        });
        popup.show();
    }

    @Override
    public void onUserClick(String userId) {
        Intent intent = new Intent(this, UserSocialProfileActivity.class);
        intent.putExtra(UserSocialProfileActivity.EXTRA_USER_ID, userId);
        startActivity(intent);
    }

    private void loadUserBirdSightings() {
        db.collection("userBirdSightings")
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        Double lat = getAnyDouble(doc, "location.latitude", "lastSeenLatitudeGeorgia", "latitude", "lat");
                        Double lng = getAnyDouble(doc, "location.longitude", "lastSeenLongitudeGeorgia", "longitude", "lng");
                        Long timeMillis = getAnyTimeMillis(doc, "timestamp");

                        if (lat == null || lng == null) continue;
                        if (shouldBeFiltered(lat, lng, timeMillis)) continue;

                        userHeatPoints.add(new WeightedLatLng(new LatLng(lat, lng), 1.8));

                        String birdName = extractBirdName(doc);
                        addToHotspotBucket(lat, lng, birdName, true);
                    }
                    onCollectionFinished();
                })
                .addOnFailureListener(e -> onCollectionFinished());
    }

    private void loadEbirdApiSightings() {
        db.collection("eBirdApiSightings")
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        Double lat = getAnyDouble(doc, "location.latitude", "lastSeenLatitudeGeorgia", "latitude", "lat");
                        Double lng = getAnyDouble(doc, "location.longitude", "lastSeenLongitudeGeorgia", "longitude", "lng");
                        Long timeMillis = getAnyTimeMillis(doc, "observationDate", "timestamp");

                        if (lat == null || lng == null) continue;
                        if (shouldBeFiltered(lat, lng, timeMillis)) continue;

                        eBirdHeatPoints.add(new WeightedLatLng(new LatLng(lat, lng), 1.0));

                        String birdName = extractBirdName(doc);
                        addToHotspotBucket(lat, lng, birdName, false);
                    }
                    onCollectionFinished();
                })
                .addOnFailureListener(e -> onCollectionFinished());
    }

    private void onCollectionFinished() {
        pendingLoads--;
        if (pendingLoads <= 0) renderHeatmaps();
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

        clearHotspotCircles();

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

        renderHotspotCircles();

        if (userHeatPoints.isEmpty() && eBirdHeatPoints.isEmpty()) {
            tvMapSubtitle.setText(hasNearbyCenter()
                    ? R.string.no_nearby_sightings
                    : R.string.no_recent_sightings);
        } else {
            String scopeText = getString(
                    hasNearbyCenter() ? R.string.heatmap_scope_72h_50km : R.string.heatmap_scope_72h
            );

            tvMapSubtitle.setText(getString(
                    R.string.heatmap_stats,
                    scopeText,
                    userHeatPoints.size(),
                    eBirdHeatPoints.size()
            ));
        }
    }

    private void renderHotspotCircles() {
        if (googleMap == null) return;

        for (HotspotBucket bucket : hotspotBuckets.values()) {
            if (bucket.pointCount == 0) continue;

            LatLng center = new LatLng(bucket.getCenterLat(), bucket.getCenterLng());

            Circle circle = googleMap.addCircle(
                    new CircleOptions()
                            .center(center)
                            .radius(HOTSPOT_CIRCLE_RADIUS_METERS)
                            .strokeWidth(2f)
                            .strokeColor(Color.argb(110, 255, 255, 255))
                            .fillColor(Color.argb(35, 255, 255, 255))
                            .clickable(true)
                            .zIndex(3f)
            );

            hotspotCircles.add(circle);
            circleIdToBucket.put(circle.getId(), bucket);
        }
    }

    private void clearHotspotCircles() {
        for (Circle circle : hotspotCircles) {
            circle.remove();
        }
        hotspotCircles.clear();
        circleIdToBucket.clear();
    }

    @Override
    public void onCircleClick(@NonNull Circle circle) {
        HotspotBucket bucket = circleIdToBucket.get(circle.getId());
        if (bucket == null) return;

        showBirdListBottomSheet(bucket);
    }

    private void showBirdListBottomSheet(@NonNull HotspotBucket bucket) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(R.layout.bottom_sheet_heatmap_birds);

        View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) {
            bottomSheet.setBackgroundColor(Color.TRANSPARENT);

            ViewGroup.LayoutParams params = bottomSheet.getLayoutParams();
            params.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.58f);
            bottomSheet.setLayoutParams(params);

            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottomSheet);
            behavior.setDraggable(true);
            behavior.setHideable(true);
            behavior.setSkipCollapsed(false);
            behavior.setPeekHeight(params.height);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }

        TextView tvTitle = dialog.findViewById(R.id.tvSheetTitle);
        TextView tvSummary = dialog.findViewById(R.id.tvSheetSummary);
        TextView tvEmpty = dialog.findViewById(R.id.tvEmptyBirds);
        LinearLayout birdListContainer = dialog.findViewById(R.id.birdListContainer);

        if (tvTitle != null) {
            tvTitle.setText("Birds in this hotspot");
        }

        if (tvSummary != null) {
            tvSummary.setText(
                    "Unverified Sightings: " + bucket.userCount +
                            "  •  Verified Sightings: " + bucket.eBirdCount
            );
        }

        if (birdListContainer != null) {
            birdListContainer.removeAllViews();

            List<String> birdNames = new ArrayList<>(bucket.birdCounts.keySet());
            Collections.sort(birdNames, String.CASE_INSENSITIVE_ORDER);

            if (birdNames.isEmpty()) {
                if (tvEmpty != null) {
                    tvEmpty.setVisibility(View.VISIBLE);
                }
            } else {
                if (tvEmpty != null) {
                    tvEmpty.setVisibility(View.GONE);
                }

                LayoutInflater inflater = LayoutInflater.from(this);

                for (String birdName : birdNames) {
                    View row = inflater.inflate(R.layout.item_heatmap_bird_placeholder, birdListContainer, false);

                    ImageView ivBird = row.findViewById(R.id.ivBirdPlaceholder);
                    TextView tvBirdName = row.findViewById(R.id.tvBirdName);
                    TextView tvBirdCount = row.findViewById(R.id.tvBirdCount);

                    Integer count = bucket.birdCounts.get(birdName);
                    if (count == null) count = 1;

                    ivBird.setImageResource(android.R.drawable.ic_menu_gallery);
                    ivBird.setColorFilter(null);

                    tvBirdName.setText(birdName);

                    if (count == 1) {
                        tvBirdCount.setText("1 sighting in this hotspot");
                    } else {
                        tvBirdCount.setText(count + " sightings in this hotspot");
                    }

                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    lp.bottomMargin = dp(10);
                    row.setLayoutParams(lp);

                    birdListContainer.addView(row);
                }
            }
        }

        dialog.show();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
    }

    private void addToHotspotBucket(double lat, double lng, String birdName, boolean isUserSighting) {
        String key = buildBucketKey(lat, lng);

        HotspotBucket bucket = hotspotBuckets.get(key);
        if (bucket == null) {
            bucket = new HotspotBucket();
            hotspotBuckets.put(key, bucket);
        }

        bucket.add(lat, lng, birdName, isUserSighting);
    }

    private String buildBucketKey(double lat, double lng) {
        double bucketLat = Math.round(lat / HOTSPOT_BUCKET_SIZE) * HOTSPOT_BUCKET_SIZE;
        double bucketLng = Math.round(lng / HOTSPOT_BUCKET_SIZE) * HOTSPOT_BUCKET_SIZE;
        return String.format(Locale.US, "%.4f,%.4f", bucketLat, bucketLng);
    }

    private String extractBirdName(DocumentSnapshot doc) {
        String name = getAnyString(doc,
                "commonName",
                "comName",
                "birdName",
                "species",
                "scientificName",
                "sciName",
                "speciesCode",
                "birdId");

        if (name == null || name.trim().isEmpty()) {
            return "Unknown bird";
        }

        name = name.trim();
        name = name.replace("_", " ").replace("-", " ");

        return name;
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

    private String getAnyString(DocumentSnapshot doc, String... fieldPaths) {
        for (String fieldPath : fieldPaths) {
            Object value = doc.get(fieldPath);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty()) {
                    return text;
                }
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

    private static class HotspotBucket {
        double latSum = 0d;
        double lngSum = 0d;
        int pointCount = 0;
        int userCount = 0;
        int eBirdCount = 0;

        final Map<String, Integer> birdCounts = new LinkedHashMap<>();

        void add(double lat, double lng, String birdName, boolean isUserSighting) {
            latSum += lat;
            lngSum += lng;
            pointCount++;

            if (isUserSighting) {
                userCount++;
            } else {
                eBirdCount++;
            }

            String cleanName = (birdName == null || birdName.trim().isEmpty())
                    ? "Unknown bird"
                    : birdName.trim();

            Integer currentCount = birdCounts.get(cleanName);
            if (currentCount == null) currentCount = 0;
            birdCounts.put(cleanName, currentCount + 1);
        }

        double getCenterLat() {
            return pointCount == 0 ? 0d : latSum / pointCount;
        }

        double getCenterLng() {
            return pointCount == 0 ? 0d : lngSum / pointCount;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (popupCommentsListener != null) popupCommentsListener.remove();
    }
}
