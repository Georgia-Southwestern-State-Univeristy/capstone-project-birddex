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

import java.util.List;

public class CollectionCardAdapter extends RecyclerView.Adapter<CollectionCardAdapter.VH> {

    public static final String EXTRA_IMAGE_URL = "com.birddex.app.extra.IMAGE_URL";
    public static final String EXTRA_COMMON_NAME = "com.birddex.app.extra.COMMON_NAME";
    public static final String EXTRA_SCI_NAME = "com.birddex.app.extra.SCI_NAME";
    public static final String EXTRA_STATE = "com.birddex.app.extra.STATE";
    public static final String EXTRA_LOCALITY = "com.birddex.app.extra.LOCALITY";
    public static final String EXTRA_CAUGHT_TIME = "com.birddex.app.extra.CAUGHT_TIME";

    private final List<CollectionSlot> slots;

    public CollectionCardAdapter(@NonNull List<CollectionSlot> slots) {
        this.slots = slots;
    }

    static class VH extends RecyclerView.ViewHolder {
        CardView cardContainer;
        View cardInner;
        TextView txtBirdName;
        ImageView imgBird;
        TextView txtScientific;
        TextView txtLocation;
        TextView txtDateCaught;
        TextView txtFooter;

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
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.view_bird_card, parent, false);

        float density = parent.getResources().getDisplayMetrics().density;
        int spacing = (int) (2 * density);

        int parentWidth = parent.getMeasuredWidth();
        if (parentWidth <= 0) {
            parentWidth = parent.getResources().getDisplayMetrics().widthPixels;
        }

        int availableWidth = parentWidth
                - parent.getPaddingLeft()
                - parent.getPaddingRight()
                - (spacing * 4);

        int itemWidth = availableWidth / 3;
        if (itemWidth < 1) itemWidth = ViewGroup.LayoutParams.WRAP_CONTENT;

        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                itemWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        v.setLayoutParams(lp);

        VH holder = new VH(v);
        applyCompactCollectionStyle(holder);
        return holder;
    }

    private void applyCompactCollectionStyle(@NonNull VH holder) {
        float density = holder.itemView.getResources().getDisplayMetrics().density;

        ViewGroup.MarginLayoutParams cardLp =
                (ViewGroup.MarginLayoutParams) holder.cardContainer.getLayoutParams();
        cardLp.setMargins((int) (1 * density), (int) (1 * density), (int) (1 * density), (int) (1 * density));
        holder.cardContainer.setLayoutParams(cardLp);

        holder.cardContainer.setRadius(10 * density);

        int compactPadding = (int) (6 * density);
        holder.cardInner.setPadding(compactPadding, compactPadding, compactPadding, compactPadding);

        holder.txtBirdName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        holder.txtScientific.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        holder.txtLocation.setTextSize(TypedValue.COMPLEX_UNIT_SP, 6);
        holder.txtDateCaught.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        holder.txtDateCaught.setGravity(Gravity.CENTER_HORIZONTAL);

        holder.txtFooter.setVisibility(View.GONE);

        holder.txtBirdName.setMinLines(2);
        holder.txtBirdName.setMaxLines(2);
        holder.txtBirdName.setEllipsize(TextUtils.TruncateAt.END);
        holder.txtBirdName.setGravity(Gravity.CENTER);

        holder.txtScientific.setMaxLines(1);
        holder.txtScientific.setEllipsize(TextUtils.TruncateAt.END);

        holder.txtLocation.setMaxLines(2);
        holder.txtLocation.setEllipsize(TextUtils.TruncateAt.END);

        holder.txtDateCaught.setMaxLines(1);
        holder.txtDateCaught.setEllipsize(TextUtils.TruncateAt.END);

        ViewGroup.LayoutParams imageLp = holder.imgBird.getLayoutParams();
        imageLp.height = (int) (92 * density);
        holder.imgBird.setLayoutParams(imageLp);

        ViewGroup.LayoutParams containerLp = holder.cardContainer.getLayoutParams();
        containerLp.height = (int) (235 * density);
        holder.cardContainer.setLayoutParams(containerLp);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        CollectionSlot slot = slots.get(position);

        String url = slot != null ? slot.getImageUrl() : null;
        String common = slot != null ? slot.getCommonName() : null;
        String sci = slot != null ? slot.getScientificName() : null;
        String state = slot != null ? slot.getState() : null;
        String locality = slot != null ? slot.getLocality() : null;

        boolean hasImage = url != null && !url.trim().isEmpty();

        holder.itemView.setOnClickListener(v -> {
            if (!hasImage) return;

            Intent i = new Intent(v.getContext(), ViewBirdCardActivity.class);
            i.putExtra(EXTRA_IMAGE_URL, url);
            i.putExtra(EXTRA_COMMON_NAME, common);
            i.putExtra(EXTRA_SCI_NAME, sci);
            i.putExtra(EXTRA_STATE, state);
            i.putExtra(EXTRA_LOCALITY, locality);

            if (slot != null && slot.getTimestamp() != null) {
                i.putExtra(EXTRA_CAUGHT_TIME, slot.getTimestamp().getTime());
            }

            v.getContext().startActivity(i);
        });

        if (hasImage) {
            if (common != null && !common.trim().isEmpty()) {
                holder.txtBirdName.setText(common);
            } else if (sci != null && !sci.trim().isEmpty()) {
                holder.txtBirdName.setText(sci);
            } else {
                holder.txtBirdName.setText("Unknown Bird");
            }

            if (sci != null && !sci.trim().isEmpty()) {
                holder.txtScientific.setText(sci);
            } else {
                holder.txtScientific.setText("--");
            }

            holder.txtLocation.setText(CardFormatUtils.formatLocation(state, locality));
            holder.txtDateCaught.setText(formatCollectionDate(slot != null ? slot.getTimestamp() : null));

            Glide.with(holder.itemView.getContext())
                    .load(url)
                    .fitCenter()
                    .into(holder.imgBird);

            holder.itemView.setAlpha(1f);
        } else {
            holder.txtBirdName.setText("Unknown Bird");
            holder.txtScientific.setText("--");
            holder.txtLocation.setText("Location: --");
            holder.txtDateCaught.setText("Date caught: --");
            holder.imgBird.setImageResource(R.drawable.birddexlogo);

            holder.itemView.setAlpha(0.88f);
        }
    }

    @Override
    public int getItemCount() {
        return slots.size();
    }
    private String formatCollectionDate(java.util.Date date) {
        if (date == null) return "--";

        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("M/d/yy", java.util.Locale.US);

        return sdf.format(date);
    }
}