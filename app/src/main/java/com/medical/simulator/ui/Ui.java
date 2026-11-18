package com.medical.simulator.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

/**
 * Palette, sizes and widget factories of the Pi app (PPG_simulator_raspi ui/theme.py, dark mode), so the phone
 * looks like the Pi screen: charcoal surfaces, hairline card borders, teal accent, IR green and RED salmon lanes.
 */
public final class Ui {
    private Ui() {}

    // Surfaces
    public static final int BG = Color.parseColor("#111619");
    public static final int PANEL = Color.parseColor("#1A2126");
    public static final int SUBTLE = Color.parseColor("#232C32");
    public static final int LINE = Color.parseColor("#2A343B");
    public static final int BORDER = Color.parseColor("#3A464E");
    // Text
    public static final int INK = Color.parseColor("#E4EAEE");
    public static final int MUTED = Color.parseColor("#9AA7B0");
    public static final int FAINT = Color.parseColor("#6C7882");
    // Buttons
    public static final int BUTTON = Color.parseColor("#2E3940");
    // Signal surfaces
    public static final int DARK = Color.parseColor("#0D1215");
    public static final int GRID = Color.parseColor("#2E3A41");
    public static final int AXIS = Color.parseColor("#8C9AA3");
    public static final int PLOT_TEXT = Color.parseColor("#D3DCE1");
    public static final int IR = Color.parseColor("#7FD8BE");
    public static final int RED = Color.parseColor("#F4958C");
    // States
    public static final int ACCENT = Color.parseColor("#3A8A8D");
    public static final int ERROR = Color.parseColor("#C84848");
    public static final int OK = Color.parseColor("#4CC08A");
    public static final int OK_BG = Color.parseColor("#173A2A");
    public static final int ERROR_BG = Color.parseColor("#48201F");
    public static final int WARN = Color.parseColor("#E0A63A");
    public static final int WARN_BG = Color.parseColor("#3B3017");
    public static final int WARN_INK = Color.parseColor("#F0C46A");
    public static final int WHITE = Color.WHITE;

    // Control sizes (dp / sp), one set for every page so buttons and sliders stay compact and the plots get the room
    /** Page action buttons (Start output, Play LED, Export CSV ...). */
    public static final int ACTION_H = 28;
    public static final float ACTION_TEXT = 10.5f;
    /** Buttons and menus in a card header (Normal ▾, 3-Gaussian, Beep). */
    public static final int TOOL_H = 24;
    public static final float TOOL_TEXT = 10f;

    /** A state name the Pi sends ("ok", "error", "warn", "accent", "muted") as a text colour. */
    public static int kindColor(String kind) {
        if (kind == null) return MUTED;
        switch (kind) {
            case "ok": return OK;
            case "error": return ERROR;
            case "warn": return WARN_INK;
            case "accent": return ACCENT;
            case "off": return FAINT;
            default: return MUTED;
        }
    }

    public static int dp(@NonNull Context c, float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, c.getResources().getDisplayMetrics()));
    }

    public static float sp(@NonNull Context c, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, c.getResources().getDisplayMetrics());
    }

    public static GradientDrawable round(@NonNull Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) d.setStroke(Math.max(1, dp(c, 1)), stroke);
        return d;
    }

    public static TextView text(@NonNull Context c, CharSequence s, float sizeSp, boolean bold, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    public static TextView line(@NonNull Context c, CharSequence s, float sizeSp, boolean bold, int color) {
        TextView t = text(c, s, sizeSp, bold, color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** Card: PANEL fill, hairline LINE border, 8 dp corners (the Pi's T.card). */
    public static LinearLayout card(@NonNull Context c, int orientation) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(orientation);
        l.setBackground(round(c, PANEL, LINE, 8));
        return l;
    }

    /** Recessed charcoal holder for a plot (the Pi's plot_holder). */
    public static LinearLayout plotHolder(@NonNull Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(c, DARK, 0, 6));
        int p = dp(c, 2);
        l.setPadding(p, p, p, p);
        return l;
    }

    private static Button baseButton(@NonNull Context c, CharSequence label, float sizeSp) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setGravity(Gravity.CENTER);
        int h = dp(c, 9);
        b.setPadding(h, 0, h, 0);
        return b;
    }

    /** Filled accent button (the Pi's primary action: Start output, Play LED, Start). */
    public static Button primary(@NonNull Context c, CharSequence label) {
        Button b = baseButton(c, label, ACTION_TEXT);
        paintPrimary(b, false);
        return b;
    }

    /** Primary in its running state: ERROR fill, as the Pi's Stop buttons. */
    public static void paintPrimary(@NonNull Button b, boolean running) {
        Context c = b.getContext();
        int fill = running ? ERROR : ACCENT;
        // Disabled: a neutral tile with muted text (faint text on the teal fill could not be read).
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, round(c, SUBTLE, BORDER, 6));
        states.addState(new int[]{}, round(c, fill, 0, 6));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), states, null));
        b.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{MUTED, WHITE}));
    }

    /** Outlined neutral button (Record CSV, 3-Gaussian, New sequence, −/+). */
    public static Button outline(@NonNull Context c, CharSequence label, float sizeSp) {
        Button b = baseButton(c, label, sizeSp);
        paintOutline(b, INK, BORDER);
        return b;
    }

    public static void paintOutline(@NonNull Button b, int textColor, int border) {
        Context c = b.getContext();
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), round(c, PANEL, border, 6), null));
        b.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{FAINT, textColor}));
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout row(@NonNull Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout column(@NonNull Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static View space(@NonNull Context c) {
        View v = new View(c);
        v.setLayoutParams(lp(0, 0, 1f));
        return v;
    }

    /** Text without a trailing ".0": 1500, 2.5. */
    public static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) return String.valueOf((long) Math.rint(v));
        return String.format(java.util.Locale.US, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
