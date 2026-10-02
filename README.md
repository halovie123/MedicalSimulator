# Medical PPG Simulator — Android App

Ứng dụng Android toàn màn hình dùng để điều khiển và theo dõi **bộ mô phỏng tín hiệu PPG chạy trên Raspberry Pi** qua Bluetooth Low Energy (BLE).

Ứng dụng được viết bằng Java, giao diện XML và Groovy Gradle DSL. Kết nối BLE sử dụng Nordic Android BLE Library.

## Mã nguồn

Nhánh `main` chứa tài liệu giới thiệu. Mã nguồn Android được duy trì trên [nhánh `test-module`](https://github.com/halovie123/MedicalSimulator/tree/test-module); các chức năng và cấu hình dưới đây mô tả nhánh này.

```bash
git clone --branch test-module https://github.com/halovie123/MedicalSimulator.git
cd MedicalSimulator
```

## Chức năng chính

- Quét và kết nối Raspberry Pi theo UUID dịch vụ BLE của bộ mô phỏng.
- Điều khiển HR, SpO₂, nhịp thở, PI, nhiễu và chế độ bệnh lý theo thời gian thực.
- Điều chỉnh riêng biên độ AC/DC của hai kênh IR và RED trong khoảng 0–1500 mV.
- Nhận và hiển thị sóng PPG khoảng 50 Hz bằng `WaveformSurfaceView` trên luồng render riêng.
- Nhận trạng thái từ Raspberry Pi khoảng 5 Hz và đồng bộ hai chiều các thông số, Run, Record và Playback.
- Ghi dữ liệu PPG cùng bộ thông số hiện tại vào tệp CSV trên thiết bị Android.
- Phát lại, tạm dừng, tiếp tục và dừng các bản ghi CSV; đồng bộ phiên phát với Raspberry Pi khi có thể.
- Tự bỏ qua gói trạng thái cũ theo `seq`, gộp các thay đổi slider và tuần tự hóa lệnh BLE để tránh ghi đè trạng thái mới.

## Yêu cầu

| Thành phần | Yêu cầu |
|---|---|
| Android | Android 8.0 (API 26) trở lên |
| Compile/Target SDK | API 34 |
| JDK chạy Gradle | JDK 17 |
| Mức ngôn ngữ Java | Java 11 |
| Android Gradle Plugin | 8.3.2 |
| Gradle Wrapper | 8.6 |
| BLE | Thiết bị Android có Bluetooth Low Energy |
| Thiết bị mô phỏng | Raspberry Pi đang chạy BLE GATT server tương thích |
| Hướng màn hình | Landscape, khóa cố định |

## Thông số mô phỏng

| Thông số | Khoảng giá trị | Bước trên giao diện | Mặc định |
|---|---:|---:|---:|
| Heart Rate | 10–300 bpm | 1 bpm | 75 |
| SpO₂ | 0–100% | 1% | 98 |
| Respiratory Rate | 1–150 rpm | 1 rpm | 16 |
| Perfusion Index | 0.01–30.00% | 0.01 | 3.00 |
| Noise | 0.00–1.00 | 0.01 | 0.10 |
| AC IR | 0–1500 mV | 1 mV | 45 mV |
| AC RED | 0–1500 mV | 1 mV | 45 mV |
| DC IR | 0–1500 mV | 1 mV | 1500 mV |
| DC RED | 0–1500 mV | 1 mV | 1500 mV |

Các chế độ hỗ trợ: `Normal`, `Arrhythmia`, `Weak Perfusion`, `Vasoconstriction`, `Strong Perfusion` và `Vasodilation`.

## Giao thức BLE

Ứng dụng và BLE server trên Raspberry Pi phải dùng cùng bộ UUID:

| Thành phần | UUID | Chiều dữ liệu | Định dạng |
|---|---|---|---|
| Service | `12345678-1234-5678-1234-56789abcdef0` | — | Dịch vụ chính |
| Command | `12345678-1234-5678-1234-56789abcdef1` | Android → Pi | JSON, write/write without response |
| Waveform | `12345678-1234-5678-1234-56789abcdef2` | Pi → Android | Chuỗi UTF-8 chứa một số thực, notify khoảng 50 Hz |
| Status | `12345678-1234-5678-1234-56789abcdef3` | Pi → Android | JSON, notify khoảng 5 Hz |

Ứng dụng yêu cầu MTU 256 sau khi kết nối. Characteristic Command là bắt buộc; Waveform và Status được đăng ký notify khi có sẵn.

Ví dụ lệnh cập nhật thông số:

```json
{
  "hr": 75,
  "spo2": 98,
  "rr": 16,
  "pi": 3.0,
  "noise": 0.1,
  "condition": 0,
  "ac_ir_mv": 45.0,
  "ac_red_mv": 45.0,
  "dc_ir_mv": 1500.0,
  "dc_red_mv": 1500.0,
  "origin": "android"
}
```

Khi chỉnh slider, ứng dụng chỉ gửi các trường vừa thay đổi cùng `origin`, tránh ghi đè thông số khác bằng giá trị cũ.

Các lệnh thao tác được gửi riêng để không bị trộn với gói thông số:

```json
{"run":true,"origin":"android"}
{"record":true,"record_file":"data_1.csv","origin":"android"}
{"pb":"start","pb_file":"data_1.csv","origin":"android"}
```

Gói Status có thể chứa các thông số ở trên cùng với `running`, `recording`, `record_file`, `pb`, `pb_file`, `origin` và `seq`. Giá trị `pb` là `0` (không phát), `1` (đang phát) hoặc `2` (tạm dừng). Raspberry Pi là nguồn trạng thái chuẩn cho các thay đổi có `origin: "rpi"`.

## Ghi và phát lại dữ liệu

Bản ghi được lưu trong thư mục riêng của ứng dụng (`getExternalFilesDir()`; dùng `getFilesDir()` làm phương án dự phòng) với tên `data_<n>.csv`.

CSV sử dụng header:

```text
Time_s,IR_Raw,HR,SpO2,RR,PI,Noise,Condition,AC_IR_mV,AC_RED_mv,DC_IR_mV,DC_RED_mV
```

Sóng PPG được ghi theo tốc độ nhận qua BLE; bộ thông số được đóng dấu định kỳ 1 Hz. Khi phát lại, ứng dụng dùng chênh lệch `Time_s` trong tệp và dùng 50 Hz làm tốc độ dự phòng nếu timestamp không hợp lệ.

## Cấu trúc dự án

```text
MedicalSimulator/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/medical/simulator/
│       │   ├── MainActivity.java
│       │   ├── DeviceScanDialog.java
│       │   ├── PlaybackDialog.java
│       │   ├── ble/RpiBleManager.java
│       │   ├── model/SimulatorParams.java
│       │   ├── recording/PpgRecorder.java
│       │   ├── playback/PpgPlayback.java
│       │   └── ui/WaveformSurfaceView.java
│       └── res/
│           ├── layout/
│           ├── drawable/
│           └── values/
├── build.gradle
├── settings.gradle
├── gradle.properties
└── README.md
```

Luồng dữ liệu chính:

```text
Raspberry Pi BLE server
    ├── Waveform notify ──> RpiBleManager ──> WaveformSurfaceView
    └── Status notify ────> RpiBleManager ──> MainActivity/UI/Recorder

MainActivity
    ├── SimulatorParams ──> Command JSON ──> RpiBleManager ──> Raspberry Pi
    ├── PpgRecorder ──────> CSV cục bộ
    └── PpgPlayback ──────> WaveformSurfaceView
```

## Build và cài đặt

### Android Studio

1. Mở thư mục `MedicalSimulator/` bằng Android Studio.
2. Chờ Gradle sync hoàn tất.
3. Kết nối thiết bị Android 8.0+ đã bật USB debugging.
4. Chạy cấu hình `app`.

### Dòng lệnh

Cài JDK 17 và Android SDK Platform 34; cấu hình đường dẫn SDK trong `local.properties` (`sdk.dir=...`) hoặc biến môi trường `ANDROID_HOME`.

```bash
./gradlew assembleDebug
```

APK debug được tạo tại:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Quyền Android

| Quyền | Phiên bản / mục đích |
|---|---|
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | Android 11 trở xuống |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Quét BLE trên Android 11 trở xuống |
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` | Android 12 trở lên |
| `BLUETOOTH_ADVERTISE` | Khai báo khả năng BLE trên Android 12 trở lên |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Được khai báo trong manifest |

Manifest chưa khai báo foreground service; các quyền trên không bảo đảm ứng dụng duy trì BLE khi chạy nền.

Quyền Bluetooth cần thiết được yêu cầu khi người dùng mở chức năng quét thiết bị.

## Lưu ý tích hợp Raspberry Pi

- BLE server phải quảng bá Service UUID để xuất hiện trong danh sách quét đã lọc.
- Command characteristic phải hỗ trợ `WRITE` hoặc `WRITE_NO_RESPONSE`.
- Waveform payload là một số thực UTF-8, ví dụ `1520.43`; không gửi dưới dạng JSON.
- Status payload là JSON UTF-8. Nên tăng `seq` theo từng kết nối để Android loại gói cũ hoặc đến sai thứ tự.
- Giữ tên tệp ghi/phát ngắn và giống nhau ở hai phía để đồng bộ phiên playback. Lệnh BLE truyền tên tệp và thao tác; không tải nội dung CSV từ Android sang Pi. Hai phía cần có bản ghi tương ứng để phát đồng bộ.
