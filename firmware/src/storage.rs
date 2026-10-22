// Persistent storage: bus config in NVS, CAN session logs on SPIFFS flash.

use std::fs::{File, OpenOptions};
use std::io::{Read, Write};

use esp_idf_svc::nvs::{EspDefaultNvsPartition, EspNvs};
use esp_idf_sys as sys;

use crate::proto::{BridgeEvent, BusConfig};

const NS: &str = "canscope";

pub fn load_config(partition: &EspDefaultNvsPartition) -> Option<BusConfig> {
    let nvs = EspNvs::new(partition.clone(), NS, true).ok()?;
    let bitrate = nvs.get_u32("bitrate").ok()??;
    let listen_only = nvs.get_u8("listen_only").ok()? == Some(1);
    let filter_id = nvs.get_u32("filter_id").ok()?;
    let filter_mask = nvs.get_u32("filter_mask").ok()?;
    Some(BusConfig { bitrate, listen_only, filter_id, filter_mask })
}

pub fn save_config(partition: &EspDefaultNvsPartition, cfg: &BusConfig) {
    if let Ok(mut nvs) = EspNvs::new(partition.clone(), NS, true) {
        let _ = nvs.set_u32("bitrate", cfg.bitrate);
        let _ = nvs.set_u8("listen_only", cfg.listen_only as u8);
        match cfg.filter_id { Some(v) => { let _ = nvs.set_u32("filter_id", v); } None => { let _ = nvs.remove("filter_id"); } }
        match cfg.filter_mask { Some(v) => { let _ = nvs.set_u32("filter_mask", v); } None => { let _ = nvs.remove("filter_mask"); } }
    }
}

// Home-AP credentials for the STA interface (mixed AP+STA mode).
pub fn load_wifi_credentials(partition: &EspDefaultNvsPartition) -> Option<(String, String)> {
    let nvs = EspNvs::new(partition.clone(), NS, true).ok()?;
    let mut ssid_buf = [0u8; 33];
    let mut pass_buf = [0u8; 65];
    let ssid = nvs.get_str("wifi_ssid", &mut ssid_buf).ok()??;
    let pass = nvs.get_str("wifi_pass", &mut pass_buf).ok()??;
    if ssid.is_empty() { return None; }
    Some((ssid.trim_end_matches('\0').to_string(), pass.trim_end_matches('\0').to_string()))
}

pub fn save_wifi_credentials(partition: &EspDefaultNvsPartition, ssid: &str, pass: &str) {
    if let Ok(mut nvs) = EspNvs::new(partition.clone(), NS, true) {
        let _ = nvs.set_str("wifi_ssid", ssid);
        let _ = nvs.set_str("wifi_pass", pass);
    }
}

pub fn mount_spiffs() {
    let conf = sys::esp_vfs_spiffs_conf_t {
        base_path: b"/spiffs\0".as_ptr() as *const _,
        partition_label: std::ptr::null(),
        max_files: 4,
        format_if_mount_failed: true,
    };
    let rc = unsafe { sys::esp_vfs_spiffs_register(&conf) };
    if rc != sys::ESP_OK {
        log::warn!("SPIFFS mount failed ({rc}) — session logging disabled");
    }
}

/// Appends frames as CSV lines: ts_ms,id,dlc,b0,b1,...
/// Rotates through session_0..session_7.csv, 64 KiB each, so flash wear stays bounded.
pub struct SessionLogger {
    file: Option<File>,
    index: u8,
    written: usize,
}

const MAX_SESSION_BYTES: usize = 64 * 1024;

impl SessionLogger {
    pub fn new() -> Self {
        let index = Self::last_index() % 8;
        let file = OpenOptions::new()
            .create(true).append(true)
            .open(format!("/spiffs/session_{index}.csv")).ok();
        Self { file, index, written: 0 }
    }

    fn last_index() -> u8 {
        File::open("/spiffs/session.idx")
            .and_then(|mut f| { let mut b = [0u8; 1]; f.read_exact(&mut b).map(|_| b[0]) })
            .unwrap_or(0)
    }

    fn rotate(&mut self) {
        self.index = (self.index + 1) % 8;
        self.written = 0;
        if let Ok(mut f) = File::create("/spiffs/session.idx") { let _ = f.write_all(&[self.index]); }
        self.file = File::create(format!("/spiffs/session_{}.csv", self.index)).ok();
    }

    pub fn record(&mut self, evt: &BridgeEvent) {
        let BridgeEvent::Frame(f) = evt else { return };
        if self.written > MAX_SESSION_BYTES { self.rotate(); }
        if let Some(file) = &mut self.file {
            let ts = unsafe { sys::xTaskGetTickCount() } as u64 * 1000 / sys::configTICK_RATE_HZ as u64;
            let mut line = format!("{ts},{:X},{}", f.id, f.dlc);
            for b in &f.data[..f.dlc as usize] { line.push_str(&format!(",{b:02X}")); }
            line.push('\n');
            if let Ok(n) = file.write(line.as_bytes()) { self.written += n; }
        }
    }
}
