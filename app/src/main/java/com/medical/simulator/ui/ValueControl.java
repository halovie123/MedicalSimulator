package com.medical.simulator.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

/**
 * A number with − and + buttons and a slider, in two shapes (after the Pi app's ui/setpoint_card.py and
 * ui/touch_slider.py): a CARD (title, range and value, then − slider +; the Monitor setpoints) and an INLINE
 * control (label and value, then − slider +; Calibration and Neural pages).
 *
 * The listener gets every user change: done=false while the slider is dragged, done=true on release and on
 * −/+. A remote value (from the Pi) is applied with {@link #setRemote} and ignored while the user is touching
 * the control or for {@link #HOLD_MS} after their last change, so the Pi's echo never fights a gesture.
 */
public class ValueControl extends LinearLayout {

    public interface OnValue { void onValue(double value, boolean done); }

    public static final long HOLD_MS = 1500;

    private final double min, max, step;
    private final int decimals;
    private double value;
    private boolean dragging;
    private long heldUntil;
    private OnValue listener;

    private final TextView titleView, rangeView, valueView;
    private final SeekBar bar;
    private final Button minus, plus;

    public static ValueControl card(@NonNull Context c, String title, String unit, double min, double max,
                                    double step, int decimals, double value) {
        return new ValueControl(c, true, title, unit, min, max, step, decimals, value);
    }

    public static ValueControl inline(@NonNull Context c, String title, double min, double max, double step,
                                      int decimals, double value) {
        return new ValueControl(c, false, title, "", min, max, step, decimals, value);
    }

    private ValueControl(Context c, boolean card, String title, String unit, double min, double max, double step,
                         int decimals, double value) {
        super(c);
        this.min = min;
        this.max = max;
        this.step = step;
        this.decimals = decimals;
        setOrientation(VERTICAL);
        // Two compact rows: title (and range) with the value on the right, then − slider +. The slider gets the
        // width between the buttons, and the card takes about half the height of the old three-row layout.
        // An inline control sits on a recessed tile, so in a row of five each value reads with its own label.
        int pad = Ui.dp(c, card ? 7 : 6);
        setPadding(pad, Ui.dp(c, card ? 5 : 4), pad, Ui.dp(c, 4));
        setBackground(card ? Ui.round(c, Ui.PANEL, Ui.LINE, 8) : Ui.round(c, Ui.SUBTLE, 0, 6));

        LinearLayout head = Ui.row(c);
        titleView = Ui.line(c, title, card ? 11 : 9.5f, card, card ? Ui.INK : Ui.MUTED);
        rangeView = Ui.line(c, Ui.num(min) + "–" + Ui.num(max), 8.5f, false, Ui.FAINT);
        int bp = Ui.dp(c, 4);
        rangeView.setPadding(bp, Ui.dp(c, 1), bp, Ui.dp(c, 1));
        LinearLayout names = Ui.row(c);
        names.addView(titleView, Ui.lp(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 1f));
        if (card) {
            LayoutParams rp = Ui.lp(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            rp.leftMargin = Ui.dp(c, 3);
            names.addView(rangeView, rp);
        }
        head.addView(names, Ui.lp(0, LayoutParams.WRAP_CONTENT, 1f));
        valueView = Ui.line(c, "", card ? 17 : 11.5f, true, Ui.INK);
        LayoutParams vp = Ui.lp(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        vp.leftMargin = Ui.dp(c, 6);
        head.addView(valueView, vp);
        if (card && !unit.isEmpty()) {
            TextView unitView = Ui.line(c, unit, 9, false, Ui.MUTED);
            unitView.setPadding(Ui.dp(c, 3), Ui.dp(c, 3), 0, 0);
            head.addView(unitView);
        }
        addView(head);

        minus = Ui.outline(c, "−", card ? 14 : 13);
        plus = Ui.outline(c, "+", card ? 14 : 13);
        minus.setPadding(0, 0, 0, 0);
        plus.setPadding(0, 0, 0, 0);
        bar = new SeekBar(c);
        bar.setMax((int) Math.round((max - min) / step));
        bar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        bar.setThumbTintList(ColorStateList.valueOf(Ui.INK));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.LINE));
        bar.setPadding(Ui.dp(c, 9), 0, Ui.dp(c, 9), 0);
        int b = Ui.dp(c, card ? 24 : 22);
        LinearLayout row = Ui.row(c);
        row.addView(minus, Ui.lp(b, b));
        row.addView(bar, Ui.lp(0, b, 1f));
        row.addView(plus, Ui.lp(b, b));
        LayoutParams rowParams = Ui.lp(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = Ui.dp(c, card ? 4 : 3);
        addView(row, rowParams);

        minus.setOnClickListener(v -> nudge(-1));
        plus.setOnClickListener(v -> nudge(1));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                set(min + p * step);
                emit(false);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                emit(true);
            }
        });
        set(value);
    }

    public void setOnValue(OnValue l) { listener = l; }

    public double value() { return value; }

    public void setTitle(String title) { titleView.setText(title); }

    /** The card's top-right corner: the range, or a warning badge such as "RED AC manual". */
    public void setBadge(String badge) {
        if (badge == null || badge.isEmpty()) {
            rangeView.setText(Ui.num(min) + "–" + Ui.num(max));
            rangeView.setTextColor(Ui.FAINT);
            rangeView.setBackground(null);
        } else {
            rangeView.setText(badge);
            rangeView.setTextColor(Ui.WARN_INK);
            rangeView.setBackground(Ui.round(getContext(), Ui.WARN_BG, 0, 4));
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        minus.setEnabled(enabled);
        plus.setEnabled(enabled);
        bar.setEnabled(enabled);
        valueView.setTextColor(enabled ? Ui.INK : Ui.FAINT);
    }

    /** A value from the Pi; skipped while the user is changing this control. */
    public void setRemote(double v) {
        if (dragging || SystemClock.elapsedRealtime() < heldUntil) return;
        set(v);
    }

    private void nudge(int direction) {
        set(value + direction * step);
        emit(true);
    }

    private void emit(boolean done) {
        heldUntil = SystemClock.elapsedRealtime() + HOLD_MS;
        if (listener != null) listener.onValue(value, done);
    }

    private void set(double v) {
        v = Math.max(min, Math.min(max, min + Math.round((v - min) / step) * step));
        value = v;
        valueView.setText(decimals > 0 ? String.format(java.util.Locale.US, "%." + decimals + "f", v) : Ui.num(v));
        bar.setProgress((int) Math.round((v - min) / step));
    }

    public View valueView() { return valueView; }
}
