// BLE peripheral: advertises as "CANScope", NUS-style GATT service.
//   TX char (notify): 13-byte binary frames / 0xFE DTC packets   (ESP32 -> dashboard)
//   RX char (write):  frames or 0xFF-prefixed commands           (dashboard -> ESP32)

use std::sync::mpsc::{Receiver, Sender};
use std::sync::{Arc, Mutex};

use esp32_nimble::{enums::*, uuid128, BLEDevice, NimbleProperties};

use crate::proto::{pack_dtcs, pack_frame, pack_status, unpack_frame, BridgeCommand, BridgeEvent, BusConfig};

const SERVICE: uuid::Uuid = uuid128!("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
const CHAR_TX: uuid::Uuid = uuid128!("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
const CHAR_RX: uuid::Uuid = uuid128!("6e400002-b5a3-f393-e0a9-e50e24dcca9e");

// command opcodes (first byte 0xFF)
const CMD: u8 = 0xFF;
const CMD_DTC_READ: u8 = 0x01;
const CMD_DTC_CLEAR: u8 = 0x02;
const CMD_PID: u8 = 0x03;
const CMD_BITRATE: u8 = 0x10;
const CMD_LISTEN: u8 = 0x11;

mod uuid {
    pub use esp32_nimble::utilities::BleUuid as Uuid;
}

pub fn run(evt: Receiver<BridgeEvent>, cmd: Sender<BridgeCommand>, cfg: Sender<BusConfig>) {
    let device = BLEDevice::take();
    device.set_name("CANScope").unwrap();
    let server = device.get_server();
    let service = server.create_service(SERVICE);

    let tx_char = Arc::new(Mutex::new(
        service.lock().create_characteristic(CHAR_TX, NimbleProperties::NOTIFY),
    ));

    let cmd2 = cmd.clone();
    let cfg2 = cfg.clone();
    service
        .lock()
        .create_characteristic(CHAR_RX, NimbleProperties::WRITE | NimbleProperties::WRITE_NR)
        .lock()
        .on_write(move |args| {
            let b = args.recv_data();
            if b.is_empty() { return; }
            if b[0] == CMD {
                match b.get(1).copied() {
                    Some(CMD_DTC_READ) => { let _ = cmd2.send(BridgeCommand::DtcRead); }
                    Some(CMD_DTC_CLEAR) => { let _ = cmd2.send(BridgeCommand::DtcClear); }
                    Some(CMD_PID) => {
                        if let Some(pid) = b.get(2) { let _ = cmd2.send(BridgeCommand::RequestPid(*pid)); }
                    }
                    Some(CMD_BITRATE) => {
                        let bitrate = match b.get(2).copied() {
                            Some(0) => 125_000, Some(1) => 250_000, Some(3) => 1_000_000, _ => 500_000,
                        };
                        let _ = cfg2.send(BusConfig { bitrate, listen_only: false, filter_id: None, filter_mask: None });
                    }
                    Some(CMD_LISTEN) => {
                        let _ = cfg2.send(BusConfig { bitrate: 500_000, listen_only: b.get(2).copied() == Some(1), filter_id: None, filter_mask: None });
                    }
                    _ => {}
                }
            } else if let Some(f) = unpack_frame(b) {
                let _ = cmd2.send(BridgeCommand::Tx(f));
            }
        });

    let mut adv = device.get_advertising().lock();
    adv.name("CANScope").unwrap()
       .add_service_uuid(SERVICE).unwrap();
    adv.start().unwrap();
    drop(adv);

    log::info!("BLE advertising as CANScope");

    // pump bus events into notifications
    loop {
        match evt.recv_timeout(std::time::Duration::from_millis(10)) {
            Ok(event) => {
                let packet = match &event {
                    BridgeEvent::Frame(f) => pack_frame(f).to_vec(),
                    BridgeEvent::Dtcs(codes) => pack_dtcs(codes),
                    BridgeEvent::BusStatus(s) => pack_status(s).to_vec(),
                };
                let ch = tx_char.lock().unwrap();
                ch.set_value(&packet);
                ch.notify();
            }
            Err(_) => std::thread::sleep(std::time::Duration::from_millis(2)),
        }
    }
}
