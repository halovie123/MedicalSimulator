package com.medical.simulator.playback;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;

/**
 * PPG recording playback engine.
 *
 * Loads a recorder CSV (Time_s + IR_Raw columns) off the main thread and
 * re-emits the IR waveform at the recorded rate (uses Time_s deltas; falls
 * back to 50 Hz whenever a delta is unusable).
 *
 * Screen review only: it never touches BLE and never feeds metric cards —
 * the owner wires the sample sink (e.g. straight into WaveformSurfaceView).
 */
public class PpgPlayback {

    private static final String TAG = "PpgPlayback";

    /** Fallback sample interval when Time_s deltas are unusable (50 Hz). */
    private static final float FALLBACK_DT_S = 1f / 50f;
    /** Any delta outside (0, MAX_DT_S] is treated as unusable. */
    private static final float MAX_DT_S = 2.0f;

    public enum State { IDLE, PLAYING, PAUSED }

    /** Receives playback samples. Called on the playback thread (sinks must be thread-safe). */
    public interface SampleSink {
        void onPlaybackSample(float value);
    }

    /** Async events, always delivered on the main thread. */
    public interface EventListener {
        void onPlaybackStopped(boolean finishedToEnd);
        void onPlaybackError(@NonNull String message);
    }

    private static final class Loaded {
        final float[] values;
        final long[]  delaysMs;
        Loaded(float[] values, long[] delaysMs) {
            this.values = values;
            this.delaysMs = delaysMs;
        }
    }

    private HandlerThread workerThread;
    private Handler       worker;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Runnable tick = this::deliverNext;

    @Nullable private SampleSink    sink;
    @Nullable private EventListener listener;

    @Nullable private Loaded loaded;
    private int idx = 0;

    private volatile State state = State.IDLE;

    public State getState() { return state; }

    /** Load the CSV asynchronously and start playing. Stops any current session first. */
    public void start(@NonNull File csv, @NonNull SampleSink sink, @Nullable EventListener listener) {
        stopInternal(false);

        this.sink = sink;
        this.listener = listener;
        ensureThread();

        worker.post(() -> {
            Loaded data = load(csv);
            if (data == null) {
                notifyError();
                return;
            }
            loaded = data;
            idx = 0;
            state = State.PLAYING;
            worker.post(tick);
        });
    }

    public void pause() {
        if (state != State.PLAYING) return;
        state = State.PAUSED;
        if (worker != null) worker.removeCallbacks(tick);
    }

    public void resume() {
        if (state != State.PAUSED) return;
        state = State.PLAYING;
        if (worker != null) worker.post(tick);
    }

    /** Stop and release. Safe to call from any thread, idempotent. */
    public void stop() {
        stopInternal(false);
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private void ensureThread() {
        if (workerThread == null || !workerThread.isAlive()) {
            workerThread = new HandlerThread("PpgPlayback");
            workerThread.start();
            worker = new Handler(workerThread.getLooper());
        }
    }

    private void deliverNext() {
        if (state != State.PLAYING) return;

        Loaded data = loaded;
        if (data == null || idx >= data.values.length) {
            stopInternal(true);
            return;
        }

        SampleSink s = sink;
        if (s != null) s.onPlaybackSample(data.values[idx]);

        long delayMs = data.delaysMs[idx];
        idx++;

        if (idx >= data.values.length) {
            stopInternal(true);   // last sample delivered — finish cleanly
            return;
        }
        if (worker != null) worker.postDelayed(tick, Math.max(1, delayMs));
    }

    private void stopInternal(boolean finished) {
        boolean wasActive = (state != State.IDLE);
        state = State.IDLE;
        loaded = null;

        Handler h = worker;
        if (h != null) h.removeCallbacks(tick);

        HandlerThread t = workerThread;
        if (t != null && t.isAlive()) {
            if (h != null) h.post(t::quit);
            workerThread = null;
            worker = null;
        }

        if (wasActive) {
            final boolean done = finished;
            main.post(() -> {
                EventListener l = listener;
                if (l != null) l.onPlaybackStopped(done);
            });
        }
    }

    private void notifyError() {
        stopInternal(false);
        main.post(() -> {
            EventListener l = listener;
            if (l != null) l.onPlaybackError("load_failed");
        });
    }

    // ─── CSV loading (worker thread) ──────────────────────────────────────────

    /** Returns samples + per-sample delays, or null if the file is missing/unusable. */
    @Nullable
    private Loaded load(@NonNull File csv) {
        ArrayList<Float> ts  = new ArrayList<>();
        ArrayList<Float> out = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(csv))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (first) { // skip header (defensive: also handles header-less files)
                    first = false;
                    char c0 = line.charAt(0);
                    if (!Character.isDigit(c0) && c0 != '-' && c0 != '+') continue;
                }
                String[] cols = line.split(",");
                if (cols.length < 2) continue;
                try {
                    ts.add(Float.parseFloat(cols[0].trim()));
                    out.add(Float.parseFloat(cols[1].trim()));
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "CSV read failed: " + e.getMessage());
            return null;
        }

        int n = out.size();
        if (n < 2) return null;

        float[] vals = new float[n];
        long[]  dts  = new long[n];
        for (int i = 0; i < n; i++) {
            vals[i] = out.get(i);
            dts[i]  = (long) (FALLBACK_DT_S * 1000f);
        }
        for (int i = 1; i < n; i++) {
            float dt = ts.get(i) - ts.get(i - 1);
            if (Float.isNaN(dt) || dt <= 0f || dt > MAX_DT_S) dt = FALLBACK_DT_S;
            dts[i - 1] = (long) (dt * 1000f);
        }
        return new Loaded(vals, dts);
    }
}
