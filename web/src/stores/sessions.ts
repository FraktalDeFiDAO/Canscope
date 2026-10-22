import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { CanFrame } from '@/lib/simulator'
import { useCanStore } from './can'
import { useObdStore } from './obd'

export interface RemoteSession {
  id: number
  label: string
  vehicle: string | null
  started_at: string
  frame_count: number
}

export const useSessionStore = defineStore('sessions', () => {
  const serverUrl = ref(localStorage.getItem('can.serverUrl') || 'http://localhost:8787')
  const recording = ref(false)
  const recordLabel = ref('')
  const buffer = ref<CanFrame[]>([])
  const recordingStartedAt = ref(0)

  const playing = ref(false)
  const playSession = ref<RemoteSession | null>(null)
  const playProgress = ref(0)
  let playTimer: ReturnType<typeof setTimeout> | null = null

  const remoteSessions = ref<RemoteSession[]>([])
  const serverOnline = ref<boolean | null>(null)

  const recordedCount = computed(() => buffer.value.length)

  function saveServerUrl() {
    localStorage.setItem('can.serverUrl', serverUrl.value)
  }

  // ---------- recording ----------
  function startRecording(label: string) {
    recordLabel.value = label || `Drive ${new Date().toLocaleString()}`
    buffer.value = []
    recordingStartedAt.value = Date.now()
    recording.value = true
  }

  function pushFrame(f: CanFrame) {
    if (recording.value) buffer.value.push(f)
  }

  function stopRecording() {
    recording.value = false
  }

  async function uploadRecording(): Promise<string | null> {
    if (!buffer.value.length) return 'Nothing recorded'
    try {
      const res = await fetch(`${serverUrl.value}/api/sessions`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ label: recordLabel.value, vehicle: null }),
      })
      if (!res.ok) return `Server returned ${res.status}`
      const { id } = await res.json()
      // chunk uploads — 5k frames per request
      for (let i = 0; i < buffer.value.length; i += 5000) {
        const chunk = buffer.value.slice(i, i + 5000).map(f => ({
          ts: f.ts, id: f.id, ext: f.ext, data: f.data,
        }))
        const r = await fetch(`${serverUrl.value}/api/sessions/${id}/frames`, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(chunk),
        })
        if (!r.ok) return `Frame upload failed (${r.status})`
      }
      serverOnline.value = true
      return null
    } catch (e) {
      serverOnline.value = false
      return e instanceof Error ? e.message : 'Upload failed'
    }
  }

  // ---------- library ----------
  async function refreshSessions() {
    try {
      const res = await fetch(`${serverUrl.value}/api/sessions`)
      remoteSessions.value = res.ok ? await res.json() : []
      serverOnline.value = res.ok
    } catch {
      serverOnline.value = false
      remoteSessions.value = []
    }
  }

  async function deleteSession(id: number) {
    await fetch(`${serverUrl.value}/api/sessions/${id}`, { method: 'DELETE' })
    await refreshSessions()
  }

  // ---------- playback ----------
  function playRows(rows: Array<{ ts: number; id: number; ext: boolean; data: number[] }>, label: string, id = -1) {
    stopPlayback()
    playing.value = true
    playSession.value = { id, label, vehicle: null, started_at: '', frame_count: rows.length }
    playProgress.value = 0
    const can = useCanStore()
    const obd = useObdStore()
    can.clear()
    let i = 0
    const t0 = rows[0]?.ts ?? 0
    const wall0 = Date.now()
    const step = () => {
      if (!playing.value) return
      const elapsed = Date.now() - wall0
      while (i < rows.length && rows[i].ts - t0 <= elapsed) {
        const r = rows[i]
        const f: CanFrame = { ts: Date.now(), id: r.id, ext: r.ext, dlc: r.data.length, data: r.data, dir: 'rx' }
        can.pushFrame(f)
        obd.feedFrame(f)
        i++
      }
      playProgress.value = rows.length ? i / rows.length : 1
      if (i < rows.length) playTimer = setTimeout(step, 20)
      else playing.value = false
    }
    step()
  }

  /** Play the just-recorded local buffer — no server needed. */
  function playLocalRecording() {
    if (!buffer.value.length) return 'Nothing recorded'
    playRows(buffer.value.map(f => ({ ts: f.ts, id: f.id, ext: f.ext, data: f.data })), recordLabel.value || 'Local recording')
    return null
  }

  async function startPlayback(s: RemoteSession): Promise<string | null> {
    try {
      const res = await fetch(`${serverUrl.value}/api/sessions/${s.id}/frames?limit=500000`)
      if (!res.ok) return `Server returned ${res.status}`
      const rows: Array<{ ts: number; id: number; ext: boolean; data: number[] }> = await res.json()
      playRows(rows, s.label, s.id)
      return null
    } catch (e) {
      return e instanceof Error ? e.message : 'Playback failed'
    }
  }

  function stopPlayback() {
    playing.value = false
    playSession.value = null
    if (playTimer) { clearTimeout(playTimer); playTimer = null }
  }

  return {
    serverUrl, recording, recordLabel, recordedCount, recordingStartedAt,
    playing, playSession, playProgress, remoteSessions, serverOnline,
    saveServerUrl, startRecording, pushFrame, stopRecording, uploadRecording,
    refreshSessions, deleteSession, startPlayback, stopPlayback, playLocalRecording,
  }
})
