import type { CanFrame } from './simulator'
import { BLE_SERVICE, BLE_CHAR_TX, BLE_CHAR_RX } from './bleClient'

// Native BLE transport for the Android app via @capacitor-community/bluetooth-le.
// Android WebView has no Web Bluetooth, so inside Capacitor the dashboard's
// "Bluetooth LE" mode is routed here. Same binary packet protocol as bleClient.
//
// Lazy-imported so the web build carries no Capacitor runtime cost.

const CMD = 0xff
const DTC_PKT = 0xfe

export class NativeBleClient {
  private deviceId: string | null = null

  onFrame: ((f: CanFrame) => void) | null = null
  onObdValue: ((pid: number, value: number, raw: number[]) => void) | null = null
  onDtc: ((codes: number[][]) => void) | null = null
  onState: ((s: 'connecting' | 'open' | 'closed' | 'error') => void) | null = null
  onBusStatus: ((s: { state: string; txErrors: number; rxErrors: number }) => void) | null = null

  private async plugin() {
    const mod = await import('@capacitor-community/bluetooth-le')
    return mod.BleClient
  }

  async connect(): Promise<string | null> {
    this.onState?.('connecting')
    try {
      const BleClient = await this.plugin()
      await BleClient.initialize({ androidNeverForLocation: true })
      const device = await BleClient.requestDevice({
        namePrefix: 'CANScope',
        services: [BLE_SERVICE],
      })
      this.deviceId = device.deviceId
      await BleClient.connect(device.deviceId, () => this.onState?.('closed'))
      await BleClient.startNotifications(device.deviceId, BLE_SERVICE, BLE_CHAR_TX, value => {
        this.onNotify(new Uint8Array(value.buffer, value.byteOffset, value.byteLength))
      })
      this.onState?.('open')
      return null
    } catch (e) {
      this.onState?.('error')
      return e instanceof Error ? e.message : 'Bluetooth connection failed'
    }
  }

  async disconnect() {
    if (!this.deviceId) return
    try {
      const BleClient = await this.plugin()
      await BleClient.stopNotifications(this.deviceId, BLE_SERVICE, BLE_CHAR_TX).catch(() => {})
      await BleClient.disconnect(this.deviceId).catch(() => {})
    } catch { /* ignore */ }
    this.deviceId = null
  }

  get connected() { return this.deviceId !== null }

  private onNotify(b: Uint8Array) {
    if (b.length === 0) return
    if (b[0] === DTC_PKT) {
      const codes: number[][] = []
      for (let i = 2; i + 1 < b.length && codes.length < b[1]; i += 2) codes.push([b[i], b[i + 1]])
      this.onDtc?.(codes)
      return
    }
    if (b[0] === 0xfd) {
      const states = ['running', 'bus-off', 'recovering', 'stopped']
      this.onBusStatus?.({ state: states[b[1]] ?? 'running', txErrors: b[2] ?? 0, rxErrors: b[3] ?? 0 })
      return
    }
    if (b.length < 13) return
    const rawId = b[0] | (b[1] << 8) | (b[2] << 16) | (b[3] << 24)
    const ext = (rawId & 0x80000000) !== 0
    const id = rawId & 0x7fffffff
    const dlc = Math.min(8, b[4])
    const data = Array.from(b.slice(5, 5 + dlc))
    this.onFrame?.({ ts: Date.now(), id, ext, dlc, data, dir: 'rx' })
    if (!ext && id >= 0x7e8 && id <= 0x7ef && data[1] === 0x41) {
      this.onObdValue?.(data[2], NaN, data.slice(3, 3 + Math.max(0, data[0] - 2)))
    }
  }

  private async write(b: number[]) {
    if (!this.deviceId) return
    try {
      const BleClient = await this.plugin()
      await BleClient.writeWithoutResponse(
        this.deviceId, BLE_SERVICE, BLE_CHAR_RX, new DataView(new Uint8Array(b).buffer),
      )
    } catch { /* disconnected */ }
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

export const nativeBle = new NativeBleClient()

export function isNativeApp(): boolean {
  return typeof (window as any).Capacitor !== 'undefined' && (window as any).Capacitor.isNativePlatform?.()
}
