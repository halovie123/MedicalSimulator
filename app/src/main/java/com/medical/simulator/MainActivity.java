package com.medical.simulator;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.medical.simulator.ble.RpiBleManager;
import com.medical.simulator.model.SimulatorParams;
import com.medical.simulator.playback.PpgPlayback;
import com.medical.simulator.recording.PpgRecorder;
import com.medical.simulator.ui.WaveformSurfaceView;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Main activity: fullscreen landscape medical monitor UI.
 *
 * Layout regions (landscape):
 *   [Status bar (top)]
 *   [Metrics panel (left)] [Waveform display (centre-right)]
 *   [Control panel (bottom): sliders + condition buttons]
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // ─── BLE & params ─────────────────────────────────────────────────────────
    private RpiBleManager    bleManager;
    private SimulatorParams  params = new SimulatorParams();

    // Debounce — don't spam the ESP32 on every slider tick
    private final Handler   sendHandler   = new Handler(Looper.getMainLooper());
    private static final long SEND_DEBOUNCE_MS = 250;
    private final Runnable sendRunnable   = this::sendCurrentParams;
    private final Set<String> pendingParamKeys = new LinkedHashSet<>();
    private static final long PARAM_CONFIRM_TIMEOUT_MS = 2_000L;
    private final Map<String, Long> pendingParamUntil = new HashMap<>();

    // ─── UI references — status bar ───────────────────────────────────────────
    private View     statusDot;
    private TextView tvConnectionStatus;
    private TextView tvDeviceName;

    // ─── UI references — top action bar ───────────────────────────────────────
    private Button   btnScan;
    private Button   btnDisconnect;
    private Button   btnRun;

    // ─── UI references — metric cards ─────────────────────────────────────────
    private TextView tvHrValue;
    private TextView tvSpo2Value;
    private TextView tvRrValue;
    private TextView tvPiValue;
    private TextView tvNoiseValue;
    private TextView tvModeValue;

    // ─── UI references — waveform ─────────────────────────────────────────────
    private WaveformSurfaceView waveformView;

    /** Mirror of the Pi's run-state; unknown until the first matching status packet. */
    private boolean simulationRunning = false;
    /** Timestamp (elapsedRealtime) of the last optimistic RUN toggle — grace window guard. */
    private long    pendingRunToggleAt = 0L;
    private static final long RUN_TOGGLE_GRACE_MS = 1_000L;

    // ─── UI references — control panel ────────────────────────────────────────
    // Each adjustable slider carries drag/sync state for the echo/sync rule.
    private static final class LiveSlider {
        final SeekBar  bar;
        final TextView label;
        boolean dragging;   // user is currently touching this slider
        boolean syncing;    // programmatic setProgress in flight (ignore callbacks)
        LiveSlider(SeekBar bar, TextView label) { this.bar = bar; this.label = label; }
    }

    private LiveSlider sliderHr;
    private LiveSlider sliderSpo2;
    private LiveSlider sliderRr;
    private LiveSlider sliderPi;
    private LiveSlider sliderNoise;
    private LiveSlider sliderAcIr;
    private LiveSlider sliderAcRed;
    private LiveSlider sliderDcIr;
    private LiveSlider sliderDcRed;

    // Condition mode buttons
    private Button[] modeButtons;
    // Last mode rendered on screen. Status packets arrive at 5 Hz; do not
    // re-apply the same selected/alpha state on every packet.
    private int renderedMode = -1;

    // ─── UI references — recording / playback ─────────────────────────────────
    private Button    btnRec;
    private Button    btnPlay;
    private Button    btnStopPlayback;
    private TextView  tvRecIndicator;
    private TextView  tvPlaybackIndicator;
    private TextView  tvRemoteLabel;   // "▶ <file>" when the Pi plays a file the phone lacks
    private File      recordingsDir;

    private PpgRecorder recorder;
    private PpgPlayback playback;

    // ─── Protocol v2 remote-sync state (main thread only) ─────────────────────
    /** True while a remote-originated change is being applied — suppresses command echo. */
    private boolean applyingRemote = false;
    /** True once onDestroy begins — never send action commands during teardown. */
    private boolean shuttingDown   = false;

    // Playback mirror tracking (see applyRemotePlayback)
    private boolean pbSessionMirrored = false;
    private int     remotePbCode      = 0;        // last applied Pi pb value
    @Nullable private String remotePbFile = null; // last applied Pi pb_file
    @Nullable private String localPlaybackFile = null;

    // ─── Permission launcher ──────────────────────────────────────────────────
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    this::onPermissionsResult
            );

    private final ActivityResultLauncher<Intent> enableBluetoothLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> checkAndInitBle()
            );

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        enterFullscreenMode(); // Giữ nguyên để ẩn thanh trạng thái ngay lập tức
        bindViews();           // Ánh xạ các View ngay lập tức

        // Sử dụng Handler để chạy các lệnh nặng sau khi UI đã sẵn sàng
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            setupSliders();
            setupModeButtons();
            setupActionButtons();

            // Khởi tạo BLE sau cùng
            bleManager = new RpiBleManager(getApplicationContext());
            observeBle();
            checkAndInitBle();
        }, 150); // Trì hoãn 150ms
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        shuttingDown = true;          // never emit action commands during teardown
        sendHandler.removeCallbacks(sendRunnable);
        stopPlayback();
        stopRecording();
        if (bleManager != null) {
            bleManager.disconnectAndClose();
            bleManager.close();
        }
    }

    // ─── Fullscreen immersive mode ────────────────────────────────────────────

    private void enterFullscreenMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController ctrl = getWindow().getInsetsController();
            if (ctrl != null) {
                ctrl.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                ctrl.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    // ─── View binding ─────────────────────────────────────────────────────────

    private void bindViews() {
        // Status bar
        statusDot          = findViewById(R.id.statusDot);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvDeviceName       = findViewById(R.id.tvDeviceName);
        btnScan            = findViewById(R.id.btnScan);
        btnDisconnect      = findViewById(R.id.btnDisconnect);
        btnRun             = findViewById(R.id.btnRun);

        // Metric cards
        tvHrValue          = findViewById(R.id.tvHrValue);
        tvSpo2Value        = findViewById(R.id.tvSpo2Value);
        tvRrValue          = findViewById(R.id.tvRrValue);
        tvPiValue          = findViewById(R.id.tvPiValue);
        tvNoiseValue       = findViewById(R.id.tvNoiseValue);
        tvModeValue        = findViewById(R.id.tvModeValue);

        // Waveform
        waveformView = findViewById(R.id.waveformView);

        // Recording / playback controls
        btnRec              = findViewById(R.id.btnRec);
        btnPlay             = findViewById(R.id.btnPlay);
        btnStopPlayback     = findViewById(R.id.btnStopPlayback);
        tvRecIndicator      = findViewById(R.id.tvRecIndicator);
        tvPlaybackIndicator = findViewById(R.id.tvPlaybackIndicator);
        tvRemoteLabel       = findViewById(R.id.tvRemoteLabel);

        // Sliders (bar + value label per parameter)
        sliderHr    = new LiveSlider(findViewById(R.id.seekHr),    findViewById(R.id.tvHrCurrent));
        sliderSpo2  = new LiveSlider(findViewById(R.id.seekSpo2),  findViewById(R.id.tvSpo2Current));
        sliderRr    = new LiveSlider(findViewById(R.id.seekRr),    findViewById(R.id.tvRrCurrent));
        sliderPi    = new LiveSlider(findViewById(R.id.seekPi),    findViewById(R.id.tvPiCurrent));
        sliderNoise = new LiveSlider(findViewById(R.id.seekNoise), findViewById(R.id.tvNoiseCurrent));
        sliderAcIr  = new LiveSlider(findViewById(R.id.seekAcIr),  findViewById(R.id.tvAcIrCurrent));
        sliderAcRed = new LiveSlider(findViewById(R.id.seekAcRed), findViewById(R.id.tvAcRedCurrent));
        sliderDcIr  = new LiveSlider(findViewById(R.id.seekDcIr),  findViewById(R.id.tvDcIrCurrent));
        sliderDcRed = new LiveSlider(findViewById(R.id.seekDcRed), findViewById(R.id.tvDcRedCurrent));

        // Condition buttons array
        modeButtons = new Button[]{
                findViewById(R.id.btnModeNormal),
                findViewById(R.id.btnModeArrhythmia),
                findViewById(R.id.btnModeWeakPerf),
                findViewById(R.id.btnModeVasoconstric),
                findViewById(R.id.btnModeStrongPerf),
                findViewById(R.id.btnModeVasodilation)
        };

        // Reflect default params in metric cards
        refreshMetricCards();
    }

    // ─── Slider setup ─────────────────────────────────────────────────────────

    private void setupSliders() {
        // HR: 10–300, step 1 (same as Pi)
        final LiveSlider sHr = sliderHr;
        sHr.bar.setMax(SimulatorParams.HR_MAX - SimulatorParams.HR_MIN);
        sHr.bar.setProgress(params.getHeartRate() - SimulatorParams.HR_MIN);
        sHr.label.setText(String.valueOf(params.getHeartRate()));
        sHr.bar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (sHr.syncing) return;
                int v = p + SimulatorParams.HR_MIN;
                params.setHeartRate(v);
                sHr.label.setText(String.valueOf(v));
                scheduleSend("hr");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { sHr.dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar)  { sHr.dragging = false; sendCurrentParams(); }
        });
        bindIncrDecr(R.id.btnHrDec, R.id.btnHrInc, sHr.bar);

        // SpO2: 0–100 (same as Pi)
        final LiveSlider sSpo2 = sliderSpo2;
        sSpo2.bar.setMax(SimulatorParams.SPO2_MAX - SimulatorParams.SPO2_MIN);
        sSpo2.bar.setProgress(params.getSpo2() - SimulatorParams.SPO2_MIN);
        sSpo2.label.setText(String.valueOf(params.getSpo2()));
        sSpo2.bar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (sSpo2.syncing) return;
                int v = p + SimulatorParams.SPO2_MIN;
                params.setSpo2(v);
                sSpo2.label.setText(String.valueOf(v));
                scheduleSend("spo2");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { sSpo2.dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar)  { sSpo2.dragging = false; sendCurrentParams(); }
        });
        bindIncrDecr(R.id.btnSpo2Dec, R.id.btnSpo2Inc, sSpo2.bar);

        // RR: 1–150 (same as Pi)
        final LiveSlider sRr = sliderRr;
        sRr.bar.setMax(SimulatorParams.RR_MAX - SimulatorParams.RR_MIN);
        sRr.bar.setProgress(params.getRespiratoryRate() - SimulatorParams.RR_MIN);
        sRr.label.setText(String.valueOf(params.getRespiratoryRate()));
        sRr.bar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (sRr.syncing) return;
                int v = p + SimulatorParams.RR_MIN;
                params.setRespiratoryRate(v);
                sRr.label.setText(String.valueOf(v));
                scheduleSend("rr");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { sRr.dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar)  { sRr.dragging = false; sendCurrentParams(); }
        });
        bindIncrDecr(R.id.btnRrDec, R.id.btnRrInc, sRr.bar);

        // PI: 0.01–30.0 → SeekBar 0–2999 (×0.01 + 0.01)
        int piRange = Math.round((SimulatorParams.PI_MAX - SimulatorParams.PI_MIN) / 0.01f);
        final LiveSlider sPi = sliderPi;
        sPi.bar.setMax(piRange);
        sPi.bar.setProgress(piToProgress(params.getPerfusionIndex()));
        sPi.label.setText(String.format(Locale.US, "%.2f", params.getPerfusionIndex()));
        sPi.bar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (sPi.syncing) return;
                float v = progressToPi(p);
                params.setPerfusionIndex(v);
                sPi.label.setText(String.format(Locale.US, "%.2f", v));
                scheduleSend("pi");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { sPi.dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar)  { sPi.dragging = false; sendCurrentParams(); }
        });
        bindIncrDecr(R.id.btnPiDec, R.id.btnPiInc, sPi.bar);

        // Noise: 0.00–1.00 → SeekBar 0–100
        final LiveSlider sNoise = sliderNoise;
        sNoise.bar.setMax(100);
        sNoise.bar.setProgress(Math.round(params.getNoiseLevel() * 100));
        sNoise.label.setText(String.format(Locale.US, "%.2f", params.getNoiseLevel()));
        sNoise.bar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (sNoise.syncing) return;
                float v = p / 100.0f;
                params.setNoiseLevel(v);
                sNoise.label.setText(String.format(Locale.US, "%.2f", v));
                scheduleSend("noise");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { sNoise.dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar)  { sNoise.dragging = false; sendCurrentParams(); }
        });
        bindIncrDecr(R.id.btnNoiseDec, R.id.btnNoiseInc, sNoise.bar);

        // AC/DC amplitude: 0–1500 mV, step 1 mV
        bindAmplitudeSlider(sliderAcIr,  "ac_ir_mv",  params.getAcIrMv(),  v -> params.setAcIrMv(v));
        bindAmplitudeSlider(sliderAcRed, "ac_red_mv", params.getAcRedMv(), v -> params.setAcRedMv(v));
        bindAmplitudeSlider(sliderDcIr,  "dc_ir_mv",  params.getDcIrMv(),  v -> params.setDcIrMv(v));
        bindAmplitudeSlider(sliderDcRed, "dc_red_mv", params.getDcRedMv(), v -> params.setDcRedMv(v));
        bindIncrDecr(R.id.btnAcIrDec,  R.id.btnAcIrInc,  sliderAcIr.bar);
        bindIncrDecr(R.id.btnAcRedDec, R.id.btnAcRedInc, sliderAcRed.bar);
        bindIncrDecr(R.id.btnDcIrDec,  R.id.btnDcIrInc,  sliderDcIr.bar);
        bindIncrDecr(R.id.btnDcRedDec, R.id.btnDcRedInc, sliderDcRed.bar);
    }

    private void bindAmplitudeSlider(final LiveSlider s, final String key,
                                     float initialMv, final FloatSetter setter) {
        s.bar.setMax(Math.round(SimulatorParams.AC_MV_MAX));
        s.bar.setProgress(Math.round(initialMv));
        s.label.setText(formatMv(initialMv));
        s.bar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (s.syncing) return;
                float v = p;
                setter.set(v);
                s.label.setText(formatMv(v));
                scheduleSend(key);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { s.dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar)  { s.dragging = false; sendCurrentParams(); }
        });
    }

    /** Push a value coming from the device into a slider (skipped while the user drags it). */
    private void syncSlider(LiveSlider s, int progress, String text) {
        if (s.dragging) return;
        s.syncing = true;
        s.bar.setProgress(progress);
        s.syncing = false;
        s.label.setText(text);
    }

    private static String formatMv(float mv) {
        return String.format(Locale.US, "%.0f", mv);
    }

    private interface FloatSetter {
        void set(float v);
    }

    private int   piToProgress(float pi) { return Math.round((pi - SimulatorParams.PI_MIN) / 0.01f); }
    private float progressToPi(int p)    { return SimulatorParams.PI_MIN + p * 0.01f; }

    /** Attach − / + buttons to a SeekBar, stepping by 1. */
    private void bindIncrDecr(int decId, int incId, SeekBar bar) {
        findViewById(decId).setOnClickListener(v -> {
            if (bar.getProgress() > 0) bar.setProgress(bar.getProgress() - 1);
        });
        findViewById(incId).setOnClickListener(v -> {
            if (bar.getProgress() < bar.getMax()) bar.setProgress(bar.getProgress() + 1);
        });
    }

    // ─── Mode button setup ────────────────────────────────────────────────────

    private void setupModeButtons() {
        for (int i = 0; i < modeButtons.length; i++) {
            final int condition = i;
            modeButtons[i].setOnClickListener(v -> selectMode(condition));
        }

        // Highlight default
        highlightModeButton(params.getCondition());
    }

    private void selectMode(int condition) {
        if (condition < 0 || condition >= SimulatorParams.MODE_LABELS.length) return;
        if (condition == params.getCondition() && renderedMode == condition) return;

        params.setCondition(condition);
        highlightModeButton(condition);

        tvModeValue.setText(SimulatorParams.MODE_LABELS[condition]);

        // A mode is a discrete action, so send it immediately. This keeps the
        // Pi and phone responsive without waiting for the slider debounce.
        scheduleSend("condition");
        sendCurrentParams();
    }

    private void highlightModeButton(int condition) {
        if (condition < 0 || condition >= modeButtons.length) return;
        if (renderedMode == condition) return;

        for (int i = 0; i < modeButtons.length; i++) {
            boolean selected = (i == condition);
            modeButtons[i].setSelected(selected);
            modeButtons[i].setAlpha(selected ? 1.0f : 0.45f);
        }
        renderedMode = condition;
    }

    // ─── Action buttons ───────────────────────────────────────────────────────

    private void setupActionButtons() {
        btnScan.setOnClickListener(v -> openScanDialog());
        btnDisconnect.setOnClickListener(v -> {
            if (bleManager != null) bleManager.disconnectAndClose();
        });
        btnRun.setOnClickListener(v -> onRunButtonClicked());

        // Recording / playback
        recordingsDir = getExternalFilesDir(null);
        if (recordingsDir == null) recordingsDir = getFilesDir();

        btnRec.setOnClickListener(v -> toggleRecording());
        btnPlay.setOnClickListener(v -> onPlayButtonClicked());
        btnStopPlayback.setOnClickListener(v -> stopPlayback());

        updateRunButtonUi();
    }

    // ─── BLE observers ────────────────────────────────────────────────────────

    private void observeBle() {
        bleManager.getState().observe(this, state -> {
            switch (state) {
                case CONNECTED:
                    statusDot.setBackgroundResource(R.drawable.ic_dot_connected);
                    tvConnectionStatus.setText(R.string.connected);
                    tvConnectionStatus.setTextColor(getColor(R.color.status_connected));
                    btnDisconnect.setEnabled(true);
                    btnScan.setEnabled(false);
                    btnRun.setEnabled(true);
                    sendAllParams(); // push one complete snapshot immediately on connect
                    break;
                case CONNECTING:
                    statusDot.setBackgroundResource(R.drawable.ic_dot_connecting);
                    tvConnectionStatus.setText(R.string.connecting);
                    tvConnectionStatus.setTextColor(getColor(R.color.status_connecting));
                    break;
                case DISCONNECTED:
                    statusDot.setBackgroundResource(R.drawable.ic_dot_disconnected);
                    tvConnectionStatus.setText(R.string.disconnected);
                    tvConnectionStatus.setTextColor(getColor(R.color.status_disconnected));
                    tvDeviceName.setText(R.string.no_device);
                    btnDisconnect.setEnabled(false);
                    btnScan.setEnabled(true);
                    btnRun.setEnabled(false);
                    resetRunMirror();
                    resetRemotePbTracking();
                    stopPlayback();      // review session dies with the link
                    waveformView.reset();
                    break;
            }
        });

        // Waveform samples — add directly to SurfaceView buffer (high frequency).
        // While a playback review is on screen, live samples only feed the recorder.
        bleManager.getPpgSample().observe(this, value -> {
            if (value == null) return;
            if (!isPlaybackActive()) waveformView.addSample(value);
            if (recorder != null && recorder.isRunning()) recorder.appendSample(value);
        });

        // STATUS packets — echo/sync rule:
        //   origin "android" → update metric cards only (echo of our own command)
        //   origin "rpi"     → update metric cards AND sliders/labels/mode highlight,
        //                      except the slider the user is currently dragging
        bleManager.getStatusPacket().observe(this, packet -> {
            if (packet == null) return;
            boolean fromPi = "rpi".equals(packet.origin);

            // Run-state mirror — label always follows the status packet, except
            // while our own optimistic toggle is still in flight (1s grace window)
            if (packet.running != null
                    && SystemClock.elapsedRealtime() - pendingRunToggleAt >= RUN_TOGGLE_GRACE_MS) {
                simulationRunning = packet.running;
                updateRunButtonUi();
            }

            // Metric cards — always follow device status
            if (packet.hr != null)    tvHrValue.setText(String.valueOf(packet.hr));
            if (packet.spo2 != null)  tvSpo2Value.setText(String.valueOf(packet.spo2));
            if (packet.rr != null)    tvRrValue.setText(String.valueOf(packet.rr));
            if (packet.pi != null)    tvPiValue.setText(String.format(Locale.US, "%.2f", packet.pi));
            if (packet.noise != null) tvNoiseValue.setText(String.format(Locale.US, "%.2f", packet.noise));
            if (packet.condition != null) {
                int idx = Math.max(0, Math.min(SimulatorParams.MODE_LABELS.length - 1, packet.condition));
                // Android-origin packets are echoes, not permission to replace
                // the user's optimistic selection. Only Pi-origin status can
                // commit a remote mode, and stale status is ignored while the
                // local command is awaiting confirmation.
                if (fromPi && canApplyStatus("condition", idx, params.getCondition())) {
                    params.setCondition(idx);
                    tvModeValue.setText(SimulatorParams.MODE_LABELS[idx]);
                    highlightModeButton(idx);
                }
            }

            // Recorder stamp columns follow the device's confirmed setpoints
            if (recorder != null && recorder.isRunning()) {
                recorder.updateSetpoints(
                        packet.hr        != null ? packet.hr        : params.getHeartRate(),
                        packet.spo2      != null ? packet.spo2      : params.getSpo2(),
                        packet.rr        != null ? packet.rr        : params.getRespiratoryRate(),
                        packet.pi        != null ? packet.pi        : params.getPerfusionIndex(),
                        packet.noise     != null ? packet.noise     : params.getNoiseLevel(),
                        packet.condition != null ? packet.condition : params.getCondition(),
                        packet.acIrMv    != null ? packet.acIrMv    : params.getAcIrMv(),
                        packet.acRedMv   != null ? packet.acRedMv   : params.getAcRedMv(),
                        packet.dcIrMv    != null ? packet.dcIrMv    : params.getDcIrMv(),
                        packet.dcRedMv   != null ? packet.dcRedMv   : params.getDcRedMv());
            }

            // Echo of our own command → sliders stay where the user put them
            if (!fromPi) return;

            // External (Pi GUI) change → params + sliders + labels
            if (packet.hr != null && canApplyStatus("hr", packet.hr, params.getHeartRate())) {
                params.setHeartRate(packet.hr);
                syncSlider(sliderHr, packet.hr - SimulatorParams.HR_MIN, String.valueOf(packet.hr));
            }
            if (packet.spo2 != null && canApplyStatus("spo2", packet.spo2, params.getSpo2())) {
                params.setSpo2(packet.spo2);
                syncSlider(sliderSpo2, packet.spo2 - SimulatorParams.SPO2_MIN, String.valueOf(packet.spo2));
            }
            if (packet.rr != null && canApplyStatus("rr", packet.rr, params.getRespiratoryRate())) {
                params.setRespiratoryRate(packet.rr);
                syncSlider(sliderRr, packet.rr - SimulatorParams.RR_MIN, String.valueOf(packet.rr));
            }
            if (packet.pi != null && canApplyStatus("pi", packet.pi, params.getPerfusionIndex(), 0.011f)) {
                params.setPerfusionIndex(packet.pi);
                syncSlider(sliderPi, piToProgress(packet.pi), String.format(Locale.US, "%.2f", packet.pi));
            }
            if (packet.noise != null && canApplyStatus("noise", packet.noise, params.getNoiseLevel(), 0.011f)) {
                params.setNoiseLevel(packet.noise);
                syncSlider(sliderNoise, Math.round(packet.noise * 100), String.format(Locale.US, "%.2f", packet.noise));
            }
            if (packet.acIrMv != null && canApplyStatus("ac_ir_mv", packet.acIrMv, params.getAcIrMv(), 0.6f)) {
                params.setAcIrMv(packet.acIrMv);
                syncSlider(sliderAcIr, Math.round(packet.acIrMv), formatMv(packet.acIrMv));
            }
            if (packet.acRedMv != null && canApplyStatus("ac_red_mv", packet.acRedMv, params.getAcRedMv(), 0.6f)) {
                params.setAcRedMv(packet.acRedMv);
                syncSlider(sliderAcRed, Math.round(packet.acRedMv), formatMv(packet.acRedMv));
            }
            if (packet.dcIrMv != null && canApplyStatus("dc_ir_mv", packet.dcIrMv, params.getDcIrMv(), 0.6f)) {
                params.setDcIrMv(packet.dcIrMv);
                syncSlider(sliderDcIr, Math.round(packet.dcIrMv), formatMv(packet.dcIrMv));
            }
            if (packet.dcRedMv != null && canApplyStatus("dc_red_mv", packet.dcRedMv, params.getDcRedMv(), 0.6f)) {
                params.setDcRedMv(packet.dcRedMv);
                syncSlider(sliderDcRed, Math.round(packet.dcRedMv), formatMv(packet.dcRedMv));
            }

            // ── Protocol v2 action sync (Pi truth only, origin guaranteed "rpi" here) ──
            applyRemoteRecording(packet.recording, packet.recordFile);
            applyRemotePlayback(packet.pb, packet.pbFile);
        });

        // Errors
        bleManager.getErrorEvent().observe(this, msg -> {
            if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
    }

    // ─── Scan dialog ──────────────────────────────────────────────────────────

    private void openScanDialog() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!adapter.isEnabled()) {
            enableBluetoothLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        DeviceScanDialog dialog = new DeviceScanDialog();
        dialog.setDeviceSelectedListener(this::onDeviceSelected);
        dialog.show(getSupportFragmentManager(), "scan");
    }

    private void onDeviceSelected(@NonNull BluetoothDevice device) {
        String name;
        try {
            name = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED)
                    ? device.getAddress()
                    : device.getName() != null ? device.getName() : device.getAddress();
        } catch (Exception e) { name = device.getAddress(); }
        tvDeviceName.setText(name);
        bleManager.connectToDevice(device);
    }

    // ─── Parameter send helpers ───────────────────────────────────────────────

    /** Schedule a debounced delta send so rapid slider moves do not queue stale snapshots. */
    private void scheduleSend(String... keys) {
        for (String key : keys) {
            if (key != null && !key.isEmpty()) {
                pendingParamKeys.add(key);
                pendingParamUntil.put(key,
                        SystemClock.elapsedRealtime() + PARAM_CONFIRM_TIMEOUT_MS);
            }
        }
        sendHandler.removeCallbacks(sendRunnable);
        sendHandler.postDelayed(sendRunnable, SEND_DEBOUNCE_MS);
    }

    /** Send the full state exactly once after connecting. */
    private void sendAllParams() {
        pendingParamKeys.clear();
        long until = SystemClock.elapsedRealtime() + PARAM_CONFIRM_TIMEOUT_MS;
        for (String key : new String[]{"hr", "spo2", "rr", "pi", "noise", "condition",
                "ac_ir_mv", "ac_red_mv", "dc_ir_mv", "dc_red_mv"}) {
            pendingParamUntil.put(key, until);
        }
        sendJson(params.toCommandJson());
    }

    /** Immediately serialise and send the changed params over BLE. */
    private void sendCurrentParams() {
        if (pendingParamKeys.isEmpty()) return;
        String json = params.toCommandJson(new LinkedHashSet<>(pendingParamKeys));
        if (bleManager != null && bleManager.isConnected()) {
            pendingParamKeys.clear();
            sendJson(json);
        }
        refreshMetricCards();
    }

    /**
     * Ignore an older/different status value while a local edit is waiting for
     * its BLE echo. Without this guard, the 5 Hz status stream can immediately
     * snap a working slider back to the previous value when a write is queued.
     */
    private boolean canApplyStatus(String key, float actual, float expected, float epsilon) {
        Long until = pendingParamUntil.get(key);
        if (until == null) return true;
        if (Math.abs(actual - expected) <= epsilon
                || SystemClock.elapsedRealtime() >= until) {
            pendingParamUntil.remove(key);
            return true;
        }
        return false;
    }

    private boolean canApplyStatus(String key, int actual, int expected) {
        return canApplyStatus(key, (float) actual, (float) expected, 0.5f);
    }

    private void sendJson(String json) {
        if (bleManager != null && bleManager.isConnected()) {
            Log.d("BLE_SEND_TEST", "SEND = " + json);
            bleManager.sendCommand(json);
        } else {
            Log.d("BLE_SEND_TEST", "BLE NOT CONNECTED");
        }
    }

    /** Keep the left metric panel in sync with slider state. */
    private void refreshMetricCards() {
        tvHrValue.setText(String.valueOf(params.getHeartRate()));
        tvSpo2Value.setText(String.valueOf(params.getSpo2()));
        tvRrValue.setText(String.valueOf(params.getRespiratoryRate()));
        tvPiValue.setText(String.format(Locale.US, "%.2f", params.getPerfusionIndex()));
        tvNoiseValue.setText(String.format(Locale.US, "%.2f", params.getNoiseLevel()));
        tvModeValue.setText(SimulatorParams.MODE_LABELS[params.getCondition()]);
    }

    // ─── Simulation run control (protocol v2 "run") ───────────────────────────

    private void onRunButtonClicked() {
        boolean target = !simulationRunning;
        simulationRunning   = target;                      // optimistic mirror
        pendingRunToggleAt  = SystemClock.elapsedRealtime();
        updateRunButtonUi();
        if (bleManager != null && bleManager.isConnected()) {
            bleManager.sendRun(target);                    // immediate, debounce-independent
        }
    }

    /** Run button label/state always follows the local mirror. */
    private void updateRunButtonUi() {
        if (btnRun == null) return;
        btnRun.setText(simulationRunning ? R.string.stop_simulation : R.string.run_simulation);
        btnRun.setSelected(simulationRunning);
    }

    private void resetRunMirror() {
        simulationRunning  = false;
        pendingRunToggleAt = 0L;
        updateRunButtonUi();
    }

    /**
     * A3 — mirror the Pi's truth for "recording". Local recorder diverging from
     * remote is reconciled without echoing a record command back (applyingRemote).
     */
    private void applyRemoteRecording(@Nullable Boolean remote, @Nullable String recordFile) {
        if (remote == null || applyingRemote || shuttingDown) return;
        boolean localRecording = recorder != null;
        if (remote && !localRecording) {
            applyingRemote = true;
            try { startRecording(recordFile); } finally { applyingRemote = false; }
        } else if (!remote && localRecording) {
            applyingRemote = true;
            try { stopRecording(); } finally { applyingRemote = false; }
        }
    }

    // ─── Recording ────────────────────────────────────────────────────────────

    private void toggleRecording() {
        if (recorder != null) {
            stopRecording();
            return;
        }
        if (!simulationRunning) {
            Toast.makeText(this, "Run simulation before recording", Toast.LENGTH_SHORT).show();
            return;
        }
        startRecording(null);
    }

    /** Starts the local CSV recorder; echoes the intent to the Pi unless applying remote truth. */
    private void startRecording(@Nullable String requestedFileName) {
        if (recorder != null) return;
        File out = recordingFile(requestedFileName);
        try {
            recorder = new PpgRecorder(out);
            recorder.start();
            pushSetpointsToRecorder();
            btnRec.setText(R.string.btn_stop);
            btnRec.setSelected(true);
            tvRecIndicator.setVisibility(View.VISIBLE);
            Toast.makeText(this, R.string.recording_started, Toast.LENGTH_SHORT).show();
            if (!applyingRemote) sendRecordToPi(true, out.getName());
        } catch (IOException e) {
            recorder = null;
            Log.e(TAG, "Failed to start recording", e);
            Toast.makeText(this, R.string.recording_error, Toast.LENGTH_SHORT).show();
        }
    }

    /** Stops the local CSV recorder; echoes the intent to the Pi unless applying remote truth. */
    private void stopRecording() {
        PpgRecorder r = recorder;
        recorder = null;
        btnRec.setText(R.string.btn_rec);
        btnRec.setSelected(false);
        tvRecIndicator.setVisibility(View.GONE);
        if (r != null) {
            if (!applyingRemote) sendRecordToPi(false, null);
            r.stop();
            Toast.makeText(this,
                    getString(R.string.recording_saved, r.getFile().getName()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** Fire-and-forget record action command (connected check inside). */
    private void sendRecordToPi(boolean on, @Nullable String fileName) {
        if (shuttingDown) return;
        if (bleManager != null && bleManager.isConnected()) bleManager.sendRecord(on, fileName);
    }

    /** Use short shared names so both sides can mirror a recording selection. */
    private File recordingFile(@Nullable String requestedFileName) {
        String requested = safeRemoteFileName(requestedFileName);
        if (requested != null) {
            if (!requested.toLowerCase(Locale.US).endsWith(".csv")) requested += ".csv";
            return new File(recordingsDir, requested);
        }
        for (int i = 1; i < 100000; i++) {
            File candidate = new File(recordingsDir, "data_" + i + ".csv");
            if (!candidate.exists()) return candidate;
        }
        return new File(recordingsDir, "data_" + System.currentTimeMillis() + ".csv");
    }

    private void pushSetpointsToRecorder() {
        if (recorder == null) return;
        recorder.updateSetpoints(
                params.getHeartRate(), params.getSpo2(), params.getRespiratoryRate(),
                params.getPerfusionIndex(), params.getNoiseLevel(), params.getCondition(),
                params.getAcIrMv(), params.getAcRedMv(), params.getDcIrMv(), params.getDcRedMv());
    }

    // ─── Playback ─────────────────────────────────────────────────────────────

    private void onPlayButtonClicked() {
        if (isPlaybackActive()) {
            if (playback.getState() == PpgPlayback.State.PLAYING) {
                pauseLocalPlayback();
                if (!applyingRemote) sendPlaybackToPi("pause", null);
            } else {
                resumeLocalPlayback();
                if (!applyingRemote) sendPlaybackToPi("resume", null);
            }
            return;
        }
        openPlaybackDialog();
    }

    /** Local pause + UI, no BLE send (callers decide whether to echo). */
    private void pauseLocalPlayback() {
        if (playback == null || playback.getState() != PpgPlayback.State.PLAYING) return;
        playback.pause();
        btnPlay.setText(R.string.btn_resume);
        tvPlaybackIndicator.setText(R.string.paused_indicator);
    }

    /** Local resume + UI, no BLE send (callers decide whether to echo). */
    private void resumeLocalPlayback() {
        if (playback == null || playback.getState() != PpgPlayback.State.PAUSED) return;
        playback.resume();
        btnPlay.setText(R.string.btn_pause);
        tvPlaybackIndicator.setText(R.string.playback_indicator);
    }

    private void openPlaybackDialog() {
        PlaybackDialog dialog = new PlaybackDialog();
        dialog.setRecordingDir(recordingsDir);
        dialog.setOnPlaybackFileListener(this::startPlayback);
        dialog.show(getSupportFragmentManager(), "playback");
    }

    private void startPlayback(File file) {
        stopPlayback();
        waveformView.reset();

        final PpgPlayback session = new PpgPlayback();
        playback = session;
        localPlaybackFile = file.getName();
        session.start(file,
                value -> waveformView.addSample(value),
                new PpgPlayback.EventListener() {
                    @Override public void onPlaybackStopped(boolean finishedToEnd) {
                        if (playback == session) {
                            localPlaybackFile = null;
                            resetPlaybackUi();
                            // A local file can finish without a button click;
                            // close the mirrored Pi session as well.
                            if (finishedToEnd && !applyingRemote && !shuttingDown) {
                                sendPlaybackToPi("stop", null);
                            }
                        }
                    }

                    @Override public void onPlaybackError(@NonNull String message) {
                        if (playback == session) {
                            localPlaybackFile = null;
                            resetPlaybackUi();
                            if (!applyingRemote && !shuttingDown) {
                                sendPlaybackToPi("stop", null);
                            }
                            Toast.makeText(MainActivity.this,
                                    R.string.playback_load_error, Toast.LENGTH_SHORT).show();
                        }
                    }
                });

        btnPlay.setText(R.string.btn_pause);
        tvPlaybackIndicator.setText(R.string.playback_indicator);
        tvPlaybackIndicator.setVisibility(View.VISIBLE);
        btnStopPlayback.setVisibility(View.VISIBLE);

        if (!applyingRemote) {
            sendPlaybackToPi("start", file.getName());
            pbSessionMirrored = false;
            remotePbCode      = RpiBleManager.StatusPacket.PB_PLAYING;
            remotePbFile      = file.getName();
        }
    }

    private void stopPlayback() {
        boolean wasActive = isPlaybackActive();
        if (playback != null) {
            playback.stop();   // fires onPlaybackStopped → resetPlaybackUi (guarded)
            playback = null;
        }
        localPlaybackFile = null;
        resetPlaybackUi();
        if (wasActive && !applyingRemote) sendPlaybackToPi("stop", null);
    }

    private void resetPlaybackUi() {
        btnPlay.setText(R.string.btn_play);
        btnStopPlayback.setVisibility(View.GONE);
        tvPlaybackIndicator.setVisibility(View.GONE);
    }

    private boolean isPlaybackActive() {
        return playback != null && playback.getState() != PpgPlayback.State.IDLE;
    }

    /** Fire-and-forget playback action command (connected check inside). */
    private void sendPlaybackToPi(String action, @Nullable String fileName) {
        if (shuttingDown) return;
        if (bleManager != null && bleManager.isConnected()) {
            bleManager.sendPlayback(action, fileName);
        }
    }

    private void resetRemotePbTracking() {
        remotePbCode      = RpiBleManager.StatusPacket.PB_INACTIVE;
        remotePbFile      = null;
        pbSessionMirrored = false;
        hideRemoteLabel();
    }

    /**
     * A4 — mirror the Pi's playback truth. Only transitions (pb code or file
     * change) act; steady-state packets merely echo the current state and are
     * ignored so mirrored sessions never restart. Everything runs on the main
     * thread (LiveData), so the applyingRemote guard is race-free.
     */
    private void applyRemotePlayback(int pb, @Nullable String pbFile) {
        if (pb == RpiBleManager.StatusPacket.PB_UNKNOWN
                || applyingRemote || shuttingDown) return;

        String name = safeRemoteFileName(pbFile);
        boolean transition = pb != remotePbCode || !Objects.equals(name, remotePbFile);
        remotePbCode = pb;
        remotePbFile = (pb == RpiBleManager.StatusPacket.PB_INACTIVE) ? null : name;
        if (!transition) return;

        applyingRemote = true;
        try {
            if (pb == RpiBleManager.StatusPacket.PB_PLAYING) {
                if (pbSessionMirrored && isPlaybackActive()) {
                    // mirrored session already on screen — only sync a remote pause→resume
                    if (playback.getState() == PpgPlayback.State.PAUSED) resumeLocalPlayback();
                    hideRemoteLabel();
                } else {
                    // A Pi-originated selection replaces a different local
                    // clip. A phone-originated selection already has the same
                    // remotePbFile and is filtered by the transition check.
                    if (isPlaybackActive() && !Objects.equals(name, localPlaybackFile)) {
                        stopPlayback();
                    }
                    if (isPlaybackActive()) return;
                    pbSessionMirrored = false;
                    if (name == null) {
                        hideRemoteLabel();
                    } else {
                        File candidate = new File(recordingsDir, name);
                        if (candidate.isFile()) {
                            startPlayback(candidate);
                            pbSessionMirrored = true;
                            hideRemoteLabel();
                        } else {
                            // Pi plays a file the phone does not have — surface it, do not start local
                            showRemoteLabel(name);
                        }
                    }
                }
                // local user-initiated session active → local owns the screen, ignore

            } else if (pb == RpiBleManager.StatusPacket.PB_PAUSED) {
                if (pbSessionMirrored && playback != null
                        && playback.getState() == PpgPlayback.State.PLAYING) {
                    pauseLocalPlayback();
                }
                // remote label (if shown) stays — the Pi just paused its own session

            } else { // PB_INACTIVE
                if (pbSessionMirrored) stopPlayback();
                pbSessionMirrored = false;
                hideRemoteLabel();
            }
        } finally {
            applyingRemote = false;
        }
    }

    /** Reject anything that is not a plain file name (path traversal guard). */
    @Nullable
    private static String safeRemoteFileName(@Nullable String raw) {
        if (raw == null) return null;
        String name = raw.trim();
        if (name.isEmpty()
                || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0
                || name.contains("..")) return null;
        return name;
    }

    private void showRemoteLabel(String fileName) {
        tvRemoteLabel.setText(getString(R.string.remote_playback_label, fileName));
        tvRemoteLabel.setVisibility(View.VISIBLE);
    }

    private void hideRemoteLabel() {
        if (tvRemoteLabel != null) tvRemoteLabel.setVisibility(View.GONE);
    }

    // ─── BLE permission helpers ───────────────────────────────────────────────

    private void checkAndInitBle() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
        }
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestBluetoothPermissions() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        permissionLauncher.launch(perms.toArray(new String[0]));
    }

    private void onPermissionsResult(Map<String, Boolean> results) {
        boolean allGranted = true;
        for (boolean v : results.values()) if (!v) { allGranted = false; break; }
        if (!allGranted) {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show();
        }
    }

    // ─── Seek bar helper ──────────────────────────────────────────────────────

    /** No-op default listener — subclass only overrides onProgressChanged. */
    private static abstract class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
