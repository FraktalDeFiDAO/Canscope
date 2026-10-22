<script setup lang="ts">
import { computed, ref } from 'vue'
import { PIDS, FUEL_TYPES } from '@/lib/obd'
import { useObdStore } from '@/stores/obd'
import StatusLed from '@/components/StatusLed.vue'

const obd = useObdStore()
const query = ref('')
const onlyLive = ref(false)

const rows = computed(() => {
  const q = query.value.trim().toLowerCase()
  return PIDS.filter(p => {
    if (onlyLive.value && !obd.values.has(p.pid)) return false
    if (!q) return true
    return p.name.toLowerCase().includes(q) || p.pid.toString(16).padStart(2, '0').includes(q)
  })
})

function fmt(pid: number): string {
  const e = obd.values.get(pid)
  if (!e || !Number.isFinite(e.value)) return '——'
  if (pid === 0x51) return FUEL_TYPES[Math.round(e.value)] ?? `Type ${e.value}`
  return Math.abs(e.value) >= 100 ? e.value.toFixed(0) : e.value.toFixed(2)
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <div class="panel">
      <div class="panel-head flex-wrap gap-y-2">
        <span class="label">OBD-II Mode 01 PID Database — {{ PIDS.length }} definitions</span>
        <div class="flex items-center gap-3 flex-wrap">
          <label class="flex items-center gap-2 cursor-pointer py-1.5">
            <input type="checkbox" v-model="onlyLive" class="accent-[#22d3ee] w-4 h-4" />
            <span class="mono text-[9px] tracking-[0.14em] uppercase text-ink-dim">Live only</span>
          </label>
          <input
            v-model="query" type="search" placeholder="FILTER BY NAME OR HEX…"
            class="mono text-[14px] sm:text-[11px] bg-bg border border-line px-3 py-2 min-h-[40px] text-ink placeholder:text-ink-faint w-full sm:w-56"
          />
        </div>
      </div>
      <div class="overflow-x-auto">
        <table class="w-full text-left">
          <thead>
            <tr class="border-b border-line">
              <th class="label px-3 py-2 font-normal">PID</th>
              <th class="label px-3 py-2 font-normal">Parameter</th>
              <th class="label px-3 py-2 font-normal hidden md:table-cell">Bytes</th>
              <th class="label px-3 py-2 font-normal hidden md:table-cell">Range</th>
              <th class="label px-3 py-2 font-normal">Value</th>
              <th class="label px-3 py-2 font-normal hidden sm:table-cell">Unit</th>
              <th class="label px-3 py-2 font-normal">Status</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="p in rows" :key="p.pid"
              class="border-b border-line/50 hover:bg-panel2 transition-colors"
              :class="{ 'opacity-45': !obd.values.has(p.pid) }"
            >
              <td class="mono text-[11px] px-3 py-2 text-live">0x{{ p.pid.toString(16).toUpperCase().padStart(2, '0') }}</td>
              <td class="text-[12px] px-3 py-2 text-ink">{{ p.name }}</td>
              <td class="mono text-[11px] px-3 py-2 text-ink-dim hidden md:table-cell">{{ p.bytes }}</td>
              <td class="mono text-[11px] px-3 py-2 text-ink-faint hidden md:table-cell">{{ p.min }}–{{ p.max }}</td>
              <td class="mono text-[12px] px-3 py-2 font-semibold" :class="obd.values.has(p.pid) ? 'text-ok' : 'text-ink-faint'">{{ fmt(p.pid) }}</td>
              <td class="mono text-[10px] px-3 py-2 text-ink-dim hidden sm:table-cell">{{ p.unit || '—' }}</td>
              <td class="px-3 py-2"><StatusLed :status="obd.values.has(p.pid) ? 'ok' : 'off'" :pulse="obd.values.has(p.pid)" /></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="!rows.length" class="p-8 text-center border border-dashed border-line m-3">
        <span class="mono text-[11px] text-ink-faint uppercase tracking-[0.14em]">No PIDs match the filter</span>
      </div>
    </div>
    <p class="mono text-[10px] text-ink-faint tracking-[0.1em] uppercase">
      Simulator mode populates a representative subset. Against a real ECU, supported PIDs are read from the 0x00/0x20/0x40… bitmasks and the rest stay dimmed.
    </p>
  </div>
</template>
