// Wi-Fi in mixed mode: the ESP32 broadcasts its own AP (with DHCP from ESP-IDF
// and a captive DNS responder in dns.rs) while simultaneously connecting as a
// station to a home/vehicle AP. The dashboard is reachable on both networks:
//   - AP side:  ws://192.168.4.1:81/ws   (captive DNS: any hostname resolves here)
//   - STA side: ws://<dhcp-assigned>:81/ws   (logged at boot / shown on serial)
//
// STA credentials come from NVS (set once over BLE/WS `cfg` command or serial).

use std::io::{Read, Write};
use std::sync::mpsc::{Receiver, Sender};
use std::sync::{Arc, Mutex};

use esp_idf_svc::eventloop::EspSystemEventLoop;
use esp_idf_svc::http::server::{Configuration as HttpCfg, EspHttpServer};
use esp_idf_svc::nvs::{EspDefaultNvsPartition, EspNvs};
use esp_idf_svc::wifi::{AccessPointConfiguration, AuthMethod, ClientConfiguration, Configuration, EspWifi};
use esp_idf_svc::hal::modem::Modem;
use esp_idf_svc::hal::prelude::Peripheral;

use serde::Deserialize;

use crate::proto::{BridgeCommand, BridgeEvent, BusConfig, FrameEvent};
use crate::storage;

const AP_SSID: &str = "CANScope-Bridge";
const AP_PASS: &str = "canscope";
const AP_IP: [u8; 4] = [192, 168, 4, 1];

#[derive(Deserialize)]
#[serde(tag = "type")]
enum Incoming {
    #[serde(rename = "tx")]
    Tx { id: u32, #[serde(default)] ext: bool, data: Vec<u8> },
    #[serde(rename = "cmd")]
    Cmd { cmd: String },
    #[serde(rename = "cfg")]
    Cfg { bitrate: Option<u32>, listen_only: Option<bool>, filter_id: Option<u32>, filter_mask: Option<u32> },
    /// Join a home AP: {"type":"wifi","ssid":"...","pass":"..."}
    #[serde(rename = "wifi")]
    WifiCred { ssid: String, pass: String },
}

pub fn run(
    modem: impl Peripheral<P = Modem> + 'static,
    sysloop: EspSystemEventLoop,
    nvs: EspDefaultNvsPartition,
    evt: Receiver<BridgeEvent>,
    cmd: Sender<BridgeCommand>,
    cfg: Sender<BusConfig>,
) {
    let mut wifi = EspWifi::new(modem, sysloop, Some(nvs.clone())).unwrap();

    // --- AP side (always on) ---
    // ESP-IDF starts its DHCP server automatically for the AP interface;
    // captive DNS is our own UDP task answering every query with AP_IP.
    let ap = AccessPointConfiguration {
        ssid: AP_SSID.try_into().unwrap(),
        password: AP_PASS.try_into().unwrap(),
        auth_method: AuthMethod::WPA2Personal,
        max_connections: 4,
        ..Default::default()
    };

    // --- STA side (optional, credentials from NVS) ---
    let sta = match storage::load_wifi_credentials(&nvs) {
        Some((ssid, pass)) => {
            log::info!("STA joining '{ssid}' …");
            ClientConfiguration {
                ssid: ssid.as_str().try_into().unwrap_or_default(),
                password: pass.as_str().try_into().unwrap_or_default(),
                auth_method: AuthMethod::None, // let the driver pick from the beacon
                ..Default::default()
            }
        }
        None => ClientConfiguration::default(), // STA up but not associated
    };

    wifi.set_configuration(&Configuration::Mixed(sta, ap)).unwrap();
    wifi.start().unwrap();
    wifi.connect().ok(); // no-op until STA credentials exist
    log::info!("AP up: {AP_SSID} (pw {AP_PASS}) → http://{}.{}.{}.{}", AP_IP[0], AP_IP[1], AP_IP[2], AP_IP[3]);

    // Captive portal DNS: resolve everything to the AP address.
    std::thread::Builder::new().stack_size(4096).spawn(|| crate::dns::run(AP_IP)).unwrap();

    // --- WebSocket bridge ---
    let sender_slot: Arc<Mutex<Option<esp_idf_svc::http::server::ws::EspHttpWsDetachedSender>>> =
        Arc::new(Mutex::new(None));
    let sender_slot_h = sender_slot.clone();
    let cmd_h = cmd.clone();
    let cfg_h = cfg.clone();
    let nvs_h = nvs.clone();
    let wifi_handle = Arc::new(Mutex::new(wifi));
    let wifi_h = wifi_handle.clone();

    let mut server = EspHttpServer::new(&HttpCfg { http_port: 81, ..Default::default() }).unwrap();
    server.ws_handler("/ws", move |ws| {
        if ws.is_new() {
            let det = ws.create_detached_sender().unwrap();
            *sender_slot_h.lock().unwrap() = Some(det);
            log::info!("WS client connected");
        }
        if ws.is_closed() {
            *sender_slot_h.lock().unwrap() = None;
            return Ok::<(), esp_idf_sys::EspError>(());
        }
        let (frame_type, payload) = ws.recv(&mut [])?;
        if frame_type != esp_idf_svc::http::server::ws::FrameType::Text { return Ok(()); }
        if let Ok(text) = std::str::from_utf8(payload) {
            if let Ok(msg) = serde_json::from_str::<Incoming>(text) {
                match msg {
                    Incoming::Tx { id, ext, data } => {
                        let mut d = [0u8; 8];
                        let n = data.len().min(8);
                        d[..n].copy_from_slice(&data[..n]);
                        let _ = cmd_h.send(BridgeCommand::Tx(FrameEvent { id, ext, dlc: n as u8, data: d }));
                    }
                    Incoming::Cmd { cmd } => match cmd.as_str() {
                        "dtc-read" => { let _ = cmd_h.send(BridgeCommand::DtcRead); }
                        "dtc-clear" => { let _ = cmd_h.send(BridgeCommand::DtcClear); }
                        _ => {}
                    },
                    Incoming::Cfg { bitrate, listen_only, filter_id, filter_mask } => {
                        let new_cfg = BusConfig {
                            bitrate: bitrate.unwrap_or(500_000),
                            listen_only: listen_only.unwrap_or(false),
                            filter_id,
                            filter_mask,
                        };
                        let _ = cfg_h.send(new_cfg);
                        storage::save_config(&nvs_h, &new_cfg);
                    }
                    Incoming::WifiCred { ssid, pass } => {
                        // persist and re-associate without reboot
                        storage::save_wifi_credentials(&nvs_h, &ssid, &pass);
                        let mut w = wifi_h.lock().unwrap();
                        let sta = ClientConfiguration {
                            ssid: ssid.as_str().try_into().unwrap_or_default(),
                            password: pass.as_str().try_into().unwrap_or_default(),
                            ..Default::default()
                        };
                        let cur_ap = AccessPointConfiguration {
                            ssid: AP_SSID.try_into().unwrap(),
                            password: AP_PASS.try_into().unwrap(),
                            auth_method: AuthMethod::WPA2Personal,
                            max_connections: 4,
                            ..Default::default()
                        };
                        w.set_configuration(&Configuration::Mixed(sta, cur_ap)).ok();
                        w.connect().ok();
                    }
                }
            }
        }
        Ok(())
    }).unwrap();

    // Minimal HTTP landing page on :80 so captive-portal probes get a real answer.
    server.fn_handler("/", esp_idf_svc::http::Method::Get, |req| {
        req.into_ok_response()?.write_all(
            b"<html><head><meta http-equiv='refresh' content='0; url=http://192.168.4.1:81/'></head>\
              <body>CANScope bridge — open the dashboard on port 81.</body></html>",
        )?;
        Ok(())
    }).unwrap();

    // OTA firmware update: POST the raw .bin image, device reboots into it.
    crate::ota::register(&mut server);

    // Download the on-flash rotating session logs (written by storage::SessionLogger).
    // GET /logs?n=3 -> /spiffs/session_3.csv  (0..7; missing file -> 404)
    server.fn_handler("/logs", esp_idf_svc::http::Method::Get, |req| {
        let n = req.uri()
            .split("n=")
            .nth(1)
            .and_then(|s| s.chars().next())
            .and_then(|c| c.to_digit(10))
            .filter(|&d| d < 8)
            .unwrap_or(0);
        let path = format!("/spiffs/session_{n}.csv");
        match std::fs::File::open(&path) {
            Ok(mut f) => {
                let mut resp = req.into_response(200, Some("OK"), &[
                    ("Content-Type", "text/csv"),
                    ("Content-Disposition", "attachment; filename=\"canscope-session.csv\""),
                ])?;
                let mut buf = [0u8; 1024];
                loop {
                    match f.read(&mut buf) {
                        Ok(0) => break,
                        Ok(r) => resp.write_all(&buf[..r])?,
                        Err(_) => break,
                    }
                }
                Ok(())
            }
            Err(_) => {
                req.into_response(404, Some("Not Found"), &[])?;
                Ok(())
            }
        }
    }).unwrap();

    for event in evt {
        let json = match &event {
            BridgeEvent::Frame(f) => serde_json::json!({
                "type": "frame", "id": f.id, "ext": f.ext,
                "dlc": f.dlc, "data": f.data[..f.dlc as usize],
            }),
            BridgeEvent::Dtcs(codes) => serde_json::json!({ "type": "dtc", "codes": codes }),
            BridgeEvent::BusStatus(s) => serde_json::json!({
                "type": "bus", "state": s.state, "txErrors": s.tx_errors, "rxErrors": s.rx_errors,
            }),
        };
        if let Some(s) = sender_slot.lock().unwrap().as_mut() {
            let _ = s.send(esp_idf_svc::http::server::ws::FrameType::Text, json.to_string().as_bytes());
        }
    }
}
