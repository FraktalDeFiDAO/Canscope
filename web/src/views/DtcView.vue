<script setup lang="ts">
import { ref } from 'vue'
import { useObdStore } from '@/stores/obd'
import { useConnectionStore } from '@/stores/connection'
import { DTC_DESCRIPTIONS, pidById } from '@/lib/obd'

const obd = useObdStore()
const conn = useConnectionStore()
const confirmClear = ref(false)
const freezePid = ref(0x02)

function pidName(pid: number): string {
  return pidById(pid)?.name ?? 'Unknown PID'
}

function doClear() {
  conn.clearDtcs()
  confirmClear.value = false
}
</script>

<template>
  <div class="flex flex-col gap-3 max-w-3xl">
    <div class="panel">
      <div class="panel-head">
        <span class="label">Diagnostic Trouble Codes</span>
        <div class="flex gap-2 flex-wrap">
          <button @click="conn.readDtcs()" :disabled="conn.state !== 'connected'" class="mono text-[9px] tracking-[0.14em] uppercase px-3 py-1 border border-live/50 text-live hover:bg-live/10 cursor-pointer transition-colors disabled:opacity-40">Read (Mode 03)</button>
          <button @click="conn.readPendingDtcs()" :disabled="conn.state !== 'connected'" class="mono text-[9px] tracking-[0.14em] uppercase px-3 py-1 border border-warn/50 text-warn hover:bg-warn/10 cursor-pointer transition-colors disabled:opacity-40">Pending (07)</button>
          <button @click="conn.readPermanentDtcs()" :disabled="conn.state !== 'connected'" class="mono text-[9px] tracking-[0.14em] uppercase px-3 py-1 border border-mag/50 text-mag hover:bg-mag/10 cursor-pointer transition-colors disabled:opacity-40">Permanent (0A)</button>
          <button @click="confirmClear = true" :disabled="conn.state !== 'connected' || !obd.dtcs.length" class="mono text-[9px] tracking-[0.14em] uppercase px-3 py-1 border border-crit/50 text-crit hover:bg-crit/10 cursor-pointer transition-colors disabled:opacity-40">Clear (Mode 04)</button>
        </div>
      </div>

      <div v-if="obd.dtcs.length" class="divide-y divide-line/50">
        <div v-for="code in obd.dtcs" :key="code" class="flex items-center gap-4 px-4 py-3 border-l-2 border-l-crit">
          <span class="mono text-lg font-semibold text-crit w-20">{{ code }}</span>
          <div>
            <div class="text-[13px] text-ink">{{ DTC_DESCRIPTIONS[code] ?? 'No description in local database' }}</div>
            <div class="mono text-[9px] tracking-[0.14em] uppercase text-ink-faint mt-0.5">
              {{ code[0] === 'P' ? 'Powertrain' : code[0] === 'C' ? 'Chassis' : code[0] === 'B' ? 'Body' : 'Network' }}
            </div>
          </div>
        </div>
      </div>
      <div v-else class="p-10 text-center">
        <span class="mono text-[11px] text-ok uppercase tracking-[0.16em]">No stored DTCs — MIL off</span>
        <p class="mono text-[10px] text-ink-faint mt-2 uppercase tracking-[0.1em]">Press Read to query the ECU</p>
      </div>
    </div>

    <!-- Pending DTCs (Mode 07) -->
    <div v-if="obd.pendingDtcs.length" class="panel">
      <div class="panel-head"><span class="label text-warn">Pending DTCs — Mode 07</span></div>
      <div class="divide-y divide-line/50">
        <div v-for="code in obd.pendingDtcs" :key="'p' + code" class="flex items-center gap-4 px-4 py-3 border-l-2 border-l-warn">
          <span class="mono text-lg font-semibold text-warn w-20">{{ code }}</span>
          <div>
            <div class="text-[13px] text-ink">{{ DTC_DESCRIPTIONS[code] ?? 'No description in local database' }}</div>
            <div class="mono text-[9px] tracking-[0.14em] uppercase text-ink-faint mt-0.5">Detected but not yet confirmed — may self-clear</div>
          </div>
        </div>
      </div>
    </div>

    <!-- Permanent DTCs (Mode 0A) -->
    <div v-if="obd.permanentDtcs.length" class="panel">
      <div class="panel-head"><span class="label text-mag">Permanent DTCs — Mode 0A</span></div>
      <div class="divide-y divide-line/50">
        <div v-for="code in obd.permanentDtcs" :key="'x' + code" class="flex items-center gap-4 px-4 py-3 border-l-2 border-l-mag">
          <span class="mono text-lg font-semibold text-mag w-20">{{ code }}</span>
          <div>
            <div class="text-[13px] text-ink">{{ DTC_DESCRIPTIONS[code] ?? 'No description in local database' }}</div>
            <div class="mono text-[9px] tracking-[0.14em] uppercase text-ink-faint mt-0.5">Cannot be cleared — only the ECU clears it after self-healing</div>
          </div>
        </div>
      </div>
    </div>

    <!-- Freeze frame (Mode 02) -->
    <div class="panel">
      <div class="panel-head">
        <span class="label">Freeze Frame — Mode 02</span>
        <button @click="freezePid = 0x02; conn.readFreezeFrame(freezePid)" :disabled="conn.state !== 'connected'" class="mono text-[9px] tracking-[0.14em] uppercase px-3 py-1 border border-live/50 text-live hover:bg-live/10 cursor-pointer transition-colors disabled:opacity-40">Read</button>
      </div>
      <div v-if="obd.freezeFrames.size" class="divide-y divide-line/50">
        <div v-for="[pid, e] in obd.freezeFrames" :key="pid" class="flex items-center gap-4 px-4 py-2.5">
          <span class="mono text-[11px] text-live w-14">{{ '0x' + pid.toString(16).toUpperCase().padStart(2, '0') }}</span>
          <span class="text-[12px] text-ink-dim flex-1">{{ pidName(pid) }}</span>
          <span class="mono text-[13px] text-ink">{{ Number.isFinite(e.value) ? e.value.toFixed(1) : '—' }}</span>
        </div>
      </div>
      <div v-else class="p-6 text-center">
        <span class="mono text-[10px] text-ink-faint uppercase tracking-[0.14em]">Snapshot of sensor values when the DTC set — read after a Mode 03 fault</span>
      </div>
    </div>

    <!-- On-board monitoring (Mode 06) -->
    <div class="panel">
      <div class="panel-head">
        <span class="label">On-Board Monitoring — Mode 06</span>
        <button @click="conn.readMonitors()" :disabled="conn.state !== 'connected'" class="mono text-[9px] tracking-[0.14em] uppercase px-3 py-1 border border-live/50 text-live hover:bg-live/10 cursor-pointer transition-colors disabled:opacity-40">Read</button>
      </div>
      <div v-if="obd.monitors.length" class="divide-y divide-line/50 max-h-64 overflow-auto">
        <div v-for="(m, i) in obd.monitors" :key="i" class="flex items-center gap-4 px-4 py-2 mono text-[11px]">
          <span class="text-live w-16">MID {{ '0x' + m.mid.toString(16).toUpperCase().padStart(2, '0') }}</span>
          <span class="text-ink-dim w-16">TID {{ '0x' + m.tid.toString(16).toUpperCase().padStart(2, '0') }}</span>
          <span class="text-ink">{{ m.raw.map(b => b.toString(16).toUpperCase().padStart(2, '0')).join(' ') }}</span>
        </div>
      </div>
      <div v-else class="p-6 text-center">
        <span class="mono text-[10px] text-ink-faint uppercase tracking-[0.14em]">Misfire / catalyst / O₂ test counters — raw values (decode is vehicle-specific)</span>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head"><span class="label">Reference</span></div>
      <div class="p-4 mono text-[11px] leading-[2] text-ink-dim">
        <div><span class="text-ink">P0xxx / P2xxx</span> — powertrain (engine, transmission, emissions)</div>
        <div><span class="text-ink">C0xxx</span> — chassis (ABS, steering, suspension)</div>
        <div><span class="text-ink">B0xxx</span> — body (airbags, climate, lighting)</div>
        <div><span class="text-ink">U0xxx</span> — network (module communication)</div>
        <div class="pt-2 text-ink-faint">x<span class="text-ok">0</span>xx = generic SAE · x<span class="text-warn">1</span>xx = manufacturer-specific</div>
      </div>
    </div>

    <!-- Confirm modal -->
    <div v-if="confirmClear" class="fixed inset-0 z-50 flex items-center justify-center bg-bg/80 backdrop-blur-sm" @click.self="confirmClear = false" @keydown.esc="confirmClear = false">
      <div class="panel max-w-sm w-full mx-4 border-crit/40" role="dialog" aria-modal="true" aria-label="Confirm DTC clear">
        <div class="panel-head"><span class="label text-crit">Clear all DTCs?</span></div>
        <div class="p-4 text-[13px] text-ink-dim leading-relaxed">
          This sends Mode 04 to the ECU: it erases stored codes, freeze-frame data and readiness monitors.
          The light may come straight back if the fault is still present.
        </div>
        <div class="flex justify-end gap-2 px-4 pb-4">
          <button @click="confirmClear = false" class="mono text-[10px] tracking-[0.14em] uppercase px-4 py-2 border border-line text-ink-dim hover:text-ink cursor-pointer">Cancel</button>
          <button @click="doClear" class="mono text-[10px] tracking-[0.14em] uppercase px-4 py-2 border border-crit/60 text-crit hover:bg-crit/10 cursor-pointer">Clear codes</button>
        </div>
      </div>
    </div>
  </div>
</template>
