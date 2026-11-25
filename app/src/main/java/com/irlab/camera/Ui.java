package com.irlab.camera;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers for the programmatic dark UI. */
final class Ui {
    static final int BG = 0xFF0A0C0F;
    static final int PANEL = 0xE6121519;
    static final int CARD = 0xFF151A20;
    static final int STROKE = 0xFF2A313B;
    static final int TEXT = 0xFFE8EAED;
    static final int TEXT2 = 0xFF9AA3AD;
    static final int ACCENT = 0xFFFF5A36;
    static final int GOOD = 0xFF34C38F;
    static final int WARN = 0xFFF5B83D;
    static final int BAD = 0xFFFF6B6B;
    static final int NEUTRAL = 0xFF5F6B78;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(int fill, float radiusPx, int stroke, int strokePx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0) d.setStroke(strokePx, stroke);
        return d;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static TextView chip(Context c, String s) {
        TextView t = text(c, s, 12, TEXT2, true);
        t.setLetterSpacing(0.06f);
        t.setGravity(Gravity.CENTER);
        int h = dp(c, 14), v = dp(c, 8);
        t.setPadding(h, v, h, v);
        setChipSelected(t, false);
        return t;
    }

    static void setChipSelected(TextView chip, boolean selected) {
        Context c = chip.getContext();
        chip.setTextColor(selected ? 0xFF0A0C0F : TEXT2);
        chip.setBackground(round(selected ? ACCENT : 0x00000000, dp(c, 18), selected ? ACCENT : STROKE, dp(c, 1)));
    }

    static TextView button(Context c, String s, boolean primary) {
        TextView t = text(c, s, 14, primary ? 0xFF0A0C0F : TEXT, true);
        t.setGravity(Gravity.CENTER);
        int h = dp(c, 18), v = dp(c, 12);
        t.setPadding(h, v, h, v);
        t.setBackground(round(primary ? ACCENT : CARD, dp(c, 12), STROKE, primary ? 0 : dp(c, 1)));
        t.setClickable(true);
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        l.setBackground(round(CARD, dp(c, 16), STROKE, dp(c, 1)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        l.setLayoutParams(lp);
        return l;
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static View spacer(Context c, float weight) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, weight));
        return v;
    }
}
