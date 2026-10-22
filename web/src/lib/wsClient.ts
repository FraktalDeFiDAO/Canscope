import type { CanFrame } from './simulator'

// WebSocket client for the ESP32 bridge firmware.
// Protocol (JSON lines):
//   ESP32 -> browser: {type:'frame', id, ext, dlc, data:[...]}   {type:'obd', mode, pid, data:[...]}  {type:'dtc', codes:[[hi,lo],...]}
//   browser -> ESP32: {type:'tx', id, ext, data:[...]}           {type:'cmd', cmd:'dtc-read'|'dtc-clear'} {type:'cfg', bitrate}
export class Esp32WsClient {
  private ws: WebSocket | null = null
  url = ''
  onFrame: ((f: CanFrame) => void) | null = null
  onObdValue: ((pid: number, value: number, raw: number[]) => void) | null = null
  onDtc: ((codes: number[][]) => void) | null = null
  onState: ((s: 'connecting' | 'open' | 'closed' | 'error') => void) | null = null
  /** Firmware TWAI health: {type:'bus', state:'running'|'bus-off'|'recovering'|'stopped', txErrors, rxErrors} */
  onBusStatus: ((s: { state: string; txErrors: number; rxErrors: number }) => void) | null = null

  connect(url: string) {
    this.disconnect()
    this.url = url
    this.onState?.('connecting')
    try {
      this.ws = new WebSocket(url)
    } catch {
      this.onState?.('error')
      return
    }
    this.ws.onopen = () => this.onState?.('open')
    this.ws.onerror = () => this.onState?.('error')
    this.ws.onclose = () => this.onState?.('closed')
    this.ws.onmessage = ev => {
      try {
        const m = JSON.parse(ev.data as string)
        if (m.type === 'frame') {
          this.onFrame?.({ ts: Date.now(), id: m.id, ext: !!m.ext, dlc: m.dlc ?? m.data.length, data: m.data, dir: 'rx' })
        } else if (m.type === 'obd' && m.mode === 1) {
          const def = m.data
          this.onObdValue?.(m.pid, NaN, def) // value decoding handled by store via pid db
        } else if (m.type === 'dtc') {
          this.onDtc?.(m.codes)
        } else if (m.type === 'bus') {
          this.onBusStatus?.({ state: String(m.state), txErrors: m.txErrors ?? 0, rxErrors: m.rxErrors ?? 0 })
        }
      } catch { /* ignore malformed */ }
    }
  }

  disconnect() {
    this.ws?.close()
    this.ws = null
  }

  get connected() { return this.ws?.readyState === WebSocket.OPEN }

  send(obj: unknown) {
    if (this.connected) this.ws!.send(JSON.stringify(obj))
  }

  txFrame(id: number, data: number[], ext = false) {
    this.send({ type: 'tx', id, ext, data })
    this.onFrame?.({ ts: Date.now(), id, ext, dlc: data.length, data, dir: 'tx' })
  }

  requestPid(pid: number) {
    this.send({ type: 'tx', id: 0x7df, ext: false, data: [2, 1, pid, 0, 0, 0, 0, 0] })
  }
}

export const esp32 = new Esp32WsClient()
