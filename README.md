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

Giao diện giống app trên Raspberry Pi: thanh điều hướng bên trái với cùng 4 trang và Signal setup, chân trang hiển thị chế độ đầu ra (HARDWARE / DRY RUN / NO DAC) và bộ đếm Buffer, Lost, Clipped của Pi. Mọi thao tác được gửi sang Pi; mọi giá trị hiển thị do Pi gửi về. Điện thoại không tự tạo tín hiệu và không chạy mô hình.

- **Classic / Monitor:** Start/Stop output, Record/Save CSV (file lưu trên Pi), chế độ bệnh lý, 3-Gaussian, tiếng bíp, nhịp tim, SpO₂ mục tiêu, nhịp thở; đồ thị TX (IR/RED) và RX (OPT101 A4/A0) kèm nhịp và SpO₂ thu do Pi ước lượng. Không còn thông số PI, giống app Pi.
- **Calibration / RX:** tần số, mức IR/RED, biên độ sine, dung sai, Start/Stop, hiệu chuẩn SpO₂ thu; chấm đạt/không đạt của từng làn do Pi so khớp.
- **Recordings:** danh sách bản ghi CSV trên Pi, chọn, Play/Pause/Replay (phát trên màn hình Pi, không ra LED), thông số đã ghi tại vị trí phát.
- **PPG morphology:** Fitted Gaussian, LSM-GAN, PSF-GAN, APFT-GAN (khoảng HR min–max), notch, HR, DC, AC, New sequence, Play LED · 30 s, vị trí xem. Mô hình chạy trên Pi; điện thoại chỉ điều khiển và hiển thị bản xem trước hoặc tín hiệu đang phát.
- **Signal setup:** AC/DC từng kênh (0–1500 mV) và mức nhiễu.
- Chuyển trang trên điện thoại thì Pi chuyển theo, và ngược lại. Ngôn ngữ nhãn theo ngôn ngữ của app Pi (Anh/Việt).
- Tự bỏ qua gói trạng thái cũ theo `seq`, gộp các thay đổi slider (tối đa 5 lệnh/giây mỗi nhóm) và tuần tự hóa lệnh BLE để tránh ghi đè trạng thái mới.

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
| Stream (v3) | `12345678-1234-5678-1234-56789abcdef4` | Pi → Android | `T<ir>,<red>;…\|R<ir>,<red>;…` mV, notify ~10 Hz, ~50 mẫu/s mỗi phía |
| UI (v3) | `12345678-1234-5678-1234-56789abcdef5` | Pi → Android | JSON `{"k": phần, …}` ≤ 240 byte, notify + read |

Ứng dụng yêu cầu MTU 517 sau khi kết nối (mọi gói v3 vẫn ≤ 240 byte, nên MTU 256 cũng đủ). Characteristic Command là bắt buộc; các characteristic còn lại được đăng ký notify khi có sẵn. Với bản app Pi chưa có v3, chỉ các thông số Monitor điều khiển được và chân trang báo điều đó.

Lệnh trang (v3), gửi trên Command cạnh các khóa v2 (chi tiết: `PPG_simulator_raspi/docs/ble_protocol.md`):

```json
{"view":"calibration","origin":"android"}
{"cal":{"ir":500,"red":500,"sw":100,"run":true},"origin":"android"}
{"nn":{"mode":"APFT-GAN","lo":70,"hi":80,"gen":true},"origin":"android"}
{"rec":{"sel":"data_1.csv"},"origin":"android"}
{"beep":false,"origin":"android"}
```

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

Như app Pi: Record CSV ghi trên Raspberry Pi (`dataset/data_<n>.csv`), trang Recordings liệt kê và phát lại các tệp đó trên Pi. Ứng dụng không còn ghi hay phát CSV trên điện thoại (`PpgRecorder`, `PpgPlayback`, `PlaybackDialog` đã bỏ).

## Cấu trúc dự án

```text
MedicalSimulator/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/medical/simulator/
│       │   ├── MainActivity.java          # thanh điều hướng, chân trang, BLE, gửi lệnh
│       │   ├── DeviceScanDialog.java
│       │   ├── ble/RpiBleManager.java      # v1–v3 characteristics
│       │   ├── pages/                      # Monitor, Calibration, Recordings, Neural, Signal setup
│       │   ├── remote/Streams.java         # 8 s TX/RX gần nhất từ Stream
│       │   └── ui/                         # Ui (bảng màu Pi), LaneView, ValueControl, Choice, Texts
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
    ├── Status notify ──> RpiBleManager ──> các trang (thông số, Run/Record)
    ├── Stream notify ──> RpiBleManager ──> Streams ──> LaneView (TX, RX)
    └── UI notify ──────> RpiBleManager ──> MainActivity ──> trang đang hiển thị trên Pi

Trang ──> Command JSON (v2 + v3) ──> RpiBleManager ──> Raspberry Pi
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
- Bản ghi nằm trên Pi; lệnh BLE chỉ truyền tên tệp và thao tác, không truyền nội dung CSV.
