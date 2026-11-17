package com.medical.simulator.remote;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.medical.simulator.ui.LaneView;

/**
 * The v3 STREAM frames ("T<ir>,<red>;...|R<ir>,<red>;...", mV, ~50 Hz per side) kept as the last 8 s of the
 * Pi's TX plot (IR, RED) and of the two OPT101 receiver lanes. An empty value is a lane the Pi has no sample
 * for: it is kept as a gap and never filled in.
 */
public final class Streams {
    public static final float DT = 0.02f;          // the Pi sends one sample per 20 ms per side
    public static final int CAPACITY = 400;         // 8 s

    public static final class Ring {
        final float[] v = new float[CAPACITY];
        int start, size;

        void add(float x) {
            int i = (start + size) % CAPACITY;
            v[i] = x;
            if (size < CAPACITY) size++; else start = (start + 1) % CAPACITY;
        }

        public void clear() { start = 0; size = 0; }

        public int size() { return size; }

        float get(int i) { return v[(start + i) % CAPACITY]; }
    }

    public final Ring txIr = new Ring(), txRed = new Ring(), rxIr = new Ring(), rxRed = new Ring();
    private long lastTx, lastRx;
    private final float[] xs = new float[CAPACITY], ys = new float[CAPACITY];

    public void onFrame(@NonNull String frame) {
        int bar = frame.indexOf('|');
        if (!frame.startsWith("T") || bar < 0 || bar + 1 >= frame.length() || frame.charAt(bar + 1) != 'R') return;
        long now = SystemClock.elapsedRealtime();
        if (parse(frame.substring(1, bar), txIr, txRed)) lastTx = now;
        if (parse(frame.substring(bar + 2), rxIr, rxRed)) lastRx = now;
    }

    private static boolean parse(String side, Ring ir, Ring red) {
        if (side.isEmpty()) return false;
        boolean any = false;
        for (String pair : side.split(";")) {
            int comma = pair.indexOf(',');
            if (comma < 0) continue;
            ir.add(value(pair.substring(0, comma)));
            red.add(value(pair.substring(comma + 1)));
            any = true;
        }
        return any;
    }

    private static float value(String s) {
        if (s.isEmpty()) return Float.NaN;
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    /** True while TX samples arrived in the last `ms` milliseconds. */
    public boolean txLive(long ms) { return SystemClock.elapsedRealtime() - lastTx < ms; }

    public boolean rxLive(long ms) { return SystemClock.elapsedRealtime() - lastRx < ms; }

    public void clearTx() { txIr.clear(); txRed.clear(); lastTx = 0; }

    public void clearAll() { clearTx(); rxIr.clear(); rxRed.clear(); lastRx = 0; }

    /**
     * Copy a ring into a lane on a -8..0 s axis (newest sample at 0). despike: a sample far outside both
     * neighbours is drawn as their mean, like the light despiking of the Pi's RX plot; gaps stay gaps.
     */
    public void fill(@NonNull LaneView.Lane lane, @NonNull Ring ring, boolean despike) {
        int n = ring.size(), k = 0;
        for (int i = 0; i < n; i++) {
            float y = ring.get(i);
            if (Float.isNaN(y)) continue;
            if (despike && i > 0 && i < n - 1) {
                float a = ring.get(i - 1), b = ring.get(i + 1);
                if (!Float.isNaN(a) && !Float.isNaN(b)) {
                    float lo = Math.min(a, b), hi = Math.max(a, b), span = Math.max(1f, hi - lo);
                    if (y > hi + 2 * span || y < lo - 2 * span) y = (a + b) / 2;
                }
            }
            xs[k] = -(n - 1 - i) * DT;
            ys[k] = y;
            k++;
        }
        lane.setData(xs, ys, k);
    }
}
