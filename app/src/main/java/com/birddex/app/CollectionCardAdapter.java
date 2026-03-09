package com.birddex.app;

import android.content.Intent;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/**
 * CollectionCardAdapter displays bird cards in a grid.
 * Fixed Race Condition:
 * 1. Navigation Flooding: Added isNavigating guard to prevent opening multiple Detail screens.
 */
public class CollectionCardAdapter extends RecyclerView.Adapter<CollectionCardAdapter.VH> {

    public static final String EXTRA_IMAGE_URL = "com.birddex.app.extra.IMAGE_URL";
    public static final String EXTRA_COMMON_NAME = "com.birddex.app.extra.COMMON_NAME";
    public static final String EXTRA_SCI_NAME = "com.birddex.app.extra.SCI_NAME";
    public static final String EXTRA_STATE = "com.birddex.app.extra.STATE";
    public static final String EXTRA_LOCALITY = "com.birddex.app.extra.LOCALITY";
    public static final String EXTRA_CAUGHT_TIME = "com.birddex.app.extra.CAUGHT_TIME";
    public static final String EXTRA_BIRD_ID = "com.birddex.app.extra.BIRD_ID";

    private final List<CollectionSlot> slots;
    private boolean isNavigating = false;

    public CollectionCardAdapter(@NonNull List<CollectionSlot> slots) {
        this.slots = slots;
    }

    public void setNavigating(boolean navigating) {
        this.isNavigating = navigating;
    }

    static class VH extends RecyclerView.ViewHolder {
        CardView cardContainer; View cardInner; TextView txtBirdName; ImageView imgBird;
        TextView txtScientific, txtLocation, txtDateCaught, txtFooter;

        VH(@NonNull View itemView) {
            super(itemView);
            cardContainer = itemView.findViewById(R.id.cardContainer);
            cardInner = itemView.findViewById(R.id.cardInner);
            txtBirdName = itemView.findViewById(R.id.txtBirdName);
            imgBird = itemView.findViewById(R.id.imgBird);
            txtScientific = itemView.findViewById(R.id.txtScientific);
            txtLocation = itemView.findViewById(R.id.txtLocation);
            txtDateCaught = itemView.findViewById(R.id.txtDateCaught);
            txtFooter = itemView.findViewById(R.id.txtFooter);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.view_bird_card, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int spacing = (int) (2 * density);
        int parentWidth = parent.getMeasuredWidth() > 0 ? parent.getMeasuredWidth() : parent.getResources().getDisplayMetrics().widthPixels;
        int availableWidth = parentWidth - parent.getPaddingLeft() - parent.getPaddingRight() - (spacing * 4);
        int itemWidth = Math.max(1, availableWidth / 3);
        v.setLayoutParams(new RecyclerView.LayoutParams(itemWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        VH holder = new VH(v);
        applyCompactCollectionStyle(holder);
        return holder;
    }

    private void applyCompactCollectionStyle(@NonNull VH holder) {
        float d = holder.itemView.getResources().getDisplayMetrics().density;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holder.cardContainer.getLayoutParams();
        lp.setMargins((int)d, (int)d, (int)d, (int)d); holder.cardContainer.setLayoutParams(lp);
        holder.cardContainer.setRadius(16 * d);
        int p = (int) (6 * d); holder.cardInner.setPadding(p, p, p, p);
        holder.txtBirdName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        holder.txtScientific.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        holder.txtLocation.setTextSize(TypedValue.COMPLEX_UNIT_SP, 6);
        holder.txtDateCaught.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        holder.txtDateCaught.setGravity(Gravity.CENTER_HORIZONTAL);
        holder.txtFooter.setVisibility(View.GONE);
        holder.txtBirdName.setMinLines(2); holder.txtBirdName.setMaxLines(2); holder.txtBirdName.setEllipsize(TextUtils.TruncateAt.END); holder.txtBirdName.setGravity(Gravity.CENTER);
        holder.txtScientific.setMaxLines(1); holder.txtScientific.setEllipsize(TextUtils.TruncateAt.END);
        holder.txtLocation.setMaxLines(2); holder.txtLocation.setEllipsize(TextUtils.TruncateAt.END);
        holder.txtDateCaught.setMaxLines(1); holder.txtDateCaught.setEllipsize(TextUtils.TruncateAt.END);
        ViewGroup.LayoutParams imgLp = holder.imgBird.getLayoutParams(); imgLp.height = (int) (92 * d); holder.imgBird.setLayoutParams(imgLp);
        ViewGroup.LayoutParams conLp = holder.cardContainer.getLayoutParams(); conLp.height = (int) (245 * d); holder.cardContainer.setLayoutParams(conLp);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        CollectionSlot slot = slots.get(position);
        String url = slot != null ? slot.getImageUrl() : null;
        boolean hasImage = url != null && !url.trim().isEmpty();

        holder.itemView.setOnClickListener(v -> {
            if (!hasImage || isNavigating) return;
            isNavigating = true;
            Intent i = new Intent(v.getContext(), ViewBirdCardActivity.class);
            i.putExtra(EXTRA_IMAGE_URL, url); i.putExtra(EXTRA_COMMON_NAME, slot.getCommonName());
            i.putExtra(EXTRA_SCI_NAME, slot.getScientificName()); i.putExtra(EXTRA_STATE, slot.getState());
            i.putExtra(EXTRA_LOCALITY, slot.getLocality()); i.putExtra(EXTRA_BIRD_ID, slot.getBirdId());
            if (slot.getTimestamp() != null) i.putExtra(EXTRA_CAUGHT_TIME, slot.getTimestamp().getTime());
            v.getContext().startActivity(i);
        });

        if (hasImage) {
            String n = slot.getCommonName(), s = slot.getScientificName();
            holder.txtBirdName.setText(!isBlank(n) ? n : (!isBlank(s) ? s : "Unknown Bird"));
            holder.txtScientific.setText(!isBlank(s) ? s : "--");
            holder.txtLocation.setText(CardFormatUtils.formatLocation(slot.getState(), slot.getLocality()));
            holder.txtDateCaught.setText(slot.getTimestamp() != null ? new SimpleDateFormat("M/d/yy", Locale.US).format(slot.getTimestamp()) : "--");
            Glide.with(holder.itemView.getContext()).load(url).fitCenter().into(holder.imgBird);
            holder.itemView.setAlpha(1f);
        } else {
            holder.txtBirdName.setText("Unknown Bird"); holder.txtScientific.setText("--"); holder.txtLocation.setText("Location: --"); holder.txtDateCaught.setText("Date: --");
            holder.imgBird.setImageResource(R.drawable.birddexlogo); holder.itemView.setAlpha(0.88f);
        }
    }

    @Override public int getItemCount() { return slots.size(); }
    private boolean isBlank(String v) { return v == null || v.trim().isEmpty(); }
}
