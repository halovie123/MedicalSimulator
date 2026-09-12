package com.medical.simulator.recording;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

/**
 * Background PPG CSV recorder.
 *
 * Appends waveform samples (written as they arrive, with elapsed seconds) and
 * periodically (1 Hz) stamps the current setpoints, so every recording has a
 * time series of the configured parameters.
 *
 * All file I/O happens on a private HandlerThread — the UI/BLE thread is
 * never blocked. CSV layout (header, exactly):
 *   Time_s,IR_Raw,HR,SpO2,RR,PI,Noise,Condition,AC_IR_mV,AC_RED_mv,DC_IR_mV,DC_RED_mV
 *
 * Waveform rows carry empty setpoint columns; 1 Hz stamp rows carry the full
 * current setpoint snapshot.
 */
public class PpgRecorder {

    private static final String TAG = "PpgRecorder";

    public static final String HEADER =
            "Time_s,IR_Raw,HR,SpO2,RR,PI,Noise,Condition,AC_IR_mV,AC_RED_mv,DC_IR_mV,DC_RED_mV";

    private static final long STAMP_INTERVAL_MS = 1_000;
    private static final int  SETPOINT_COLS     = 10; // HR..DC_RED_mV

    private final File outFile;

    private HandlerThread workerThread;
    private Handler       worker;

    private volatile boolean running = false;
    private volatile BufferedWriter writer;
    private volatile long   startNs      = 0;
    private volatile float  lastSample   = 0f;
    private volatile String[] setpointCols = new String[SETPOINT_COLS];

    private final Runnable stampRunnable = this::writeStampRow;

    public PpgRecorder(@NonNull File outFile) {
        this.outFile = outFile;
    }

    public File getFile() { return outFile; }

    public boolean isRunning() { return running; }

    /** Start the writer thread and open the CSV. Throws if the file can't be created. */
    public void start() throws IOException {
        if (running) return;

        // Fail fast if the file can't be opened
        BufferedWriter probe = openWriter();
        probe.close();

        startNs = System.nanoTime();
        for (int i = 0; i < SETPOINT_COLS; i++) setpointCols[i] = "";

        workerThread = new HandlerThread("PpgRecorder");
        workerThread.start();
        final Handler h = new Handler(workerThread.getLooper());
        worker = h;
        h.post(() -> {
            try {
                writer = openWriter();
                writeLine(HEADER);
            } catch (IOException e) {
                Log.e(TAG, "Failed to open CSV: " + e.getMessage());
                running = false;
            }
            h.postDelayed(stampRunnable, STAMP_INTERVAL_MS);
        });
        running = true;
    }

    /**
     * Append one waveform sample. Called from the BLE/main thread at ~50 Hz;
     * the actual write is posted to the background thread.
     */
    public void appendSample(float irRaw) {
        if (!running || writer == null) return;
        lastSample = irRaw;
        final float t = elapsedS();
        worker.post(() -> writeLine(String.format(Locale.US, "%.3f,%.2f,,,,,,,,,", t, irRaw)));
    }

    /** Update the setpoint snapshot used for periodic stamp rows. */
    public void updateSetpoints(int hr, int spo2, int rr, float pi, float noise, int condition,
                                float acIrMv, float acRedMv, float dcIrMv, float dcRedMv) {
        String[] cols = new String[SETPOINT_COLS];
        cols[0] = String.valueOf(hr);
        cols[1] = String.valueOf(spo2);
        cols[2] = String.valueOf(rr);
        cols[3] = String.format(Locale.US, "%.2f", pi);
        cols[4] = String.format(Locale.US, "%.2f", noise);
        cols[5] = String.valueOf(condition);
        cols[6] = String.format(Locale.US, "%.2f", acIrMv);
        cols[7] = String.format(Locale.US, "%.2f", acRedMv);
        cols[8] = String.format(Locale.US, "%.2f", dcIrMv);
        cols[9] = String.format(Locale.US, "%.2f", dcRedMv);
        setpointCols = cols;
    }

    /** Flush, close and shut down the writer thread. Idempotent. */
    public void stop() {
        if (!running) return;
        running = false;

        Handler h = worker;
        HandlerThread t = workerThread;
        if (h != null) {
            h.post(() -> {
                h.removeCallbacks(stampRunnable);
                closeWriter();
                if (t != null) t.quit();
            });
        }
        worker = null;
        workerThread = null;
    }

    // ─── Internals (all on worker thread) ────────────────────────────────────

    private float elapsedS() {
        return (System.nanoTime() - startNs) / 1_000_000_000f;
    }

    private BufferedWriter openWriter() throws IOException {
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        return new BufferedWriter(new FileWriter(outFile, true));
    }

    private void writeLine(String line) {
        BufferedWriter w = writer;
        if (w == null) return;
        try {
            w.write(line);
            w.newLine();
        } catch (IOException e) {
            Log.w(TAG, "CSV write failed: " + e.getMessage());
        }
    }

    private void writeStampRow() {
        if (!running) return;
        String[] cols = setpointCols;
        StringBuilder sb = new StringBuilder(96);
        sb.append(String.format(Locale.US, "%.3f,%.2f", elapsedS(), lastSample));
        for (int i = 0; i < SETPOINT_COLS; i++) {
            sb.append(',');
            if (i < cols.length) sb.append(cols[i] == null ? "" : cols[i]);
        }
        writeLine(sb.toString());
        // Flush once per second so data survives an unclean shutdown
        BufferedWriter w = writer;
        if (w != null) {
            try { w.flush(); } catch (IOException ignored) {}
        }
        if (running) {
            Handler h = worker;
            if (h != null) h.postDelayed(stampRunnable, STAMP_INTERVAL_MS);
        }
    }

    private void closeWriter() {
        BufferedWriter w = writer;
        writer = null;
        if (w == null) return;
        try {
            w.flush();
            w.close();
        } catch (IOException e) {
            Log.w(TAG, "CSV close failed: " + e.getMessage());
        }
    }
}
