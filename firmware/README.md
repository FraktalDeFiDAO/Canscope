# CANScope ESP32 Bridge Firmware (Rust)

Bridges a vehicle CAN bus (SN65HVD230 on **GPIO5=CTX / GPIO4=CRX**, 3V3) to the CANScope dashboard over **Bluetooth LE** and **Wi-Fi/WebSocket**, with session logging to onboard SPIFFS flash.

## Interfaces

| Link | Details |
|---|---|
| BLE | Advertises as `CANScope`, GATT service `6e400001-…` (NUS-style), 13-byte binary frames |
| Wi-Fi AP | `CANScope-Bridge` (pw `canscope`), DHCP from ESP-IDF, **captive DNS** (every hostname → `192.168.4.1`), WS at `ws://192.168.4.1:81/ws` |
| Wi-Fi STA | Simultaneous station on your home/shop AP (mixed AP+STA mode). Credentials persist in NVS; set them without a reboot via WS `{"type":"wifi","ssid":"…","pass":"…"}` |
| Flash | Config in NVS; rotating CAN logs `/spiffs/session_0..7.csv` (64 KiB each) |

Mixed AP+STA means the dash works both ways at once: a phone in the car joins the
ESP32's own AP (captive portal pops open automatically), while the same bridge is
also reachable on your home network once STA credentials are set.

## Build & flash

```bash
cargo install espup ldproxy espflash
espup install                 # xtensa toolchain + ESP-IDF v5.2
. ~/export-esp.sh             # or: source the env espup prints
cargo run --release           # builds, flashes, monitors
```

Requires a 4 MB flash partition layout — `partitions.csv` is provided; reference it in
`sdkconfig.defaults` via `CONFIG_PARTITION_TABLE_CUSTOM=y` /
`CONFIG_PARTITION_TABLE_CUSTOM_FILENAME="partitions.csv"` if your template doesn't pick it up.

## Protocol

See the Connection page in the dashboard, or `src/proto.rs`:

- **BLE/WS frame**: 13-byte `[u32le id|ext<<31, u8 dlc, 8×data]` (BLE) or `{"type":"frame",...}` JSON (WS)
- **Commands** (browser → ESP32): `FF 01/02/03/10/11` (BLE) or `{"type":"cmd"/"tx"/"cfg"}` (WS)
- **Bus health** (ESP32 → browser): BLE packet `[0xFD, state, tx_err, rx_err]` / WS `{"type":"bus","state":"running|bus-off|recovering|stopped",...}`

## Bus-off recovery

A watchdog in `src/can.rs` polls the TWAI controller status every loop. On bus-off
(>128 TX errors — typically a shorted bus or wrong bitrate) it initiates the
ESP-IDF recovery sequence (`twai_initiate_recovery` → wait for stopped →
`twai_start`) and reports every phase over BLE/WS so the dashboard LED tracks it.

## OTA firmware update

Dual-slot OTA partition layout (`ota_0`/`ota_1` in `partitions.csv`):

```bash
curl -X POST --data-binary @target/xtensa-esp32-espidf/release/canscope-bridge.bin \
     http://192.168.4.1:81/ota        # or the STA address
```

The device validates the image, marks the slot bootable, and reboots into it.
A failed image is rejected at validation; the running firmware is untouched.

## Session logs

The last 8 sessions (64 KiB each, rotated) are downloadable over HTTP:

```bash
curl -O http://192.168.4.1:81/logs?n=3    # /spiffs/session_3.csv
```

Format: `millis,can_id_hex,dlc,b0,b1,...` per line.

## Notes

- Tested API targets: esp-idf-svc 0.51 / esp32-nimble 0.11. API drift in newer ESP-IDF is possible.
- BLE and Wi-Fi run concurrently (coexistence enabled in `sdkconfig.defaults`); expect reduced BLE notify throughput while Wi-Fi AP is active.
- Start in listen-only mode against a live vehicle.
