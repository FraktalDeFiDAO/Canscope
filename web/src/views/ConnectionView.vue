<script setup lang="ts">
import { computed, ref } from 'vue'
import { useConnectionStore } from '@/stores/connection'
import { useObdStore } from '@/stores/obd'
import StatusLed from '@/components/StatusLed.vue'

const conn = useConnectionStore()
const obd = useObdStore()

const apkStatus = ref('')
const apkBusy = ref(false)

async function downloadApk() {
  apkBusy.value = true
  apkStatus.value = 'Downloading…'
  try {
    const res = await fetch('canscope-debug.apk')
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const buf = await res.arrayBuffer()
    if (buf.byteLength < 1_000_000) throw new Error('file too small — server did not return the APK')
    const url = URL.createObjectURL(new Blob([buf], { type: 'application/vnd.android.package-archive' }))
    const a = document.createElement('a')
    a.href = url
    a.download = 'canscope-debug.apk'
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 10_000)
    apkStatus.value = `Saved (${(buf.byteLength / 1024 / 1024).toFixed(1)} MB) — check your downloads, then allow "install unknown apps" once.`
  } catch (e) {
    apkStatus.value = `Download failed: ${e instanceof Error ? e.message : e}. Try the ZIP below instead.`
  } finally {
    apkBusy.value = false
  }
}

const stateLabel = computed(() => ({
  connected: 'CONNECTED', connecting: 'CONNECTING', disconnected: 'DISCONNECTED', error: 'ERROR',
}[conn.state]))
const stateColor = computed(() => ({
  connected: 'ok', connecting: 'warn', disconnected: 'off', error: 'crit',
}[conn.state] as 'ok' | 'warn' | 'off' | 'crit'))
</script>

<template>
  <div class="grid grid-cols-1 lg:grid-cols-2 gap-3">
    <div class="flex flex-col gap-3">
      <div class="panel">
        <div class="panel-head">
          <span class="label">Data Source</span>
          <div class="flex items-center gap-2">
            <StatusLed :status="stateColor" :pulse="conn.state === 'connecting'" />
            <span class="mono text-[10px] tracking-[0.14em] uppercase" :class="conn.state === 'connected' ? 'text-ok' : 'text-ink-dim'">{{ stateLabel }}</span>
          </div>
        </div>
        <div class="p-4 flex flex-col gap-4">
          <div class="grid grid-cols-1 sm:grid-cols-3 gap-2">
            <button
              @click="conn.setMode('simulator')"
              class="text-left p-3 border cursor-pointer transition-colors"
              :class="conn.mode === 'simulator' ? 'border-live/60 bg-live/5' : 'border-line hover:border-line2'"
            >
              <div class="mono text-[11px] tracking-[0.14em] uppercase" :class="conn.mode === 'simulator' ? 'text-live' : 'text-ink'">Simulator</div>
              <div class="text-[11px] text-ink-dim mt-1">Built-in virtual ECU + bus traffic. No hardware needed.</div>
            </button>
            <button
              @click="conn.setMode('websocket')"
              class="text-left p-3 border cursor-pointer transition-colors"
              :class="conn.mode === 'websocket' ? 'border-live/60 bg-live/5' : 'border-line hover:border-line2'"
            >
              <div class="mono text-[11px] tracking-[0.14em] uppercase" :class="conn.mode === 'websocket' ? 'text-live' : 'text-ink'">Wi-Fi / WebSocket</div>
              <div class="text-[11px] text-ink-dim mt-1">Live link to your ESP32 bridge over Wi-Fi. Highest throughput.</div>
            </button>
            <button
              @click="conn.setMode('bluetooth')"
              class="text-left p-3 border cursor-pointer transition-colors"
              :class="conn.mode === 'bluetooth' ? 'border-live/60 bg-live/5' : 'border-line hover:border-line2'"
            >
              <div class="mono text-[11px] tracking-[0.14em] uppercase" :class="conn.mode === 'bluetooth' ? 'text-live' : 'text-ink'">Bluetooth LE</div>
              <div class="text-[11px] text-ink-dim mt-1">Direct browser → ESP32 link. No Wi-Fi network required.</div>
            </button>
          </div>

          <template v-if="conn.mode === 'bluetooth'">
            <div class="grid grid-cols-2 gap-3">
              <label class="flex flex-col gap-1.5">
                <span class="label">Bus bitrate</span>
                <select v-model.number="conn.bitrate" @change="conn.saveSettings(); conn.applyLinkConfig()" class="mono text-[15px] sm:text-[13px] bg-bg border border-line px-3 py-2.5 min-h-[44px] text-ink cursor-pointer">
                  <option :value="125000">125 kbit/s</option>
                  <option :value="250000">250 kbit/s</option>
                  <option :value="500000">500 kbit/s (OBD-II standard)</option>
                  <option :value="1000000">1 Mbit/s</option>
                </select>
              </label>
              <label class="flex items-center gap-2 cursor-pointer pt-5">
                <input type="checkbox" v-model="conn.listenOnly" @change="conn.saveSettings(); conn.applyLinkConfig()" class="accent-[#22d3ee]" />
                <span class="mono text-[10px] tracking-[0.14em] uppercase text-ink-dim">Listen-only mode</span>
              </label>
            </div>
            <p class="mono text-[10px] text-ink-faint uppercase tracking-[0.1em] leading-relaxed">
              In the browser: Chrome / Edge on HTTPS or localhost, device picker → “CANScope”. In the Android app: native BLE, no browser limits. Frames use the compact 13-byte binary BLE packet (see protocol below).
            </p>
          </template>

          <template v-if="conn.mode === 'websocket'">
            <label class="flex flex-col gap-1.5">
              <span class="label">ESP32 WebSocket URL</span>
              <input v-model="conn.wsUrl" @change="conn.saveSettings()" class="mono text-[15px] sm:text-[13px] bg-bg border border-line px-3 py-2.5 min-h-[44px] text-ink" placeholder="ws://192.168.4.1:81" />
            </label>
            <div class="grid grid-cols-2 gap-3">
              <label class="flex flex-col gap-1.5">
                <span class="label">Bus bitrate</span>
                <select v-model.number="conn.bitrate" @change="conn.saveSettings()" class="mono text-[15px] sm:text-[13px] bg-bg border border-line px-3 py-2.5 min-h-[44px] text-ink cursor-pointer">
                  <option :value="125000">125 kbit/s</option>
                  <option :value="250000">250 kbit/s</option>
                  <option :value="500000">500 kbit/s (OBD-II standard)</option>
                  <option :value="1000000">1 Mbit/s</option>
                </select>
              </label>
              <label class="flex items-center gap-2 cursor-pointer pt-5">
                <input type="checkbox" v-model="conn.listenOnly" @change="conn.saveSettings()" class="accent-[#22d3ee]" />
                <span class="mono text-[10px] tracking-[0.14em] uppercase text-ink-dim">Listen-only mode</span>
              </label>
            </div>
            <div class="grid grid-cols-2 gap-3">
              <label class="flex flex-col gap-1.5">
                <span class="label">Acceptance filter ID (hex, optional)</span>
                <input v-model="conn.filterId" @change="conn.saveFilter()" class="mono text-[15px] sm:text-[13px] bg-bg border border-line px-3 py-2.5 min-h-[44px] text-ink" placeholder="empty = accept all" />
              </label>
              <label class="flex flex-col gap-1.5">
                <span class="label">Filter mask (hex)</span>
                <input v-model="conn.filterMask" @change="conn.saveFilter()" class="mono text-[15px] sm:text-[13px] bg-bg border border-line px-3 py-2.5 min-h-[44px] text-ink" placeholder="7FF = exact ID only" />
              </label>
            </div>
            <p class="mono text-[9px] text-ink-faint uppercase tracking-[0.1em] leading-relaxed">
              Hardware TWAI filter — drops irrelevant IDs before they reach the app. Mask bit 1 = must match. Applied at bridge boot.
            </p>
          </template>

          <button
            @click="conn.state === 'connected' ? conn.disconnect() : conn.connect()"
            class="mono text-[11px] tracking-[0.14em] uppercase px-5 py-2.5 border cursor-pointer transition-colors self-start"
            :class="conn.state === 'connected' ? 'border-crit/50 text-crit hover:bg-crit/10' : 'border-ok/50 text-ok hover:bg-ok/10'"
          >{{ conn.state === 'connected' ? 'Disconnect' : 'Connect' }}</button>
          <p v-if="conn.lastError" class="mono text-[10px] text-crit uppercase tracking-[0.1em] leading-relaxed">{{ conn.lastError }}</p>
        </div>
      </div>
    </div>

    <div class="flex flex-col gap-3">
      <div class="panel">
        <div class="panel-head">
          <span class="label">Vehicle Identity (Mode 09)</span>
          <button @click="conn.requestVehicleInfo(); conn.discoverSupportedPids()" class="mono text-[9px] tracking-[0.14em] uppercase px-3 py-1.5 border border-line text-ink-dim hover:text-ink cursor-pointer transition-colors">Re-query</button>
        </div>
        <div class="p-4 mono text-[11px] leading-[2]">
          <div><span class="text-ink-faint">VIN&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;</span><span class="text-ink">{{ obd.vehicle.vin || '——' }}</span><span v-if="obd.vehicle.vinSource === 'sniffed'" class="text-mag ml-2">sniffed from bus</span></div>
          <div><span class="text-ink-faint">ECU NAME&nbsp;&nbsp;</span><span class="text-ink">{{ obd.vehicle.ecuName || '——' }}</span></div>
          <div><span class="text-ink-faint">CAL ID&nbsp;&nbsp;&nbsp;&nbsp;</span><span class="text-ink">{{ obd.vehicle.calibrationId || '——' }}</span></div>
          <div><span class="text-ink-faint">PIDS&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;</span><span class="text-live">{{ obd.supported.size ? obd.supported.size + ' supported (bitmask discovered)' : 'not queried' }}</span></div>
        </div>
      </div>
      <div class="panel">
        <div class="panel-head"><span class="label">Wiring — ESP32 ↔ SN65HVD230 ↔ OBD-II</span></div>
        <div class="p-4">
          <svg viewBox="0 0 560 190" class="w-full" role="img" aria-label="Wiring diagram: ESP32 GPIO5 to CTX, GPIO4 to CRX, 3V3 and GND to module, CANH to OBD pin 6, CANL to pin 14, GND to pin 4">
            <rect x="10" y="30" width="130" height="130" fill="#111a2b" stroke="#1a2540"/>
            <text x="75" y="48" text-anchor="middle" fill="#cbc6b9" font-size="11" font-family="JetBrains Mono" letter-spacing="2">ESP32</text>
            <text x="75" y="62" text-anchor="middle" fill="#4c5a75" font-size="8" font-family="JetBrains Mono">DEVKIT V1</text>
            <g font-family="JetBrains Mono" font-size="9" fill="#7d8aa5">
              <text x="20" y="86">GPIO 5</text><text x="20" y="106">GPIO 4</text><text x="20" y="126">3V3</text><text x="20" y="146">GND</text>
            </g>
            <rect x="220" y="30" width="150" height="130" fill="#111a2b" stroke="#22d3ee55"/>
            <text x="295" y="48" text-anchor="middle" fill="#22d3ee" font-size="10" font-family="JetBrains Mono" letter-spacing="1">SN65HVD230</text>
            <g font-family="JetBrains Mono" font-size="9" fill="#7d8aa5">
              <text x="320" y="86">CTX</text><text x="320" y="106">CRX</text><text x="320" y="126">VCC</text><text x="320" y="146">GND / Rs</text>
              <text x="232" y="86">CANH</text><text x="232" y="106">CANL</text>
            </g>
            <rect x="450" y="30" width="100" height="130" fill="#111a2b" stroke="#1a2540"/>
            <text x="500" y="48" text-anchor="middle" fill="#cbc6b9" font-size="10" font-family="JetBrains Mono" letter-spacing="1">OBD-II</text>
            <g font-family="JetBrains Mono" font-size="9" fill="#7d8aa5">
              <text x="462" y="86">Pin 6 (H)</text><text x="462" y="106">Pin 14 (L)</text><text x="462" y="126">Pin 4 (GND)</text>
              <text x="462" y="146" fill="#f87171">Pin 16 = 12V!</text>
            </g>
            <g stroke-width="1.5" fill="none" font-family="JetBrains Mono" font-size="8">
              <path d="M140 82 H220" stroke="#22d3ee"/><path d="M140 102 H220" stroke="#34d399"/>
              <path d="M140 122 H220" stroke="#4c5a75"/><path d="M140 142 H220" stroke="#4c5a75"/>
              <path d="M370 82 H450" stroke="#fbbf24"/><path d="M370 102 H450" stroke="#fbbf24" stroke-dasharray="4 3"/>
              <path d="M295 146 V170 H500 V160" stroke="#4c5a75"/>
            </g>
          </svg>
        </div>
      </div>

      <div class="panel">
        <div class="panel-head"><span class="label">Android App</span></div>
        <div class="p-4 flex flex-col gap-2">
          <p class="text-[13px] text-ink-dim leading-relaxed">
            The native companion app mirrors this dashboard — gauges, PIDs, sniffer, transmit, DTCs, sessions, BLE and Wi-Fi links.
          </p>
          <button
            @click="downloadApk"
            :disabled="apkBusy"
            class="mono text-[10px] tracking-[0.14em] uppercase px-4 py-2.5 min-h-[44px] border border-ok/60 text-ok hover:bg-ok/10 transition-colors self-start flex items-center disabled:opacity-50"
          >{{ apkBusy ? 'Downloading…' : 'Download APK (debug build)' }}</button>
          <p v-if="apkStatus" class="mono text-[10px] text-ink-dim">{{ apkStatus }}</p>
          <a
            href="canscope-debug.zip"
            download="canscope-debug.zip"
            class="mono text-[10px] tracking-[0.14em] uppercase text-ink-faint underline self-start"
          >Fallback: download as ZIP</a>
          <p class="mono text-[9px] text-ink-faint uppercase tracking-[0.1em]">Open this page on your phone, tap the button, allow "install unknown apps" once.</p>
        </div>
      </div>

      <div class="panel">
        <div class="panel-head"><span class="label">WebSocket Protocol</span></div>
        <div class="p-4 mono text-[10.5px] leading-[1.8] text-ink-dim overflow-x-auto">
          <div class="text-ink-faint uppercase tracking-[0.1em] mb-1">ESP32 → dashboard</div>
          <div>{"type":"frame","id":193,"ext":false,"dlc":8,"data":[2,1,12,0,0,0,0,0]}</div>
          <div>{"type":"obd","mode":1,"pid":12,"data":[26,128]}</div>
          <div>{"type":"dtc","codes":[[1,113],[4,32]]}</div>
          <div>{"type":"bus","state":"running","txErrors":0,"rxErrors":0} — TWAI health</div>
          <div class="text-ink-faint uppercase tracking-[0.1em] mt-3 mb-1">Dashboard → ESP32</div>
          <div>{"type":"tx","id":2015,"ext":false,"data":[2,1,12,0,0,0,0,0]}</div>
          <div>{"type":"cmd","cmd":"dtc-read"} · {"type":"cmd","cmd":"dtc-clear"}</div>
          <div>{"type":"cfg","bitrate":500000,"listen_only":false,"filter_id":2016,"filter_mask":2047}</div>
          <div class="text-ink-faint uppercase tracking-[0.1em] mt-3 mb-1">Bluetooth LE (GATT 6E400001…, Nordic-UART style)</div>
          <div>TX (notify, ESP32→browser): 13-byte frame [u32 id|ext&lt;&lt;31, u8 dlc, 8× data]</div>
          <div>RX (write, browser→ESP32): same layout, or 0xFF-prefixed commands:</div>
          <div>FF 01 dtc-read · FF 02 dtc-clear · FF 03 pid · FF 10 bitrate · FF 11 listen-only</div>
          <div>DTC notification: [0xFE, count, hi,lo word pairs…]</div>
        </div>
      </div>
    </div>
  </div>
</template>
