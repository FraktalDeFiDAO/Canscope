<script setup lang="ts">
withDefaults(defineProps<{ status: 'ok' | 'warn' | 'crit' | 'live' | 'off'; pulse?: boolean }>(), { pulse: false })
const colors: Record<string, string> = {
  ok: '#34d399', warn: '#fbbf24', crit: '#f87171', live: '#22d3ee', off: '#1a2540',
}
</script>

<template>
  <span class="relative inline-flex items-center justify-center w-3.5 h-3.5" aria-hidden="true">
    <span
      class="absolute inset-0 rounded-full border"
      :style="{ borderColor: colors[status], boxShadow: status !== 'off' ? `0 0 6px ${colors[status]}66` : 'none' }"
    />
    <span
      class="w-1.5 h-1.5 rounded-full"
      :class="{ 'pulse-dot': pulse }"
      :style="{ background: colors[status] }"
    />
  </span>
</template>
