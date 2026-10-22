// Shared protocol types between the bridge tasks.
// BLE side uses the compact binary form (proto::pack_frame), Wi-Fi/WS side uses JSON.

use serde::Serialize;

#[derive(Clone, Copy, Debug)]
pub struct BusConfig {
    pub bitrate: u32,       // 125_000 / 250_000 / 500_000 / 1_000_000
    pub listen_only: bool,
    /// Optional TWAI acceptance filter (11-bit standard IDs).
    /// mask bit 1 = incoming ID bit must match filter bit; 0 = don't care.
    /// None = accept everything (the default for sniffing).
    /// Applied at boot; changes take effect after the next restart.
    pub filter_id: Option<u32>,
    pub filter_mask: Option<u32>,
}

impl Default for BusConfig {
    fn default() -> Self {
        Self { bitrate: 500_000, listen_only: false, filter_id: None, filter_mask: None }
    }
}

#[derive(Clone, Debug, Serialize)]
pub struct FrameEvent {
    pub id: u32,
    pub ext: bool,
    pub dlc: u8,
    pub data: [u8; 8],
}

/// Health of the TWAI controller, emitted on state transitions and
/// whenever the error counters change (see can.rs watchdog).
#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
pub struct BusStatus {
    /// "running" | "bus-off" | "recovering" | "stopped"
    pub state: &'static str,
    pub tx_errors: u8,
    pub rx_errors: u8,
}

#[derive(Clone, Debug)]
pub enum BridgeEvent {
    Frame(FrameEvent),
    /// raw DTC word pairs, e.g. [[0x01,0x71],[0x04,0x20]]
    Dtcs(Vec<[u8; 2]>),
    BusStatus(BusStatus),
}

#[derive(Clone, Debug)]
pub enum BridgeCommand {
    Tx(FrameEvent),
    DtcRead,
    DtcClear,
    RequestPid(u8),
}

/// Pack a frame into the 13-byte BLE packet:
/// [u32le id | ext<<31, u8 dlc, 8 x data]
pub fn pack_frame(f: &FrameEvent) -> [u8; 13] {
    let mut out = [0u8; 13];
    let raw_id = (f.id & 0x7FFF_FFFF) | if f.ext { 0x8000_0000 } else { 0 };
    out[0..4].copy_from_slice(&raw_id.to_le_bytes());
    out[4] = f.dlc.min(8);
    out[5..13].copy_from_slice(&f.data);
    out
}

pub fn unpack_frame(b: &[u8]) -> Option<FrameEvent> {
    if b.len() < 13 { return None; }
    let raw_id = u32::from_le_bytes([b[0], b[1], b[2], b[3]]);
    let dlc = b[4].min(8);
    let mut data = [0u8; 8];
    data[..dlc as usize].copy_from_slice(&b[5..5 + dlc as usize]);
    Some(FrameEvent { id: raw_id & 0x7FFF_FFFF, ext: raw_id & 0x8000_0000 != 0, dlc, data })
}

/// DTC notification packet: [0xFE, count, hi,lo, ...]
pub fn pack_dtcs(codes: &[[u8; 2]]) -> Vec<u8> {
    let mut v = Vec::with_capacity(2 + codes.len() * 2);
    v.push(0xFE);
    v.push(codes.len() as u8);
    for c in codes { v.extend_from_slice(c); }
    v
}

/// Bus status packet: [0xFD, state_code, tx_errors, rx_errors]
/// state_code: 0=running, 1=bus-off, 2=recovering, 3=stopped
pub fn pack_status(s: &BusStatus) -> [u8; 4] {
    let code = match s.state {
        "bus-off" => 1,
        "recovering" => 2,
        "stopped" => 3,
        _ => 0,
    };
    [0xFD, code, s.tx_errors, s.rx_errors]
}
