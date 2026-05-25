package com.medical.simulator;

import android.Manifest;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.medical.simulator.ble.RpiBleManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Dialog for scanning and selecting a BLE peripheral.
 * Scans up to SCAN_TIMEOUT_MS and shows all discovered devices.
 * Highlights devices advertising our simulator service UUID.
 */
public class DeviceScanDialog extends DialogFragment {

    public interface DeviceSelectedListener {
        void onDeviceSelected(@NonNull BluetoothDevice device);
    }

    private static final long SCAN_TIMEOUT_MS = 12_000;

    private BluetoothLeScanner  scanner;
    private DeviceAdapter       adapter;
    private ProgressBar         progressBar;
    private TextView            tvStatus;
    private Button              btnScanToggle;
    private boolean             isScanning = false;

    private DeviceSelectedListener listener;

    private final Handler handler = new Handler(Looper.getMainLooper());

    public void setDeviceSelectedListener(DeviceSelectedListener l) {
        this.listener = l;
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NORMAL, R.style.Theme_MedicalSimulator_Dialog);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_device_scan, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        progressBar   = view.findViewById(R.id.scanProgress);
        tvStatus      = view.findViewById(R.id.tvScanStatus);
        btnScanToggle = view.findViewById(R.id.btnScanToggle);
        RecyclerView rv = view.findViewById(R.id.rvDevices);

        adapter = new DeviceAdapter(device -> {
            stopScan();
            dismiss();
            if (listener != null) listener.onDeviceSelected(device);
        });

        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(adapter);

        btnScanToggle.setOnClickListener(v -> {
            if (isScanning) stopScan();
            else startScan();
        });

        // Auto-start scan when dialog opens
        startScan();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopScan();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }
    }

    // ─── Scan control ─────────────────────────────────────────────────────────

    private void startScan() {
        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null || !btAdapter.isEnabled()) {
            tvStatus.setText("Bluetooth is off");
            return;
        }
        if (!hasPermission()) {
            tvStatus.setText("Permission required");
            return;
        }

        scanner = btAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            tvStatus.setText("BLE scanner unavailable");
            return;
        }

        adapter.clear();
        isScanning = true;
        progressBar.setVisibility(View.VISIBLE);
        btnScanToggle.setText(R.string.stop_scan);
        tvStatus.setText(R.string.scanning);

        // Scan settings: low latency for fast discovery
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();

        // Filter by our simulator service UUID — only shows matching devices
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(RpiBleManager.SERVICE_UUID))
                .build());

        try {
            scanner.startScan(filters, settings, scanCallback);
        } catch (SecurityException e) {
            tvStatus.setText("Permission denied");
            isScanning = false;
            return;
        }

        // Auto-stop after timeout
        handler.postDelayed(this::stopScan, SCAN_TIMEOUT_MS);
    }

    @SuppressWarnings("MissingPermission")
    private void stopScan() {
        if (!isScanning) return;
        isScanning = false;
        handler.removeCallbacksAndMessages(null);

        if (scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (btnScanToggle != null) btnScanToggle.setText(R.string.scan_again);
        if (tvStatus != null) {
            tvStatus.setText(adapter.getItemCount() == 0 ? "No devices found" : "Select a device");
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            String name = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (requireContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED) {
                        name = result.getDevice().getName();
                    }
                } else {
                    name = result.getDevice().getName();
                }
            } catch (Exception ignored) {}
            if (name == null) name = result.getDevice().getAddress();

            boolean isSim = result.getScanRecord() != null
                    && result.getScanRecord().getServiceUuids() != null
                    && result.getScanRecord().getServiceUuids()
                             .contains(new ParcelUuid(RpiBleManager.SERVICE_UUID));

            final String finalName = name;
            final boolean finalIsSim = isSim;
            handler.post(() -> adapter.addOrUpdate(result.getDevice(), finalName, result.getRssi(), finalIsSim));
        }

        @Override
        public void onScanFailed(int errorCode) {
            handler.post(() -> {
                isScanning = false;
                if (tvStatus != null) tvStatus.setText("Scan failed (code " + errorCode + ")");
                if (progressBar != null) progressBar.setVisibility(View.GONE);
            });
        }
    };

    // ─── Permission helper ────────────────────────────────────────────────────

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return requireContext().checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return requireContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    // ─── RecyclerView Adapter ─────────────────────────────────────────────────

    interface OnDeviceClickListener {
        void onDeviceClick(BluetoothDevice device);
    }

    static class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {

        private final List<DeviceItem>     items    = new ArrayList<>();
        private final OnDeviceClickListener listener;

        DeviceAdapter(OnDeviceClickListener listener) {
            this.listener = listener;
        }

        void addOrUpdate(BluetoothDevice device, String name, int rssi, boolean isSimulator) {
            String addr = device.getAddress();
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).address.equals(addr)) {
                    items.get(i).rssi = rssi;
                    notifyItemChanged(i);
                    return;
                }
            }
            items.add(new DeviceItem(device, name, addr, rssi, isSimulator));
            notifyItemInserted(items.size() - 1);
        }

        void clear() {
            items.clear();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_ble_device, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder h, int position) {
            DeviceItem item = items.get(position);
            h.tvName.setText(item.name);
            h.tvAddr.setText(item.address);
            h.tvRssi.setText(item.rssi + " dBm");
            if (item.isSimulator) {
                h.tvBadge.setVisibility(View.VISIBLE);
                h.itemView.setAlpha(1.0f);
            } else {
                h.tvBadge.setVisibility(View.GONE);
                h.itemView.setAlpha(0.65f);
            }
            h.itemView.setOnClickListener(v -> listener.onDeviceClick(item.device));
        }

        @Override public int getItemCount() { return items.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvAddr, tvRssi, tvBadge;
            ViewHolder(@NonNull View v) {
                super(v);
                tvName  = v.findViewById(R.id.tvDeviceName);
                tvAddr  = v.findViewById(R.id.tvDeviceAddr);
                tvRssi  = v.findViewById(R.id.tvDeviceRssi);
                tvBadge = v.findViewById(R.id.tvDeviceBadge);
            }
        }

        static class DeviceItem {
            BluetoothDevice device;
            String name, address;
            int rssi;
            boolean isSimulator;
            DeviceItem(BluetoothDevice d, String n, String a, int r, boolean sim) {
                device = d; name = n; address = a; rssi = r; isSimulator = sim;
            }
        }
    }
}
