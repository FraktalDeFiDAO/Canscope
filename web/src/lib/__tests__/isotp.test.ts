import { describe, it, expect } from 'vitest'
import { feedIsoTp, flowControlFrame, segmentPayload, decodeMode09, decodeSupportedPids } from '../isotp'

describe('ISO-TP reassembly', () => {
  it('passes through single frames', () => {
    const r = feedIsoTp(0x7e8, [0x04, 0x41, 0x0c, 0x1a, 0xf8, 0, 0, 0])
    expect(r?.complete).toBe(true)
    expect(r?.data).toEqual([0x41, 0x0c, 0x1a, 0xf8])
  })

  it('signals first frames so the caller sends flow control', () => {
    const ff = feedIsoTp(0x7e8, [0x10, 0x14, 0x49, 0x02, 0x01, 0x41, 0x42, 0x43])
    expect(ff?.firstFrame).toBe(true)
    expect(ff?.complete).toBe(false)
    expect(flowControlFrame()).toEqual([0x30, 0, 0, 0, 0, 0, 0, 0])
  })

  it('reassembles a VIN multi-frame response', () => {
    // VIN "1HGCAN5C0PE123456" = 17 bytes; total payload 20 bytes: 49 02 01 + 17
    const vin = '1HGCAN5C0PE123456'
    const payload = [0x49, 0x02, 0x01, ...[...vin].map(c => c.charCodeAt(0))]
    const first = [0x10, payload.length, ...payload.slice(0, 6)]
    const cf1 = [0x21, ...payload.slice(6, 13)]
    const cf2 = [0x22, ...payload.slice(13, 20)]
    expect(feedIsoTp(0x7e8, first)?.firstFrame).toBe(true)
    expect(feedIsoTp(0x7e8, cf1)).toBeNull()
    const done = feedIsoTp(0x7e8, cf2)
    expect(done?.complete).toBe(true)
    expect(decodeMode09(0x02, done!.data).vin).toBe(vin)
  })

  it('drops transfers with a lost consecutive frame', () => {
    feedIsoTp(0x7e8, [0x10, 0x14, 0x49, 0x02, 0x01, 0x41, 0x42, 0x43])
    expect(feedIsoTp(0x7e8, [0x23, 1, 2, 3, 4, 5, 6, 7])).toBeNull() // wrong seq
  })

  it('decodes supported-PID bitmasks', () => {
    // bit for PID 0x01 = MSB of first byte
    const s = decodeSupportedPids(0x00, [0x80, 0x00, 0x00, 0x01])
    expect(s.has(0x01)).toBe(true)
    expect(s.has(0x20)).toBe(true)
    expect(s.has(0x02)).toBe(false)
  })
})

describe('ISO-TP segmentation (transmit)', () => {
  it('wraps short payloads in a single frame', () => {
    expect(segmentPayload([0x01, 0x0c])).toEqual([[0x02, 0x01, 0x0c, 0, 0, 0, 0, 0]])
  })

  it('segments long payloads into FF + CF frames', () => {
    const payload = Array.from({ length: 20 }, (_, i) => i + 1)
    const frames = segmentPayload(payload)
    expect(frames.length).toBe(3)
    expect(frames[0]).toEqual([0x10, 20, 1, 2, 3, 4, 5, 6])
    expect(frames[1]).toEqual([0x21, 7, 8, 9, 10, 11, 12, 13])
    expect(frames[2]).toEqual([0x22, 14, 15, 16, 17, 18, 19, 20])
  })

  it('segments wrap sequence numbers mod 16', () => {
    const payload = new Array(7 + 16 * 7).fill(0xaa) // 17 CFs
    const frames = segmentPayload(payload)
    expect(frames[1][0]).toBe(0x21)
    expect(frames[16][0]).toBe(0x20) // seq wraps to 0
    expect(frames[17][0]).toBe(0x21)
  })

  it('round-trips: segment then reassemble', () => {
    const payload = Array.from({ length: 25 }, (_, i) => (i * 7) & 0xff)
    const frames = segmentPayload(payload)
    let out: number[] | null = null
    for (const f of frames) {
      const r = feedIsoTp(0x7e0, f)
      if (r?.complete) out = r.data
    }
    expect(out).toEqual(payload)
  })
})
