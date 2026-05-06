# Medical PPG Simulator — Android App

Fullscreen Android BLE controller for an **ESP32-S3 PPG medical simulator**.  
Built with Java + XML + Groovy Gradle DSL, using the **Nordic Android BLE Library**.

---

## Project Structure

```
MedicalSimulator/
├── app/
│   ├── build.gradle                         # App Groovy DSL (minSdk 26, targetSdk 34)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/medical/simulator/
│       │   ├── MainActivity.java            # Main UI orchestrator
│       │   ├── DeviceScanDialog.java        # BLE scan + device selection dialog
│       │   ├── ble/
│       │   │   └── EspBleManager.java       # Nordic BLE manager (NUS protocol)
│       │   ├── model/
│       │   │   └── SimulatorParams.java     # Parameter model + JSON serialisation
│       │   └── ui/
│       │       └── WaveformSurfaceView.java # 60fps realtime PPG renderer
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml        # Fullscreen landscape monitor UI
│           │   ├── dialog_device_scan.xml   # BLE scan dialog
│           │   ├── item_ble_device.xml      # Device list row
│           │   └── row_param_slider.xml     # Reusable slider row (reference)
│           ├── values/
│           │   ├── colors.xml               # Dark neon monitor palette
│           │   ├── strings.xml
│           │   ├── themes.xml               # Dark monitor styles
│           │   └── dimens.xml
│           └── drawable/                    # Backgrounds, buttons, dot indicators
├── build.gradle                             # Root Groovy DSL
├── settings.gradle
├── gradle.properties
├── ESP32_PROTOCOL.md                        # BLE protocol spec + ESP32 sketch
└── README.md
```

---

## Requirements

| Item          | Value                             |
|---------------|-----------------------------------|
| Minimum SDK   | API 26 (Android 8.0 Oreo)         |
| Target SDK    | API 34 (Android 14)               |
| Language      | Java only                         |
| Layout        | XML only                          |
| Build system  | Groovy Gradle DSL                 |
| BLE library   | Nordic Android BLE Library 2.7.2  |
| Orientation   | Landscape (locked)                |

---

## Key Features

### BLE Communication
- **Nordic Android BLE Library** — robust connection management, auto-retry, auto-reconnect
- **NUS (Nordic UART Service)** as BLE transport layer
- MTU negotiation (512 bytes) for full JSON command packets
- Automatic reconnect on link loss
- Debounced parameter sends (250ms) to avoid flooding the ESP32

### Realtime Waveform
- **Custom `WaveformSurfaceView`** — dedicated render thread at 60 fps
- Circular lock-free sample buffer (4096 capacity)
- 50 Hz input, 4-second visible window (200 samples)
- Pure black background + neon green/cyan waveform (`#00FFAA`)
- Optional grid overlay (major/minor lines)
- Adaptive amplitude scaling with exponential smoothing
- Scan-line cursor at trailing edge

### UI Design
- Fullscreen immersive landscape (hides system bars)
- Dark hospital monitor aesthetic (pure black + neon green palette)
- Left metric cards: HR / SpO2 / RR / PI / Noise / Mode
- Large touch-friendly sliders with − / + step buttons (Redmi Note 7 optimised)
- 6 condition mode selector buttons with per-mode colour coding
- BLE status dot indicator (red/amber/green)

### Simulator Parameters

| Parameter      | Range         | Step   | Default |
|----------------|---------------|--------|---------|
| Heart Rate     | 20–300 bpm    | 1 bpm  | 75      |
| SpO2           | 70–100 %      | 1 %    | 98      |
| Resp. Rate     | 4–60 rpm      | 1 rpm  | 16      |
| Perf. Index    | 0.02–20.00 %  | 0.01   | 2.50    |
| Noise Level    | 0.00–1.00     | 0.01   | 0.10    |

### Condition Modes

| Mode            | Colour  |
|-----------------|---------|
| Normal          | Green   |
| Arrhythmia      | Orange  |
| Weak Perfusion  | Blue    |
| Vasoconstriction| Red     |
| Strong Perfusion| Cyan    |
| Vasodilation    | Purple  |

---

## Building

1. Open the `MedicalSimulator/` folder in **Android Studio Hedgehog (2023.1)** or newer.
2. Let Gradle sync complete.
3. Connect your Redmi Note 7 (or any Android 8.0+ device) via USB with debugging enabled.
4. **Run** the `app` configuration.

> First build downloads dependencies (~50 MB). Subsequent builds are cached.

---

## BLE Protocol

See [`ESP32_PROTOCOL.md`](ESP32_PROTOCOL.md) for the complete BLE protocol spec,
JSON packet formats, and a minimal ESP32 Arduino firmware skeleton.

---

## Permissions

| Permission                   | When required        |
|------------------------------|----------------------|
| `BLUETOOTH_SCAN`             | Android 12+ scanning |
| `BLUETOOTH_CONNECT`          | Android 12+ connect  |
| `ACCESS_FINE_LOCATION`       | Android 11 and below |
| `FOREGROUND_SERVICE`         | Background BLE keep-alive |

Permissions are requested at runtime on first launch.

---

## Architecture

```
MainActivity
    │
    ├── EspBleManager (Nordic BLE)
    │       ├── connectToDevice()
    │       ├── sendCommand(json)       ← SimulatorParams.toCommandJson()
    │       ├── LiveData<ConnectionState>
    │       ├── LiveData<Float> ppgSample    → WaveformSurfaceView.addSample()
    │       └── LiveData<Integer> liveHr/Spo2/Rr/Pi
    │
    ├── WaveformSurfaceView
    │       ├── RenderThread @ 60fps
    │       ├── Circular float[] buffer (4096)
    │       └── Canvas drawLines() — zero allocation hot path
    │
    ├── SimulatorParams (model)
    │       └── toCommandJson() → UTF-8 BLE packet
    │
    └── DeviceScanDialog
            ├── BluetoothLeScanner
            ├── ScanFilter (NUS service UUID)
            └── RecyclerView device list
```
