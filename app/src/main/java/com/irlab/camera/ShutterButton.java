package com.irlab.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Round shutter button with a progress ring for multi-frame (low-light) captures. */
final class ShutterButton extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progress = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private float fraction = -1f;

    ShutterButton(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 4));
        ring.setColor(0xFFE8EAED);
        fill.setColor(0xFFE8EAED);
        progress.setStyle(Paint.Style.STROKE);
        progress.setStrokeWidth(Ui.dp(c, 4));
        progress.setStrokeCap(Paint.Cap.ROUND);
        progress.setColor(Ui.ACCENT);
        setClickable(true);
        setContentDescription("Capture");
    }

    /** -1 = idle; 0..1 = capture progress. */
    void setProgress(float f) {
        fraction = f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - ring.getStrokeWidth();
        canvas.drawCircle(cx, cy, r, ring);
        fill.setColor(isPressed() ? 0xFFB0B4B8 : (fraction >= 0 ? Ui.ACCENT : 0xFFE8EAED));
        canvas.drawCircle(cx, cy, r * 0.80f, fill);
        if (fraction >= 0) {
            oval.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(oval, -90, 360 * fraction, false, progress);
        }
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }
}
