<script setup lang="ts">
import { computed } from 'vue'
import { useObdStore } from '@/stores/obd'
import { DTC_DESCRIPTIONS } from '@/lib/obd'

const obd = useObdStore()
const items = computed(() =>
  obd.dtcs.map(c => `MIL ACTIVE — ${c} ${DTC_DESCRIPTIONS[c] ?? ''}`.trim()).concat([
    'Check vehicle before clearing codes', 'Pull over safely if the light is flashing',
  ])
)
</script>

<template>
  <div class="border-b border-line bg-crit/5 overflow-hidden" role="alert">
    <div class="ticker-track flex whitespace-nowrap py-1.5" :style="{ width: 'max-content' }">
      <span v-for="(it, i) in [...items, ...items]" :key="i" class="mono text-[10px] tracking-[0.16em] uppercase text-crit px-8">
        {{ it }} <span class="text-ink-faint pl-8">///</span>
      </span>
    </div>
  </div>
</template>
