// Passive VIN detection: watches raw CAN traffic for 17-character ASCII runs
// matching the VIN format and validates the ISO 3780 check digit (position 9).
//
// VINs are not broadcast by any standard, but several manufacturers embed them
// in proprietary frames — sometimes split across two frames. This keeps a short
// rolling buffer per CAN ID and scans every 17-byte window. Mode 09 results
// always win over a sniffed candidate (see obd store).

const WEIGHTS = [8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2]

const TRANS: Record<string, number> = {
  A: 1, B: 2, C: 3, D: 4, E: 5, F: 6, G: 7, H: 8,
  J: 1, K: 2, L: 3, M: 4, N: 5, P: 7, R: 9,
  S: 2, T: 3, U: 4, V: 5, W: 6, X: 7, Y: 8, Z: 9,
}

const VIN_RE = /^[A-HJ-NPR-Z0-9]{17}$/

export type VinConfidence = 'check-digit' | 'format'

/**
 * Validate a 17-char VIN candidate.
 * - 'check-digit': position 9 matches the ISO 3780 weighted sum (North America, mandatory 1981+)
 * - 'format':      valid VIN character set but position 9 is 0/Z (common on EU/Asian vehicles
 *                  that don't compute the check digit) — weaker confidence
 * - null:          not a plausible VIN
 */
export function validateVin(v: string): VinConfidence | null {
  if (!VIN_RE.test(v)) return null
  let sum = 0
  for (let i = 0; i < 17; i++) {
    const c = v[i]
    const n = c >= '0' && c <= '9' ? c.charCodeAt(0) - 48 : TRANS[c]
    if (n === undefined) return null
    sum += n * WEIGHTS[i]
  }
  const rem = sum % 11
  const expected = rem === 10 ? 'X' : String(rem)
  if (v[8] === expected) return 'check-digit'
  if (v[8] === '0' || v[8] === 'Z') return 'format'
  return null
}

// Rolling per-ID payload window: VINs split across frames stay detectable.
const windows = new Map<number, number[]>()
const WINDOW_BYTES = 40 // ~5 frames of history per ID

export interface VinHit {
  vin: string
  confidence: VinConfidence
  sourceId: number
}

/**
 * Feed one raw frame. Returns a validated VIN the first time one is seen,
 * null otherwise. Cheap: pure byte scanning, no allocation on the hot path
 * beyond the window array.
 */
export function feedVin(id: number, data: number[]): VinHit | null {
  const w = windows.get(id) ?? []
  for (const b of data) w.push(b)
  if (w.length > WINDOW_BYTES) w.splice(0, w.length - WINDOW_BYTES)
  windows.set(id, w)

  if (w.length < 17) return null
  // scan every 17-byte window, newest last so recent bytes win
  outer: for (let start = w.length - 17; start >= 0; start--) {
    let s = ''
    for (let i = start; i < start + 17; i++) {
      const b = w[i]
      if (b < 0x30 || b > 0x5a) continue outer // not [0-9A-Z]
      s += String.fromCharCode(b)
    }
    const conf = validateVin(s)
    if (conf) {
      windows.delete(id) // report once, then stop watching this ID
      return { vin: s, confidence: conf, sourceId: id }
    }
  }
  return null
}

/** Test helper: clear all per-ID windows. */
export function resetVinSniffer() {
  windows.clear()
}
