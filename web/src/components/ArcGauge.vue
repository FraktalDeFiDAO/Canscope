<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  value: number | null
  min: number
  max: number
  label: string
  unit: string
  warnFrom?: number
  critFrom?: number
  decimals?: number
  size?: number
}>(), { decimals: 0, size: 168 })

const R = 62
const CX = 80, CY = 78
const START = 135, SWEEP = 270

function polar(deg: number, r: number): [number, number] {
  const a = ((deg - 90) * Math.PI) / 180
  return [CX + r * Math.cos(a), CY + r * Math.sin(a)]
}

function arc(fromDeg: number, toDeg: number, r: number) {
  const [x1, y1] = polar(fromDeg, r)
  const [x2, y2] = polar(toDeg, r)
  const large = toDeg - fromDeg > 180 ? 1 : 0
  return `M ${x1} ${y1} A ${r} ${r} 0 ${large} 1 ${x2} ${y2}`
}

const pct = computed(() => {
  if (props.value == null || !Number.isFinite(props.value)) return 0
  return Math.min(1, Math.max(0, (props.value - props.min) / (props.max - props.min)))
})

const angle = computed(() => START + pct.value * SWEEP)

const stateColor = computed(() => {
  const v = props.value
  if (v == null) return '#4c5a75'
  if (props.critFrom != null && v >= props.critFrom) return '#f87171'
  if (props.warnFrom != null && v >= props.warnFrom) return '#fbbf24'
  return '#22d3ee'
})

const ticks = computed(() => {
  const out: Array<{ x1: number; y1: number; x2: number; y2: number; major: boolean }> = []
  for (let i = 0; i <= 24; i++) {
    const deg = START + (i / 24) * SWEEP
    const major = i % 6 === 0
    const [x1, y1] = polar(deg, R + (major ? 6 : 3))
    const [x2, y2] = polar(deg, R + 1)
    out.push({ x1, y1, x2, y2, major })
  }
  return out
})

const display = computed(() => {
  if (props.value == null || !Number.isFinite(props.value)) return '——'
  return props.value.toFixed(props.decimals)
})
</script>

<template>
  <div class="panel relative flex flex-col" :style="{ width: '100%' }">
    <div class="panel-head">
      <span class="label">{{ label }}</span>
      <span class="mono text-[9px] text-ink-faint">{{ min }}–{{ max }}</span>
    </div>
    <svg :viewBox="`0 0 160 118`" class="w-full" role="img" :aria-label="`${label}: ${display} ${unit}`">
      <path :d="arc(START, START + SWEEP, R)" stroke="#1a2540" stroke-width="5" fill="none" stroke-linecap="round"/>
      <path
        v-if="pct > 0"
        :d="arc(START, angle, R)"
        :stroke="stateColor" stroke-width="5" fill="none" stroke-linecap="round"
        :style="{ filter: `drop-shadow(0 0 4px ${stateColor}88)` }"
      />
      <line
        v-for="(t, i) in ticks" :key="i"
        :x1="t.x1" :y1="t.y1" :x2="t.x2" :y2="t.y2"
        :stroke="t.major ? '#4c5a75' : '#243356'" :stroke-width="t.major ? 1.5 : 1"
      />
      <g class="gauge-needle" :style="{ transform: `rotate(${angle}deg)`, transformOrigin: `${CX}px ${CY}px` }">
        <line :x1="CX" :y1="CY" :x2="CX" :y2="CY - R + 14" :stroke="stateColor" stroke-width="2"/>
        <circle :cx="CX" :cy="CY" r="4" :fill="stateColor"/>
      </g>
      <text :x="CX" :y="CY + 26" text-anchor="middle" class="mono" fill="#cbc6b9" font-size="17" font-weight="600">{{ display }}</text>
      <text :x="CX" :y="CY + 38" text-anchor="middle" class="mono" fill="#4c5a75" font-size="8" letter-spacing="2">{{ unit.toUpperCase() }}</text>
    </svg>
  </div>
</template>
