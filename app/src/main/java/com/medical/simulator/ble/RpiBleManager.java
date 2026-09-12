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
 * │    ESP32 gửi raw UTF-8 string, ví dụ: "85.20"                  │
 * │    (KHÔNG phải JSON)                                            │
 * │    Range: 0.0 – 150.0  (firmware AC_MAX = 150.0f)              │
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

    // ═══════════════════════════════════════════════════════════════════
    //  Giới hạn trục Y — khớp firmware
    //  AC_MAX = 150.0f → Y_MAX = 160 (padding trên)
    //  Giá trị âm có thể xảy ra (vd: "-10.42") → Y_MIN = -20 (padding dưới)
    //  Y_RANGE = 180.0f  (từ -20 đến 160)
    // ═══════════════════════════════════════════════════════════════════
    public static final float PPG_Y_MIN = -20.0f;
    public static final float PPG_Y_MAX = 160.0f;

    // ─── GATT characteristics ─────────────────────────────────────────
    @Nullable private BluetoothGattCharacteristic writeCharacteristic;
    @Nullable private BluetoothGattCharacteristic notifyCharacteristic;  // waveform PPG
    @Nullable private BluetoothGattCharacteristic statusCharacteristic;  // HR/SpO2/RR status

    // ─── LiveData ─────────────────────────────────────────────────────
    private final MutableLiveData<ConnectionState> connectionState =
            new MutableLiveData<>(ConnectionState.DISCONNECTED);
    private final MutableLiveData<Float>   ppgSample      = new MutableLiveData<>();
    // STATUS characteristic fields (500ms cadence)
    private final MutableLiveData<Integer> liveHr         = new MutableLiveData<>();  // bpm (rounded)
    private final MutableLiveData<Integer> liveSpo2       = new MutableLiveData<>();  // % (fixed 98)
    private final MutableLiveData<Integer> liveRr         = new MutableLiveData<>();  // rpm (fixed 16)
    private final MutableLiveData<Float>   livePi         = new MutableLiveData<>();  // perfusion index
    private final MutableLiveData<Float>   liveNoise      = new MutableLiveData<>();  // noise level
    private final MutableLiveData<Integer> liveCondition  = new MutableLiveData<>();  // 0-5 mode index
    private final MutableLiveData<String>  errorEvent     = new MutableLiveData<>();

    /** Full STATUS packet (with origin/seq) for echo/sync handling in UI layer. */
    private final MutableLiveData<StatusPacket> statusPacket = new MutableLiveData<>();

    public RpiBleManager(@NonNull Context context) {
        super(context);
    }

    // ─── Accessors ────────────────────────────────────────────────────

    /** Trạng thái kết nối — MainActivity observe cái này */
    public LiveData<ConnectionState> getState()           { return connectionState; }

    /** Alias tương thích */

    /** Giá trị PPG mới nhất (0–150 raw từ firmware), 50Hz */
    public LiveData<Float>           getPpgSample()       { return ppgSample; }

    /** Vital signs từ STATUS characteristic (500ms cadence) */
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
                .useAutoConnect(true)
                .timeout(15_000)
                .fail((dev, status) -> {
                    Log.e(TAG, "Connection failed: " + status);
                    connectionState.postValue(ConnectionState.DISCONNECTED);
                    errorEvent.postValue("Kết nối thất bại (code " + status + ")");
                })
                .enqueue();
    }

    public void sendCommand(@NonNull String jsonCommand) {
        if (writeCharacteristic == null || !isConnected()) {
            Log.w(TAG, "sendCommand: not ready");
            return;
        }
        writeCharacteristic(
                writeCharacteristic,
                jsonCommand.getBytes(StandardCharsets.UTF_8),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
                .fail((dev, status) -> Log.e(TAG, "Write failed: " + status))
                .enqueue();
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
        }

        @Override
        protected void onServicesInvalidated() {
            writeCharacteristic  = null;
            notifyCharacteristic = null;
            statusCharacteristic = null;
            connectionState.postValue(ConnectionState.DISCONNECTED);
        }
    }

    // ─── PPG Notification Handler ─────────────────────────────────────

    /**
     * Decode gói PPG từ BLE_CHAR_WAVEFORM_UUID (abcdef2).
     *
     * Firmware gửi: snprintf(buffer, sizeof(buffer), "%.2f", acValue)
     * Ví dụ: "85.20" / "94.15" / "-10.42"
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
     * Firmware gửi JSON 100% qua JsonProtocol::buildStatusResponse(), 500ms/lần.
     * Cấu trúc chính xác 6 key:
     * {
     *   "hr": 75.0,        ← double trong JSON, round → int để hiển thị
     *   "pi": 2.5,         ← double → float
     *   "noise": 0.05,     ← double → float
     *   "condition": 0,    ← int, index mode 0-5
     *   "spo2": 98,        ← int, fixed cứng trong firmware
     *   "rr": 16           ← int, fixed cứng trong firmware
     * }
     */
    private void handleStatusNotification(@NonNull BluetoothDevice device,
                                          @NonNull Data data) {
        final byte[] raw = data.getValue();
        if (raw == null || raw.length == 0) return;

        final String str = new String(raw, StandardCharsets.UTF_8).trim();
        if (str.isEmpty()) return;

        try {
            final JSONObject obj = new JSONObject(str);

            // "hr": 75.0 — firmware gửi double, round về int để hiển thị "75 bpm"
            if (obj.has("hr"))
                liveHr.postValue((int) Math.round(obj.getDouble("hr")));

            // "spo2": 98, "rr": 16 — int thuần
            if (obj.has("spo2"))
                liveSpo2.postValue(obj.getInt("spo2"));
            if (obj.has("rr"))
                liveRr.postValue(obj.getInt("rr"));

            // "pi": 2.5, "noise": 0.05 — float
            if (obj.has("pi"))
                livePi.postValue((float) obj.getDouble("pi"));
            if (obj.has("noise"))
                liveNoise.postValue((float) obj.getDouble("noise"));

            // "condition": 0 — index enum 0-5 map sang mode string ở UI layer
            if (obj.has("condition"))
                liveCondition.postValue(obj.getInt("condition"));

            // Full packet (echo/sync rule + AC/DC amplitude) — UI layer xử lý
            statusPacket.postValue(new StatusPacket(obj));

        } catch (Exception e) {
            Log.w(TAG, "Status JSON parse lỗi: " + str + " — " + e.getMessage());
        }
    }

    // ─── Status Packet ────────────────────────────────────────────────────────

    /**
     * Một gói STATUS từ thiết bị. Mọi field đều optional (null nếu không có).
     * origin: "android" (echo của lệnh ta vừa gửi) hoặc "rpi" (thay đổi từ Pi GUI).
     */
    public static final class StatusPacket {
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
        }

        private static Integer optInt(@NonNull JSONObject o, @NonNull String key) {
            try { return o.has(key) ? (int) Math.round(o.getDouble(key)) : null; }
            catch (Exception e) { return null; }
        }

        private static Float optFloat(@NonNull JSONObject o, @NonNull String key) {
            try { return o.has(key) ? (float) o.getDouble(key) : null; }
            catch (Exception e) { return null; }
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