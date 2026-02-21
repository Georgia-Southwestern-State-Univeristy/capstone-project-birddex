package com.birddex.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * BirdCardGenerator builds a "pseudo" collectible bird card view and can render/save it as a PNG.
 */
public class BirdCardGenerator {

    /**
     * Simple data container for the card text fields.
     */
    public static class BirdCardData {
        public final String birdName;
        public final String scientificName;
        public final String rarity;
        public final String confidence;
        public final String footer;

        public BirdCardData(String birdName, String scientificName, String rarity, String confidence, String footer) {
            this.birdName = birdName;
            this.scientificName = scientificName;
            this.rarity = rarity;
            this.confidence = confidence;
            this.footer = footer;
        }
    }

    /** Inflates the card layout and populates text + image. */
    public static View buildCardView(@NonNull Context context, @NonNull Bitmap birdBitmap, @NonNull BirdCardData data) {
        // IMPORTANT: do NOT inflate with null root (can break layout params/measurement)
        FrameLayout fakeRoot = new FrameLayout(context);
        View v = LayoutInflater.from(context).inflate(R.layout.view_bird_card, fakeRoot, false);

        TextView txtBirdName = v.findViewById(R.id.txtBirdName);
        TextView txtScientific = v.findViewById(R.id.txtScientific);
        TextView txtRarity = v.findViewById(R.id.txtRarity);
        TextView txtConfidence = v.findViewById(R.id.txtConfidence);
        TextView txtFooter = v.findViewById(R.id.txtFooter);
        ImageView imgBird = v.findViewById(R.id.imgBird);
        LinearLayout statsRow = v.findViewById(R.id.statsRow);

        // Populate text
        if (txtBirdName != null) txtBirdName.setText(data.birdName != null ? data.birdName : "Unknown");
        if (txtScientific != null) txtScientific.setText(data.scientificName != null ? data.scientificName : "");
        if (txtRarity != null) txtRarity.setText("Rarity: " + (data.rarity != null ? data.rarity : "Unknown"));
        if (txtConfidence != null) txtConfidence.setText("Confidence: " + (data.confidence != null ? data.confidence : "--"));
        if (txtFooter != null) txtFooter.setText(data.footer != null ? data.footer : "");

        // Populate image (force shrink-to-fit)
        if (imgBird != null) {
            imgBird.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imgBird.setAdjustViewBounds(true);
            imgBird.setImageBitmap(birdBitmap);
        }

        // statsRow is here if you want to hide/show later; keep reference to avoid "never used" warnings
        if (statsRow != null) {
            // no-op (kept for future layout tweaks)
        }

        return v;
    }

    /**
     * Renders a view to a Bitmap (used to save the card as an image).
     */
    public static Bitmap renderViewToBitmap(@NonNull Context context, @NonNull View view) {
        // Target width: screen width minus a little margin so it matches how it looks on-device
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int targetWidth = dm.widthPixels - dpToPx(context, 48); // ~24dp margin each side

        if (targetWidth < dpToPx(context, 280)) {
            targetWidth = dpToPx(context, 280);
        }

        int widthSpec = View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);

        view.measure(widthSpec, heightSpec);
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());

        Bitmap bitmap = Bitmap.createBitmap(view.getMeasuredWidth(), view.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        view.draw(canvas);

        return bitmap;
    }

    /**
     * Convenience overload if you already laid out the view in a container and just want a bitmap.
     */
    public static Bitmap renderViewToBitmap(@NonNull View view) {
        if (view.getWidth() == 0 || view.getHeight() == 0) {
            // Fallback: give it a reasonable measure if it hasn't been laid out
            int widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.AT_MOST);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            view.measure(widthSpec, heightSpec);
            view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        }

        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        view.draw(canvas);
        return bitmap;
    }

    /**
     * Saves a bitmap as a PNG in app internal storage and returns the File.
     */
    public static File savePngToAppFiles(@NonNull Context context, @NonNull Bitmap bitmap, @NonNull String baseName) throws IOException {
        File dir = new File(context.getFilesDir(), "cards");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }

        File outFile = new File(dir, baseName + ".png");
        FileOutputStream fos = new FileOutputStream(outFile);
        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
        } finally {
            try { fos.close(); } catch (Exception ignored) {}
        }
        return outFile;
    }

    private static int dpToPx(@NonNull Context context, int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics()
        ));
    }
}