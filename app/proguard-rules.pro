# Nordic BLE Library — keep all public API
-keep class no.nordicsemi.android.ble.** { *; }
-keep interface no.nordicsemi.android.ble.** { *; }
-keep class no.nordicsemi.android.ble.data.** { *; }
-keep class no.nordicsemi.android.ble.callback.** { *; }

# Keep BleManager subclass
-keep class com.medical.simulator.ble.EspBleManager { *; }

# Keep data model (used with JSON)
-keep class com.medical.simulator.model.** { *; }

# Standard Android rules
-dontwarn androidx.**
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
