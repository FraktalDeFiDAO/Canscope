// Minimal DBC (CAN database) parser + runtime decoder.
// Supports the common subset: BO_ message definitions and SG_ signals
// with Intel/Motorola byte order, signedness, factor/offset, min/max, unit.

export interface DbcSignal {
  name: string
  startBit: number
  length: number
  littleEndian: boolean
  signed: boolean
  factor: number
  offset: number
  min: number
  max: number
  unit: string
  receiver: string
}

export interface DbcMessage {
  id: number
  name: string
  dlc: number
  sender: string
  signals: DbcSignal[]
}

export interface Dbc {
  messages: Map<number, DbcMessage>
  name: string
}

export function parseDbc(text: string): Dbc {
  const messages = new Map<number, DbcMessage>()
  let current: DbcMessage | null = null

  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim()
    const bo = line.match(/^BO_\s+(\d+)\s+(\w+)\s*:\s*(\d+)\s+(\w+)/)
    if (bo) {
      current = { id: parseInt(bo[1], 10), name: bo[2], dlc: parseInt(bo[3], 10), sender: bo[4], signals: [] }
      messages.set(current.id, current)
      continue
    }
    const sg = line.match(
      /^SG_\s+(\w+)\s*(?:[mM]\d*\s*)?:\s*(\d+)\|(\d+)@(\d)([+-])\s+\(\s*([-\d.eE]+)\s*,\s*([-\d.eE]+)\s*\)\s+\[\s*([-\d.eE]+)\s*\|\s*([-\d.eE]+)\s*\]\s+"([^"]*)"\s+(.*)$/,
    )
    if (sg && current) {
      current.signals.push({
        name: sg[1],
        startBit: parseInt(sg[2], 10),
        length: parseInt(sg[3], 10),
        littleEndian: sg[4] === '1',
        signed: sg[5] === '-',
        factor: parseFloat(sg[6]),
        offset: parseFloat(sg[7]),
        min: parseFloat(sg[8]),
        max: parseFloat(sg[9]),
        unit: sg[10],
        receiver: sg[11].trim(),
      })
    }
  }
  return { messages, name: '' }
}

/** Extract a raw unsigned signal value from frame data. */
function extractRaw(data: number[], sig: DbcSignal): number {
  let bits = 0n
  const totalBits = data.length * 8
  for (let i = 0; i < data.length; i++) bits |= BigInt(data[i]) << BigInt(i * 8) // little-endian bit array

  if (sig.littleEndian) {
    const mask = (1n << BigInt(sig.length)) - 1n
    return Number((bits >> BigInt(sig.startBit)) & mask)
  }
  // Motorola big-endian: walk start bit backward through the DBC "sawtooth" order
  let value = 0
  let bit = sig.startBit
  for (let i = 0; i < sig.length; i++) {
    const byteIdx = Math.floor(bit / 8)
    const bitIdx = 7 - (bit % 8)
    if (byteIdx * 8 + bitIdx < totalBits) {
      value = (value << 1) | ((data[byteIdx] >> bitIdx) & 1)
    } else {
      value <<= 1
    }
    // next bit: +1 within byte continues left-to-right in DBC big-endian numbering
    bit = bit % 8 === 0 ? bit + 15 : bit - 1
  }
  return value >>> 0
}

export function decodeSignal(data: number[], sig: DbcSignal): number {
  let raw = extractRaw(data, sig)
  if (sig.signed && sig.length > 0 && raw >= 2 ** (sig.length - 1)) {
    raw -= 2 ** sig.length
  }
  return raw * sig.factor + sig.offset
}

export function decodeMessage(msg: DbcMessage, data: number[]): Array<{ signal: DbcSignal; value: number }> {
  return msg.signals.map(signal => ({ signal, value: decodeSignal(data, signal) }))
}
