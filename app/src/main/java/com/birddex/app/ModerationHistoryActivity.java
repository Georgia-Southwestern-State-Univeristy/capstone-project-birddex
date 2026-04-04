package com.birddex.app;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ModerationHistoryActivity: Hub for users to view their account standing and appeal status.
 *
 * It shows active warnings, strikes, and any forum restrictions. Users can also track
 * the status of their submitted appeals here.
 */
public class ModerationHistoryActivity extends AppCompatActivity {

    private RecyclerView rvHistory, rvAppeals;
    private ProgressBar progressBar;
    private TextView tvWarningCount, tvStrikeCount, tvNoHistory, tvRestrictionTitle, tvRestrictionDetails, labelAppeals;
    private View cardRestriction;
    private FirebaseManager firebaseManager;
    private ModerationAdapter adapter;
    private AppealsAdapter appealsAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_moderation_history);

        firebaseManager = new FirebaseManager(this);

        rvHistory = findViewById(R.id.rvModerationHistory);
        rvAppeals = findViewById(R.id.rvAppeals);
        progressBar = findViewById(R.id.progressBar);
        tvWarningCount = findViewById(R.id.tvWarningCount);
        tvStrikeCount = findViewById(R.id.tvStrikeCount);
        tvNoHistory = findViewById(R.id.tvNoHistory);
        tvRestrictionTitle = findViewById(R.id.tvRestrictionTitle);
        tvRestrictionDetails = findViewById(R.id.tvRestrictionDetails);
        cardRestriction = findViewById(R.id.cardRestrictionStatus);
        labelAppeals = findViewById(R.id.labelAppeals);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        setupRecyclerViews();
        loadModerationState();
    }

    private void setupRecyclerViews() {
        adapter = new ModerationAdapter(this::showAppealDialog);
        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        rvHistory.setAdapter(adapter);

        appealsAdapter = new AppealsAdapter();
        rvAppeals.setLayoutManager(new LinearLayoutManager(this));
        rvAppeals.setAdapter(appealsAdapter);
    }

    private void loadModerationState() {
        progressBar.setVisibility(View.VISIBLE);
        firebaseManager.getMyModerationState(new FirebaseManager.ModerationStateListener() {
            @Override
            public void onSuccess(Map<String, Object> state) {
                progressBar.setVisibility(View.GONE);
                updateUI(state);
            }

            @Override
            public void onFailure(String errorMessage) {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(ModerationHistoryActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateUI(Map<String, Object> state) {
        long warnings = 0;
        if (state.get("warningCount") instanceof Number) {
            warnings = ((Number) state.get("warningCount")).longValue();
        }
        long strikes = 0;
        if (state.get("strikeCount") instanceof Number) {
            strikes = ((Number) state.get("strikeCount")).longValue();
        }

        boolean isPermBan = Boolean.TRUE.equals(state.get("permanentForumBan"));
        Object suspendedUntil = state.get("forumSuspendedUntil");

        tvWarningCount.setText(String.valueOf(warnings));
        tvStrikeCount.setText(String.valueOf(strikes));

        if (isPermBan) {
            cardRestriction.setVisibility(View.VISIBLE);
            tvRestrictionTitle.setText(R.string.forum_restricted_title);
            tvRestrictionDetails.setText("Your forum access is permanently restricted.");
        } else if (suspendedUntil != null) {
            cardRestriction.setVisibility(View.VISIBLE);
            tvRestrictionTitle.setText(R.string.forum_restricted_title);
            String dateStr = formatTimestamp(suspendedUntil);
            tvRestrictionDetails.setText("Your forum access is suspended until " + dateStr + ".");
        } else {
            cardRestriction.setVisibility(View.GONE);
        }

        // Handle Appeals first to build the set of appealed event IDs
        List<Map<String, Object>> appeals = (List<Map<String, Object>>) state.get("appeals");
        Set<String> appealedEventIds = new HashSet<>();
        if (appeals != null && !appeals.isEmpty()) {
            labelAppeals.setVisibility(View.VISIBLE);
            rvAppeals.setVisibility(View.VISIBLE);
            appealsAdapter.setAppeals(appeals);
            for (Map<String, Object> appeal : appeals) {
                Object eventId = appeal.get("moderationEventId");
                if (eventId != null) appealedEventIds.add(eventId.toString());
            }
        } else {
            labelAppeals.setVisibility(View.GONE);
            rvAppeals.setVisibility(View.GONE);
        }

        // Handle Moderation Events
        List<Map<String, Object>> events = (List<Map<String, Object>>) state.get("events");
        if (events == null || events.isEmpty()) {
            tvNoHistory.setVisibility(View.VISIBLE);
            rvHistory.setVisibility(View.GONE);
        } else {
            tvNoHistory.setVisibility(View.GONE);
            rvHistory.setVisibility(View.VISIBLE);
            adapter.setEvents(events, appealedEventIds);
        }
    }

    private void showAppealDialog(String eventId) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.dialog_appeal_title);

        final EditText input = new EditText(this);
        input.setHint(R.string.hint_appeal_reason);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = 48;
        params.rightMargin = 48;
        params.topMargin = 24;
        input.setLayoutParams(params);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("Submit", (dialog, which) -> {
            String reason = input.getText().toString().trim();
            if (reason.isEmpty()) {
                Toast.makeText(this, "Please enter a reason.", Toast.LENGTH_SHORT).show();
                return;
            }
            submitAppeal(eventId, reason);
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void submitAppeal(String eventId, String reason) {
        progressBar.setVisibility(View.VISIBLE);
        firebaseManager.submitModerationAppeal(eventId, reason, new FirebaseManager.ForumWriteListener() {
            @Override
            public void onSuccess() {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(ModerationHistoryActivity.this, "Appeal submitted successfully.", Toast.LENGTH_LONG).show();
                loadModerationState(); // Refresh to show pending appeal
            }

            @Override
            public void onFailure(String errorMessage) {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(ModerationHistoryActivity.this, errorMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static String formatTimestamp(Object timestamp) {
        if (timestamp == null) return "N/A";
        Date date = null;
        if (timestamp instanceof com.google.firebase.Timestamp) {
            date = ((com.google.firebase.Timestamp) timestamp).toDate();
        } else if (timestamp instanceof Long) {
            date = new Date((Long) timestamp);
        } else if (timestamp instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) timestamp;
            if (map.containsKey("_seconds")) {
                date = new Date(((Number) map.get("_seconds")).longValue() * 1000);
            }
        }

        if (date == null) return "N/A";
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());
        return sdf.format(date);
    }



    private static void bindContentText(TextView labelView, TextView valueView, String label, Map<String, Object> data, String... keys) {
        if (labelView == null || valueView == null) return;

        String value = null;
        if (data != null && keys != null) {
            for (String key : keys) {
                Object raw = data.get(key);
                if (raw instanceof String) {
                    String cleaned = ((String) raw).trim();
                    if (!cleaned.isEmpty()) {
                        value = cleaned;
                        break;
                    }
                }
            }
        }

        if (value == null || value.isEmpty()) {
            labelView.setVisibility(View.GONE);
            valueView.setVisibility(View.GONE);
            valueView.setText(null);
            return;
        }

        labelView.setText(label);
        labelView.setVisibility(View.VISIBLE);
        valueView.setText(value);
        valueView.setVisibility(View.VISIBLE);
    }

    private static void bindEvidenceImage(ImageView imageView, Map<String, Object> data, String... keys) {
        String url = null;
        if (data != null && keys != null) {
            for (String key : keys) {
                Object value = data.get(key);
                if (value instanceof String && !((String) value).trim().isEmpty()) {
                    url = ((String) value).trim();
                    break;
                }
            }
        }

        if (url == null || url.isEmpty()) {
            imageView.setVisibility(View.GONE);
            imageView.setOnClickListener(null);
            return;
        }

        imageView.setVisibility(View.VISIBLE);
        Glide.with(imageView.getContext())
                .load(url)
                .placeholder(R.drawable.bg_image_placeholder)
                .error(R.drawable.bg_image_placeholder)
                .into(imageView);

        final String finalUrl = url;
        imageView.setOnClickListener(v -> showEvidenceImageDialog(v.getContext(), finalUrl));
    }

    private static void showEvidenceImageDialog(android.content.Context context, String imageUrl) {
        if (context == null || imageUrl == null || imageUrl.trim().isEmpty()) {
            return;
        }

        ImageView imageView = new ImageView(context);
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int padding = Math.round(context.getResources().getDisplayMetrics().density * 16f);
        imageView.setPadding(padding, padding, padding, padding);

        Glide.with(context)
                .load(imageUrl)
                .placeholder(R.drawable.bg_image_placeholder)
                .error(R.drawable.bg_image_placeholder)
                .into(imageView);

        CardView container = new CardView(context);
        container.setRadius(Math.round(context.getResources().getDisplayMetrics().density * 18f));
        container.addView(imageView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        new AlertDialog.Builder(context)
                .setTitle("Flagged image")
                .setView(container)
                .setPositiveButton("Close", null)
                .show();
    }

    private static String formatTargetTypeLabel(String rawType) {
        if (rawType == null) return "Content";
        String normalized = rawType.trim().toLowerCase(Locale.getDefault());
        if ("reply".equals(normalized)) return "Reply";
        if ("comment".equals(normalized)) return "Comment";
        if ("post".equals(normalized)) return "Post";
        return rawType.replace("_", " ");
    }

    private static String formatSourceContextLabel(String sourceContext) {
        if (sourceContext == null) return "";
        String normalized = sourceContext.trim().toLowerCase(Locale.getDefault());
        if (normalized.isEmpty()) return "";
        if ("heatmap".equals(normalized)) return "Heatmap";
        if ("post_detail".equals(normalized)) return "Post detail";
        if ("forum_feed".equals(normalized)) return "Forum feed";
        if ("profile".equals(normalized)) return "Profile";
        return sourceContext.replace("_", " ");
    }

    private static String getTrimmedString(Map<String, Object> data, String... keys) {
        if (data == null || keys == null) return "";
        for (String key : keys) {
            Object value = data.get(key);
            if (value instanceof String) {
                String trimmed = ((String) value).trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        return "";
    }

    private static String formatModerationSourceLabel(String rawSource) {
        String source = rawSource != null ? rawSource.trim() : "";
        if (source.isEmpty()) return "";

        int tagStart = source.lastIndexOf(" (");
        String base = tagStart > 0 && source.endsWith(")")
                ? source.substring(0, tagStart)
                : source;

        return base.replace("_", " ");
    }

    private static String extractModeratorIdentity(String rawSource) {
        String source = rawSource != null ? rawSource.trim() : "";
        if (source.isEmpty() || !source.endsWith(")")) return "";

        int tagStart = source.lastIndexOf(" (");
        if (tagStart < 0 || tagStart + 2 >= source.length() - 1) return "";

        return source.substring(tagStart + 2, source.length() - 1).trim();
    }

    // --- Inner Adapter Class for Events ---
    private static class ModerationAdapter extends RecyclerView.Adapter<ModerationAdapter.ViewHolder> {
        private List<Map<String, Object>> events = new ArrayList<>();
        private Set<String> appealedEventIds = new HashSet<>();
        private final OnAppealClickListener appealClickListener;

        interface OnAppealClickListener { void onAppealClick(String eventId); }

        ModerationAdapter(OnAppealClickListener listener) { this.appealClickListener = listener; }

        void setEvents(List<Map<String, Object>> events, Set<String> appealedEventIds) {
            this.events = events;
            this.appealedEventIds = appealedEventIds != null ? appealedEventIds : new HashSet<>();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = android.view.LayoutInflater.from(parent.getContext()).inflate(R.layout.item_moderation_event, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Map<String, Object> event = events.get(position);
            String actionType = (String) event.get("actionType");
            holder.tvType.setText(actionType != null ? actionType.replace("_", " ").toUpperCase() : "UNKNOWN");

            StringBuilder reasonDetails = new StringBuilder("Reason: ").append(event.get("reasonCode"));
            String targetType = formatTargetTypeLabel(String.valueOf(event.get("targetType") != null ? event.get("targetType") : "content"));
            Object metadataObj = event.get("metadata");
            String sourceLabel = "";
            if (metadataObj instanceof Map) {
                Object rawSource = ((Map<?, ?>) metadataObj).get("sourceContext");
                sourceLabel = formatSourceContextLabel(rawSource != null ? String.valueOf(rawSource) : null);
            }
            reasonDetails.append("\nTarget: ").append(targetType);
            if (!sourceLabel.isEmpty()) {
                reasonDetails.append("\nSource: ").append(sourceLabel);
            }
            Object expiresAt = event.get("expiresAt");
            if (expiresAt != null) {
                reasonDetails.append("\nExpires: ").append(formatTimestamp(expiresAt));
            }
            holder.tvReason.setText(reasonDetails.toString());

            String status = (String) event.get("status");
            holder.tvStatus.setText("Status: " + (status != null ? status : "active"));

            holder.tvDate.setText("Created: " + formatTimestamp(event.get("createdAt")));
            bindEvidenceImage(holder.ivEvidenceImage, event, "evidenceImageUrl", "snapshotEvidenceImageUrl");
            bindContentText(holder.tvContentLabel, holder.tvContentValue, "Your text", event, "evidenceText", "snapshotEvidenceText", "targetTextSnapshot", "targetPreview");

            Object id = event.get("id");
            if (id == null) id = event.get("eventId");
            String eventIdStr = id != null ? id.toString() : null;

            boolean alreadyAppealed = eventIdStr != null && appealedEventIds.contains(eventIdStr);
            boolean appealable = Boolean.TRUE.equals(event.get("appealable"));

            if (alreadyAppealed) {
                holder.btnAppeal.setVisibility(View.VISIBLE);
                holder.btnAppeal.setText(R.string.btn_appeal_submitted);
                holder.btnAppeal.setEnabled(false);
                holder.btnAppeal.setOnClickListener(null);
            } else {
                holder.btnAppeal.setVisibility(appealable && "active".equals(status) ? View.VISIBLE : View.GONE);
                holder.btnAppeal.setText(R.string.btn_appeal_decision);
                holder.btnAppeal.setEnabled(true);
                holder.btnAppeal.setOnClickListener(v -> {
                    if (eventIdStr != null) appealClickListener.onAppealClick(eventIdStr);
                });
            }
        }

        @Override
        public int getItemCount() { return events.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvType, tvReason, tvStatus, tvDate, tvContentLabel, tvContentValue;
            MaterialButton btnAppeal;
            ImageView ivEvidenceImage;
            ViewHolder(View v) {
                super(v);
                tvType = v.findViewById(R.id.tvActionType);
                tvReason = v.findViewById(R.id.tvReason);
                tvStatus = v.findViewById(R.id.tvStatus);
                tvDate = v.findViewById(R.id.tvDate);
                tvContentLabel = v.findViewById(R.id.tvContentLabel);
                tvContentValue = v.findViewById(R.id.tvContentValue);
                btnAppeal = v.findViewById(R.id.btnAppeal);
                ivEvidenceImage = v.findViewById(R.id.ivEvidenceImage);
            }
        }
    }

    // --- Inner Adapter Class for Appeals ---
    private static class AppealsAdapter extends RecyclerView.Adapter<AppealsAdapter.ViewHolder> {
        private List<Map<String, Object>> appeals = new ArrayList<>();

        void setAppeals(List<Map<String, Object>> appeals) {
            this.appeals = appeals;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = android.view.LayoutInflater.from(parent.getContext()).inflate(R.layout.item_moderation_event, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Map<String, Object> appeal = appeals.get(position);
            String actionType = String.valueOf(appeal.get("snapshotActionType") != null
                    ? appeal.get("snapshotActionType")
                    : "appeal");
            String targetType = formatTargetTypeLabel(String.valueOf(appeal.get("targetType") != null
                    ? appeal.get("targetType")
                    : "content"));
            String sourceLabel = formatSourceContextLabel(String.valueOf(appeal.get("snapshotSourceContext") != null
                    ? appeal.get("snapshotSourceContext")
                    : ""));
            String moderationSourceRaw = getTrimmedString(appeal, "snapshotSource", "source");
            String moderationSourceLabel = formatModerationSourceLabel(moderationSourceRaw);
            String moderatorIdentity = extractModeratorIdentity(moderationSourceRaw);
            if (moderatorIdentity.isEmpty()) {
                moderatorIdentity = getTrimmedString(appeal, "reviewedBy", "createdBy");
            }
            String reasonCode = String.valueOf(appeal.get("snapshotReasonCode") != null
                    ? appeal.get("snapshotReasonCode")
                    : "unknown");
            String reasonText = appeal.get("snapshotReasonText") instanceof String
                    ? ((String) appeal.get("snapshotReasonText")).trim()
                    : "";

            holder.tvType.setText("APPEAL");
            StringBuilder reasonBuilder = new StringBuilder();
            reasonBuilder.append("Decision: ").append(actionType.replace("_", " ").toUpperCase(Locale.getDefault()));
            reasonBuilder.append("\nCode: ").append(reasonCode.replace("_", " "));
            reasonBuilder.append("\nTarget: ").append(targetType);
            if (!sourceLabel.isEmpty()) {
                reasonBuilder.append("\nSource: ").append(sourceLabel);
            }
            reasonBuilder.append("\nAppeal: ").append(String.valueOf(appeal.get("appealText")));
            if (!reasonText.isEmpty()) {
                reasonBuilder.append("\nOriginal reason: ").append(reasonText);
            }
            holder.tvReason.setText(reasonBuilder.toString());

            String status = (String) appeal.get("status");
            holder.tvStatus.setText("Status: " + (status != null ? status : "pending"));
            holder.tvDate.setText("Submitted: " + formatTimestamp(appeal.get("createdAt")));

            holder.btnAppeal.setVisibility(View.GONE);
            bindEvidenceImage(holder.ivEvidenceImage, appeal, "snapshotEvidenceImageUrl", "evidenceImageUrl");
            bindContentText(holder.tvContentLabel, holder.tvContentValue, "Your text", appeal, "snapshotEvidenceText", "evidenceText", "targetTextSnapshot", "targetPreview");

            if ("denied".equals(status) && appeal.containsKey("decisionNote")) {
                holder.tvStatus.setText(holder.tvStatus.getText() + "\nNote: " + appeal.get("decisionNote"));
            } else if ("approved".equals(status)) {
                holder.tvStatus.setText("Status: Approved");
            }
        }

        @Override
        public int getItemCount() { return appeals.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvType, tvReason, tvStatus, tvDate, tvContentLabel, tvContentValue;
            MaterialButton btnAppeal;
            ImageView ivEvidenceImage;
            ViewHolder(View v) {
                super(v);
                tvType = v.findViewById(R.id.tvActionType);
                tvReason = v.findViewById(R.id.tvReason);
                tvStatus = v.findViewById(R.id.tvStatus);
                tvDate = v.findViewById(R.id.tvDate);
                tvContentLabel = v.findViewById(R.id.tvContentLabel);
                tvContentValue = v.findViewById(R.id.tvContentValue);
                btnAppeal = v.findViewById(R.id.btnAppeal);
                ivEvidenceImage = v.findViewById(R.id.ivEvidenceImage);
            }
        }
    }
}
