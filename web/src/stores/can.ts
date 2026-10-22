import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { CanFrame } from '@/lib/simulator'

export interface FrameStats {
  id: number
  ext: boolean
  lastData: number[]
  count: number
  lastSeen: number
  rate: number
  dir: 'rx' | 'tx'
}

export const useCanStore = defineStore('can', () => {
  const frames = ref<CanFrame[]>([])          // ring buffer for the raw log
  const stats = ref(new Map<number, FrameStats>())
  const paused = ref(false)
  const total = ref(0)
  const windowStart = ref(Date.now())
  const MAX_LOG = 500

  function pushFrame(f: CanFrame) {
    total.value++
    const s = stats.value.get(f.id)
    if (s) {
      s.count++
      s.lastData = f.data
      s.dir = f.dir
      const dt = (f.ts - s.lastSeen) / 1000
      s.rate = dt > 0 ? s.rate * 0.8 + (1 / dt) * 0.2 : s.rate
      s.lastSeen = f.ts
    } else {
      stats.value.set(f.id, { id: f.id, ext: f.ext, lastData: f.data, count: 1, lastSeen: f.ts, rate: 0, dir: f.dir })
    }
    if (!paused.value) {
      frames.value.push(f)
      if (frames.value.length > MAX_LOG) frames.value.splice(0, frames.value.length - MAX_LOG)
    }
  }

  const busLoad = computed(() => {
    const now = Date.now()
    const secs = Math.max(1, (now - windowStart.value) / 1000)
    return Math.round(total.value / secs)
  })

  const uniqueIds = computed(() => stats.value.size)

  function clear() {
    frames.value = []
    stats.value = new Map()
    total.value = 0
    windowStart.value = Date.now()
  }

  return { frames, stats, paused, total, busLoad, uniqueIds, pushFrame, clear }
})
