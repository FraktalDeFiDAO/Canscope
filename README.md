# CANScope

Vehicle CAN bus diagnostics built around an **ESP32 DevKit V1 + SN65HVD230** transceiver, with three components:

| Folder | Component | Stack |
|---|---|---|
| `firmware/` | ESP32 CAN↔Wi-Fi/BLE bridge | Rust (esp-idf-svc), TWAI, dual-role Wi-Fi (AP+STA), BLE GATT, OTA |
| `web/` | Browser dashboard | Vue 3 + TypeScript + Vite + Tailwind 4 + Pinia |
| `android/` | Native companion app | Kotlin + Jetpack Compose, BLE + WebSocket links |

## Features

- **Live CAN sniffer** — raw frame stream, filtering, DBC file loading and signal decoding (Intel + Motorola)
- **OBD-II** — Mode 01 live PIDs with gauges, Mode 02 freeze frame, Mode 03/07/0A DTCs (confirmed / pending / permanent), Mode 04 clear, Mode 06 on-board monitoring, Mode 09 vehicle info (VIN, calibration ID, ECU name)
- **Automatic VIN detection** — passive sniffing of VINs broadcast on the bus with ISO 3780 check-digit validation, plus active Mode 09 querying
- **ISO-TP (ISO 15765-2)** — multi-frame reassembly with automatic flow control, and multi-frame transmit segmentation
- **Transmit** — raw frames or ISO-TP payloads up to 4095 bytes, periodic transmit
- **Sessions** — record live traffic, replay locally, upload to the bridge, browse/download/delete recordings
- **Connection resilience** — auto-reconnect with backoff, TWAI bus-off detection and automatic recovery, acceptance filters
- **OTA firmware updates** and CSV log download over HTTP

## Hardware

- ESP32 DevKit V1
- SN65HVD230 (or compatible 3.3 V CAN transceiver): CTX → GPIO 5, CRX → GPIO 4
- OBD-II: CANH → pin 6, CANL → pin 14, GND → pins 4/5 (pin 16 is 12 V — keep it away from the ESP32!)

## Quick start

**Firmware** (`firmware/` — see its README for full details):

```sh
cargo espflash flash --release
```

The bridge starts a Wi-Fi AP (`CANScope-Bridge`, password `canscope`) with DHCP + captive DNS, and can simultaneously join your home network as a station.

**Web dashboard** (`web/`):

```sh
npm install
npm run dev      # or: npm run build
npm test         # vitest unit tests
```

In simulator mode it runs with no hardware at all. Against real hardware, open `http://192.168.4.1` (or connect to `ws://192.168.4.1:81/ws`).

**Android app** (`android/`):

```sh
gradle assembleDebug        # APK in app/build/outputs/apk/debug/
gradle testDebugUnitTest    # unit tests
```

## Protocols

- **WebSocket** (`:81/ws`) — JSON: `frame`, `obd`, `dtc`, `bus` (bridge → client); `tx`, `cmd`, `cfg`, `wifi` (client → bridge)
- **BLE GATT** — service `6e400001-…`, TX notify `6e400003`, RX write `6e400002`; 13-byte binary frames, status/notify opcodes `0xFD`/`0xFE`/`0xFF`

## License

MIT
