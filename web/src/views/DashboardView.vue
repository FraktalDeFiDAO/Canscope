<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useObdStore } from '@/stores/obd'
import { useCanStore } from '@/stores/can'
import ArcGauge from '@/components/ArcGauge.vue'
import BarGauge from '@/components/BarGauge.vue'

const obd = useObdStore()
const can = useCanStore()

// sparkline history for RPM/speed
const histLen = 60
const rpmHist = ref<number[]>([])
const spdHist = ref<number[]>([])
let timer: ReturnType<typeof setInterval>
onMounted(() => {
  timer = setInterval(() => {
    rpmHist.value = [...rpmHist.value.slice(-histLen + 1), obd.get(0x0c) ?? 0]
    spdHist.value = [...spdHist.value.slice(-histLen + 1), obd.get(0x0d) ?? 0]
  }, 200)
})
onUnmounted(() => clearInterval(timer))

function spark(data: number[], max: number, w = 200, h = 36) {
  if (data.length < 2) return ''
  return data.map((v, i) => {
    const x = (i / (histLen - 1)) * w
    const y = h - Math.min(1, Math.max(0, v / max)) * h
    return `${i ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
}

const rpm = computed(() => obd.get(0x0c))
const spd = computed(() => obd.get(0x0d))
</script>

<template>
  <div class="flex flex-col gap-3">
    <!-- Primary cluster -->
    <div class="grid grid-cols-1 md:grid-cols-3 gap-3">
      <ArcGauge :value="rpm" :min="0" :max="8000" label="Engine RPM" unit="rpm" :warn-from="5500" :crit-from="6800" />
      <ArcGauge :value="spd" :min="0" :max="240" label="Vehicle Speed" unit="km/h" :warn-from="140" :crit-from="200" />
      <ArcGauge :value="obd.get(0x05)" :min="-40" :max="140" label="Coolant Temp" unit="°C" :warn-from="105" :crit-from="115" />
    </div>

    <!-- Sparklines -->
    <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
      <div class="panel">
        <div class="panel-head"><span class="label">RPM — last 12s</span><span class="mono text-[9px] text-ink-faint">{{ rpm?.toFixed(0) ?? '——' }}</span></div>
        <svg viewBox="0 0 200 36" class="w-full h-12 p-1" preserveAspectRatio="none" aria-hidden="true">
          <path :d="spark(rpmHist, 8000)" fill="none" stroke="#22d3ee" stroke-width="1.2" vector-effect="non-scaling-stroke"/>
        </svg>
      </div>
      <div class="panel">
        <div class="panel-head"><span class="label">Speed — last 12s</span><span class="mono text-[9px] text-ink-faint">{{ spd?.toFixed(0) ?? '——' }} km/h</span></div>
        <svg viewBox="0 0 200 36" class="w-full h-12 p-1" preserveAspectRatio="none" aria-hidden="true">
          <path :d="spark(spdHist, 240)" fill="none" stroke="#34d399" stroke-width="1.2" vector-effect="non-scaling-stroke"/>
        </svg>
      </div>
    </div>

    <!-- Secondary readouts -->
    <div class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
      <BarGauge :value="obd.get(0x04)" :min="0" :max="100" label="Engine Load" unit="%" />
      <BarGauge :value="obd.get(0x11)" :min="0" :max="100" label="Throttle" unit="%" />
      <BarGauge :value="obd.get(0x0f)" :min="-40" :max="100" label="Intake Air" unit="°C" />
      <BarGauge :value="obd.get(0x10)" :min="0" :max="60" label="MAF Rate" unit="g/s" />
      <BarGauge :value="obd.get(0x0b)" :min="0" :max="255" label="MAP" unit="kPa" :decimals="0" />
      <BarGauge :value="obd.get(0x0e)" :min="-20" :max="50" label="Timing Adv" unit="°" />
      <BarGauge :value="obd.get(0x42)" :min="10" :max="16" label="Module Voltage" unit="V" :decimals="2" :warn-from="15" />
      <BarGauge :value="obd.get(0x2f)" :min="0" :max="100" label="Fuel Level" unit="%" />
      <BarGauge :value="obd.get(0x5e)" :min="0" :max="20" label="Fuel Rate" unit="L/h" />
      <BarGauge :value="obd.get(0x5c)" :min="-40" :max="150" label="Oil Temp" unit="°C" :warn-from="125" />
      <BarGauge :value="obd.get(0x06)" :min="-25" :max="25" label="STFT B1" unit="%" />
      <BarGauge :value="obd.get(0x07)" :min="-25" :max="25" label="LTFT B1" unit="%" />
      <BarGauge :value="obd.get(0x3c)" :min="0" :max="900" label="Catalyst B1S1" unit="°C" :decimals="0" :warn-from="750" />
      <BarGauge :value="obd.get(0x33)" :min="80" :max="110" label="Baro" unit="kPa" :decimals="0" />
      <BarGauge :value="obd.get(0x62)" :min="-50" :max="100" label="Actual Torque" unit="%" />
    </div>

    <!-- Bus vitals -->
    <div class="panel">
      <div class="panel-head"><span class="label">Bus Vitals</span></div>
      <div class="grid grid-cols-2 md:grid-cols-4 divide-x divide-line">
        <div class="px-4 py-3"><div class="label">Frames total</div><div class="mono text-lg text-ink mt-1">{{ can.total.toLocaleString() }}</div></div>
        <div class="px-4 py-3"><div class="label">Frame rate</div><div class="mono text-lg text-live mt-1">{{ can.busLoad }} f/s</div></div>
        <div class="px-4 py-3"><div class="label">Unique IDs</div><div class="mono text-lg text-ink mt-1">{{ can.uniqueIds }}</div></div>
        <div class="px-4 py-3"><div class="label">Live PIDs</div><div class="mono text-lg text-ok mt-1">{{ obd.supportedPids.length }}</div></div>
      </div>
    </div>
  </div>
</template>
