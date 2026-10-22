<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useCanStore } from '@/stores/can'
import { useDbcStore } from '@/stores/dbc'

const can = useCanStore()
const dbc = useDbcStore()
const filterId = ref('')
const autoScroll = ref(true)
const view = ref<'log' | 'stats'>('log')
const logEl = ref<HTMLElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

const hex = (n: number, w = 3) => n.toString(16).toUpperCase().padStart(w, '0')
const bytes = (d: number[]) => d.map(b => b.toString(16).toUpperCase().padStart(2, '0')).join(' ')
const ascii = (d: number[]) => d.map(b => (b >= 32 && b < 127 ? String.fromCharCode(b) : '·')).join('')

function onDbcFile(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  file.text().then(t => dbc.load(t, file.name))
  ;(e.target as HTMLInputElement).value = ''
}

function decodedSummary(id: number, data: number[]): string | null {
  const d = dbc.decode(id, data)
  if (!d) return null
  return d.signals.map(s => `${s.signal.name}=${s.value % 1 === 0 ? s.value : s.value.toFixed(2)}${s.signal.unit ? ' ' + s.signal.unit : ''}`).join('  ')
}

function msgName(id: number): string | null {
  return dbc.dbc?.messages.get(id)?.name ?? null
}

const filtered = computed(() => {
  const f = filterId.value.trim().toLowerCase()
  if (!f) return can.frames
  return can.frames.filter(fr => hex(fr.id).toLowerCase().includes(f))
})

watch(() => can.frames.length, async () => {
  if (autoScroll.value && logEl.value) {
    await nextTick()
    logEl.value.scrollTop = logEl.value.scrollHeight
  }
})

const statRows = computed(() =>
  [...can.stats.values()].sort((a, b) => b.count - a.count)
)
</script>

<template>
  <div class="grid grid-cols-1 lg:grid-cols-[1fr_320px] gap-3">
    <!-- Frame log -->
    <div class="panel flex flex-col min-h-[480px]">
      <div class="panel-head flex-wrap gap-y-2">
        <div class="flex items-center gap-3">
          <span class="label">Live Frame Log</span>
          <div class="flex border border-line">
            <button @click="view = 'log'" class="mono text-[9px] tracking-[0.14em] uppercase px-3 py-2 min-h-[36px] cursor-pointer" :class="view === 'log' ? 'bg-live/15 text-live' : 'text-ink-dim hover:text-ink'">Log</button>
            <button @click="view = 'stats'" class="mono text-[9px] tracking-[0.14em] uppercase px-3 py-2 min-h-[36px] cursor-pointer border-l border-line" :class="view === 'stats' ? 'bg-live/15 text-live' : 'text-ink-dim hover:text-ink'">By ID</button>
          </div>
        </div>
        <div class="flex items-center gap-2 flex-wrap">
          <input v-model="filterId" placeholder="ID FILTER" class="mono text-[12px] sm:text-[10px] bg-bg border border-line px-2 py-2 min-h-[36px] text-ink placeholder:text-ink-faint w-28" />
          <input ref="fileInput" type="file" accept=".dbc" class="hidden" @change="onDbcFile" />
          <button
            v-if="!dbc.dbc" @click="fileInput?.click()"
            class="mono text-[9px] tracking-[0.14em] uppercase px-3 py-2 min-h-[36px] border border-live/40 text-live hover:bg-live/10 cursor-pointer transition-colors"
          >Load DBC</button>
          <button
            v-else @click="dbc.unload()"
            class="mono text-[9px] tracking-[0.14em] uppercase px-3 py-2 min-h-[36px] border border-ok/40 text-ok hover:bg-ok/10 cursor-pointer transition-colors"
          >{{ dbc.fileName }} ✕</button>
          <button @click="can.paused = !can.paused" class="mono text-[9px] tracking-[0.14em] uppercase px-3 py-2 min-h-[36px] border cursor-pointer transition-colors" :class="can.paused ? 'border-warn/50 text-warn hover:bg-warn/10' : 'border-line text-ink-dim hover:text-ink'">{{ can.paused ? 'Resume' : 'Pause' }}</button>
          <button @click="can.clear()" class="mono text-[9px] tracking-[0.14em] uppercase px-3 py-2 min-h-[36px] border border-line text-ink-dim hover:text-ink cursor-pointer transition-colors">Clear</button>
          <label class="flex items-center gap-1.5 cursor-pointer py-2">
            <input type="checkbox" v-model="autoScroll" class="accent-[#22d3ee] w-4 h-4" />
            <span class="mono text-[9px] text-ink-dim uppercase">Scroll</span>
          </label>
        </div>
      </div>

      <div v-if="view === 'log'" ref="logEl" class="flex-1 overflow-auto mono text-[9.5px] sm:text-[10.5px] leading-[1.7] p-2 max-h-[65vh]">
        <div v-for="(f, i) in filtered" :key="i" class="px-1 hover:bg-panel2">
          <div class="flex gap-3 whitespace-nowrap">
            <span class="text-ink-faint">{{ new Date(f.ts).toLocaleTimeString('en-GB') }}.{{ String(f.ts % 1000).padStart(3, '0') }}</span>
            <span :class="f.dir === 'tx' ? 'text-mag' : 'text-live'">{{ f.dir === 'tx' ? 'TX' : 'RX' }}</span>
            <span class="text-ink">{{ hex(f.id) }}{{ f.ext ? 'x' : '' }}</span>
            <span class="text-ink-dim">[{{ f.dlc }}]</span>
            <span class="text-ok">{{ bytes(f.data) }}</span>
            <span class="text-ink-faint">{{ ascii(f.data) }}</span>
          </div>
          <div v-if="decodedSummary(f.id, f.data)" class="pl-[120px] text-warn/80 whitespace-normal">
            {{ msgName(f.id) }}: {{ decodedSummary(f.id, f.data) }}
          </div>
        </div>
        <div v-if="!filtered.length" class="p-8 text-center">
          <span class="text-ink-faint uppercase tracking-[0.14em]">No frames — connect or adjust the ID filter</span>
        </div>
      </div>

      <div v-else class="flex-1 overflow-y-auto max-h-[65vh]">
        <table class="w-full text-left">
          <thead class="sticky top-0 bg-panel2">
            <tr class="border-b border-line">
              <th class="label px-3 py-2 font-normal">ID</th>
              <th class="label px-3 py-2 font-normal">Count</th>
              <th class="label px-3 py-2 font-normal">Rate</th>
              <th class="label px-3 py-2 font-normal">Dir</th>
              <th class="label px-3 py-2 font-normal">Last data</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in statRows" :key="s.id" class="border-b border-line/50 hover:bg-panel2">
              <td class="mono text-[11px] px-3 py-1.5 text-live">{{ hex(s.id) }}{{ s.ext ? 'x' : '' }}<span v-if="msgName(s.id)" class="text-warn/80 block">{{ msgName(s.id) }}</span></td>
              <td class="mono text-[11px] px-3 py-1.5 text-ink">{{ s.count.toLocaleString() }}</td>
              <td class="mono text-[11px] px-3 py-1.5 text-ink-dim">{{ s.rate.toFixed(1) }} Hz</td>
              <td class="mono text-[11px] px-3 py-1.5" :class="s.dir === 'tx' ? 'text-mag' : 'text-ok'">{{ s.dir.toUpperCase() }}</td>
              <td class="mono text-[11px] px-3 py-1.5 text-ink-dim">{{ bytes(s.lastData) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Bus stats sidebar -->
    <div class="flex flex-col gap-3">
      <div class="panel">
        <div class="panel-head"><span class="label">Bus Statistics</span></div>
        <div class="p-3 flex flex-col gap-2.5">
          <div class="flex justify-between"><span class="label">Total frames</span><span class="mono text-[12px] text-ink">{{ can.total.toLocaleString() }}</span></div>
          <div class="flex justify-between"><span class="label">Avg rate</span><span class="mono text-[12px] text-live">{{ can.busLoad }} f/s</span></div>
          <div class="flex justify-between"><span class="label">Unique IDs</span><span class="mono text-[12px] text-ink">{{ can.uniqueIds }}</span></div>
          <div class="flex justify-between"><span class="label">Log buffer</span><span class="mono text-[12px] text-ink-dim">{{ can.frames.length }} / 500</span></div>
          <div class="flex justify-between"><span class="label">State</span><span class="mono text-[12px]" :class="can.paused ? 'text-warn' : 'text-ok'">{{ can.paused ? 'PAUSED' : 'STREAMING' }}</span></div>
        </div>
      </div>
      <div class="panel">
        <div class="panel-head"><span class="label">Known Traffic (SIM)</span></div>
        <div class="p-3 mono text-[10px] leading-[1.9] text-ink-dim">
          <div><span class="text-live">0x0C1</span> — engine ECU: rpm, throttle</div>
          <div><span class="text-live">0x2B0</span> — vehicle speed</div>
          <div><span class="text-live">0x0C2</span> — steering angle</div>
          <div><span class="text-live">0x1A0</span> — ABS wheel speeds</div>
          <div><span class="text-live">0x321</span> — gearbox state</div>
          <div><span class="text-mag">0x7DF</span> — OBD-II broadcast request</div>
          <div><span class="text-mag">0x7E8</span> — ECM OBD-II response</div>
        </div>
      </div>
    </div>
  </div>
</template>
