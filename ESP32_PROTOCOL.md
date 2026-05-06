# ESP32 BLE Protocol — Medical PPG Simulator

## Overview

This document specifies the BLE communication protocol between the Android app
and the ESP32-S3 PPG medical simulator firmware.

---

## BLE Service & Characteristics

Uses **Nordic UART Service (NUS)** as transport layer.

| Role      | UUID                                   | Direction     | Description              |
|-----------|----------------------------------------|---------------|--------------------------|
| Service   | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | —             | NUS primary service      |
| TX (Write)| `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` | App → ESP32   | Command characteristic   |
| RX (Notify)| `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`| ESP32 → App  | Waveform data (notify)   |

---

## App → ESP32: Command Packet (JSON, UTF-8)

Sent when any parameter changes (debounced 250 ms).

```json
{
  "cmd":   "setParams",
  "hr":    75,
  "spo2":  98,
  "rr":    16,
  "pi":    2.50,
  "noise": 0.10,
  "mode":  "Normal"
}
```

### Field specification

| Field   | Type    | Range          | Unit     | Description              |
|---------|---------|----------------|----------|--------------------------|
| `cmd`   | string  | `"setParams"`  | —        | Command type             |
| `hr`    | int     | 20–300         | bpm      | Heart rate               |
| `spo2`  | int     | 70–100         | %        | Oxygen saturation        |
| `rr`    | int     | 4–60           | rpm      | Respiratory rate         |
| `pi`    | float   | 0.02–20.00     | %        | Perfusion index          |
| `noise` | float   | 0.00–1.00      | —        | Noise amplitude (0=none) |
| `mode`  | string  | see below      | —        | Simulator condition mode |

### Mode values

| `mode` value        | Description                     |
|---------------------|---------------------------------|
| `"Normal"`          | Healthy baseline PPG            |
| `"Arrhythmia"`      | Irregular heartbeat             |
| `"WeakPerfusion"`   | Low amplitude perfusion         |
| `"Vasoconstriction"`| High vascular resistance        |
| `"StrongPerfusion"` | High amplitude, strong pulse    |
| `"Vasodilation"`    | Low resistance, widened vessels |

---

## ESP32 → App: Waveform Notification Packet (JSON, 50 Hz)

Sent at **50 Hz** (every 20 ms) via BLE CCCD notification.

```json
{
  "ppg":  0.654,
  "hr":   75,
  "spo2": 98,
  "rr":   16,
  "pi":   2.5
}
```

### Field specification

| Field   | Type   | Range    | Description                                     |
|---------|--------|----------|-------------------------------------------------|
| `ppg`   | float  | 0.0–1.0  | Normalised PPG waveform sample (required, 50Hz) |
| `hr`    | int    | 20–300   | Current simulated HR (may be at lower cadence)  |
| `spo2`  | int    | 70–100   | Current simulated SpO2                          |
| `rr`    | int    | 4–60     | Current simulated respiratory rate              |
| `pi`    | float  | 0.02–20  | Current simulated perfusion index               |

> `ppg` must be present in every packet. Other fields are optional and can be
> sent less frequently (e.g. every 1 second) to reduce BLE payload size.

---

## MTU

The app requests **MTU = 512 bytes** on connect.
ESP32 should accept negotiated MTU.
JSON packets are typically < 80 bytes, well within any negotiated MTU.

---

## Connection

- **Advertising interval**: 100–200 ms for fast discovery
- **Connection interval**: 20–40 ms (request from ESP32 side) for low latency
- **Auto-reconnect**: handled by Android Nordic BLE Library
- **Device name** (optional but helpful): `"PPG-SIM"` or `"ESP32-PPG"`

---

## ESP32 Arduino Sketch (minimal skeleton)

```cpp
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <ArduinoJson.h>

#define SERVICE_UUID        "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_RX   "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_TX   "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

BLECharacteristic* pTxCharacteristic;
bool deviceConnected = false;

// Simulator state
int   hr    = 75;
int   spo2  = 98;
int   rr    = 16;
float pi    = 2.5f;
float noise = 0.10f;
String mode = "Normal";

// ── Command callback (app → ESP32) ──
class CommandCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* pChar) override {
        String raw = pChar->getValue().c_str();
        StaticJsonDocument<256> doc;
        if (deserializeJson(doc, raw) == DeserializationError::Ok) {
            if (doc["cmd"] == "setParams") {
                hr    = doc["hr"]    | hr;
                spo2  = doc["spo2"]  | spo2;
                rr    = doc["rr"]    | rr;
                pi    = doc["pi"]    | pi;
                noise = doc["noise"] | noise;
                mode  = doc["mode"].as<String>();
            }
        }
    }
};

// ── Connection callbacks ──
class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer*)    { deviceConnected = true;  }
    void onDisconnect(BLEServer* s){ deviceConnected = false;
                                    s->startAdvertising(); }
};

void setup() {
    BLEDevice::init("PPG-SIM");
    BLEDevice::setMTU(512);
    BLEServer*  server  = BLEDevice::createServer();
    server->setCallbacks(new ServerCallbacks());
    BLEService* service = server->createService(SERVICE_UUID);

    // RX (write from app)
    BLECharacteristic* pRx = service->createCharacteristic(
        CHARACTERISTIC_RX,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
    pRx->setCallbacks(new CommandCallbacks());

    // TX (notify to app)
    pTxCharacteristic = service->createCharacteristic(
        CHARACTERISTIC_TX,
        BLECharacteristic::PROPERTY_NOTIFY);
    pTxCharacteristic->addDescriptor(new BLE2902());

    service->start();
    BLEAdvertising* adv = BLEDevice::getAdvertising();
    adv->addServiceUUID(SERVICE_UUID);
    adv->setScanResponse(true);
    adv->start();
}

// ── PPG waveform generator (simplified) ──
float generatePPG(float t) {
    float ppg = 0.5f + 0.4f * sin(2 * PI * t);           // main pulse
    ppg += 0.08f * sin(4 * PI * t);                        // dicrotic notch
    if (noise > 0) ppg += noise * (random(-100,100)/1000.0f);
    ppg = constrain(ppg, 0.0f, 1.0f);
    return ppg;
}

unsigned long lastSend = 0;
float phase = 0.0f;

void loop() {
    if (!deviceConnected) { delay(100); return; }
    unsigned long now = millis();
    if (now - lastSend >= 20) {  // 50 Hz
        lastSend = now;
        float bps = hr / 60.0f;
        phase = fmod(phase + bps * 0.02f, 1.0f);  // advance phase

        char buf[128];
        snprintf(buf, sizeof(buf),
            "{\"ppg\":%.3f,\"hr\":%d,\"spo2\":%d,\"rr\":%d,\"pi\":%.2f}",
            generatePPG(phase), hr, spo2, rr, pi);

        pTxCharacteristic->setValue((uint8_t*)buf, strlen(buf));
        pTxCharacteristic->notify();
    }
}
```
