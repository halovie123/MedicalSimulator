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

import com.medical.simulator.ble.EspBleManager;
import com.medical.simulator.model.SimulatorParams;
import com.medical.simulator.ui.WaveformSurfaceView;

import java.util.ArrayList;
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
    private EspBleManager    bleManager;
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
    // HR
    private SeekBar  seekHr;
    private TextView tvHrCurrent;
    // SpO2
    private SeekBar  seekSpo2;
    private TextView tvSpo2Current;
    // RR
    private SeekBar  seekRr;
    private TextView tvRrCurrent;
    // PI
    private SeekBar  seekPi;
    private TextView tvPiCurrent;
    // Noise
    private SeekBar  seekNoise;
    private TextView tvNoiseCurrent;

    // Condition mode buttons
    private Button[] modeButtons;

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
            bleManager = new EspBleManager(getApplicationContext());
            observeBle();
            checkAndInitBle();
        }, 150); // Trì hoãn 150ms
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sendHandler.removeCallbacks(sendRunnable);
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

        // Sliders + value labels
        seekHr         = findViewById(R.id.seekHr);
        tvHrCurrent    = findViewById(R.id.tvHrCurrent);
        seekSpo2       = findViewById(R.id.seekSpo2);
        tvSpo2Current  = findViewById(R.id.tvSpo2Current);
        seekRr         = findViewById(R.id.seekRr);
        tvRrCurrent    = findViewById(R.id.tvRrCurrent);
        seekPi         = findViewById(R.id.seekPi);
        tvPiCurrent    = findViewById(R.id.tvPiCurrent);
        seekNoise      = findViewById(R.id.seekNoise);
        tvNoiseCurrent = findViewById(R.id.tvNoiseCurrent);

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
        seekHr.setMax(SimulatorParams.HR_MAX - SimulatorParams.HR_MIN);
        seekHr.setProgress(params.getHeartRate() - SimulatorParams.HR_MIN);
        tvHrCurrent.setText(String.valueOf(params.getHeartRate()));
        seekHr.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                int v = p + SimulatorParams.HR_MIN;
                params.setHeartRate(v);
                tvHrCurrent.setText(String.valueOf(v));
                scheduleSend();
            }
        });
        bindIncrDecr(R.id.btnHrDec, R.id.btnHrInc, seekHr);

        // SpO2: 70–100
        seekSpo2.setMax(SimulatorParams.SPO2_MAX - SimulatorParams.SPO2_MIN);
        seekSpo2.setProgress(params.getSpo2() - SimulatorParams.SPO2_MIN);
        tvSpo2Current.setText(String.valueOf(params.getSpo2()));
        seekSpo2.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                int v = p + SimulatorParams.SPO2_MIN;
                params.setSpo2(v);
                tvSpo2Current.setText(String.valueOf(v));
                scheduleSend();
            }
        });
        bindIncrDecr(R.id.btnSpo2Dec, R.id.btnSpo2Inc, seekSpo2);

        // RR: 4–60
        seekRr.setMax(SimulatorParams.RR_MAX - SimulatorParams.RR_MIN);
        seekRr.setProgress(params.getRespiratoryRate() - SimulatorParams.RR_MIN);
        tvRrCurrent.setText(String.valueOf(params.getRespiratoryRate()));
        seekRr.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                int v = p + SimulatorParams.RR_MIN;
                params.setRespiratoryRate(v);
                tvRrCurrent.setText(String.valueOf(v));
                scheduleSend();
            }
        });
        bindIncrDecr(R.id.btnRrDec, R.id.btnRrInc, seekRr);

        // PI: 0.02–20.0 → SeekBar 0–1998 (×0.01 + 0.02)
        int piRange = 1998; // (20.00 - 0.02) / 0.01 = 1998
        seekPi.setMax(piRange);
        seekPi.setProgress(piToProgress(params.getPerfusionIndex()));
        tvPiCurrent.setText(String.format(Locale.US, "%.2f", params.getPerfusionIndex()));
        seekPi.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                float v = progressToPi(p);
                params.setPerfusionIndex(v);
                tvPiCurrent.setText(String.format(Locale.US, "%.2f", v));
                scheduleSend();
            }
        });
        bindIncrDecr(R.id.btnPiDec, R.id.btnPiInc, seekPi);

        // Noise: 0.00–1.00 → SeekBar 0–100
        seekNoise.setMax(100);
        seekNoise.setProgress(Math.round(params.getNoiseLevel() * 100));
        tvNoiseCurrent.setText(String.format(Locale.US, "%.2f", params.getNoiseLevel()));
        seekNoise.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                float v = p / 100.0f;
                params.setNoiseLevel(v);
                tvNoiseCurrent.setText(String.format(Locale.US, "%.2f", v));
                scheduleSend();
            }
        });
        bindIncrDecr(R.id.btnNoiseDec, R.id.btnNoiseInc, seekNoise);
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
                    waveformView.reset();
                    break;
            }
        });

        // Waveform samples — add directly to SurfaceView buffer (high frequency)
        bleManager.getPpgSample().observe(this, value -> {
            if (value != null) waveformView.addSample(value);
        });

        // Vital signs — update metric cards
        bleManager.getLiveHr().observe(this, hr -> {
            if (hr != null) tvHrValue.setText(String.valueOf(hr));
        });
        bleManager.getLiveSpo2().observe(this, spo2 -> {
            if (spo2 != null) tvSpo2Value.setText(String.valueOf(spo2));
        });
        bleManager.getLiveRr().observe(this, rr -> {
            if (rr != null) tvRrValue.setText(String.valueOf(rr));
        });
        bleManager.getLivePi().observe(this, pi -> {
            if (pi != null) tvPiValue.setText(String.format(Locale.US, "%.2f", pi));
        });

        // Noise — cập nhật card metric từ firmware (không phải slider)
        bleManager.getLiveNoise().observe(this, noise -> {
            if (noise != null)
                tvNoiseValue.setText(String.format(Locale.US, "%.2f", noise));
        });

        // Condition (0-5) — firmware xác nhận mode thực tế đang chạy
        // Cập nhật cả label card và highlight button tương ứng
        bleManager.getLiveCondition().observe(this, condition -> {
            if (condition == null) return;
            // Clamp phòng firmware trả giá trị ngoài range
            int idx = Math.max(0, Math.min(SimulatorParams.MODE_LABELS.length - 1, condition));
            tvModeValue.setText(SimulatorParams.MODE_LABELS[idx]);
            highlightModeButton(idx);
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