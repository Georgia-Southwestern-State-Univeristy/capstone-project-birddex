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

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_change_card_image);
        View root = findViewById(R.id.rootChangeCardImage);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

            int extraTop = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    6,
                    getResources().getDisplayMetrics()
            );

            v.setPadding(
                    v.getPaddingLeft(),
                    systemBars.top + extraTop,
                    v.getPaddingRight(),
                    v.getPaddingBottom()
            );

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
        adapter = new ChangeCardImageBrowserAdapter(this::onImageChosen);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.isHeader(position) ? 3 : 1;
            }
        });

        rvImages.setLayoutManager(layoutManager);
        rvImages.setAdapter(adapter);

        btnBack.setOnClickListener(v -> finish());

        if (isBlank(birdId)) {
            Toast.makeText(this, "No bird ID found for this card.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        fetchImages();
    }

    private void fetchImages() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "No user logged in.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .collection("userBirdImage")
                .whereEqualTo("birdId", birdId)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
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

                    Collections.sort(photos, (a, b) ->
                            Long.compare(getTime(b.timestamp), getTime(a.timestamp))
                    );

                    List<ChangeCardImageBrowserAdapter.BrowserItem> items = new ArrayList<>();
                    SimpleDateFormat headerFormat = new SimpleDateFormat("MMMM yyyy", Locale.US);
                    SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy", Locale.US);

                    String lastHeader = null;
                    for (BrowserPhoto photo : photos) {
                        String headerTitle = photo.timestamp != null
                                ? headerFormat.format(photo.timestamp)
                                : "Unknown Date";

                        if (!headerTitle.equals(lastHeader)) {
                            items.add(ChangeCardImageBrowserAdapter.BrowserItem.createHeader(headerTitle));
                            lastHeader = headerTitle;
                        }

                        String dateLabel = photo.timestamp != null
                                ? dateFormat.format(photo.timestamp)
                                : "Unknown date";

                        boolean isCurrent = currentImageUrl != null
                                && currentImageUrl.equals(photo.imageUrl);

                        items.add(ChangeCardImageBrowserAdapter.BrowserItem.createPhoto(
                                photo.imageUrl,
                                dateLabel,
                                photo.timestamp,
                                photo.userBirdRefId,
                                isCurrent
                        ));
                    }

                    adapter.submitList(items);
                    updateEmptyState(items.isEmpty());
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to load stored images.", Toast.LENGTH_SHORT).show();
                    updateEmptyState(true);
                });
    }

    private void onImageChosen(@NonNull ChangeCardImageBrowserAdapter.BrowserItem item) {
        if (item.isCurrent) {
            Toast.makeText(this, "That image is already being used.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent data = new Intent();
        data.putExtra(RESULT_IMAGE_URL, item.imageUrl);
        data.putExtra(RESULT_TIMESTAMP, item.timestamp != null ? item.timestamp.getTime() : -1L);
        data.putExtra(RESULT_USER_BIRD_REF_ID, item.userBirdRefId);
        setResult(RESULT_OK, data);
        finish();
    }

    private void updateEmptyState(boolean isEmpty) {
        tvEmpty.setVisibility(isEmpty ? TextView.VISIBLE : TextView.GONE);
        rvImages.setVisibility(isEmpty ? RecyclerView.GONE : RecyclerView.VISIBLE);
    }

    private String buildSubtitle() {
        if (!isBlank(commonName)) {
            return commonName;
        }
        if (!isBlank(scientificName)) {
            return scientificName;
        }
        return "Your saved photos";
    }

    private long getTime(Date date) {
        return date == null ? 0L : date.getTime();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class BrowserPhoto {
        final String imageUrl;
        final Date timestamp;
        final String userBirdRefId;

        BrowserPhoto(String imageUrl, Date timestamp, String userBirdRefId) {
            this.imageUrl = imageUrl;
            this.timestamp = timestamp;
            this.userBirdRefId = userBirdRefId;
        }
    }
}