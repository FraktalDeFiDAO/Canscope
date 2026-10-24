# CANScope Android (native)

Native Android app — **Kotlin + Jetpack Compose** — replicating the CANScope web dashboard 1:1:
same screens (Gauges, PIDs, Sniffer, TX, DTC, Link), same dark mission-control palette, same
ESP32 protocols (13-byte BLE packets and WS JSON lines), same OBD-II PID database and DTC decoder.

## Stack

- Kotlin, Jetpack Compose (BOM 2024.10), Material 3, Navigation Compose
- Links: Android `BluetoothLeScanner`/GATT (`BleLink`), OkHttp WebSocket (`WsLink`), built-in virtual ECU (`SimLink`)
- `LinkViewModel` mirrors the web Pinia stores: frame ring buffer, per-ID stats, PID values, DTCs

## Build

Prereqs: Android Studio (Hedgehog+), JDK 17, Android SDK 34.

```bash
# open this folder in Android Studio, or:
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
```

Gradle wrapper: generate once with `gradle wrapper --gradle-version 8.9` if you don't use Android Studio.

## Permissions

BLE scan/connect (Android 12+ runtime), legacy BT + location for API ≤ 30, INTERNET for WebSocket.
`usesCleartextTraffic=true` is required for `ws://192.168.4.1:81` on the ESP32's AP.

## Feature parity notes

- BLE protocol is byte-identical to the web app — same GATT UUIDs, same 13-byte frames, same 0xFF command set.
- The simulator reproduces the web simulator's traffic and PID set.
- Full web parity: ISO-TP reassembly **and** segmentation (flow control `0x30` answers first frames), Mode 01/02/06/07/09/0A, PID support-bitmask discovery, DBC decoding in the sniffer, passive VIN sniffing (ISO 3780 check digit), session record/playback/server upload, auto-reconnect with backoff, TWAI bus-health status.
- Unit tests: `./gradlew testDebugUnitTest` covers ISO-TP, VIN validation/sniffing, and DBC parsing (`app/src/test/`).
- Untested here (no Android SDK in this environment): expect a normal first-build fix round.

## Torque-style extras

- **Gauges tab**: round dial gauges (bezel, numbered scale, warn/crit zones, needle) plus a theme
  picker — MISSION (cyan), TORQUE (amber), NIGHT (red), PHOSPHOR (green); persisted.
- **Graphs tab**: plot up to 4 PIDs over the last 48 s with min/max/current legend.
- **HUD tab**: fullscreen windshield display — giant speed, RPM bar, clock; MIRROR flip and
  keep-screen-on.
- **Trip tab**: distance, fuel used (fuel-rate PID or MAF estimate), instant/avg economy
  (L/100 km or MPG), avg/max speed, drive time; resettable.
- **Perf tab**: 0–60 mph, 0–100/0–200 km/h, 60–120, ⅛/¼ mile and 100–0 braking timers with
  arm/cancel and persisted best times.
