import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { PIDS, pidById, decodeDtcBytes } from '@/lib/obd'
import { feedIsoTp, flowControlFrame, decodeMode09, decodeSupportedPids, type VehicleInfo } from '@/lib/isotp'
import type { CanFrame } from '@/lib/simulator'

export const useObdStore = defineStore('obd', () => {
  // pid -> { value, raw, updated }
  const values = ref(new Map<number, { value: number; raw: number[]; updated: number }>())
  const dtcs = ref<string[]>([])
  const dtcRaw = ref<number[][]>([])
  /** Mode 07 pending + Mode 0A permanent DTCs */
  const pendingDtcs = ref<string[]>([])
  const permanentDtcs = ref<string[]>([])
  /** Mode 02 freeze-frame values: pid -> decoded value at the moment the DTC set */
  const freezeFrames = ref(new Map<number, { value: number; raw: number[]; updated: number }>())
  /** Mode 06 on-board monitoring results, kept raw (MID/TID decode is vehicle-specific) */
  const monitors = ref<Array<{ mid: number; tid: number; raw: number[]; updated: number }>>([])
  const supported = ref<Set<number>>(new Set())      // from 0x00/0x20/… bitmasks
  const vehicle = ref<VehicleInfo>({})

  function setValue(pid: number, value: number) {
    values.value.set(pid, { value, raw: [], updated: Date.now() })
  }

  // decode raw bytes from a real ECU response
  function setRaw(pid: number, raw: number[]) {
    const def = pidById(pid)
    if (!def) return
    try {
      const value = def.decode(raw) ?? NaN
      values.value.set(pid, { value, raw, updated: Date.now() })
    } catch { /* malformed response */ }
  }

  function setDtcs(codes: number[][]) {
    dtcRaw.value = codes
    dtcs.value = decodeDtcBytes(codes.flat())
  }

  function setVehicle(v: Partial<VehicleInfo>) {
    vehicle.value = { ...vehicle.value, ...v }
  }

  function addSupported(pids: Set<number>) {
    supported.value = new Set([...supported.value, ...pids])
  }

  /**
   * Feed any raw RX frame. ISO-TP reassembly happens here; completed payloads
   * are dispatched as mode-01 values, mode-02 freeze frames, mode-06 monitors,
   * mode-07/0A DTC lists, or mode-09 vehicle info.
   *
   * Pass sendFrame so first frames get an ISO-TP flow-control reply
   * (ISO 15765-2: requester must answer 0x30 to the response id − 8).
   */
  function feedFrame(f: CanFrame, sendFrame?: (id: number, data: number[]) => void) {
    if (f.ext || f.id < 0x7e8 || f.id > 0x7ef) return
    const payload = feedIsoTp(f.id, f.data)
    if (!payload) return
    if (payload.firstFrame) {
      // reply with flow control so the ECU keeps streaming consecutive frames
      sendFrame?.(f.id - 8, flowControlFrame())
      return
    }
    const d = payload.data
    if (d.length === 0) return
    const mode = d[0]
    if (mode === 0x41 && d.length >= 2) {
      const pid = d[1]
      if (pid % 0x20 === 0) {
        // support bitmask: [0x41, 0x00, b0..b3]
        addSupported(decodeSupportedPids(pid, d.slice(2, 6)))
      } else {
        setRaw(pid, d.slice(2))
      }
    } else if (mode === 0x42 && d.length >= 2) {
      // Mode 02 freeze frame: same PID layout as mode 01
      const pid = d[1]
      if (pid % 0x20 !== 0) {
        const def = pidById(pid)
        if (def) {
          const raw = d.slice(2)
          try {
            freezeFrames.value.set(pid, { value: def.decode(raw) ?? NaN, raw, updated: Date.now() })
          } catch { /* malformed */ }
        }
      }
    } else if (mode === 0x43) {
      // confirmed DTC response: [0x43, count, hi,lo, ...]
      const pairs: number[][] = []
      for (let i = 2; i + 1 < d.length; i += 2) pairs.push([d[i], d[i + 1]])
      setDtcs(pairs)
    } else if (mode === 0x44) {
      setDtcs([]) // clear acknowledged
    } else if (mode === 0x46) {
      // Mode 06 on-board monitoring: [0x46, MID, TID..., raw...] — keep raw
      for (let i = 1; i + 4 < d.length; i += 5) {
        monitors.value.push({ mid: d[i], tid: d[i + 1], raw: d.slice(i + 2, i + 5), updated: Date.now() })
      }
      if (monitors.value.length > 64) monitors.value.splice(0, monitors.value.length - 64)
    } else if (mode === 0x47 || mode === 0x4a) {
      const pairs: number[][] = []
      for (let i = 2; i + 1 < d.length; i += 2) pairs.push([d[i], d[i + 1]])
      const list = decodeDtcBytes(pairs.flat())
      if (mode === 0x47) pendingDtcs.value = list
      else permanentDtcs.value = list
    } else if (mode === 0x49 && d.length >= 2) {
      setVehicle(decodeMode09(d[1], d))
    }
  }

  function get(pid: number): number | null {
    const e = values.value.get(pid)
    return e && Number.isFinite(e.value) ? e.value : null
  }

  const supportedPids = computed(() => PIDS.filter(p => values.value.has(p.pid)))
  const milOn = computed(() => dtcs.value.length > 0)

  return {
    values, dtcs, dtcRaw, pendingDtcs, permanentDtcs, freezeFrames, monitors,
    supported, vehicle, milOn, supportedPids,
    setValue, setRaw, setDtcs, setVehicle, addSupported, feedFrame, get,
  }
})
