import { describe, it, expect } from 'vitest'
import { parseDbc, decodeMessage, decodeSignal } from '../dbc'

const SAMPLE = `
VERSION ""
BO_ 201 EngineData: 8 ECM
 SG_ RPM : 0|16@1+ (0.25,0) [0|8000] "rpm" ECU
 SG_ Throttle : 16|8@1+ (0.392157,0) [0|100] "%" ECU
BO_ 320 Temps: 4 ECM
 SG_ Coolant : 0|8@1+ (1,-40) [-40|215] "C" ECU
 SG_ SignedVal : 8|8@1- (1,0) [-128|127] "" ECU
`

describe('DBC parser', () => {
  it('parses messages and signals', () => {
    const dbc = parseDbc(SAMPLE)
    expect(dbc.messages.size).toBe(2)
    const eng = dbc.messages.get(201)!
    expect(eng.name).toBe('EngineData')
    expect(eng.dlc).toBe(8)
    expect(eng.signals.map(s => s.name)).toEqual(['RPM', 'Throttle'])
    expect(eng.signals[0].factor).toBeCloseTo(0.25)
  })

  it('decodes little-endian signals with factor/offset', () => {
    const dbc = parseDbc(SAMPLE)
    const eng = dbc.messages.get(201)!
    // RPM raw 4000 -> 1000 rpm; throttle raw 128 -> ~50.2%
    const data = [0xa0, 0x0f, 0x80, 0, 0, 0, 0, 0]
    const out = decodeMessage(eng, data)
    expect(out[0].value).toBeCloseTo(1000)
    expect(out[1].value).toBeCloseTo(50.2, 0)
  })

  it('applies signedness', () => {
    const dbc = parseDbc(SAMPLE)
    const temps = dbc.messages.get(320)!
    const sig = temps.signals[1]
    expect(decodeSignal([0, 0xff], sig)).toBe(-1)
    expect(decodeSignal([0, 0x05], sig)).toBe(5)
  })

  it('rejects files with no messages', () => {
    expect(parseDbc('VERSION ""\nNS_ :\n').messages.size).toBe(0)
  })
})
