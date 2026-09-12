package com.medical.simulator.ble;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.UUID;

import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.data.Data;

import org.json.JSONObject;

/**
 * BLE manager cho ESP32-S3 PPG Medical Simulator.
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  CÁCH TÌM UUID ĐÚNG TỪ FIRMWARE ESP32                           │
 * │                                                                 │
 * │  1. Mở file .ino / .cpp của firmware                            │
 * │  2. Tìm các dòng define UUID, ví dụ:                            │
 * │       #define SERVICE_UUID    "xxxx...xxxx"                     │
 * │       #define CHAR_CMD_UUID   "xxxx...xxxx"  ← Write (điều kh) │
 * │       #define CHAR_PPG_UUID   "xxxx...xxxx"  ← Notify (sóng)   │
 * │  3. Copy vào 3 hằng SERVICE_UUID, WRITE_UUID, NOTIFY_UUID bên  │
 * │     dưới.                                                       │
 * │                                                                 │
 * │  GIAO THỨC NHẬN SÓNG PPG (50 Hz):                               │
 * │    Pi gửi raw UTF-8 string, ví dụ: "1520.43"                    │
 * │    (KHÔNG phải JSON)                                            │
 * │    Range: millivolts sóng IR trên đường DAC (DC+AC+noise)       │
 * └─────────────────────────────────────────────────────────────────┘
 */
public class RpiBleManager extends BleManager {

    private static final String TAG = "RpiBleManager";

    // ═══════════════════════════════════════════════════════════════════
    //  ★ ĐIỀN UUID TỪ FIRMWARE ESP32 VÀO ĐÂY ★
    // ═══════════════════════════════════════════════════════════════════

    public static final UUID SERVICE_UUID =
            UUID.fromString("12345678-1234-5678-1234-56789abcdef0");

    /** Write: app → ESP32 — ghi lệnh JSON điều khiển (BLE_CHAR_COMMAND_UUID) */
    public static final UUID WRITE_UUID =
            UUID.fromString("12345678-1234-5678-1234-56789abcdef1");

    /** Notify: ESP32 → app — raw float sóng PPG 50Hz (BLE_CHAR_WAVEFORM_UUID) */
    public static final UUID NOTIFY_UUID =
            UUID.fromString("12345678-1234-5678-1234-56789abcdef2");

    /** Notify: ESP32 → app — gói trạng thái HR/SpO2/RR (BLE_CHAR_STATUS_UUID) */
    public static final UUID STATUS_UUID =
            UUID.fromString("12345678-1234-5678-1234-56789abcdef3");

    // ─── GATT characteristics ─────────────────────────────────────────
    @Nullable private BluetoothGattCharacteristic writeCharacteristic;
    @Nullable private BluetoothGattCharacteristic notifyCharacteristic;  // waveform PPG
    @Nullable private BluetoothGattCharacteristic statusCharacteristic;  // HR/SpO2/RR status

    // ─── LiveData ─────────────────────────────────────────────────────
    private final MutableLiveData<ConnectionState> connectionState =
            new MutableLiveData<>(ConnectionState.DISCONNECTED);
    private final MutableLiveData<Float>   ppgSample      = new MutableLiveData<>();
    // STATUS characteristic fields (~5 Hz / 200 ms cadence, protocol v2)
    private final MutableLiveData<Integer> liveHr         = new MutableLiveData<>();  // bpm (rounded)
    private final MutableLiveData<Integer> liveSpo2       = new MutableLiveData<>();  // %
    private final MutableLiveData<Integer> liveRr         = new MutableLiveData<>();  // brpm
    private final MutableLiveData<Float>   livePi         = new MutableLiveData<>();  // perfusion index
    private final MutableLiveData<Float>   liveNoise      = new MutableLiveData<>();  // noise level
    private final MutableLiveData<Integer> liveCondition  = new MutableLiveData<>();  // 0-5 mode index
    private final MutableLiveData<String>  errorEvent     = new MutableLiveData<>();

    /** Full STATUS packet (with origin/seq) for echo/sync handling in UI layer. */
    private final MutableLiveData<StatusPacket> statusPacket = new MutableLiveData<>();

    /** Last applied STATUS seq (16-bit space, -1 = none yet). Reset on every (re)connect. */
    private int lastStatusSeq = -1;

    // Nordic queues GATT operations, but a command issued while initialize() is
    // still completing can otherwise be rejected and silently disappear. Keep
    // a small application queue and serialize writes explicitly. Parameter
    // deltas are already coalesced by MainActivity; action commands retain FIFO
    // ordering (run -> record -> playback, etc.).
    private static final int MAX_PENDING_COMMANDS = 32;
    private final Object commandLock = new Object();
    private final ArrayDeque<PendingCommand> pendingCommands = new ArrayDeque<>();
    private boolean commandWriteInFlight;

    private static final class PendingCommand {
        final String json;
        int attempts;
        PendingCommand(String json) { this.json = json; }
    }

    public RpiBleManager(@NonNull Context context) {
        super(context);
    }

    // ─── Accessors ────────────────────────────────────────────────────

    /** Trạng thái kết nối — MainActivity observe cái này */
    public LiveData<ConnectionState> getState()           { return connectionState; }

    /** Alias tương thích */

    /** Giá trị PPG mới nhất — display-IR millivolts từ Pi (đường DAC), 50Hz */
    public LiveData<Float>           getPpgSample()       { return ppgSample; }

    /** Vital signs từ STATUS characteristic (nhịp ~200 ms / 5 Hz) */
    public LiveData<Integer>         getLiveHr()          { return liveHr; }
    public LiveData<Integer>         getLiveSpo2()        { return liveSpo2; }
    public LiveData<Integer>         getLiveRr()          { return liveRr; }
    public LiveData<Float>           getLivePi()          { return livePi; }
    public LiveData<Float>           getLiveNoise()       { return liveNoise; }
    public LiveData<Integer>         getLiveCondition()   { return liveCondition; }

    /** Lỗi để hiện Toast */
    public LiveData<String>          getErrorEvent()      { return errorEvent; }

    /** Gói STATUS đầy đủ (origin/seq + AC/DC) — dùng cho echo/sync rule */
    public LiveData<StatusPacket>    getStatusPacket()    { return statusPacket; }

    // ─── Connection ───────────────────────────────────────────────────

    public void connectToDevice(@NonNull BluetoothDevice device) {
        connectionState.postValue(ConnectionState.CONNECTING);
        connect(device)
                .retry(5, 300)
                // This is an explicit user-selected device from the scan list.
                // AutoConnect can defer the GATT session for minutes on some
                // Android/BlueZ combinations, which made the first slider
                // edits appear frozen.
                .useAutoConnect(false)
                .timeout(15_000)
                .fail((dev, status) -> {
                    Log.e(TAG, "Connection failed: " + status);
                    connectionState.postValue(ConnectionState.DISCONNECTED);
                    errorEvent.postValue("Kết nối thất bại (code " + status + ")");
                })
                .enqueue();
    }

    public void sendCommand(@NonNull String jsonCommand) {
        synchronized (commandLock) {
            while (pendingCommands.size() >= MAX_PENDING_COMMANDS) {
                pendingCommands.removeFirst();
            }
            pendingCommands.addLast(new PendingCommand(jsonCommand));
        }
        drainCommandQueue();
    }

    /** Start exactly one queued write when the GATT is ready. */
    private void drainCommandQueue() {
        final PendingCommand command;
        final BluetoothGattCharacteristic characteristic;
        synchronized (commandLock) {
            if (commandWriteInFlight || pendingCommands.isEmpty()) return;
            characteristic = writeCharacteristic;
            if (characteristic == null || !isConnected()) return;
            command = pendingCommands.removeFirst();
            commandWriteInFlight = true;
        }

        // The Pi exposes write-without-response. Serializing the operations is
        // still required by Nordic's operation queue; it prevents a rapid
        // slider gesture from overtaking a newer action command.
        writeCharacteristic(
                characteristic,
                command.json.getBytes(StandardCharsets.UTF_8),
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        )
                .done(dev -> commandFinished(command, true))
                .fail((dev, status) -> {
                    Log.e(TAG, "Write failed: " + status + " command=" + command.json);
                    commandFinished(command, false);
                })
                .enqueue();
    }

    private void commandFinished(@NonNull PendingCommand command, boolean success) {
        synchronized (commandLock) {
            commandWriteInFlight = false;
            if (!success && command.attempts++ < 2) {
                pendingCommands.addFirst(command);
            }
        }
        drainCommandQueue();
    }

    // ─── Protocol v2 action senders ───────────────────────────────────
    // Minimal separate JSON messages — never merged into the params full-state packet.

    /** {"run":true|false,"origin":"android"} — start/stop the live simulation. */
    public void sendRun(boolean on) {
        sendCommand("{\"run\":" + (on ? "true" : "false") + ",\"origin\":\"android\"}");
    }

    /** {"record":true|false,"origin":"android"} — start/stop remote-side recording. */
    public void sendRecord(boolean on) {
        sendRecord(on, null);
    }

    /** Record state plus the short shared file name used by both applications. */
    public void sendRecord(boolean on, @Nullable String fileName) {
        StringBuilder sb = new StringBuilder(80);
        sb.append("{\"record\":").append(on ? "true" : "false");
        if (on && fileName != null && !fileName.isEmpty()) {
            sb.append(",\"record_file\":\"")
                    .append(escapeJsonString(fileName)).append('"');
        }
        sb.append(",\"origin\":\"android\"}");
        sendCommand(sb.toString());
    }

    /**
     * {"pb":"start"|"pause"|"resume"|"stop","pb_file":"<name>","origin":"android"}
     * pb_file is included only when non-null (required for "start").
     */
    public void sendPlayback(@NonNull String action, @Nullable String fileName) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("{\"pb\":\"").append(action).append('"');
        if (fileName != null && !fileName.isEmpty()) {
            sb.append(",\"pb_file\":\"").append(escapeJsonString(fileName)).append('"');
        }
        sb.append(",\"origin\":\"android\"}");
        sendCommand(sb.toString());
    }

    private static String escapeJsonString(@NonNull String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    public void disconnectAndClose() {
        disconnect().enqueue();
    }

    // ─── GATT Callback ────────────────────────────────────────────────

    @NonNull
    @Override
    protected BleManagerGattCallback getGattCallback() {
        return new EspGattCallback();
    }

    private class EspGattCallback extends BleManagerGattCallback {

        @Override
        public boolean isRequiredServiceSupported(@NonNull BluetoothGatt gatt) {
            final BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                Log.e(TAG, "Service không tìm thấy: " + SERVICE_UUID);
                // In tất cả service để tìm UUID đúng
                for (BluetoothGattService s : gatt.getServices()) {
                    Log.d(TAG, "  Available service: " + s.getUuid());
                    for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                        Log.d(TAG, "    char: " + c.getUuid()
                                + " props=0x" + Integer.toHexString(c.getProperties()));
                    }
                }
                return false;
            }

            writeCharacteristic  = service.getCharacteristic(WRITE_UUID);
            notifyCharacteristic = service.getCharacteristic(NOTIFY_UUID);
            statusCharacteristic = service.getCharacteristic(STATUS_UUID);  // optional

            boolean writeOk = writeCharacteristic != null
                    && (writeCharacteristic.getProperties()
                    & (BluetoothGattCharacteristic.PROPERTY_WRITE
                    | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;

            boolean notifyOk = notifyCharacteristic != null
                    && (notifyCharacteristic.getProperties()
                    & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;

            if (!writeOk)  Log.e(TAG, "Write char missing/no-write-prop: " + WRITE_UUID);
            if (!notifyOk) Log.e(TAG, "Notify char missing/no-notify-prop: " + NOTIFY_UUID);

            // Cho kết nối tiếp kể cả khi notify chưa đúng UUID —
            // để điều khiển vẫn hoạt động trong khi bạn debug UUID
            return writeOk;
        }

        @Override
    protected void initialize() {
            lastStatusSeq = -1;   // fresh seq baseline per connection

            // MTU — tăng cho gói lệnh JSON
            requestMtu(256)
                    .with((dev, mtu) -> Log.d(TAG, "MTU negotiated: " + mtu))
                    .enqueue();

            // ── BƯỚC 1: Subscribe Characteristic nhận sóng PPG ──
            if (notifyCharacteristic != null) {

                // Đăng ký callback — gọi mỗi khi ESP32 gửi packet
                setNotificationCallback(notifyCharacteristic)
                        .with(RpiBleManager.this::handlePpgNotification);

                // Bật CCCD — báo cho ESP32 biết app sẵn sàng nhận
                enableNotifications(notifyCharacteristic)
                        .done(dev -> Log.d(TAG, "✓ PPG waveform notifications ON"))
                        .fail((dev, status) -> {
                            Log.e(TAG, "enableNotifications(waveform) failed: " + status);
                            errorEvent.postValue("Không nhận được sóng PPG (code " + status + ")");
                        })
                        .enqueue();
            } else {
                Log.w(TAG, "notifyCharacteristic null — kiểm tra NOTIFY_UUID (abcdef2)");
                errorEvent.postValue("UUID sóng PPG sai — xem Logcat tag RpiBleManager");
            }

            // ── Subscribe STATUS characteristic (HR/SpO2/RR) ──
            if (statusCharacteristic != null) {
                setNotificationCallback(statusCharacteristic)
                        .with(RpiBleManager.this::handleStatusNotification);
                enableNotifications(statusCharacteristic)
                        .done(dev -> Log.d(TAG, "✓ Status notifications ON"))
                        .fail((dev, status) -> Log.w(TAG, "Status notify failed: " + status))
                        .enqueue();
            }

            connectionState.postValue(ConnectionState.CONNECTED);
            // Flush edits made while the connection was negotiating services.
            drainCommandQueue();
        }

        @Override
        protected void onServicesInvalidated() {
            writeCharacteristic  = null;
            notifyCharacteristic = null;
            statusCharacteristic = null;
            lastStatusSeq        = -1;
            synchronized (commandLock) {
                commandWriteInFlight = false;
            }
            connectionState.postValue(ConnectionState.DISCONNECTED);
        }
    }

    // ─── PPG Notification Handler ─────────────────────────────────────

    /**
     * Decode gói PPG từ BLE_CHAR_WAVEFORM_UUID (abcdef2).
     *
     * Pi gửi: sprintf("%.2f") của display-IR signal tính theo millivolts trên
     * đường DAC (DC + AC + noise), ví dụ: "1520.43" / "1640.05".
     * Khi simulation stopped, giá trị vẫn được phát tiếp (ring value cuối) —
     * dùng trường "running" trong status frame để biết trạng thái dừng.
     *
     * KHÔNG clamp — giá trị âm hợp lệ, WaveformSurfaceView tự xử lý range.
     */
    private void handlePpgNotification(@NonNull BluetoothDevice device,
                                       @NonNull Data data) {
        final byte[] raw = data.getValue();
        if (raw == null || raw.length == 0) return;

        final String str = new String(raw, StandardCharsets.UTF_8).trim();
        if (str.isEmpty()) return;

        try {
            // Float.parseFloat xử lý được cả số âm "-10.42"
            final float value = Float.parseFloat(str);
            ppgSample.postValue(value);   // raw value, không clamp
        } catch (NumberFormatException e) {
            Log.v(TAG, "PPG packet lỗi (bỏ qua): \"" + str + "\"");
        }
    }

    // ─── Status Notification Handler ─────────────────────────────────

    /**
     * Decode gói STATUS từ BLE_CHAR_STATUS_UUID (abcdef3).
     *
     * Pi (comm/ble_server.py) gửi JSON 15 key ở nhịp ~5 Hz — giao thức v2
     * (see PPG_simulator_raspi/docs/ble_protocol.md):
     * {
     *   "hr": 75.0, "spo2": 98.0, "rr": 16.0,   ← vitals (double → int hiển thị)
     *   "pi": 3.0, "noise": 0.0, "condition": 0,← double / double / int 0-5
     *   "ac_ir_mv": 45.0, "ac_red_mv": 21.6,    ← echoed AC/DC setpoints (mV)
     *   "dc_ir_mv": 1500.0, "dc_red_mv": 1500.0,
     *   "running": true,   "recording": false,  ← Pi truth của nút Run/Record
     *   "pb": 0,           ← playback: 0 inactive | 1 playing | 2 paused
     *   "origin": "rpi",   "seq": 42
     * }
     * "pb_file" (≤ 14 chars) xuất hiện CHỈ khi pb > 0.
     *
     * origin = "android" chỉ trong 1 s grace window sau một phone command;
     * mọi tick khác là "rpi" — status frame là source of truth.
     * seq tăng đơn điệu trong mỗi kết nối (reset lại baseline khi (re)connect).
     */
    private void handleStatusNotification(@NonNull BluetoothDevice device,
                                          @NonNull Data data) {
        final byte[] raw = data.getValue();
        if (raw == null || raw.length == 0) return;

        final String str = new String(raw, StandardCharsets.UTF_8).trim();
        if (str.isEmpty()) return;

        try {
            final JSONObject obj = new JSONObject(str);
            final StatusPacket pkt = new StatusPacket(obj);

            // A5: drop stale / out-of-order status packets before touching any UI feed
            if (isStaleStatusSeq(pkt.seq)) {
                Log.v(TAG, "Bỏ qua status cũ: seq=" + pkt.seq + " (last=" + lastStatusSeq + ")");
                return;
            }

            // Full packet is the single UI update path. The old implementation
            // also posted six individual LiveData values for the same packet,
            // needlessly multiplying main-thread work at 5 Hz.
            statusPacket.postValue(pkt);

        } catch (Exception e) {
            Log.w(TAG, "Status JSON parse lỗi: " + str + " — " + e.getMessage());
        }
    }

    /**
     * True if seq is strictly older than the last applied packet within the 16-bit
     * space (wrap-unsafe gap > 0x8000 is treated as a plausible sequence wrap, accepted).
     * Advances lastStatusSeq when the packet is accepted.
     */
    private boolean isStaleStatusSeq(@Nullable Integer seqObj) {
        if (seqObj == null) return false;   // no seq information — never drop
        final int s    = seqObj;
        final int prev = lastStatusSeq;
        if (prev >= 0) {
            final int behind = prev - s;
            if (behind > 0 && behind < 0x8000) return true;
        }
        lastStatusSeq = s;
        return false;
    }

    // ─── Status Packet ────────────────────────────────────────────────────────

    /**
     * Một gói STATUS từ thiết bị. Mọi field đều optional (null nếu không có).
     * origin: "android" (echo của lệnh ta vừa gửi) hoặc "rpi" (thay đổi từ Pi GUI).
     *
     * Protocol v2 — mirrored Pi truth fields:
     *   running  — "running": boolean (null if absent)
     *   recording— "recording": boolean (null if absent)
     *   pb       — "pb": 0 inactive | 1 playing | 2 paused   (-1 sentinel if absent)
     *   pbFile   — "pb_file": name, present only when pb > 0  (null if absent)
     *
     * PB code values exposed as constants for the UI sync layer.
     */
    public static final class StatusPacket {
        /** pb codes as reported by the device. */
        public static final int PB_INACTIVE = 0;
        public static final int PB_PLAYING  = 1;
        public static final int PB_PAUSED   = 2;
        /** Sentinel when the "pb" key was absent from the packet. */
        public static final int PB_UNKNOWN  = -1;

        @Nullable public final String  origin;
        @Nullable public final Integer hr;
        @Nullable public final Integer spo2;
        @Nullable public final Integer rr;
        @Nullable public final Float   pi;
        @Nullable public final Float   noise;
        @Nullable public final Integer condition;
        @Nullable public final Float   acIrMv;
        @Nullable public final Float   acRedMv;
        @Nullable public final Float   dcIrMv;
        @Nullable public final Float   dcRedMv;
        @Nullable public final Integer seq;

        // ─── Protocol v2 action/status echo fields ────────────────────
        @Nullable public final Boolean running;
        @Nullable public final Boolean recording;
        @Nullable public final String  recordFile;
        public final int pb;                       // PB_* or PB_UNKNOWN if absent
        @Nullable public final String  pbFile;

        StatusPacket(@NonNull JSONObject o) {
            String origin = null;
            try { origin = o.has("origin") ? o.getString("origin") : null; } catch (Exception ignored) {}
            this.origin     = origin;
            this.hr         = optInt(o, "hr");
            this.spo2       = optInt(o, "spo2");
            this.rr         = optInt(o, "rr");
            this.pi         = optFloat(o, "pi");
            this.noise      = optFloat(o, "noise");
            this.condition  = optInt(o, "condition");
            this.acIrMv     = optFloat(o, "ac_ir_mv");
            this.acRedMv    = optFloat(o, "ac_red_mv");
            this.dcIrMv     = optFloat(o, "dc_ir_mv");
            this.dcRedMv    = optFloat(o, "dc_red_mv");
            this.seq        = optInt(o, "seq");

            // v2 fields
            this.running   = optBoolean(o, "running");
            this.recording = optBoolean(o, "recording");
            this.recordFile = optString(o, "record_file");
            this.pb        = o.has("pb") ? o.optInt("pb", PB_UNKNOWN) : PB_UNKNOWN;
            this.pbFile    = optString(o, "pb_file");
        }

        /** Mirrored Pi running-state (null if the packet didn't carry "running"). */
        @Nullable public Boolean isRunning()    { return running; }
        /** Mirrored Pi recording-state (null if the packet didn't carry "recording"). */
        @Nullable public Boolean isRecording()  { return recording; }
        /** Mirrored Pi playback code — PB_* / PB_UNKNOWN. */
        public int pbCode()                     { return pb; }
        /** Mirrored Pi playback file name (null unless pb > 0). */
        @Nullable public String playbackFile()  { return pbFile; }

        private static Integer optInt(@NonNull JSONObject o, @NonNull String key) {
            try { return o.has(key) ? (int) Math.round(o.getDouble(key)) : null; }
            catch (Exception e) { return null; }
        }

        private static Float optFloat(@NonNull JSONObject o, @NonNull String key) {
            try { return o.has(key) ? (float) o.getDouble(key) : null; }
            catch (Exception e) { return null; }
        }

        @Nullable
        private static Boolean optBoolean(@NonNull JSONObject o, @NonNull String key) {
            try {
                if (!o.has(key)) return null;
                Object v = o.get(key);
                if (v instanceof Boolean) return (Boolean) v;
                // defensive: numeric/string coercion of "true"/1
                if (v instanceof Number)  return ((Number) v).intValue() != 0;
                if (v instanceof String)  return "true".equalsIgnoreCase((String) v) || "1".equals(v);
                return null;
            } catch (Exception e) {
                return null;
            }
        }

        @Nullable
        private static String optString(@NonNull JSONObject o, @NonNull String key) {
            try {
                if (!o.has(key)) return null;
                String s = o.getString(key);
                return (s == null || s.isEmpty()) ? null : s;
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ─── Logging ──────────────────────────────────────────────────────

    @Override
    public void log(int priority, @NonNull String message) {
        if (priority >= Log.DEBUG) {
            Log.println(priority, TAG, message);
        }
    }

    // ─── Enum ─────────────────────────────────────────────────────────

    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }
}
