import type { CanFrame } from './simulator'

// BLE bridge protocol for the ESP32 firmware (Nordic-UART-style GATT).
//
// Service: 6e400001-b5a3-f393-e0a9-e50e24dcca9e
//   TX char (notify, ESP32 -> browser): 6e400003-...   binary frame packets
//   RX char (write,  browser -> ESP32): 6e400002-...   binary frame packets / commands
//
// Frame packet (13 bytes, little-endian):
//   [0..3]  u32  CAN ID (bit31 = extended flag)
//   [4]     u8   DLC
//   [5..12] u8[] data (zero-padded to 8)
// Command packet (browser -> ESP32, first byte 0xFF):
//   [0xFF, 0x01]          read DTCs
//   [0xFF, 0x02]          clear DTCs
//   [0xFF, 0x03, pid]     request OBD-II PID (mode 01)
//   [0xFF, 0x10, bps_k]   set bitrate: 0=125k 1=250k 2=500k 3=1M
//   [0xFF, 0x11, mode]    0=normal 1=listen-only
// DTC notification (ESP32 -> browser, first byte 0xFE):
//   [0xFE, n, hi,lo, hi,lo, ...]  raw DTC word pairs
export const BLE_SERVICE = '6e400001-b5a3-f393-e0a9-e50e24dcca9e'
export const BLE_CHAR_TX = '6e400003-b5a3-f393-e0a9-e50e24dcca9e'
export const BLE_CHAR_RX = '6e400002-b5a3-f393-e0a9-e50e24dcca9e'

const CMD = 0xff
const DTC_PKT = 0xfe

export class BleClient {
  private device: BluetoothDevice | null = null
  private txChar: BluetoothRemoteGATTCharacteristic | null = null
  private rxChar: BluetoothRemoteGATTCharacteristic | null = null

  onFrame: ((f: CanFrame) => void) | null = null
  onObdValue: ((pid: number, value: number, raw: number[]) => void) | null = null
  onDtc: ((codes: number[][]) => void) | null = null
  onState: ((s: 'connecting' | 'open' | 'closed' | 'error') => void) | null = null
  onBusStatus: ((s: { state: string; txErrors: number; rxErrors: number }) => void) | null = null

  static supported(): boolean {
    return typeof navigator !== 'undefined' && 'bluetooth' in navigator
  }

  async connect(): Promise<string | null> {
    if (!BleClient.supported()) return 'Web Bluetooth is not available in this browser (use Chrome/Edge, HTTPS or localhost)'
    this.onState?.('connecting')
    try {
      this.device = await navigator.bluetooth!.requestDevice({
        filters: [{ namePrefix: 'CANScope' }, { services: [BLE_SERVICE] }],
        optionalServices: [BLE_SERVICE],
      })
      this.device.addEventListener('gattserverdisconnected', () => this.onState?.('closed'))
      const server = await this.device.gatt!.connect()
      const svc = await server.getPrimaryService(BLE_SERVICE)
      this.txChar = await svc.getCharacteristic(BLE_CHAR_TX)
      this.rxChar = await svc.getCharacteristic(BLE_CHAR_RX)
      await this.txChar.startNotifications()
      this.txChar.addEventListener('characteristicvaluechanged', ev => this.onNotify(ev))
      this.onState?.('open')
      return null
    } catch (e) {
      this.onState?.('error')
      return e instanceof Error ? e.message : 'Bluetooth connection failed'
    }
  }

  disconnect() {
    try { this.device?.gatt?.disconnect() } catch { /* ignore */ }
    this.device = null
    this.txChar = null
    this.rxChar = null
  }

  get connected() { return !!this.device?.gatt?.connected }

  private onNotify(ev: Event) {
    const v = (ev.target as BluetoothRemoteGATTCharacteristic).value
    if (!v || v.byteLength === 0) return
    const b = new Uint8Array(v.buffer, v.byteOffset, v.byteLength)
    if (b[0] === DTC_PKT) {
      const codes: number[][] = []
      for (let i = 2; i + 1 < b.length && codes.length < b[1]; i += 2) codes.push([b[i], b[i + 1]])
      this.onDtc?.(codes)
      return
    }
    // Bus status packet (ESP32 -> browser): [0xFD, state_code, tx_errors, rx_errors]
    if (b[0] === 0xfd) {
      const states = ['running', 'bus-off', 'recovering', 'stopped']
      this.onBusStatus?.({ state: states[b[1]] ?? 'running', txErrors: b[2] ?? 0, rxErrors: b[3] ?? 0 })
      return
    }
    if (b.byteLength < 13) return
    const rawId = b[0] | (b[1] << 8) | (b[2] << 16) | (b[3] << 24)
    const ext = (rawId & 0x80000000) !== 0
    const id = rawId & 0x7fffffff
    const dlc = Math.min(8, b[4])
    const data = Array.from(b.slice(5, 5 + dlc))
    const f: CanFrame = { ts: Date.now(), id, ext, dlc, data, dir: 'rx' }
    this.onFrame?.(f)
    // OBD-II ECM response -> decode via pid db through onObdValue
    if (!ext && id >= 0x7e8 && id <= 0x7ef && data[1] === 0x41) {
      this.onObdValue?.(data[2], NaN, data.slice(3, 3 + Math.max(0, data[0] - 2)))
    }
  }

  private async write(b: number[]) {
    if (!this.rxChar) return
    try { await this.rxChar.writeValueWithoutResponse(new Uint8Array(b)) } catch { /* disconnected */ }
  }

  txFrame(id: number, data: number[], ext = false) {
    const buf = new Array(13).fill(0)
    const rawId = (id & 0x7fffffff) | (ext ? 0x80000000 : 0)
    buf[0] = rawId & 0xff; buf[1] = (rawId >> 8) & 0xff; buf[2] = (rawId >> 16) & 0xff; buf[3] = (rawId >> 24) & 0xff
    buf[4] = data.length
    data.slice(0, 8).forEach((d, i) => (buf[5 + i] = d & 0xff))
    this.write(buf)
    this.onFrame?.({ ts: Date.now(), id, ext, dlc: data.length, data, dir: 'tx' })
  }

  readDtcs() { this.write([CMD, 0x01]) }
  clearDtcs() { this.write([CMD, 0x02]) }
  requestPid(pid: number) { this.write([CMD, 0x03, pid & 0xff]) }
  setBitrate(idx: 0 | 1 | 2 | 3) { this.write([CMD, 0x10, idx]) }
  setListenOnly(on: boolean) { this.write([CMD, 0x11, on ? 1 : 0]) }
}

export const ble = new BleClient()
