// ISO-TP (ISO 15765-2) reassembly for OBD-II responses on 0x7E8–0x7EF.
// Single frame: [0x0N, ...N bytes]; First frame: [0x1L_hi, L_lo, ...6 bytes];
// Consecutive: [0x2S, ...7 bytes]. Flow control (0x30) is sent by the requester;
// for typical OBD responses (VIN, mode 09) ECUs usually fit or self-pace.

export interface IsoTpResult {
  data: number[]
  complete: boolean
  /** true when this frame was a First Frame — the caller should answer with
   *  a flow-control frame (0x30) to the matching request ID (response id − 8),
   *  or the ECU may stop after the first block. */
  firstFrame?: boolean
}

interface Assembly {
  expected: number
  buf: number[]
  seq: number
  deadline: number
}

const assemblies = new Map<number, Assembly>()
const TIMEOUT_MS = 1000

/** Feed one raw frame; returns the full payload when a transfer completes. */
export function feedIsoTp(id: number, data: number[]): IsoTpResult | null {
  if (data.length === 0) return null
  const pci = data[0] >> 4
  const now = Date.now()

  // GC expired assemblies
  for (const [k, v] of assemblies) if (now > v.deadline) assemblies.delete(k)

  if (pci === 0x0) {
    const len = data[0] & 0x0f
    return { data: data.slice(1, 1 + len), complete: true }
  }
  if (pci === 0x1) {
    const len = ((data[0] & 0x0f) << 8) | data[1]
    assemblies.set(id, {
      expected: len,
      buf: data.slice(2),
      seq: 1,
      deadline: now + TIMEOUT_MS,
    })
    return { data: [], complete: false, firstFrame: true }
  }
  if (pci === 0x2) {
    const a = assemblies.get(id)
    if (!a) return null
    const seq = data[0] & 0x0f
    if (seq !== a.seq % 16) { assemblies.delete(id); return null } // lost frame — drop transfer
    a.buf.push(...data.slice(1))
    a.seq++
    a.deadline = now + TIMEOUT_MS
    if (a.buf.length >= a.expected) {
      assemblies.delete(id)
      return { data: a.buf.slice(0, a.expected), complete: true }
    }
    return null
  }
  return null // flow-control frames — we are the sender side, ignore here
}

/** Build the flow-control frame a requester should send after a first frame. */
export function flowControlFrame(): number[] {
  return [0x30, 0x00, 0x00, 0, 0, 0, 0, 0]
}

/**
 * Segment an outbound ISO-TP payload into CAN frames.
 * ≤7 bytes → one single frame; longer → first frame + consecutive frames
 * (padding 0x00, block size 0 — we assume the ECU sends flow control or
 * accepts continuous frames, which holds for OBD/UDS request sizes).
 */
export function segmentPayload(payload: number[]): number[][] {
  if (payload.length <= 7) {
    return [[payload.length, ...payload, ...Array(7 - payload.length).fill(0)]]
  }
  const frames: number[][] = []
  frames.push([
    0x10 | ((payload.length >> 8) & 0x0f),
    payload.length & 0xff,
    ...payload.slice(0, 6),
  ])
  let seq = 1
  for (let off = 6; off < payload.length; off += 7) {
    const chunk = payload.slice(off, off + 7)
    frames.push([0x20 | (seq % 16), ...chunk, ...Array(7 - chunk.length).fill(0)])
    seq++
  }
  return frames
}

// ---------- Mode 09 (vehicle info) ----------

export interface VehicleInfo {
  vin?: string
  ecuName?: string
  calibrationId?: string
  /** where the VIN came from: 'mode09' (asked the ECU) or 'sniffed' (passive bus detection) */
  vinSource?: 'mode09' | 'sniffed'
}

export function decodeMode09(infoType: number, data: number[]): Partial<VehicleInfo> {
  // data = full ISO-TP payload starting with 0x49
  if (data[0] !== 0x49) return {}
  const body = data.slice(1)
  if (infoType === 0x02) {
    // VIN: [0x49, 0x02, 0x01, <17 ascii>]
    const vinBytes = body.slice(2, 19)
    return { vin: vinBytes.map(b => (b >= 32 && b < 127 ? String.fromCharCode(b) : '')).join(''), vinSource: 'mode09' as const }
  }
  if (infoType === 0x04 || infoType === 0x0a) {
    // calibration id / ecu name: multiple NODI blocks [n, ascii...]
    const texts: string[] = []
    for (let i = 1; i < body.length;) {
      const n = body[i]
      if (n === 0) break
      const start = i + 1
      let end = start
      while (end < body.length && body[end] !== 0 && !(end > start && body[end] === n + 1)) end++
      // heuristic: fixed 4- or 5-byte ascii blocks
      end = Math.min(body.length, start + 4)
      const s = body.slice(start, end).map(b => (b >= 32 && b < 127 ? String.fromCharCode(b) : '')).join('')
      if (s) texts.push(s)
      i = end
    }
    const joined = texts.join(' ')
    return infoType === 0x04 ? { calibrationId: joined } : { ecuName: joined }
  }
  return {}
}

// ---------- Supported-PID bitmasks ----------

/** bitmask payload: data = [0x41, base, b0..b3] -> set of supported pid numbers */
export function decodeSupportedPids(base: number, bytes: number[]): Set<number> {
  const out = new Set<number>()
  for (let i = 0; i < 4 && i < bytes.length; i++) {
    for (let bit = 0; bit < 8; bit++) {
      if (bytes[i] & (0x80 >> bit)) out.add(base + 1 + i * 8 + bit)
    }
  }
  return out
}
