<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useSessionStore } from '@/stores/sessions'
import { useConnectionStore } from '@/stores/connection'
import StatusLed from '@/components/StatusLed.vue'

const sessions = useSessionStore()
const conn = useConnectionStore()
const uploadMsg = ref('')
const playMsg = ref('')

onMounted(() => sessions.refreshSessions())

async function upload() {
  sessions.stopRecording()
  uploadMsg.value = 'Uploading…'
  const err = await sessions.uploadRecording()
  uploadMsg.value = err ?? `Uploaded ${sessions.recordedCount.toLocaleString()} frames`
}

async function play(id: number) {
  const s = sessions.remoteSessions.find(x => x.id === id)
  if (!s) return
  playMsg.value = ''
  const err = await sessions.startPlayback(s)
  if (err) playMsg.value = err
}
function playLocal() {
  playMsg.value = ''
  const err = sessions.playLocalRecording()
  if (err) playMsg.value = err
}
</script>

<template>
  <div class="grid grid-cols-1 lg:grid-cols-2 gap-3">
    <div class="flex flex-col gap-3">
      <div class="panel">
        <div class="panel-head">
          <span class="label">Record Session</span>
          <StatusLed :status="sessions.recording ? 'crit' : 'off'" :pulse="sessions.recording" />
        </div>
        <div class="p-4 flex flex-col gap-3">
          <label class="flex flex-col gap-1.5">
            <span class="label">Label</span>
            <input v-model="sessions.recordLabel" class="mono text-[15px] sm:text-[13px] bg-bg border border-line px-3 py-2.5 min-h-[44px] text-ink" placeholder="Morning commute…" />
          </label>
          <div class="flex items-center gap-3 flex-wrap">
            <button
              v-if="!sessions.recording"
              @click="sessions.startRecording(sessions.recordLabel)"
              :disabled="conn.state !== 'connected'"
              class="mono text-[10px] tracking-[0.14em] uppercase px-4 py-2.5 min-h-[44px] border border-crit/60 text-crit hover:bg-crit/10 cursor-pointer transition-colors disabled:opacity-40"
            >● Record</button>
            <button
              v-else
              @click="sessions.stopRecording()"
              class="mono text-[10px] tracking-[0.14em] uppercase px-4 py-2.5 min-h-[44px] border border-warn/60 text-warn hover:bg-warn/10 cursor-pointer transition-colors"
            >■ Stop — {{ sessions.recordedCount.toLocaleString() }} frames</button>
            <button
              v-if="!sessions.recording && sessions.recordedCount > 0"
              @click="playLocal"
              class="mono text-[10px] tracking-[0.14em] uppercase px-4 py-2.5 min-h-[44px] border border-ok/60 text-ok hover:bg-ok/10 cursor-pointer transition-colors"
            >Play recording</button>
            <button
              v-if="!sessions.recording && sessions.recordedCount > 0"
              @click="upload"
              class="mono text-[10px] tracking-[0.14em] uppercase px-4 py-2.5 min-h-[44px] border border-live/60 text-live hover:bg-live/10 cursor-pointer transition-colors"
            >Upload to server</button>
          </div>
          <p v-if="uploadMsg" class="mono text-[10px] text-ok uppercase tracking-[0.1em]">{{ uploadMsg }}</p>
        </div>
      </div>

      <div v-if="sessions.playing" class="panel border-l-2 border-l-live">
        <div class="panel-head"><span class="label text-live">Playing — {{ sessions.playSession?.label }}</span>
          <button @click="sessions.stopPlayback()" class="mono text-[9px] tracking-[0.14em] uppercase px-3 py-1 border border-crit/50 text-crit hover:bg-crit/10 cursor-pointer">Stop</button>
        </div>
        <div class="h-1 bg-line"><div class="h-full bg-live transition-all" :style="{ width: (sessions.playProgress * 100) + '%' }" /></div>
      </div>

      <div class="panel">
        <div class="panel-head"><span class="label">Storage Server</span></div>
        <div class="p-4 flex flex-col gap-3">
          <label class="flex flex-col gap-1.5">
            <span class="label">Server URL (canscope-server)</span>
            <input v-model="sessions.serverUrl" @change="sessions.saveServerUrl(); sessions.refreshSessions()" class="mono text-[15px] sm:text-[13px] bg-bg border border-line px-3 py-2.5 min-h-[44px] text-ink" placeholder="http://localhost:8787" />
          </label>
          <div class="flex items-center gap-2">
            <StatusLed :status="sessions.serverOnline === null ? 'off' : sessions.serverOnline ? 'ok' : 'crit'" />
            <span class="mono text-[10px] uppercase tracking-[0.12em] text-ink-dim">
              {{ sessions.serverOnline === null ? 'Not checked' : sessions.serverOnline ? 'Server online' : 'Server unreachable' }}
            </span>
          </div>
        </div>
      </div>
    </div>

    <div class="panel self-start">
      <div class="panel-head">
        <span class="label">Session Library</span>
        <button @click="sessions.refreshSessions()" class="mono text-[9px] tracking-[0.14em] uppercase px-3 py-1.5 border border-line text-ink-dim hover:text-ink cursor-pointer transition-colors">Refresh</button>
      </div>
      <div v-if="sessions.remoteSessions.length" class="divide-y divide-line/50">
        <div v-for="s in sessions.remoteSessions" :key="s.id" class="flex items-center gap-3 px-4 py-3">
          <div class="flex-1 min-w-0">
            <div class="text-[13px] text-ink truncate">{{ s.label }}</div>
            <div class="mono text-[9px] tracking-[0.1em] uppercase text-ink-faint mt-0.5">{{ s.started_at }} · {{ s.frame_count.toLocaleString() }} frames</div>
          </div>
          <button @click="play(s.id)" class="mono text-[9px] tracking-[0.14em] uppercase px-3 py-2 min-h-[36px] border border-ok/50 text-ok hover:bg-ok/10 cursor-pointer transition-colors">Play</button>
          <button @click="sessions.deleteSession(s.id)" class="mono text-[9px] tracking-[0.14em] uppercase px-3 py-2 min-h-[36px] border border-line text-ink-dim hover:text-crit hover:border-crit/50 cursor-pointer transition-colors">Delete</button>
        </div>
      </div>
      <div v-else class="p-8 text-center">
        <span class="mono text-[10px] text-ink-faint uppercase tracking-[0.14em]">No sessions — record a drive and upload it</span>
      </div>
      <p v-if="playMsg" class="mono text-[10px] text-crit px-4 pb-3 uppercase">{{ playMsg }}</p>
    </div>
  </div>
</template>
