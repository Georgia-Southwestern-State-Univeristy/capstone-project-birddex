package com.example.birddex;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class CameraOverlayView extends View {

    private final Paint boxPaint = new Paint();
    private final Paint textPaint = new Paint();
    private RectF box;

    public CameraOverlayView(Context context) {
        super(context);
        init();
    }

    public CameraOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(6f);
        boxPaint.setAntiAlias(true);
        boxPaint.setColor(0xFFFFFFFF);

        textPaint.setAntiAlias(true);
        textPaint.setTextSize(42f);
        textPaint.setColor(0xFFFFFFFF);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        // Center box (square-ish)
        float size = Math.min(w, h) * 0.55f;
        float left = (w - size) / 2f;
        float top = (h - size) / 2f;
        box = new RectF(left, top, left + size, top + size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (box != null) {
            canvas.drawRect(box, boxPaint);
            canvas.drawText("Center bird in box", box.left, box.top - 18f, textPaint);
        }
    }
}
