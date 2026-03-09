package com.birddex.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * CameraOverlayView: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class CameraOverlayView extends View {

    private final Paint boxPaint = new Paint();
    private final Paint textPaint = new Paint();
    private RectF box;

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public CameraOverlayView(Context context) {
        super(context);
        init();
    }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public CameraOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    /**
     * Initializes helpers, adapters, listeners, or default values used by the rest of this file.
     */
    private void init() {
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(6f);
        boxPaint.setAntiAlias(true);
        boxPaint.setColor(0xFFFFFFFF);

        textPaint.setAntiAlias(true);
        textPaint.setTextSize(42f);
        textPaint.setColor(0xFFFFFFFF);
    }

    /**
     * Main logic block for this part of the feature.
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        // Center box (square-ish)
        float size = Math.min(w, h) * 0.55f;
        float left = (w - size) / 2f;
        float top = (h - size) / 2f;
        box = new RectF(left, top, left + size, top + size);
    }

    /**
     * Main logic block for this part of the feature.
     * Bitmap/rendering work happens here, so this block is shaping the final card/image output
     * rather than just text data.
     */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (box != null) {
            canvas.drawRect(box, boxPaint);
            canvas.drawText("Center bird in box", box.left, box.top - 18f, textPaint);
        }
    }
}
