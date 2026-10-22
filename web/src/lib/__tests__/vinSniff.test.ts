import { describe, it, expect, beforeEach } from 'vitest'
import { validateVin, feedVin, resetVinSniffer } from '../vinSniff'

const ascii = (s: string) => [...s].map(c => c.charCodeAt(0))
// real check-digit-valid VIN: 1HGCM82633A004352
const VIN = '1HGCM82633A004352'

describe('VIN validation (ISO 3780)', () => {
  it('accepts a check-digit-valid VIN', () => {
    expect(validateVin(VIN)).toBe('check-digit')
  })

  it('rejects a VIN with a wrong check digit', () => {
    expect(validateVin('1HGCM82643A004352')).toBeNull()
  })

  it('rejects illegal characters (I, O, Q)', () => {
    expect(validateVin('1HGCAN5C0PE12345I')).toBeNull()
    expect(validateVin('1HGCAN5C0PE12345O')).toBeNull()
    expect(validateVin('1HGCAN5C0PE12345Q')).toBeNull()
  })

  it('accepts EU-style VINs (0/Z at position 9) at format confidence', () => {
    const v = [...VIN]; v[8] = '0'
    expect(validateVin(v.join(''))).toBe('format')
  })

  it('rejects wrong length', () => {
    expect(validateVin(VIN.slice(0, 16))).toBeNull()
    expect(validateVin(VIN + '1')).toBeNull()
  })
})

describe('passive bus sniffing', () => {
  beforeEach(() => resetVinSniffer())

  it('detects a VIN embedded in a single frame', () => {
    const hit = feedVin(0x4d2, ascii('1HGCM826'))
    expect(hit).toBeNull() // only 8 bytes so far
  })

  it('detects a VIN split across three frames', () => {
    expect(feedVin(0x4d2, ascii('1HGCM826'))).toBeNull()
    expect(feedVin(0x4d2, ascii('33A00435'))).toBeNull()
    const hit = feedVin(0x4d2, [ascii('2')[0]])
    expect(hit?.vin).toBe(VIN)
    expect(hit?.confidence).toBe('check-digit')
    expect(hit?.sourceId).toBe(0x4d2)
  })

  it('detects a VIN with non-ASCII padding around it', () => {
    expect(feedVin(0x123, [0x00, 0xff, 0x10, 0x02])).toBeNull()
    const hit = feedVin(0x123, [...ascii(VIN), 0x00])
    expect(hit?.vin).toBe(VIN)
  })

  it('ignores frames with no VIN pattern', () => {
    expect(feedVin(0x100, [0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08])).toBeNull()
    expect(feedVin(0x100, ascii('A0A0A0A0'))).toBeNull()
  })

  it('keeps per-ID windows independent', () => {
    feedVin(0x111, ascii('1HGCM826'))
    // same bytes under a different ID should not complete the VIN
    expect(feedVin(0x222, ascii('33A00435'))).toBeNull()
  })
})
