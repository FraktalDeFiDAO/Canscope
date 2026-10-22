import { PIDS } from './obd'

export interface CanFrame {
  ts: number
  id: number
  ext: boolean
  dlc: number
  data: number[]
  dir: 'rx' | 'tx'
}

// Deterministic-ish engine state driving both OBD responses and raw traffic.
export class Simulator {
  private t = 0
  private timer: ReturnType<typeof setInterval> | null = null
  private driving = true
  private dtcs: number[][] = [[0x01, 0x71], [0x04, 0x20]] // P0171, P0420
  rpm = 820
  speed = 0

  onFrame: ((f: CanFrame) => void) | null = null
  onObdValue: ((pid: number, value: number, raw: number[]) => void) | null = null
  onDtc: ((codes: number[][]) => void) | null = null

  start() {
    if (this.timer) return
    this.timer = setInterval(() => this.tick(), 100)
  }

  stop() {
    if (this.timer) clearInterval(this.timer)
    this.timer = null
  }

  clearDtcs() {
    this.dtcs = []
    this.onDtc?.(this.dtcs)
  }

  readDtcs() {
    this.onDtc?.(this.dtcs)
  }

  private tick() {
    this.t += 0.1
    const t = this.t
    // engine cycle: idle, gentle cruise, accel bursts
    const phase = (Math.sin(t / 7) + 1) / 2
    const burst = Math.max(0, Math.sin(t / 2.3)) ** 3
    this.rpm = 800 + phase * 900 + burst * 3800 + Math.random() * 60
    this.speed = this.driving ? Math.max(0, (this.rpm - 800) / 42 + Math.random() * 2) : 0
    this.emitRawTraffic()
    this.pollValues()
  }

  private emitRawTraffic() {
    const mk = (id: number, data: number[]): CanFrame => ({
      ts: Date.now(), id, ext: false, dlc: data.length, data, dir: 'rx',
    })
    // Engine ECU broadcast (RPM + throttle packed)
    this.onFrame?.(mk(0x0c1, [
      (this.rpm * 4) >> 8, (this.rpm * 4) & 0xff,
      Math.min(255, Math.round(this.rpm / 30)), 0, 0, 0, 0, 0,
    ]))
    // Speed
    this.onFrame?.(mk(0x2b0, [Math.round(this.speed), Math.round(this.speed) >> 8, 0, 0, 0, 0, 0, 0]))
    // Steering angle noise
    this.onFrame?.(mk(0x0c2, [Math.round(127 + Math.sin(this.t / 1.8) * 90), 0, 0, 0, 0, 0, 0, 0]))
    // ABS / wheel speeds every other tick
    if (Math.random() > 0.5) {
      const v = Math.round(this.speed * 16)
      this.onFrame?.(mk(0x1a0, [v & 0xff, v >> 8, v & 0xff, v >> 8, v & 0xff, v >> 8, v & 0xff, v >> 8]))
    }
    // Gearbox
    if (Math.random() > 0.7) {
      const gear = Math.min(6, 1 + Math.floor(this.speed / 22))
      this.onFrame?.(mk(0x321, [gear, Math.round(this.rpm / 8) & 0xff, 0x80, 0, 0, 0, 0, 0]))
    }
  }

  private pollValues() {
    const r = this.rpm, s = this.speed
    const load = Math.min(95, 8 + (r - 700) / 60 + Math.random() * 3)
    const coolant = 88 + Math.sin(this.t / 40) * 3 + Math.random() * 0.8
    const intake = 24 + s / 12 + Math.random()
    const throttle = Math.min(100, Math.max(0, (r - 780) / 45))
    const maf = 2.1 + r / 220 + Math.random() * 0.2
    const timing = 8 + (r - 800) / 160 + Math.random()
    const mapKpa = Math.max(20, 35 + load * 0.55)
    const stft = Math.sin(this.t / 3) * 4 + (Math.random() - 0.5)
    const lambda = 1 + Math.sin(this.t / 5) * 0.012
    const fuelRate = 0.9 + (r / 1000) * (load / 100) * 8
    const voltage = 14.1 + Math.random() * 0.25
    const baro = 101
    const oilTemp = coolant + 8 + Math.random()
    const catTemp = 420 + r / 12 + Math.random() * 6
    const egr = s > 20 ? 12 + Math.random() * 4 : 0
    const torque = Math.min(90, load - 4)
    const pedal = throttle + Math.random()

    const vals: Array<[number, number]> = [
      [0x04, load], [0x05, coolant], [0x06, stft], [0x07, stft * 0.6],
      [0x0b, mapKpa], [0x0c, r], [0x0d, s], [0x0e, timing],
      [0x0f, intake], [0x10, maf], [0x11, throttle], [0x1f, Math.floor(this.t)],
      [0x2c, egr], [0x2f, 62], [0x31, 1284], [0x33, baro],
      [0x3c, catTemp], [0x42, voltage], [0x43, load + 6], [0x44, lambda],
      [0x45, throttle * 0.97], [0x46, intake - 4], [0x49, pedal],
      [0x4a, pedal * 0.5], [0x4c, throttle], [0x51, 1], [0x5c, oilTemp],
      [0x5e, fuelRate], [0x61, torque + 6], [0x62, torque], [0x63, 320],
    ]
    for (const [pid, value] of vals) {
      const def = PIDS.find(p => p.pid === pid)
      this.onObdValue?.(pid, value, def ? new Array(def.bytes).fill(0) : [])
    }
  }
}

export const simulator = new Simulator()
