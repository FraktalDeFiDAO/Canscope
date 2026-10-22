<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  value: number | null
  min: number
  max: number
  label: string
  unit: string
  decimals?: number
  warnFrom?: number
  critFrom?: number
}>(), { decimals: 1 })

const pct = computed(() => {
  if (props.value == null || !Number.isFinite(props.value)) return 0
  return Math.min(100, Math.max(0, ((props.value - props.min) / (props.max - props.min)) * 100))
})

const color = computed(() => {
  const v = props.value
  if (v == null) return '#4c5a75'
  if (props.critFrom != null && v >= props.critFrom) return '#f87171'
  if (props.warnFrom != null && v >= props.warnFrom) return '#fbbf24'
  return '#34d399'
})

const display = computed(() =>
  props.value == null || !Number.isFinite(props.value) ? '——' : props.value.toFixed(props.decimals)
)
</script>

<template>
  <div class="panel px-3 py-2.5">
    <div class="flex items-baseline justify-between gap-2">
      <span class="label">{{ label }}</span>
      <span class="mono text-[9px] text-ink-faint">{{ unit }}</span>
    </div>
    <div class="mono text-xl font-semibold mt-1" :style="{ color }">{{ display }}</div>
    <div class="h-1 bg-line mt-2">
      <div class="h-full transition-all duration-150" :style="{ width: pct + '%', background: color, boxShadow: `0 0 6px ${color}66` }" />
    </div>
  </div>
</template>
