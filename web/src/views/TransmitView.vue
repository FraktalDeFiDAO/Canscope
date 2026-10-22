<script setup lang="ts">
import { computed, ref } from 'vue'
import { useConnectionStore } from '@/stores/connection'
import { PIDS } from '@/lib/obd'
import { segmentPayload } from '@/lib/isotp'

const conn = useConnectionStore()

const idHex = ref('7DF')
const dataHex = ref('02 01 0C 00 00 00 00 00')
const ext = ref(false)
const isotp = ref(false)
const repeat = ref(0)
const feedback = ref('')
let feedbackTimer: ReturnType<typeof setTimeout>

const parsedData = computed<number[] | null>(() => {
  const parts = dataHex.value.trim().split(/[\s,]+/).filter(Boolean)
  if (!parts.length) return null
  const max = isotp.value ? 4095 : 8
  if (parts.length > max) return null
  const nums = parts.map(p => parseInt(p, 16))
  return nums.every(n => Number.isFinite(n) && n >= 0 && n <= 255) ? nums : null
})

const parsedId = computed(() => {
  const n = parseInt(idHex.value, 16)
  return Number.isFinite(n) && n >= 0 && n <= 0x1fffffff ? n : null
})

function flash(msg: string) {
  feedback.value = msg
  clearTimeout(feedbackTimer)
  feedbackTimer = setTimeout(() => (feedback.value = ''), 2500)
}

function send() {
  if (parsedId.value == null) return flash('Invalid CAN ID')
  if (!parsedData.value) return flash(isotp.value ? 'Data must be 1–4095 hex bytes' : 'Data must be 1–8 hex bytes')
  if (isotp.value && parsedData.value.length > 7) {
    // multi-frame: segment as FF + CFs, paced 10 ms apart
    const frames = segmentPayload(parsedData.value)
    frames.forEach((f, i) => setTimeout(() => conn.txFrame(parsedId.value!, f, ext.value), i * 10))
    flash(`Sent ISO-TP: ${frames.length} frames (${parsedData.value.length} bytes)`)
    return
  }
  const data = parsedData.value
  if (isotp.value) {
    // ≤7 bytes still goes through ISO-TP as a single frame
    conn.txFrame(parsedId.value, segmentPayload(data)[0], ext.value)
    flash(`Sent ISO-TP single frame (${data.length} bytes)`)
    return
  }
  conn.txFrame(parsedId.value, data, ext.value)
  flash(`Sent 0x${parsedId.value.toString(16).toUpperCase()} × ${repeat.value || 1}`)
}

const presets = PIDS.filter(p => [0x0c, 0x0d, 0x05, 0x0f, 0x11, 0x10, 0x42, 0x2f].includes(p.pid))
function preset(pid: number) {
  idHex.value = '7DF'
  dataHex.value = `02 01 ${pid.toString(16).toUpperCase().padStart(2, '0')} 00 00 00 00 00`
  ext.value = false
}
</script>

<template>
  <div class="grid grid-cols-1 lg:grid-cols-2 gap-3">
    <div class="panel">
      <div class="panel-head"><span class="label">Transmit Raw Frame</span></div>
      <div class="p-4 flex flex-col gap-4">
        <div class="grid grid-cols-2 gap-3">
          <label class="flex flex-col gap-1.5">
            <span class="label">CAN ID (hex)</span>
            <input v-model="idHex" class="mono text-[15px] sm:text-[13px] bg-bg border border-line px-3 py-2.5 min-h-[44px] text-ink" placeholder="7DF" />
          </label>
          <label class="flex flex-col gap-1.5">
            <span class="label">Repeat ×</span>
            <input v-model.number="repeat" type="number" min="0" max="1000" class="mono text-[15px] sm:text-[13px] bg-bg border border-line px-3 py-2.5 min-h-[44px] text-ink" />
          </label>
        </div>
        <label class="flex flex-col gap-1.5">
          <span class="label">Data bytes (hex, space separated, max 8)</span>
          <input v-model="dataHex" class="mono text-[15px] sm:text-[13px] bg-bg border border-line px-3 py-2.5 min-h-[44px] text-ink" placeholder="02 01 0C 00 00 00 00 00" />
        </label>
        <label class="flex items-center gap-2 cursor-pointer">
          <input type="checkbox" v-model="ext" class="accent-[#22d3ee]" />
          <span class="mono text-[10px] tracking-[0.14em] uppercase text-ink-dim">Extended (29-bit) ID</span>
        </label>
        <label class="flex items-center gap-2 cursor-pointer">
          <input type="checkbox" v-model="isotp" class="accent-[#22d3ee]" />
          <span class="mono text-[10px] tracking-[0.14em] uppercase text-ink-dim">ISO-TP — payload is segmented into FF/CF frames (up to 4095 bytes)</span>
        </label>
        <div class="flex items-center gap-3">
          <button
            @click="send"
            :disabled="conn.state !== 'connected'"
            class="mono text-[11px] tracking-[0.14em] uppercase px-5 py-2 border border-live/60 text-live hover:bg-live/10 cursor-pointer transition-colors disabled:opacity-40 disabled:cursor-not-allowed min-h-[44px]"
          >Send Frame</button>
          <span v-if="feedback" class="mono text-[11px] text-ok">{{ feedback }}</span>
        </div>
        <p v-if="conn.state !== 'connected'" class="mono text-[10px] text-warn uppercase tracking-[0.12em]">Connect first — header button or Connection page</p>
      </div>
    </div>

    <div class="flex flex-col gap-3">
      <div class="panel">
        <div class="panel-head"><span class="label">OBD-II Request Presets</span></div>
        <div class="p-3 flex flex-wrap gap-2">
          <button
            v-for="p in presets" :key="p.pid" @click="preset(p.pid)"
            class="mono text-[10px] tracking-[0.08em] px-3 py-1.5 border border-line text-ink-dim hover:text-live hover:border-live/50 cursor-pointer transition-colors"
          >0x{{ p.pid.toString(16).toUpperCase().padStart(2, '0') }} {{ p.name }}</button>
        </div>
      </div>
      <div class="panel border-l-2 border-l-crit">
        <div class="panel-head"><span class="label text-crit">Safety</span></div>
        <div class="p-4 text-[13px] leading-relaxed text-ink-dim">
          OBD-II <span class="mono text-ink">0x7DF</span> requests are read-only queries and safe. Injecting arbitrary frames onto a
          <span class="text-crit">live powertrain bus</span> can interfere with steering, braking and throttle ECUs.
          Keep the ESP32 in listen-only mode until you know exactly what each ID does, and test writes on a bench rig first.
        </div>
      </div>
    </div>
  </div>
</template>
