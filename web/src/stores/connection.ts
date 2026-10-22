import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { simulator } from '@/lib/simulator'
import { esp32 } from '@/lib/wsClient'
import { ble } from '@/lib/bleClient'
import { nativeBle, isNativeApp } from '@/lib/nativeBle'
import { useCanStore } from './can'
import { useObdStore } from './obd'
import { useSessionStore } from './sessions'
import { feedVin } from '@/lib/vinSniff'

export type ConnMode = 'simulator' | 'websocket' | 'bluetooth'
export type ConnState = 'disconnected' | 'connecting' | 'connected' | 'error'

export const useConnectionStore = defineStore('connection', () => {
  const mode = ref<ConnMode>((localStorage.getItem('can.mode') as ConnMode) || 'simulator')
  const state = ref<ConnState>('disconnected')
  const wsUrl = ref(localStorage.getItem('can.wsUrl') || 'ws://192.168.4.1:81')
  const bitrate = ref(Number(localStorage.getItem('can.bitrate') || 500000))
  const listenOnly = ref(localStorage.getItem('can.listenOnly') === '1')
  const startedAt = ref<number | null>(null)
  /** TWAI controller health reported by the firmware watchdog. */
  const busStatus = ref<{ state: string; txErrors: number; rxErrors: number } | null>(null)

  const can = useCanStore()
  const obd = useObdStore()

  // wire transports -> stores once
  const sessions = useSessionStore()
  const onAnyFrame = (f: Parameters<typeof can.pushFrame>[0]) => {
    can.pushFrame(f)
    // ISO-TP reassembly + OBD dispatch; FC frames go back out over the link
    obd.feedFrame(f, (id, data) => txFrame(id, data))
    sessions.pushFrame(f) // session recorder
    // Passive VIN detection: scan non-OBD traffic for ISO 3780 VINs
    // (manufacturer broadcasts). Mode 09 results always take precedence.
    if (!obd.vehicle.vin || obd.vehicle.vinSource === 'sniffed') {
      const hit = feedVin(f.id, f.data)
      if (hit) obd.setVehicle({ vin: hit.vin, vinSource: 'sniffed' })
    }
  }
  simulator.onFrame = onAnyFrame
  simulator.onObdValue = (pid, value) => obd.setValue(pid, value)
  simulator.onDtc = codes => obd.setDtcs(codes)
  esp32.onFrame = onAnyFrame
  esp32.onObdValue = (pid, _v, raw) => obd.setRaw(pid, raw)
  esp32.onDtc = codes => obd.setDtcs(codes)
  esp32.onState = s => handleTransportState(s)
  esp32.onBusStatus = s => { busStatus.value = s }
  type BleLike = Pick<typeof ble, 'onFrame' | 'onObdValue' | 'onDtc' | 'onState' | 'onBusStatus'
    | 'connect' | 'disconnect' | 'connected' | 'txFrame' | 'readDtcs' | 'clearDtcs' | 'setBitrate' | 'setListenOnly'>
  const wireBle = (client: BleLike) => {
    client.onFrame = onAnyFrame
    client.onObdValue = (pid, _v, raw) => obd.setRaw(pid, raw)
    client.onDtc = codes => obd.setDtcs(codes)
    client.onState = s => handleTransportState(s)
    client.onBusStatus = s => { busStatus.value = s }
  }
  wireBle(ble)
  wireBle(nativeBle)

  // --- auto-reconnect with exponential backoff (2s → 15s cap) ---
  let manualDisconnect = false
  let retryTimer: ReturnType<typeof setTimeout> | null = null
  let retryDelay = 2000

  function handleTransportState(s: 'connecting' | 'open' | 'closed' | 'error') {
    if (s === 'open') {
      state.value = 'connected'
      retryDelay = 2000
      // discover vehicle once per connection
      discoverSupportedPids()
      requestVehicleInfo()
    } else if (s === 'connecting') {
      state.value = 'connecting'
    } else if (s === 'error') {
      state.value = 'error'
      scheduleReconnect()
    } else {
      state.value = 'disconnected'
      if (!manualDisconnect) scheduleReconnect()
    }
  }

  function scheduleReconnect() {
    if (manualDisconnect || mode.value === 'simulator') return
    if (retryTimer) return
    retryTimer = setTimeout(() => {
      retryTimer = null
      if (!manualDisconnect) connect()
    }, retryDelay)
    retryDelay = Math.min(retryDelay * 2, 15000)
  }

  // Inside the Android app (Capacitor) BLE goes through the native plugin;
  // in a browser it uses Web Bluetooth.
  const bleTransport = () => (isNativeApp() ? nativeBle : ble)

  const lastError = ref('')

  async function connect() {
    disconnectTransport()
    manualDisconnect = false
    lastError.value = ''
    if (mode.value === 'simulator') {
      simulator.start()
      seedSimulatorIdentity()
      state.value = 'connected'
    } else if (mode.value === 'websocket') {
      esp32.connect(wsUrl.value)
    } else {
      const err = await bleTransport().connect()
      if (err) lastError.value = err
    }
    startedAt.value = Date.now()
  }

  function seedSimulatorIdentity() {
    obd.setVehicle({ vin: '1HGCM82633A004352', ecuName: 'SIM-ECU 2.4T', calibrationId: 'SIM-37805-R70' })
    obd.addSupported(new Set([0x04, 0x05, 0x06, 0x07, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x1f,
      0x2c, 0x2f, 0x31, 0x33, 0x3c, 0x42, 0x43, 0x44, 0x45, 0x46, 0x49, 0x4a, 0x4c, 0x51, 0x5c, 0x5e, 0x61, 0x62, 0x63]))
  }

  function disconnectTransport() {
    simulator.stop()
    esp32.disconnect()
    ble.disconnect()
    nativeBle.disconnect()
  }

  function disconnect() {
    manualDisconnect = true
    busStatus.value = null
    if (retryTimer) { clearTimeout(retryTimer); retryTimer = null }
    disconnectTransport()
    if (mode.value === 'simulator' || mode.value === 'bluetooth') state.value = 'disconnected'
    startedAt.value = null
  }

  function setMode(m: ConnMode) {
    mode.value = m
    localStorage.setItem('can.mode', m)
    if (state.value === 'connected') connect()
  }

  function saveSettings() {
    localStorage.setItem('can.wsUrl', wsUrl.value)
    localStorage.setItem('can.bitrate', String(bitrate.value))
    localStorage.setItem('can.listenOnly', listenOnly.value ? '1' : '0')
  }

  function txFrame(id: number, data: number[], ext = false) {
    const frame = { ts: Date.now(), id, ext, dlc: data.length, data, dir: 'tx' as const }
    if (mode.value === 'websocket') esp32.txFrame(id, data, ext)
    else if (mode.value === 'bluetooth') bleTransport().txFrame(id, data, ext)
    else can.pushFrame(frame)
  }

  function readDtcs() {
    if (mode.value === 'simulator') simulator.readDtcs()
    else if (mode.value === 'bluetooth') bleTransport().readDtcs()
    else esp32.send({ type: 'cmd', cmd: 'dtc-read' })
  }

  function clearDtcs() {
    if (mode.value === 'simulator') simulator.clearDtcs()
    else if (mode.value === 'bluetooth') bleTransport().clearDtcs()
    else esp32.send({ type: 'cmd', cmd: 'dtc-clear' })
  }

  function applyLinkConfig() {
    const t = bleTransport()
    if (mode.value === 'bluetooth' && t.connected) {
      const idx = (bitrate.value <= 125000 ? 0 : bitrate.value <= 250000 ? 1 : bitrate.value <= 500000 ? 2 : 3) as 0 | 1 | 2 | 3
      t.setBitrate(idx)
      t.setListenOnly(listenOnly.value)
    } else if (mode.value === 'websocket') {
      esp32.send({
        type: 'cfg', bitrate: bitrate.value, listen_only: listenOnly.value,
        filter_id: filterId.value === '' ? undefined : parseInt(filterId.value, 16),
        filter_mask: filterMask.value === '' ? undefined : parseInt(filterMask.value, 16),
      })
    }
  }

  /** TWAI acceptance filter (hex strings, empty = accept all). Applied at bridge boot. */
  const filterId = ref(localStorage.getItem('can.filterId') || '')
  const filterMask = ref(localStorage.getItem('can.filterMask') || '')
  function saveFilter() {
    localStorage.setItem('can.filterId', filterId.value)
    localStorage.setItem('can.filterMask', filterMask.value)
    applyLinkConfig()
  }

  /** Mode 01 support-bitmask discovery: 0x00, 0x20, 0x40, 0x60. */
  function discoverSupportedPids() {
    if (mode.value === 'simulator') return
    for (const base of [0x00, 0x20, 0x40, 0x60]) {
      txFrame(0x7df, [0x02, 0x01, base, 0, 0, 0, 0, 0])
    }
  }

  /** Mode 09 vehicle info: VIN, calibration ID, ECU name. */
  function requestVehicleInfo() {
    if (mode.value === 'simulator') return
    for (const infoType of [0x02, 0x04, 0x0a]) {
      txFrame(0x7df, [0x02, 0x09, infoType, 0, 0, 0, 0, 0])
    }
  }

  /** Generic OBD request: service + optional PID (mode 01/02 style). */
  function requestMode(service: number, pid?: number) {
    if (mode.value === 'simulator') return
    txFrame(0x7df, pid === undefined
      ? [0x01, service, 0, 0, 0, 0, 0, 0]
      : [0x02, service, pid, 0, 0, 0, 0, 0])
  }

  /** Mode 07: pending DTCs; Mode 0A: permanent DTCs. */
  const readPendingDtcs = () => requestMode(0x07)
  const readPermanentDtcs = () => requestMode(0x0a)
  /** Mode 02 freeze frame for one PID (default: the DTC that triggered it, 0x02). */
  const readFreezeFrame = (pid = 0x02) => requestMode(0x02, pid)
  /** Mode 06 on-board monitoring (raw MID/TID results). */
  const readMonitors = () => requestMode(0x06)

  const uptime = computed(() => startedAt.value ? Math.floor((Date.now() - startedAt.value) / 1000) : 0)

  return { mode, state, wsUrl, bitrate, listenOnly, uptime, lastError, busStatus, filterId, filterMask, saveFilter, connect, disconnect, setMode, saveSettings, txFrame, readDtcs, clearDtcs, applyLinkConfig, discoverSupportedPids, requestVehicleInfo, requestMode, readPendingDtcs, readPermanentDtcs, readFreezeFrame, readMonitors }
})
