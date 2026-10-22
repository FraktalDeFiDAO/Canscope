import { describe, it, expect } from 'vitest'
import { PIDS, pidById, decodeDtcBytes } from '../obd'

describe('OBD PID decoders', () => {
  it('decodes RPM (0x0C): (256A+B)/4', () => {
    expect(pidById(0x0c)!.decode([0x1a, 0xf8])).toBeCloseTo((0x1a * 256 + 0xf8) / 4)
  })
  it('decodes coolant temp (0x05): A-40', () => {
    expect(pidById(0x05)!.decode([128])).toBe(88)
  })
  it('decodes fuel trim (0x06): A/1.28-100', () => {
    expect(pidById(0x06)!.decode([128])).toBeCloseTo(0, 1)
  })
  it('decodes MAF (0x10): (256A+B)/100', () => {
    expect(pidById(0x10)!.decode([0x01, 0x00])).toBeCloseTo(2.56)
  })
  it('decodes module voltage (0x42): U16/1000', () => {
    expect(pidById(0x42)!.decode([0x37, 0x7c])).toBeCloseTo(14.204)
  })
  it('every PID has sane metadata', () => {
    for (const p of PIDS) {
      expect(p.pid).toBeGreaterThan(0)
      expect(p.name.length).toBeGreaterThan(3)
      expect(p.bytes).toBeGreaterThanOrEqual(1)
      expect(p.max).toBeGreaterThan(p.min)
    }
  })
})

describe('DTC decoding', () => {
  it('decodes raw pairs into code strings', () => {
    expect(decodeDtcBytes([0x01, 0x71])).toEqual(['P0171'])
    expect(decodeDtcBytes([0x04, 0x20])).toEqual(['P0420'])
  })
  it('skips zero padding', () => {
    expect(decodeDtcBytes([0x01, 0x71, 0x00, 0x00])).toEqual(['P0171'])
  })
  it('maps system letters P/C/B/U', () => {
    expect(decodeDtcBytes([0x40, 0x01])[0][0]).toBe('C')
    expect(decodeDtcBytes([0x80, 0x01])[0][0]).toBe('B')
    expect(decodeDtcBytes([0xC0, 0x01])[0][0]).toBe('U')
  })
})
