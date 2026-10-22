import { defineStore } from 'pinia'
import { ref } from 'vue'
import { parseDbc, decodeMessage, type Dbc, type DbcSignal } from '@/lib/dbc'

export interface DecodedSignal { signal: DbcSignal; value: number }

export const useDbcStore = defineStore('dbc', () => {
  const dbc = ref<Dbc | null>(null)
  const fileName = ref('')
  const error = ref('')

  function load(text: string, name: string) {
    const parsed = parseDbc(text)
    if (parsed.messages.size === 0) {
      error.value = 'No BO_ message definitions found — is this a DBC file?'
      return
    }
    error.value = ''
    dbc.value = parsed
    fileName.value = name
  }

  function unload() {
    dbc.value = null
    fileName.value = ''
    error.value = ''
  }

  function decode(id: number, data: number[]): { name: string; signals: DecodedSignal[] } | null {
    const msg = dbc.value?.messages.get(id)
    if (!msg) return null
    return { name: msg.name, signals: decodeMessage(msg, data) }
  }

  return { dbc, fileName, error, load, unload, decode }
})
