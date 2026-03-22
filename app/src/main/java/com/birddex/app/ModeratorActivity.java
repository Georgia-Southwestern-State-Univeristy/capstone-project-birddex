package com.birddex.app;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ModeratorActivity: Dashboard for staff to review pending moderation appeals and reports.
 */
public class ModeratorActivity extends AppCompatActivity {

    private static final String QUEUE_TYPE_APPEAL = "appeal";
    private static final String QUEUE_TYPE_REPORT = "report";

    private RecyclerView rvPendingAppeals;
    private ProgressBar progressBar;
    private TextView tvNoAppeals;
    private FirebaseManager firebaseManager;
    private ModeratorQueueAdapter adapter;

    private boolean appealsLoaded = false;
    private boolean reportsLoaded = false;
    private List<Map<String, Object>> pendingAppeals = new ArrayList<>();
    private List<Map<String, Object>> pendingReports = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarHelper.applyStandardNavBar(this);
        setContentView(R.layout.activity_moderator);

        firebaseManager = new FirebaseManager(this);

        rvPendingAppeals = findViewById(R.id.rvPendingAppeals);
        progressBar = findViewById(R.id.progressBar);
        tvNoAppeals = findViewById(R.id.tvNoAppeals);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        setupRecyclerView();
        loadModeratorQueue();
    }

    private void setupRecyclerView() {
        adapter = new ModeratorQueueAdapter(this::showQueueReviewDialog);
        rvPendingAppeals.setLayoutManager(new LinearLayoutManager(this));
        rvPendingAppeals.setAdapter(adapter);
    }

    private void loadModeratorQueue() {
        progressBar.setVisibility(View.VISIBLE);
        appealsLoaded = false;
        reportsLoaded = false;
        pendingAppeals = new ArrayList<>();
        pendingReports = new ArrayList<>();

        firebaseManager.getPendingModerationAppeals(new FirebaseManager.AppealsListListener() {
            @Override
            public void onSuccess(List<Map<String, Object>> appeals) {
                pendingAppeals = appeals != null ? appeals : new ArrayList<>();
                appealsLoaded = true;
                finalizeQueueLoadIfReady();
            }

            @Override
            public void onFailure(String errorMessage) {
                pendingAppeals = new ArrayList<>();
                appealsLoaded = true;
                Toast.makeText(ModeratorActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                finalizeQueueLoadIfReady();
            }
        });

        firebaseManager.getPendingModerationReports(new FirebaseManager.ReportsListListener() {
            @Override
            public void onSuccess(List<Map<String, Object>> reports) {
                pendingReports = reports != null ? reports : new ArrayList<>();
                reportsLoaded = true;
                finalizeQueueLoadIfReady();
            }

            @Override
            public void onFailure(String errorMessage) {
                pendingReports = new ArrayList<>();
                reportsLoaded = true;
                Toast.makeText(ModeratorActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                finalizeQueueLoadIfReady();
            }
        });
    }

    private void finalizeQueueLoadIfReady() {
        if (!appealsLoaded || !reportsLoaded) {
            return;
        }

        progressBar.setVisibility(View.GONE);

        List<Map<String, Object>> queueItems = new ArrayList<>();

        for (Map<String, Object> appeal : pendingAppeals) {
            Map<String, Object> item = new HashMap<>(appeal);
            item.put("queueType", QUEUE_TYPE_APPEAL);
            queueItems.add(item);
        }

        for (Map<String, Object> report : pendingReports) {
            Map<String, Object> item = new HashMap<>(report);
            item.put("queueType", QUEUE_TYPE_REPORT);
            queueItems.add(item);
        }

        Collections.sort(queueItems, (left, right) -> {
            long rightMillis = getQueueSortMillis(right);
            long leftMillis = getQueueSortMillis(left);
            return Long.compare(rightMillis, leftMillis);
        });

        if (queueItems.isEmpty()) {
            tvNoAppeals.setVisibility(View.VISIBLE);
            rvPendingAppeals.setVisibility(View.GONE);
        } else {
            tvNoAppeals.setVisibility(View.GONE);
            rvPendingAppeals.setVisibility(View.VISIBLE);
            adapter.setItems(queueItems);
        }
    }

    private void showQueueReviewDialog(Map<String, Object> item) {
        String queueType = getDisplayValue(item, "queueType", QUEUE_TYPE_APPEAL);
        if (QUEUE_TYPE_REPORT.equals(queueType)) {
            showReportReviewDialog(item);
        } else {
            showAppealReviewDialog(item);
        }
    }

    private void showAppealReviewDialog(Map<String, Object> appeal) {
        Object appealIdObj = appeal.get("id");
        if (appealIdObj == null) appealIdObj = appeal.get("appealId");
        String appealId = appealIdObj != null ? appealIdObj.toString() : null;

        if (appealId == null) {
            Toast.makeText(this, "Error: Appeal ID missing.", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] options = {"Approve (Reverse Action)", "Deny Appeal"};
        String finalAppealId = appealId;
        new AlertDialog.Builder(this)
                .setTitle("Review Appeal")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        reviewAppeal(finalAppealId, "approved", "Appeal approved by moderator.");
                    } else {
                        showAppealDenialReasonDialog(finalAppealId);
                    }
                })
                .show();
    }

    private void showAppealDenialReasonDialog(String appealId) {
        final EditText input = buildDialogInput("Reason for denial...");
        FrameLayout container = buildDialogContainer(input);

        new AlertDialog.Builder(this)
                .setTitle("Deny Appeal")
                .setView(container)
                .setPositiveButton("Submit", (dialog, which) -> {
                    String note = input.getText().toString().trim();
                    if (note.isEmpty()) note = "Appeal denied.";
                    reviewAppeal(appealId, "denied", note);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showReportReviewDialog(Map<String, Object> report) {
        Object reportIdObj = report.get("id");
        if (reportIdObj == null) reportIdObj = report.get("reportId");
        String reportId = reportIdObj != null ? reportIdObj.toString() : null;

        if (reportId == null) {
            Toast.makeText(this, "Error: Report ID missing.", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] userActionOptions = {
                "No penalty",
                "Warning",
                "Strike",
                "24-hour suspension",
                "7-day suspension",
                "Permanent forum ban"
        };

        new AlertDialog.Builder(this)
                .setTitle("Select User Action")
                .setItems(userActionOptions, (dialog, which) -> {
                    String userAction = mapSelectedUserAction(which);
                    showReportContentActionDialog(report, reportId, userAction);
                })
                .show();
    }

    private void showReportContentActionDialog(Map<String, Object> report, String reportId, String userAction) {
        final boolean targetExists = getBooleanValue(report, "targetExists", false);
        final String[] contentActionOptions = targetExists
                ? new String[]{"Keep current visibility", "Hide content", "Remove content"}
                : new String[]{"Keep current visibility"};

        new AlertDialog.Builder(this)
                .setTitle("Select Content Action")
                .setItems(contentActionOptions, (dialog, which) -> {
                    String contentAction = mapSelectedContentAction(targetExists, which);
                    showReportDecisionNoteDialog(reportId, userAction, contentAction);
                })
                .show();
    }

    private void showReportDecisionNoteDialog(String reportId, String userAction, String contentAction) {
        final EditText input = buildDialogInput("Optional moderator note...");
        FrameLayout container = buildDialogContainer(input);

        new AlertDialog.Builder(this)
                .setTitle("Review Report")
                .setView(container)
                .setPositiveButton("Submit", (dialog, which) -> {
                    String note = input.getText().toString().trim();
                    if (note.isEmpty()) {
                        note = "Report reviewed by moderator.";
                    }
                    reviewReport(reportId, userAction, contentAction, note);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EditText buildDialogInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        return input;
    }

    private FrameLayout buildDialogContainer(EditText input) {
        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.leftMargin = 48;
        params.rightMargin = 48;
        params.topMargin = 24;
        input.setLayoutParams(params);
        container.addView(input);
        return container;
    }

    private void reviewAppeal(String appealId, String decision, String note) {
        progressBar.setVisibility(View.VISIBLE);
        firebaseManager.reviewModerationAppeal(appealId, decision, note, new FirebaseManager.ForumWriteListener() {
            @Override
            public void onSuccess() {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(ModeratorActivity.this, "Appeal review submitted.", Toast.LENGTH_SHORT).show();
                loadModeratorQueue();
            }

            @Override
            public void onFailure(String errorMessage) {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(ModeratorActivity.this, errorMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void reviewReport(String reportId, String userAction, String contentAction, String note) {
        progressBar.setVisibility(View.VISIBLE);
        firebaseManager.reviewPendingReport(reportId, userAction, contentAction, note, new FirebaseManager.ForumWriteListener() {
            @Override
            public void onSuccess() {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(ModeratorActivity.this, "Report review submitted.", Toast.LENGTH_SHORT).show();
                loadModeratorQueue();
            }

            @Override
            public void onFailure(String errorMessage) {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(ModeratorActivity.this, errorMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static String mapSelectedUserAction(int which) {
        switch (which) {
            case 1:
                return "warning";
            case 2:
                return "strike";
            case 3:
                return "suspend_24h";
            case 4:
                return "suspend_7d";
            case 5:
                return "forum_ban";
            default:
                return "none";
        }
    }

    private static String mapSelectedContentAction(boolean targetExists, int which) {
        if (!targetExists) {
            return "keep";
        }
        switch (which) {
            case 1:
                return "hide_content";
            case 2:
                return "remove_content";
            default:
                return "keep";
        }
    }

    private static long getQueueSortMillis(Map<String, Object> item) {
        String queueType = getDisplayValue(item, "queueType", QUEUE_TYPE_APPEAL);
        Object timestamp = QUEUE_TYPE_REPORT.equals(queueType) ? item.get("timestamp") : item.get("createdAt");
        return Math.max(0L, extractTimestampMillis(timestamp));
    }

    private static long extractTimestampMillis(Object timestamp) {
        if (timestamp == null) return 0L;
        if (timestamp instanceof com.google.firebase.Timestamp) {
            return ((com.google.firebase.Timestamp) timestamp).toDate().getTime();
        }
        if (timestamp instanceof Long) {
            return (Long) timestamp;
        }
        if (timestamp instanceof Date) {
            return ((Date) timestamp).getTime();
        }
        if (timestamp instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) timestamp;
            Object seconds = map.get("_seconds");
            if (seconds instanceof Number) {
                return ((Number) seconds).longValue() * 1000L;
            }
        }
        return 0L;
    }

    private static String formatTimestamp(Object timestamp) {
        long millis = extractTimestampMillis(timestamp);
        if (millis <= 0L) return "N/A";
        return new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    private static String getDisplayValue(Map<String, Object> data, String key, String fallback) {
        Object value = data.get(key);
        if (value == null) return fallback;
        String text = value.toString().trim();
        return text.isEmpty() ? fallback : text;
    }

    private static boolean getBooleanValue(Map<String, Object> data, String key, boolean fallback) {
        Object value = data.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return fallback;
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        String cleaned = text.trim();
        if (cleaned.length() <= maxLength) return cleaned;
        return cleaned.substring(0, Math.max(0, maxLength - 1)).trim() + "…";
    }

    private static String formatModerationValue(String raw) {
        if (raw == null) return "Unknown";
        String cleaned = raw.trim().replace('_', ' ');
        if (cleaned.isEmpty()) return "Unknown";

        String[] parts = cleaned.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1).toLowerCase(Locale.getDefault()));
            }
        }
        return builder.toString();
    }

    private static class ModeratorQueueAdapter extends RecyclerView.Adapter<ModeratorQueueAdapter.ViewHolder> {
        private List<Map<String, Object>> items = new ArrayList<>();
        private final OnReviewClickListener listener;

        interface OnReviewClickListener { void onReview(Map<String, Object> item); }

        ModeratorQueueAdapter(OnReviewClickListener listener) {
            this.listener = listener;
        }

        void setItems(List<Map<String, Object>> items) {
            this.items = items;
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
            Map<String, Object> item = items.get(position);
            String queueType = getDisplayValue(item, "queueType", QUEUE_TYPE_APPEAL);

            if (QUEUE_TYPE_REPORT.equals(queueType)) {
                bindReport(holder, item);
            } else {
                bindAppeal(holder, item);
            }

            holder.btnAppeal.setVisibility(View.VISIBLE);
            holder.btnAppeal.setOnClickListener(v -> listener.onReview(item));
        }

        private void bindAppeal(@NonNull ViewHolder holder, Map<String, Object> appeal) {
            String actionType = formatModerationValue(getDisplayValue(appeal, "snapshotActionType", "Unknown"));
            String reasonCode = formatModerationValue(getDisplayValue(appeal, "snapshotReasonCode", "Unknown"));
            String userId = getDisplayValue(appeal, "userId", "Unknown");
            String appealText = truncate(getDisplayValue(appeal, "appealText", "No appeal text provided."), 180);

            holder.tvType.setText("PENDING APPEAL");
            holder.tvReason.setText("User ID: " + userId + "\nAction: " + actionType + "\nAppeal: " + appealText);
            holder.tvStatus.setText("Reason Code: " + reasonCode);
            holder.tvDate.setText("Submitted: " + formatTimestamp(appeal.get("createdAt")));
            holder.btnAppeal.setText("Review Appeal");
        }

        private void bindReport(@NonNull ViewHolder holder, Map<String, Object> report) {
            String targetType = formatModerationValue(getDisplayValue(report, "targetType", "content"));
            String ownerLabel = getDisplayValue(report, "targetOwnerUsername", getDisplayValue(report, "targetOwnerId", "Unknown"));
            String reasonCode = formatModerationValue(getDisplayValue(report, "reasonCode", "other"));
            String reasonText = truncate(getDisplayValue(report, "reasonText", "No report reason provided."), 150);
            String preview = truncate(getDisplayValue(report, "targetPreview", ""), 150);
            String moderationStatus = formatModerationValue(getDisplayValue(report, "targetModerationStatus", "visible"));
            boolean targetExists = getBooleanValue(report, "targetExists", false);

            StringBuilder reasonBuilder = new StringBuilder();
            reasonBuilder.append("Target: ").append(targetType).append(" by ").append(ownerLabel);
            reasonBuilder.append("\nReason: ").append(reasonText);
            if (!preview.isEmpty()) {
                reasonBuilder.append("\nPreview: ").append(preview);
            }

            StringBuilder statusBuilder = new StringBuilder();
            statusBuilder.append("Report Code: ").append(reasonCode);
            statusBuilder.append(" • Status: ").append(moderationStatus);
            if (!targetExists) {
                statusBuilder.append(" • Target missing");
            }

            holder.tvType.setText("PENDING REPORT");
            holder.tvReason.setText(reasonBuilder.toString());
            holder.tvStatus.setText(statusBuilder.toString());
            holder.tvDate.setText("Reported: " + formatTimestamp(report.get("timestamp")));
            holder.btnAppeal.setText("Review Report");
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvType, tvReason, tvStatus, tvDate;
            MaterialButton btnAppeal;

            ViewHolder(View v) {
                super(v);
                tvType = v.findViewById(R.id.tvActionType);
                tvReason = v.findViewById(R.id.tvReason);
                tvStatus = v.findViewById(R.id.tvStatus);
                tvDate = v.findViewById(R.id.tvDate);
                btnAppeal = v.findViewById(R.id.btnAppeal);
            }
        }
    }
}
