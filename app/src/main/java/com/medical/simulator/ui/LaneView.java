package com.medical.simulator.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stacked, independently auto-scaled lanes on one time axis: the phone version of the Pi's LaneView
 * (PPG_simulator_raspi ui/trace_view.py). Each lane has a coloured title, a muted detail, up to two
 * right-aligned badges and, when it has no samples, its own status message. Values are drawn as given.
 */
public class LaneView extends View {

    public static final class Lane {
        public String title = "", detail = "", badge = "", badge2 = "", message = "", sub = "";
        public int color = Ui.IR;
        float[] x = new float[0];
        float[] y = new float[0];
        int n;

        /** Samples (x in seconds on the view's axis, y in the lane's unit); the arrays are copied. */
        public void setData(@NonNull float[] xs, @NonNull float[] ys, int count) {
            if (x.length < count) {
                x = new float[count];
                y = new float[count];
            }
            System.arraycopy(xs, 0, x, 0, count);
            System.arraycopy(ys, 0, y, 0, count);
            n = count;
        }

        public void clear() { n = 0; }

        public int count() { return n; }
    }

    private final List<Lane> lanes = new ArrayList<>();
    private float axisStart = -8f, axisEnd = 0f;
    private String placeholder = "", placeholderSub = "";

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Paint trace = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float density;

    public LaneView(Context context) { this(context, null); }

    public LaneView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        grid.setColor(Ui.GRID);
        grid.setStrokeWidth(Math.max(1f, density * 0.7f));
        trace.setStyle(Paint.Style.STROKE);
        trace.setStrokeWidth(1.4f * density);
        trace.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(Ui.DARK);
    }

    public void setLaneCount(int count) {
        while (lanes.size() < count) lanes.add(new Lane());
        while (lanes.size() > count) lanes.remove(lanes.size() - 1);
    }

    public Lane lane(int i) { return lanes.get(i); }

    public int laneCount() { return lanes.size(); }

    /** Time axis in seconds, e.g. -8..0 for the live RX window or 0..8 for a stored window. */
    public void setAxis(float start, float end) {
        axisStart = start;
        axisEnd = end;
    }

    /** Text shown centred while no lane has samples (the Pi's "Output is off" placeholder). */
    public void setPlaceholder(@Nullable String text, @Nullable String sub) {
        placeholder = text == null ? "" : text;
        placeholderSub = sub == null ? "" : sub;
    }

    private float sp(float v) { return v * getResources().getDisplayMetrics().scaledDensity; }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        final float w = getWidth(), h = getHeight();
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, 5 * density, 5 * density, fill);
        final float axisH = 15 * density;
        final float left = 30 * density, right = w - 8 * density;
        final int count = Math.max(1, lanes.size());
        final float laneH = (h - axisH) / count;
        boolean any = false;
        for (Lane lane : lanes) any |= lane.n > 1;

        for (int i = 0; i < lanes.size(); i++) {
            Lane lane = lanes.get(i);
            float top = i * laneH;
            drawHeader(canvas, lane, top, left, right);
            float plotTop = top + (lane.badge2.isEmpty() ? 22 : 30) * density;
            float plotBottom = top + laneH - 6 * density;
            if (plotBottom - plotTop < 12 * density) plotTop = plotBottom - 12 * density;
            if (lane.n > 1) {
                drawLane(canvas, lane, left, right, plotTop, plotBottom);
            } else {
                canvas.drawLine(left, plotBottom, right, plotBottom, grid);
                if (!lane.message.isEmpty()) {
                    float mid = (plotTop + plotBottom) / 2;
                    text(canvas, lane.message, (left + right) / 2, mid, 11, Ui.PLOT_TEXT, false, Paint.Align.CENTER);
                    if (!lane.sub.isEmpty())
                        text(canvas, lane.sub, (left + right) / 2, mid + sp(13), 9, Ui.AXIS, false, Paint.Align.CENTER);
                }
            }
        }
        drawAxis(canvas, left, right, h - axisH, h);
        if (!any && !placeholder.isEmpty()) {
            // In the first lane's plot area, as on the Pi, so it never runs into the next lane's header.
            float mid = lanes.size() > 1 ? laneH * 0.55f : (h - axisH) / 2;
            text(canvas, placeholder, (left + right) / 2, mid, 12, Ui.PLOT_TEXT, false, Paint.Align.CENTER);
            if (!placeholderSub.isEmpty())
                text(canvas, placeholderSub, (left + right) / 2, mid + sp(14), 9, Ui.AXIS, false, Paint.Align.CENTER);
        }
    }

    private void drawHeader(Canvas canvas, Lane lane, float top, float left, float right) {
        float base = top + sp(12) + 2 * density;
        float x = left + 4 * density;
        float tw = text(canvas, lane.title, x, base, 10.5f, lane.color, true, Paint.Align.LEFT);
        float badgeW = 0;
        if (!lane.badge.isEmpty()) {
            badgeW = measure(lane.badge, 9.5f, true);
            text(canvas, lane.badge, right, base, 9.5f, lane.color, true, Paint.Align.RIGHT);
        }
        if (!lane.detail.isEmpty()) {
            float room = right - badgeW - 10 * density - (x + tw + 6 * density);
            text(canvas, ellipsize(lane.detail, room, 9), x + tw + 6 * density, base, 9, Ui.AXIS, false, Paint.Align.LEFT);
        }
        if (!lane.badge2.isEmpty()) {
            String b2 = ellipsize(lane.badge2, right - left, 9.5f);
            text(canvas, b2, right, base + sp(11), 9.5f, lane.color, true, Paint.Align.RIGHT);
        }
    }

    private void drawLane(Canvas canvas, Lane lane, float left, float right, float top, float bottom) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < lane.n; i++) {
            if (lane.x[i] < axisStart || lane.x[i] > axisEnd) continue;
            lo = Math.min(lo, lane.y[i]);
            hi = Math.max(hi, lane.y[i]);
        }
        if (lo > hi) { lo = 0; hi = 1; }
        float span = hi - lo;
        if (span < 1e-3f) span = Math.max(1f, Math.abs(hi) * 0.02f);
        float pad = span * 0.08f;
        lo -= pad;
        hi += pad;
        span = hi - lo;
        for (int g = 0; g < 3; g++) {
            float yy = top + (bottom - top) * g / 2f;
            canvas.drawLine(left, yy, right, yy, grid);
            float value = hi - span * g / 2f;
            text(canvas, format(value, span), left - 4 * density, yy + sp(3.5f), 9, Ui.AXIS, false, Paint.Align.RIGHT);
        }
        trace.setColor(lane.color);
        path.reset();
        boolean started = false;
        float axisSpan = axisEnd - axisStart;
        for (int i = 0; i < lane.n; i++) {
            float xv = lane.x[i];
            if (xv < axisStart || xv > axisEnd) { started = false; continue; }
            float px = left + (xv - axisStart) / axisSpan * (right - left);
            float py = bottom - (lane.y[i] - lo) / span * (bottom - top);
            if (!started) { path.moveTo(px, py); started = true; } else path.lineTo(px, py);
        }
        canvas.drawPath(path, trace);
    }

    private void drawAxis(Canvas canvas, float left, float right, float top, float bottom) {
        float base = bottom - 4 * density;
        text(canvas, "s", left - 14 * density, base, 9, Ui.AXIS, false, Paint.Align.LEFT);
        float span = axisEnd - axisStart;
        int step = span > 12 ? 4 : (span > 6 ? 1 : 1);
        int first = (int) Math.ceil(axisStart);
        for (int t = first; t <= axisEnd + 1e-3; t += step) {
            float px = left + (t - axisStart) / span * (right - left);
            canvas.drawLine(px, top, px, top + 3 * density, grid);
            text(canvas, String.valueOf(t), px, base, 9, Ui.AXIS, false, Paint.Align.CENTER);
        }
    }

    private static String format(float v, float span) {
        if (span >= 20) return String.format(Locale.US, "%.0f", v);
        if (span >= 2) return String.format(Locale.US, "%.1f", v);
        return String.format(Locale.US, "%.2f", v);
    }

    private float measure(String s, float sizeSp, boolean bold) {
        textPaint.setTextSize(sp(sizeSp));
        textPaint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return textPaint.measureText(s);
    }

    private String ellipsize(String s, float room, float sizeSp) {
        if (room <= 0) return "";
        if (measure(s, sizeSp, false) <= room) return s;
        int end = s.length();
        while (end > 1 && measure(s.substring(0, end) + "…", sizeSp, false) > room) end--;
        return s.substring(0, end) + "…";
    }

    private float text(Canvas canvas, String s, float x, float y, float sizeSp, int color, boolean bold, Paint.Align align) {
        textPaint.setTextSize(sp(sizeSp));
        textPaint.setColor(color);
        textPaint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        textPaint.setTextAlign(align);
        canvas.drawText(s, x, y, textPaint);
        return textPaint.measureText(s);
    }
}
