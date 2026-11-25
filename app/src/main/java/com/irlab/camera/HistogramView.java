package com.irlab.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Luminance histogram of the unprocessed camera frame. */
final class HistogramView extends View {
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] data = new int[64];

    HistogramView(Context c) {
        super(c);
        bar.setColor(0xCCE8EAED);
        clip.setColor(Ui.ACCENT);
        setBackground(Ui.round(0x99000000, Ui.dp(c, 8), Ui.STROKE, Ui.dp(c, 1)));
    }

    void setData(int[] hist) {
        data = hist;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        int pad = Ui.dp(getContext(), 4);
        int max = 1;
        for (int v : data) max = Math.max(max, v);
        float bw = (w - 2f * pad) / data.length;
        float hh = h - 2f * pad;
        for (int i = 0; i < data.length; i++) {
            float v = (float) Math.sqrt(data[i] / (float) max); // sqrt scale keeps shadows readable
            float top = pad + hh * (1f - v);
            canvas.drawRect(pad + i * bw, top, pad + (i + 1) * bw - 1, h - pad, i >= data.length - 2 ? clip : bar);
        }
    }
}
