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
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    // ─── UI references — status bar ───────────────────────────────────────────
    private View     statusDot;
    private TextView tvConnectionStatus;
    private TextView tvDeviceName;
    private Button   btnScan;
    private Button   btnDisconnect;

    // ─── UI references — metric cards ─────────────────────────────────────────
    private TextView tvHrValue;
    private TextView tvSpo2Value;
    private TextView tvRrValue;
    private TextView tvPiValue;
    private TextView tvNoiseValue;
    private TextView tvModeValue;

    // ─── UI references — waveform ─────────────────────────────────────────────
    private WaveformSurfaceView waveformView;

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

    // ─── UI references — recording / playback ─────────────────────────────────
    private Button    btnRec;
    private Button    btnPlay;
    private Button    btnStopPlayback;
    private TextView  tvRecIndicator;
    private TextView  tvPlaybackIndicator;
    private File      recordingsDir;

    private PpgRecorder recorder;
    private PpgPlayback playback;

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
        // HR: 20–300, step 1
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
                scheduleSend();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { sHr.dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar)  { sHr.dragging = false; }
        });
        bindIncrDecr(R.id.btnHrDec, R.id.btnHrInc, sHr.bar);

        // SpO2: 70–100
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
                scheduleSend();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { sSpo2.dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar)  { sSpo2.dragging = false; }
        });
        bindIncrDecr(R.id.btnSpo2Dec, R.id.btnSpo2Inc, sSpo2.bar);

        // RR: 4–60
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
                scheduleSend();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { sRr.dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar)  { sRr.dragging = false; }
        });
        bindIncrDecr(R.id.btnRrDec, R.id.btnRrInc, sRr.bar);

        // PI: 0.02–20.0 → SeekBar 0–1998 (×0.01 + 0.02)
        int piRange = 1998; // (20.00 - 0.02) / 0.01 = 1998
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
                scheduleSend();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { sPi.dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar)  { sPi.dragging = false; }
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
                scheduleSend();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { sNoise.dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar)  { sNoise.dragging = false; }
        });
        bindIncrDecr(R.id.btnNoiseDec, R.id.btnNoiseInc, sNoise.bar);

        // AC/DC amplitude: 0–1500 mV, step 1 mV
        bindAmplitudeSlider(sliderAcIr,  params.getAcIrMv(),  v -> params.setAcIrMv(v));
        bindAmplitudeSlider(sliderAcRed, params.getAcRedMv(), v -> params.setAcRedMv(v));
        bindAmplitudeSlider(sliderDcIr,  params.getDcIrMv(),  v -> params.setDcIrMv(v));
        bindAmplitudeSlider(sliderDcRed, params.getDcRedMv(), v -> params.setDcRedMv(v));
        bindIncrDecr(R.id.btnAcIrDec,  R.id.btnAcIrInc,  sliderAcIr.bar);
        bindIncrDecr(R.id.btnAcRedDec, R.id.btnAcRedInc, sliderAcRed.bar);
        bindIncrDecr(R.id.btnDcIrDec,  R.id.btnDcIrInc,  sliderDcIr.bar);
        bindIncrDecr(R.id.btnDcRedDec, R.id.btnDcRedInc, sliderDcRed.bar);
    }

    private void bindAmplitudeSlider(final LiveSlider s, float initialMv, final FloatSetter setter) {
        s.bar.setMax(Math.round(SimulatorParams.AC_MV_MAX));
        s.bar.setProgress(Math.round(initialMv));
        s.label.setText(formatMv(initialMv));
        s.bar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (s.syncing) return;
                float v = p;
                setter.set(v);
                s.label.setText(formatMv(v));
                scheduleSend();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { s.dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar)  { s.dragging = false; }
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
        params.setCondition(condition);
        highlightModeButton(condition);

        tvModeValue.setText(SimulatorParams.MODE_LABELS[condition]);

        scheduleSend();
    }

    private void highlightModeButton(int condition) {
        for (int i = 0; i < modeButtons.length; i++) {
            boolean selected = (i == condition);
            modeButtons[i].setSelected(selected);
            modeButtons[i].setAlpha(selected ? 1.0f : 0.45f);
        }
    }

    // ─── Action buttons ───────────────────────────────────────────────────────

    private void setupActionButtons() {
        btnScan.setOnClickListener(v -> openScanDialog());
        btnDisconnect.setOnClickListener(v -> {
            if (bleManager != null) bleManager.disconnectAndClose();
        });

        // Recording / playback
        recordingsDir = getExternalFilesDir(null);
        if (recordingsDir == null) recordingsDir = getFilesDir();

        btnRec.setOnClickListener(v -> toggleRecording());
        btnPlay.setOnClickListener(v -> onPlayButtonClicked());
        btnStopPlayback.setOnClickListener(v -> stopPlayback());
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
                    sendCurrentParams(); // push current params immediately on connect
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

            // Metric cards — always follow device status
            if (packet.hr != null)    tvHrValue.setText(String.valueOf(packet.hr));
            if (packet.spo2 != null)  tvSpo2Value.setText(String.valueOf(packet.spo2));
            if (packet.rr != null)    tvRrValue.setText(String.valueOf(packet.rr));
            if (packet.pi != null)    tvPiValue.setText(String.format(Locale.US, "%.2f", packet.pi));
            if (packet.noise != null) tvNoiseValue.setText(String.format(Locale.US, "%.2f", packet.noise));
            if (packet.condition != null) {
                int idx = Math.max(0, Math.min(SimulatorParams.MODE_LABELS.length - 1, packet.condition));
                tvModeValue.setText(SimulatorParams.MODE_LABELS[idx]);
                if (fromPi) highlightModeButton(idx);
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
            if (packet.hr != null) {
                params.setHeartRate(packet.hr);
                syncSlider(sliderHr, packet.hr - SimulatorParams.HR_MIN, String.valueOf(packet.hr));
            }
            if (packet.spo2 != null) {
                params.setSpo2(packet.spo2);
                syncSlider(sliderSpo2, packet.spo2 - SimulatorParams.SPO2_MIN, String.valueOf(packet.spo2));
            }
            if (packet.rr != null) {
                params.setRespiratoryRate(packet.rr);
                syncSlider(sliderRr, packet.rr - SimulatorParams.RR_MIN, String.valueOf(packet.rr));
            }
            if (packet.pi != null) {
                params.setPerfusionIndex(packet.pi);
                syncSlider(sliderPi, piToProgress(packet.pi), String.format(Locale.US, "%.2f", packet.pi));
            }
            if (packet.noise != null) {
                params.setNoiseLevel(packet.noise);
                syncSlider(sliderNoise, Math.round(packet.noise * 100), String.format(Locale.US, "%.2f", packet.noise));
            }
            if (packet.acIrMv != null) {
                params.setAcIrMv(packet.acIrMv);
                syncSlider(sliderAcIr, Math.round(packet.acIrMv), formatMv(packet.acIrMv));
            }
            if (packet.acRedMv != null) {
                params.setAcRedMv(packet.acRedMv);
                syncSlider(sliderAcRed, Math.round(packet.acRedMv), formatMv(packet.acRedMv));
            }
            if (packet.dcIrMv != null) {
                params.setDcIrMv(packet.dcIrMv);
                syncSlider(sliderDcIr, Math.round(packet.dcIrMv), formatMv(packet.dcIrMv));
            }
            if (packet.dcRedMv != null) {
                params.setDcRedMv(packet.dcRedMv);
                syncSlider(sliderDcRed, Math.round(packet.dcRedMv), formatMv(packet.dcRedMv));
            }
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

    /** Schedule a debounced send so we don't flood ESP32 on rapid slider moves. */
    private void scheduleSend() {
        sendHandler.removeCallbacks(sendRunnable);
        sendHandler.postDelayed(sendRunnable, SEND_DEBOUNCE_MS);
    }

    /** Immediately serialise and send current params over BLE. */
    private void sendCurrentParams() {
        if (bleManager != null && bleManager.isConnected()) {
            String json = params.toCommandJson();
            Log.d("BLE_SEND_TEST", "SEND = " + json);
            bleManager.sendCommand(json);
        } else {
            Log.d("BLE_SEND_TEST", "BLE NOT CONNECTED");
        }

        refreshMetricCards();
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

    // ─── Recording ────────────────────────────────────────────────────────────

    private void toggleRecording() {
        if (recorder != null) {
            stopRecording();
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        File out = new File(recordingsDir, "ppg_" + fmt.format(new Date()) + ".csv");
        try {
            recorder = new PpgRecorder(out);
            recorder.start();
            pushSetpointsToRecorder();
            btnRec.setText(R.string.btn_stop);
            btnRec.setSelected(true);
            tvRecIndicator.setVisibility(View.VISIBLE);
            Toast.makeText(this, R.string.recording_started, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            recorder = null;
            Log.e(TAG, "Failed to start recording", e);
            Toast.makeText(this, R.string.recording_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecording() {
        PpgRecorder r = recorder;
        recorder = null;
        btnRec.setText(R.string.btn_rec);
        btnRec.setSelected(false);
        tvRecIndicator.setVisibility(View.GONE);
        if (r != null) {
            r.stop();
            Toast.makeText(this,
                    getString(R.string.recording_saved, r.getFile().getName()),
                    Toast.LENGTH_SHORT).show();
        }
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
                playback.pause();
                btnPlay.setText(R.string.btn_resume);
                tvPlaybackIndicator.setText(R.string.paused_indicator);
            } else {
                playback.resume();
                btnPlay.setText(R.string.btn_pause);
                tvPlaybackIndicator.setText(R.string.playback_indicator);
            }
            return;
        }
        openPlaybackDialog();
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
        session.start(file,
                value -> waveformView.addSample(value),
                new PpgPlayback.EventListener() {
                    @Override public void onPlaybackStopped(boolean finishedToEnd) {
                        if (playback == session) resetPlaybackUi();
                    }

                    @Override public void onPlaybackError(@NonNull String message) {
                        if (playback == session) {
                            resetPlaybackUi();
                            Toast.makeText(MainActivity.this,
                                    R.string.playback_load_error, Toast.LENGTH_SHORT).show();
                        }
                    }
                });

        btnPlay.setText(R.string.btn_pause);
        tvPlaybackIndicator.setText(R.string.playback_indicator);
        tvPlaybackIndicator.setVisibility(View.VISIBLE);
        btnStopPlayback.setVisibility(View.VISIBLE);
    }

    private void stopPlayback() {
        if (playback != null) {
            playback.stop();   // fires onPlaybackStopped → resetPlaybackUi (guarded)
            playback = null;
        }
        resetPlaybackUi();
    }

    private void resetPlaybackUi() {
        btnPlay.setText(R.string.btn_play);
        btnStopPlayback.setVisibility(View.GONE);
        tvPlaybackIndicator.setVisibility(View.GONE);
    }

    private boolean isPlaybackActive() {
        return playback != null && playback.getState() != PpgPlayback.State.IDLE;
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