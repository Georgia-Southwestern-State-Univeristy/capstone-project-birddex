package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import android.util.TypedValue;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * ChangeCardImageActivity lets the user browse and select a different photo
 * for a bird species in their collection.
 * Fixed Race Conditions:
 * 1. Async Fetch Desync: Added fetchGeneration counter.
 */
/**
 * ChangeCardImageActivity: Activity class for one BirdDex screen. It owns screen setup, user actions, and navigation for this part of the app.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class ChangeCardImageActivity extends AppCompatActivity {

    public static final String EXTRA_BIRD_ID = "com.birddex.app.extra.CHANGE_IMAGE_BIRD_ID";
    public static final String EXTRA_CURRENT_IMAGE_URL = "com.birddex.app.extra.CHANGE_IMAGE_CURRENT_URL";
    public static final String EXTRA_COMMON_NAME = "com.birddex.app.extra.CHANGE_IMAGE_COMMON_NAME";
    public static final String EXTRA_SCI_NAME = "com.birddex.app.extra.CHANGE_IMAGE_SCI_NAME";

    public static final String RESULT_IMAGE_URL = "com.birddex.app.result.CHANGE_IMAGE_URL";
    public static final String RESULT_TIMESTAMP = "com.birddex.app.result.CHANGE_IMAGE_TIMESTAMP";
    public static final String RESULT_USER_BIRD_REF_ID = "com.birddex.app.result.CHANGE_IMAGE_USER_BIRD_REF_ID";

    private RecyclerView rvImages;
    private TextView tvTitle;
    private TextView tvSubtitle;
    private TextView tvEmpty;

    private ChangeCardImageBrowserAdapter adapter;

    private String birdId;
    private String currentImageUrl;
    private String commonName;
    private String scientificName;

    // --- FIXES ---
    private int fetchGeneration = 0;

    /**
     * Android calls this when the Activity is first created. This is where the screen usually
     * inflates its layout, grabs views, creates helpers, and wires listeners.
     * It grabs layout/view references here so later code can read from them, update them, or
     * attach listeners.
     * It wires user actions here, so taps on buttons/cards/menus trigger the next step in the
     * flow.
     * It prepares or refreshes adapter-backed lists/grids here so the latest model objects are
     * rendered on screen.
     */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_change_card_image);
        // Bind or inflate the UI pieces this method needs before it can update the screen.
        View root = findViewById(R.id.rootChangeCardImage);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int extraTop = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6, getResources().getDisplayMetrics());
            v.setPadding(v.getPaddingLeft(), systemBars.top + extraTop, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        ImageButton btnBack = findViewById(R.id.btnBack);
        tvTitle = findViewById(R.id.tvTitle);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        tvEmpty = findViewById(R.id.tvEmpty);
        rvImages = findViewById(R.id.rvImages);

        birdId = getIntent().getStringExtra(EXTRA_BIRD_ID);
        currentImageUrl = getIntent().getStringExtra(EXTRA_CURRENT_IMAGE_URL);
        commonName = getIntent().getStringExtra(EXTRA_COMMON_NAME);
        scientificName = getIntent().getStringExtra(EXTRA_SCI_NAME);

        tvTitle.setText("");
        tvSubtitle.setText(buildSubtitle());

        GridLayoutManager layoutManager = new GridLayoutManager(this, 3);
        // Hook the data source to the list/grid adapter so model objects can render as UI rows/cards.
        adapter = new ChangeCardImageBrowserAdapter(this::onImageChosen);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return adapter.isHeader(position) ? 3 : 1; }
        });

        rvImages.setLayoutManager(layoutManager);
        rvImages.setAdapter(adapter);

        // Attach the user interaction that should run when this control is tapped.
        btnBack.setOnClickListener(v -> finish());

        if (isBlank(birdId)) {
            // Give the user immediate feedback about the result of this action.
            Toast.makeText(this, "No bird ID found.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        fetchImages();
    }

    /**
     * Pulls data from a local source, Firebase, or an external API and prepares it for the UI or
     * caller.
     * It talks to Firebase/Firestore in this method, either to read live data or to persist app
     * changes.
     * There is also one-time async data loading here, so success/failure callbacks are important
     * for the final UI state.
     */
    private void fetchImages() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        final int myGen = ++fetchGeneration;

        // Set up or query the Firebase layer that supplies/stores this feature's data.
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .collection("userBirdImage")
                .whereEqualTo("birdId", birdId)
                // Kick off an asynchronous one-time read; the callbacks below decide how the UI should react.
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (myGen != fetchGeneration) return;

                    List<BrowserPhoto> photos = new ArrayList<>();
                    LinkedHashSet<String> seenUrls = new LinkedHashSet<>();

                    for (DocumentSnapshot doc : queryDocumentSnapshots.getDocuments()) {
                        String imageUrl = doc.getString("imageUrl");
                        Date timestamp = doc.getDate("timestamp");
                        String userBirdRefId = doc.getString("userBirdRefId");
                        Boolean hiddenFromUser = doc.getBoolean("hiddenFromUser");

                        if (Boolean.TRUE.equals(hiddenFromUser)) continue;
                        if (isBlank(imageUrl)) continue;
                        if (seenUrls.contains(imageUrl)) continue;

                        seenUrls.add(imageUrl);
                        photos.add(new BrowserPhoto(imageUrl, timestamp, userBirdRefId));
                    }

                    if (photos.isEmpty()) {
                        adapter.submitList(new ArrayList<>());
                        updateEmptyState(true);
                        return;
                    }

                    Collections.sort(photos, (a, b) -> Long.compare(getTime(b.timestamp), getTime(a.timestamp)));

                    List<ChangeCardImageBrowserAdapter.BrowserItem> items = new ArrayList<>();
                    SimpleDateFormat headerFormat = new SimpleDateFormat("MMMM yyyy", Locale.US);
                    SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy", Locale.US);

                    String lastHeader = null;
                    for (BrowserPhoto photo : photos) {
                        String headerTitle = photo.timestamp != null ? headerFormat.format(photo.timestamp) : "Unknown Date";
                        if (!headerTitle.equals(lastHeader)) {
                            items.add(ChangeCardImageBrowserAdapter.BrowserItem.createHeader(headerTitle));
                            lastHeader = headerTitle;
                        }
                        String dateLabel = photo.timestamp != null ? dateFormat.format(photo.timestamp) : "Unknown date";
                        boolean isCurrent = currentImageUrl != null && currentImageUrl.equals(photo.imageUrl);
                        items.add(ChangeCardImageBrowserAdapter.BrowserItem.createPhoto(photo.imageUrl, dateLabel, photo.timestamp, photo.userBirdRefId, isCurrent));
                    }

                    adapter.submitList(items);
                    updateEmptyState(items.isEmpty());
                })
                .addOnFailureListener(e -> {
                    if (myGen == fetchGeneration) updateEmptyState(true);
                });
    }

    /**
     * Main logic block for this part of the feature.
     * It also packages extras into an Intent when this flow needs to open another Activity.
     * User-facing feedback is shown here so the user knows whether the action succeeded, failed,
     * or needs attention.
     */
    private void onImageChosen(@NonNull ChangeCardImageBrowserAdapter.BrowserItem item) {
        if (item.isCurrent) {
            // Give the user immediate feedback about the result of this action.
            Toast.makeText(this, "Already in use.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent data = new Intent();
        data.putExtra(RESULT_IMAGE_URL, item.imageUrl);
        data.putExtra(RESULT_TIMESTAMP, item.timestamp != null ? item.timestamp.getTime() : -1L);
        data.putExtra(RESULT_USER_BIRD_REF_ID, item.userBirdRefId);
        setResult(RESULT_OK, data);
        finish();
    }

    /**
     * Applies the latest values to existing UI/data so the screen and backend stay in sync.
     */
    private void updateEmptyState(boolean isEmpty) {
        tvEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        rvImages.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    /**
     * Main logic block for this part of the feature.
     */
    private String buildSubtitle() {
        if (!isBlank(commonName)) return commonName;
        if (!isBlank(scientificName)) return scientificName;
        return "Your saved photos";
    }

    private long getTime(Date date) { return date == null ? 0L : date.getTime(); }
    private boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }

    private static class BrowserPhoto {
        final String imageUrl;
        final Date timestamp;
        final String userBirdRefId;
        /**
         * Main logic block for this part of the feature.
         */
        BrowserPhoto(String imageUrl, Date timestamp, String userBirdRefId) {
            this.imageUrl = imageUrl;
            this.timestamp = timestamp;
            this.userBirdRefId = userBirdRefId;
        }
    }
}
