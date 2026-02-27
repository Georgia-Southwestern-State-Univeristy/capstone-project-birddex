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
import java.util.Date;

public class BirdCardGenerator {

    public static class BirdCardData {
        public final String birdName;
        public final String scientificName;
        public final String state;
        public final String locality;
        public final Date dateCaught;
        public final String footer;

        public BirdCardData(String birdName,
                            String scientificName,
                            String state,
                            String locality,
                            Date dateCaught,
                            String footer) {
            this.birdName = birdName;
            this.scientificName = scientificName;
            this.state = state;
            this.locality = locality;
            this.dateCaught = dateCaught;
            this.footer = footer;
        }
    }

    public static View buildCardView(@NonNull Context context,
                                     @NonNull Bitmap birdBitmap,
                                     @NonNull BirdCardData data) {
        FrameLayout fakeRoot = new FrameLayout(context);
        View v = LayoutInflater.from(context).inflate(R.layout.view_bird_card, fakeRoot, false);

        TextView txtBirdName = v.findViewById(R.id.txtBirdName);
        TextView txtScientific = v.findViewById(R.id.txtScientific);
        TextView txtLocation = v.findViewById(R.id.txtLocation);
        TextView txtDateCaught = v.findViewById(R.id.txtDateCaught);
        TextView txtFooter = v.findViewById(R.id.txtFooter);
        ImageView imgBird = v.findViewById(R.id.imgBird);
        LinearLayout statsRow = v.findViewById(R.id.statsRow);

        if (txtBirdName != null) txtBirdName.setText(data.birdName != null ? data.birdName : "Unknown");
        if (txtScientific != null) txtScientific.setText(data.scientificName != null ? data.scientificName : "");
        if (txtLocation != null) txtLocation.setText(CardFormatUtils.formatLocation(data.state, data.locality));
        if (txtDateCaught != null) txtDateCaught.setText(CardFormatUtils.formatCaughtDate(data.dateCaught));
        if (txtFooter != null) txtFooter.setText(data.footer != null ? data.footer : "");

        if (imgBird != null) {
            imgBird.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imgBird.setAdjustViewBounds(true);
            imgBird.setImageBitmap(birdBitmap);
        }

        if (statsRow != null) {
            // no-op
        }

        return v;
    }

    public static Bitmap renderViewToBitmap(@NonNull Context context, @NonNull View view) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int targetWidth = dm.widthPixels - dpToPx(context, 48);

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

    public static Bitmap renderViewToBitmap(@NonNull View view) {
        if (view.getWidth() == 0 || view.getHeight() == 0) {
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

    public static File savePngToAppFiles(@NonNull Context context,
                                         @NonNull Bitmap bitmap,
                                         @NonNull String baseName) throws IOException {
        File dir = new File(context.getFilesDir(), "cards");
        if (!dir.exists()) {
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