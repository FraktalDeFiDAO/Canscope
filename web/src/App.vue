<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useConnectionStore } from '@/stores/connection'
import { useCanStore } from '@/stores/can'
import { useObdStore } from '@/stores/obd'
import StatusLed from '@/components/StatusLed.vue'
import TickerBar from '@/components/TickerBar.vue'

const conn = useConnectionStore()
const can = useCanStore()
const obd = useObdStore()
const route = useRoute()

const now = ref(Date.now())
let clockTimer: ReturnType<typeof setInterval>
onMounted(() => {
  conn.connect()
  clockTimer = setInterval(() => (now.value = Date.now()), 1000)
})
onUnmounted(() => clearInterval(clockTimer))

const nav = [
  {
    to: '/', label: 'Gauges', long: 'Dashboard',
    icon: '<circle cx="12" cy="14" r="8"/><path d="M12 14 L16 9"/><path d="M4.5 14 h2 M17.5 14 h2 M12 6.5 v2"/>',
  },
  {
    to: '/pids', label: 'PIDs', long: 'PID Explorer',
    icon: '<path d="M4 6 h16 M4 12 h16 M4 18 h10"/><circle cx="19" cy="18" r="2"/>',
  },
  {
    to: '/sniffer', label: 'Sniffer', long: 'CAN Sniffer',
    icon: '<path d="M4 17 L8 17 L10 8 L12 13 L14 10 L16 17 L20 17"/>',
  },
  {
    to: '/transmit', label: 'Transmit', long: 'Transmit',
    icon: '<path d="M5 12 h12 M13 6 l6 6 -6 6"/>',
  },
  {
    to: '/dtc', label: 'DTC', long: 'DTC',
    icon: '<path d="M12 3 L21 19 H3 Z"/><line x1="12" y1="9" x2="12" y2="13.5"/><circle cx="12" cy="16.4" r="0.4"/>',
  },
  {
    to: '/sessions', label: 'Sessions', long: 'Sessions',
    icon: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="3" fill="currentColor" stroke="none"/>',
  },
  {
    to: '/connection', label: 'Link', long: 'Connection',
    icon: '<path d="M12 19 a1 1 0 1 0 0.01 0"/><path d="M5 12 a10 10 0 0 1 14 0 M8 15.5 a5.5 5.5 0 0 1 8 0"/>',
  },
]

const ledStatus = computed<'ok' | 'warn' | 'crit' | 'off'>(() => {
  if (conn.state === 'connected') return 'ok'
  if (conn.state === 'connecting') return 'warn'
  if (conn.state === 'error') return 'crit'
  return 'off'
})

const clock = computed(() => new Date(now.value).toLocaleTimeString('en-GB'))
</script>

<template>
  <div class="bg-depth min-h-dvh flex flex-col">
    <!-- Header -->
    <header class="border-b border-line bg-panel/80 backdrop-blur sticky top-0 z-40">
      <div class="flex items-stretch">
        <RouterLink to="/" class="flex items-center gap-2 px-3 py-2.5 border-r border-line hover:bg-panel2 transition-colors cursor-pointer min-h-[48px]">
          <svg width="24" height="24" viewBox="0 0 26 26" fill="none" aria-hidden="true">
            <rect x="1" y="1" width="24" height="24" stroke="#22d3ee" stroke-width="1.5"/>
            <path d="M5 18 L11 18 L13 8 L15 14 L21 14" stroke="#34d399" stroke-width="1.8" fill="none" stroke-linejoin="round"/>
          </svg>
          <div>
            <div class="mono text-[13px] font-semibold tracking-[0.22em] text-ink uppercase">CAN<span class="text-live">Scope</span></div>
            <div class="mono text-[9px] tracking-[0.18em] text-ink-faint uppercase hidden sm:block">ESP32 · TWAI · OBD-II</div>
          </div>
        </RouterLink>

        <nav class="hidden md:flex items-stretch flex-1" aria-label="Main navigation">
          <RouterLink
            v-for="n in nav" :key="n.to" :to="n.to"
            class="mono text-[10px] tracking-[0.12em] uppercase px-4 flex items-center min-h-[48px] whitespace-nowrap border-r border-line transition-colors cursor-pointer hover:text-live"
            :class="route.path === n.to ? 'text-live bg-panel2 shadow-[inset_0_-2px_0_#22d3ee]' : 'text-ink-dim'"
          >{{ n.long }}</RouterLink>
        </nav>
        <div class="flex-1 md:hidden" />

        <div class="flex items-center gap-2 sm:gap-4 px-2.5 sm:px-4 border-l border-line">
          <div class="hidden lg:flex items-center gap-2">
            <span class="label">Bus</span>
            <span class="mono text-[11px] text-ink">{{ can.busLoad }} f/s</span>
            <StatusLed
              v-if="conn.busStatus"
              :status="conn.busStatus.state === 'running' ? 'ok' : conn.busStatus.state === 'recovering' ? 'warn' : 'crit'"
              :pulse="conn.busStatus.state !== 'running'"
            />
            <span v-if="conn.busStatus && conn.busStatus.state !== 'running'" class="mono text-[9px] tracking-[0.1em] uppercase text-crit">{{ conn.busStatus.state }}</span>
          </div>
          <div class="hidden md:flex items-center gap-2">
            <span class="label">MIL</span>
            <StatusLed :status="obd.milOn ? 'crit' : 'off'" :pulse="obd.milOn" />
          </div>
          <div class="flex items-center gap-2">
            <span class="label hidden sm:inline">{{ conn.mode === 'simulator' ? 'SIM' : conn.mode === 'websocket' ? 'WS' : 'BLE' }}</span>
            <StatusLed :status="ledStatus" :pulse="conn.state === 'connecting'" />
          </div>
          <span class="mono text-[11px] text-ink-dim hidden xl:inline">{{ clock }}</span>
          <button
            @click="conn.state === 'connected' ? conn.disconnect() : conn.connect()"
            class="mono text-[10px] tracking-[0.12em] uppercase px-3 py-2.5 min-h-[44px] border cursor-pointer transition-colors"
            :class="conn.state === 'connected'
              ? 'border-crit/50 text-crit hover:bg-crit/10'
              : 'border-ok/50 text-ok hover:bg-ok/10'"
          >{{ conn.state === 'connected' ? 'Disconnect' : 'Connect' }}</button>
        </div>
      </div>
    </header>

    <TickerBar v-if="obd.dtcs.length" />

    <main class="flex-1 p-3 md:p-4 pb-24 md:pb-4 max-w-[1600px] w-full mx-auto">
      <RouterView />
    </main>

    <!-- Mobile bottom navigation -->
    <nav
      class="md:hidden fixed bottom-0 inset-x-0 z-40 bg-panel/95 backdrop-blur border-t border-line grid grid-cols-7"
      style="padding-bottom: env(safe-area-inset-bottom)"
      aria-label="Main navigation"
    >
      <RouterLink
        v-for="n in nav" :key="n.to" :to="n.to"
        class="flex flex-col items-center justify-center gap-1 pt-2.5 pb-2 min-h-[56px] cursor-pointer transition-colors"
        :class="route.path === n.to ? 'text-live' : 'text-ink-faint active:text-ink-dim'"
      >
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" v-html="n.icon" aria-hidden="true" />
        <span class="mono text-[8px] tracking-[0.12em] uppercase">{{ n.label }}</span>
        <span class="h-0.5 w-6 rounded-full transition-colors" :class="route.path === n.to ? 'bg-live' : 'bg-transparent'" />
      </RouterLink>
    </nav>

    <footer class="hidden md:flex border-t border-line px-4 py-2 flex-wrap gap-x-6 gap-y-1">
      <span class="mono text-[9px] tracking-[0.14em] uppercase text-ink-faint">SN65HVD230 · TX GPIO5 / RX GPIO4</span>
      <span class="mono text-[9px] tracking-[0.14em] uppercase text-ink-faint">OBD-II pins 6/14 CAN-H/L</span>
      <span class="mono text-[9px] tracking-[0.14em] uppercase text-warn/70 flex items-center gap-1.5">
        <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true"><path d="M5 0.8 L9.4 9 H0.6 Z" fill="none" stroke="#fbbf24" stroke-width="1"/><line x1="5" y1="3.6" x2="5" y2="6.2" stroke="#fbbf24"/><circle cx="5" cy="7.6" r="0.6" fill="#fbbf24"/></svg>
        Never inject frames into a live powertrain bus
      </span>
    </footer>
  </div>
</template>
