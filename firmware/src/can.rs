// TWAI (CAN) driver task: receive -> BridgeEvent, BridgeCommand/BusConfig -> bus.

use std::sync::mpsc::{Receiver, Sender};
use std::time::Duration;

use esp_idf_hal::can::{self, CanDriver};
use esp_idf_hal::gpio::{InputPin, OutputPin};
use esp_idf_hal::peripheral::Peripheral;

use crate::proto::{BridgeCommand, BridgeEvent, BusConfig, BusStatus, FrameEvent};

const OBD_REQUEST: u32 = 0x7DF;

pub fn init<'d>(
    can_periph: impl Peripheral<P = esp_idf_hal::can::CAN> + 'd,
    tx: impl Peripheral<P = impl OutputPin> + 'd,
    rx: impl Peripheral<P = impl InputPin> + 'd,
    cfg: &BusConfig,
) -> CanDriver<'d> {
    let timing = match cfg.bitrate {
        125_000 => can::config::Timing::B125K,
        250_000 => can::config::Timing::B250K,
        1_000_000 => can::config::Timing::B1M,
        _ => can::config::Timing::B500K,
    };
    let filter = match (cfg.filter_id, cfg.filter_mask) {
        (Some(id), Some(mask)) => can::config::Filter::Standard { filter: id, mask },
        _ => can::config::Filter::standard_allow_all(),
    };
    let mode = if cfg.listen_only { can::config::Mode::ListenOnly } else { can::config::Mode::Normal };
    let mut driver = CanDriver::new(
        can_periph, tx, rx,
        &can::config::Config::new().timing(timing).filter(filter).mode(mode),
    ).expect("TWAI driver init");
    driver.start().expect("TWAI start");
    driver
}

pub fn run(
    driver: &mut CanDriver,
    evt: Sender<BridgeEvent>,
    cmd: Receiver<BridgeCommand>,
    cfg_rx: Receiver<BusConfig>,
) {
    let mut last_status: Option<BusStatus> = None;
    loop {
        // 1. inbound frames
        if let Ok(frame) = driver.receive(Duration::from_millis(5)) {
            let mut data = [0u8; 8];
            data[..frame.data().len()].copy_from_slice(frame.data());
            let _ = evt.send(BridgeEvent::Frame(FrameEvent {
                id: frame.identifier(),
                ext: frame.is_extended(),
                dlc: frame.dlc(),
                data,
            }));
        }

        // 1b. bus health watchdog: poll TWAI state, recover from bus-off,
        //     and report transitions / error-counter changes to the UI.
        let st = twai_status();
        if let Some(st) = st {
            if st.state == "bus-off" {
                recover(&evt);
            }
            if last_status.as_ref() != Some(&st) {
                let _ = evt.send(BridgeEvent::BusStatus(st.clone()));
                last_status = Some(st);
            }
        }

        // 2. reconfiguration
        if let Ok(new_cfg) = cfg_rx.try_recv() {
            driver.stop().ok();
            let timing = match new_cfg.bitrate {
                125_000 => can::config::Timing::B125K,
                250_000 => can::config::Timing::B250K,
                1_000_000 => can::config::Timing::B1M,
                _ => can::config::Timing::B500K,
            };
            driver.set_timing(timing).ok();
            driver.start().ok();
        }

        // 3. outbound commands
        while let Ok(c) = cmd.try_recv() {
            match c {
                BridgeCommand::Tx(f) => {
                    let _ = tx(driver, f.id, f.ext, &f.data[..f.dlc as usize]);
                }
                BridgeCommand::RequestPid(pid) => {
                    let _ = tx(driver, OBD_REQUEST, false, &[0x02, 0x01, pid, 0, 0, 0, 0, 0]);
                }
                BridgeCommand::DtcRead => {
                    let _ = tx(driver, OBD_REQUEST, false, &[0x01, 0x03, 0, 0, 0, 0, 0, 0]);
                }
                BridgeCommand::DtcClear => {
                    let _ = tx(driver, OBD_REQUEST, false, &[0x01, 0x04, 0, 0, 0, 0, 0, 0]);
                }
            }
        }
    }
}

fn tx(driver: &mut CanDriver, id: u32, ext: bool, data: &[u8]) -> anyhow::Result<()> {
    let frame = can::Frame::new(id, ext, data).ok_or(anyhow::anyhow!("bad frame"))?;
    driver.transmit(&frame, Duration::from_millis(50))?;
    Ok(())
}

/// Raw TWAI controller state. Goes through esp-idf-sys FFI (rather than the
/// esp-idf-hal wrapper) because recovery requires stepping the controller
/// through bus-off -> recovering -> stopped -> started, which the safe
/// wrapper does not model.
fn twai_status() -> Option<BusStatus> {
    use esp_idf_sys::*;
    let mut info: twai_status_info_t = unsafe { core::mem::zeroed() };
    let rc = unsafe { twai_get_status_info(&mut info) };
    if rc != 0 {
        return None;
    }
    let state = match info.state {
        s if s == twai_state_t_TWAI_STATE_RUNNING => "running",
        s if s == twai_state_t_TWAI_STATE_BUS_OFF => "bus-off",
        s if s == twai_state_t_TWAI_STATE_STOPPED => "stopped",
        _ => "recovering",
    };
    Some(BusStatus { state, tx_errors: info.tx_error_counter, rx_errors: info.rx_error_counter })
}

/// Bus-off recovery per ESP-IDF TWAI docs: initiate recovery, wait for the
/// 128x11 consecutive recessive bits that move the controller to STOPPED,
/// then restart it. Reports each phase so the dashboard LED tracks it.
fn recover(evt: &Sender<BridgeEvent>) {
    use esp_idf_sys::*;
    log::warn!("TWAI bus-off — initiating recovery");
    let _ = evt.send(BridgeEvent::BusStatus(BusStatus { state: "bus-off", tx_errors: 128, rx_errors: 0 }));
    unsafe {
        twai_initiate_recovery();
    }
    let _ = evt.send(BridgeEvent::BusStatus(BusStatus { state: "recovering", tx_errors: 128, rx_errors: 0 }));
    for _ in 0..200 {
        // ~10s worst case: 128 * 11 bits at 125 kbit/s ≈ 11.3 ms; poll generously
        match twai_status() {
            Some(s) if s.state == "stopped" => break,
            Some(s) if s.state == "running" => return, // recovered by driver already
            _ => std::thread::sleep(Duration::from_millis(50)),
        }
    }
    let rc = unsafe { twai_start() };
    if rc == 0 {
        log::info!("TWAI recovered, bus running again");
    } else {
        log::error!("TWAI restart failed: esp_err {rc}");
    }
}
