// CANScope ESP32 bridge firmware (Rust / ESP-IDF).
//
// Bridges the vehicle CAN bus (via SN65HVD230 on GPIO5 TX / GPIO4 RX) to:
//   - Bluetooth LE GATT  (13-byte binary packets — matches the dashboard's BLE protocol)
//   - Wi-Fi AP + WebSocket (JSON lines — matches the dashboard's WS protocol)
//   - SPIFFS flash       (ring-buffered CAN session logs, settings in NVS)
//
// Build:  cargo install espup ldproxy espflash && espup install
//         cargo build --release && cargo run --release   (flashes + monitors)

mod ble;
mod can;
mod dns;
mod ota;
mod proto;
mod storage;
mod wifi;

use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use esp_idf_svc::eventloop::EspSystemEventLoop;
use esp_idf_svc::hal::prelude::Peripherals;
use esp_idf_svc::nvs::EspDefaultNvsPartition;
use esp_idf_sys as _;

use proto::{BridgeEvent, BusConfig};

fn main() {
    esp_idf_svc::sys::link_patches();
    esp_idf_svc::log::EspLogger::initialize_default();

    let peripherals = Peripherals::take().unwrap();
    let sysloop = EspSystemEventLoop::take().unwrap();
    let nvs = EspDefaultNvsPartition::take().unwrap();

    // Persisted bus settings (bitrate / listen-only survive reboots)
    let cfg = storage::load_config(&nvs).unwrap_or_default();
    log::info!("CANScope bridge boot: bitrate={} listen_only={}", cfg.bitrate, cfg.listen_only);
    storage::mount_spiffs();

    // Events flow CAN->ble/ws/logger (frames) and ble/ws->CAN (tx requests, commands).
    // CAN task emits into one channel; a fan-out loop multicasts to each consumer.
    let (evt_tx, evt_rx) = mpsc::channel::<BridgeEvent>();
    let (ble_evt_tx, ble_evt_rx) = mpsc::channel::<BridgeEvent>();
    let (ws_evt_tx, ws_evt_rx) = mpsc::channel::<BridgeEvent>();
    let (cmd_tx, cmd_rx) = mpsc::channel::<proto::BridgeCommand>();
    let (cfg_tx, cfg_rx) = mpsc::channel::<BusConfig>();

    // CAN bus task (TWAI driver)
    let mut can_drv = can::init(peripherals.can, peripherals.pins.gpio5, peripherals.pins.gpio4, &cfg);
    {
        let evt_tx = evt_tx.clone();
        let cmd_rx = cmd_rx;
        let cfg_rx = cfg_rx;
        thread::Builder::new().stack_size(6144).spawn(move || {
            can::run(&mut can_drv, evt_tx, cmd_rx, cfg_rx);
        }).unwrap();
    }

    // Bluetooth LE peripheral ("CANScope" + NUS-style service)
    {
        let cmd_tx = cmd_tx.clone();
        let cfg_tx = cfg_tx.clone();
        thread::Builder::new().stack_size(6144).spawn(move || {
            ble::run(ble_evt_rx, cmd_tx, cfg_tx);
        }).unwrap();
    }

    // Wi-Fi AP ("CANScope-Bridge", pw "canscope") + WS server on ws://192.168.4.1:81
    {
        let modem = peripherals.modem;
        let cmd_tx = cmd_tx.clone();
        let cfg_tx = cfg_tx.clone();
        thread::Builder::new().stack_size(8192).spawn(move || {
            wifi::run(modem, sysloop, nvs.clone(), ws_evt_rx, cmd_tx, cfg_tx);
        }).unwrap();
    }

    // Fan-out + session logger: every event is mirrored to BLE, WS, and /spiffs/session_N.csv
    let mut logger = storage::SessionLogger::new();
    for evt in evt_rx {
        let _ = ble_evt_tx.send(evt.clone());
        let _ = ws_evt_tx.send(evt.clone());
        logger.record(&evt);
    }

    loop {
        thread::sleep(Duration::from_secs(60));
    }
}
