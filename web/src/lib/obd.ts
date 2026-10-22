// OBD-II Mode 01 PID database (SAE J1979) with decode formulas.
export interface PidDef {
  pid: number
  name: string
  unit: string
  bytes: number
  min: number
  max: number
  decode: (d: number[]) => number | null
  format?: (v: number) => string
}

const A = (d: number[]) => d[0]
const U16 = (d: number[]) => (d[0] << 8) | d[1]
const S16 = (d: number[]) => ((d[0] << 8) | d[1]) - (d[0] & 0x80 ? 65536 : 0)

export const PIDS: PidDef[] = [
  { pid: 0x04, name: 'Calculated engine load', unit: '%', bytes: 1, min: 0, max: 100, decode: d => A(d) / 2.55 },
  { pid: 0x05, name: 'Engine coolant temperature', unit: '°C', bytes: 1, min: -40, max: 215, decode: d => A(d) - 40 },
  { pid: 0x06, name: 'Short term fuel trim — Bank 1', unit: '%', bytes: 1, min: -100, max: 99.2, decode: d => A(d) / 1.28 - 100 },
  { pid: 0x07, name: 'Long term fuel trim — Bank 1', unit: '%', bytes: 1, min: -100, max: 99.2, decode: d => A(d) / 1.28 - 100 },
  { pid: 0x08, name: 'Short term fuel trim — Bank 2', unit: '%', bytes: 1, min: -100, max: 99.2, decode: d => A(d) / 1.28 - 100 },
  { pid: 0x09, name: 'Long term fuel trim — Bank 2', unit: '%', bytes: 1, min: -100, max: 99.2, decode: d => A(d) / 1.28 - 100 },
  { pid: 0x0a, name: 'Fuel pressure (gauge)', unit: 'kPa', bytes: 1, min: 0, max: 765, decode: d => A(d) * 3 },
  { pid: 0x0b, name: 'Intake manifold absolute pressure', unit: 'kPa', bytes: 1, min: 0, max: 255, decode: d => A(d) },
  { pid: 0x0c, name: 'Engine RPM', unit: 'rpm', bytes: 2, min: 0, max: 16384, decode: d => U16(d) / 4 },
  { pid: 0x0d, name: 'Vehicle speed', unit: 'km/h', bytes: 1, min: 0, max: 255, decode: d => A(d) },
  { pid: 0x0e, name: 'Timing advance', unit: '°BTDC', bytes: 1, min: -64, max: 63.5, decode: d => A(d) / 2 - 64 },
  { pid: 0x0f, name: 'Intake air temperature', unit: '°C', bytes: 1, min: -40, max: 215, decode: d => A(d) - 40 },
  { pid: 0x10, name: 'Mass air flow rate', unit: 'g/s', bytes: 2, min: 0, max: 655.35, decode: d => U16(d) / 100 },
  { pid: 0x11, name: 'Throttle position', unit: '%', bytes: 1, min: 0, max: 100, decode: d => A(d) / 2.55 },
  { pid: 0x12, name: 'Commanded secondary air status', unit: '', bytes: 1, min: 0, max: 255, decode: d => A(d) },
  { pid: 0x13, name: 'Oxygen sensors present (2 banks)', unit: 'bitmap', bytes: 1, min: 0, max: 255, decode: d => A(d) },
  { pid: 0x14, name: 'O2 sensor 1 (Bank 1)', unit: 'V', bytes: 2, min: 0, max: 1.275, decode: d => A(d) / 200 },
  { pid: 0x15, name: 'O2 sensor 2 (Bank 1)', unit: 'V', bytes: 2, min: 0, max: 1.275, decode: d => A(d) / 200 },
  { pid: 0x16, name: 'O2 sensor 3 (Bank 1)', unit: 'V', bytes: 2, min: 0, max: 1.275, decode: d => A(d) / 200 },
  { pid: 0x17, name: 'O2 sensor 4 (Bank 1)', unit: 'V', bytes: 2, min: 0, max: 1.275, decode: d => A(d) / 200 },
  { pid: 0x18, name: 'O2 sensor 1 (Bank 2)', unit: 'V', bytes: 2, min: 0, max: 1.275, decode: d => A(d) / 200 },
  { pid: 0x19, name: 'O2 sensor 2 (Bank 2)', unit: 'V', bytes: 2, min: 0, max: 1.275, decode: d => A(d) / 200 },
  { pid: 0x1a, name: 'O2 sensor 3 (Bank 2)', unit: 'V', bytes: 2, min: 0, max: 1.275, decode: d => A(d) / 200 },
  { pid: 0x1b, name: 'O2 sensor 4 (Bank 2)', unit: 'V', bytes: 2, min: 0, max: 1.275, decode: d => A(d) / 200 },
  { pid: 0x1c, name: 'OBD standard compliance', unit: '', bytes: 1, min: 0, max: 255, decode: d => A(d) },
  { pid: 0x1d, name: 'Oxygen sensors present (4 banks)', unit: 'bitmap', bytes: 1, min: 0, max: 255, decode: d => A(d) },
  { pid: 0x1e, name: 'Auxiliary input status', unit: '', bytes: 1, min: 0, max: 255, decode: d => A(d) },
  { pid: 0x1f, name: 'Run time since engine start', unit: 's', bytes: 2, min: 0, max: 65535, decode: d => U16(d) },
  { pid: 0x21, name: 'Distance traveled with MIL on', unit: 'km', bytes: 2, min: 0, max: 65535, decode: d => U16(d) },
  { pid: 0x22, name: 'Fuel rail pressure (relative)', unit: 'kPa', bytes: 2, min: 0, max: 5177.265, decode: d => U16(d) * 0.079 },
  { pid: 0x23, name: 'Fuel rail gauge pressure', unit: 'kPa', bytes: 2, min: 0, max: 655350, decode: d => U16(d) * 10 },
  { pid: 0x24, name: 'Wideband O2 sensor 1 ratio', unit: 'λ', bytes: 4, min: 0, max: 2, decode: d => ((d[0] << 8) | d[1]) / 32768 },
  { pid: 0x25, name: 'Wideband O2 sensor 2 ratio', unit: 'λ', bytes: 4, min: 0, max: 2, decode: d => ((d[0] << 8) | d[1]) / 32768 },
  { pid: 0x2c, name: 'Commanded EGR', unit: '%', bytes: 1, min: 0, max: 100, decode: d => A(d) / 2.55 },
  { pid: 0x2d, name: 'EGR error', unit: '%', bytes: 1, min: -100, max: 99.2, decode: d => A(d) / 1.28 - 100 },
  { pid: 0x2e, name: 'Commanded evaporative purge', unit: '%', bytes: 1, min: 0, max: 100, decode: d => A(d) / 2.55 },
  { pid: 0x2f, name: 'Fuel tank level input', unit: '%', bytes: 1, min: 0, max: 100, decode: d => A(d) / 2.55 },
  { pid: 0x30, name: 'Warm-ups since codes cleared', unit: '', bytes: 1, min: 0, max: 255, decode: d => A(d) },
  { pid: 0x31, name: 'Distance since codes cleared', unit: 'km', bytes: 2, min: 0, max: 65535, decode: d => U16(d) },
  { pid: 0x32, name: 'Evap. system vapor pressure', unit: 'Pa', bytes: 2, min: -8192, max: 8192, decode: d => S16(d) / 4 },
  { pid: 0x33, name: 'Absolute barometric pressure', unit: 'kPa', bytes: 1, min: 0, max: 255, decode: d => A(d) },
  { pid: 0x34, name: 'Wideband O2 sensor 1 current', unit: 'mA', bytes: 4, min: -128, max: 128, decode: d => ((d[2] << 8) | d[3]) / 256 - 128 },
  { pid: 0x3c, name: 'Catalyst temperature B1S1', unit: '°C', bytes: 2, min: -40, max: 6513.5, decode: d => U16(d) / 10 - 40 },
  { pid: 0x3d, name: 'Catalyst temperature B2S1', unit: '°C', bytes: 2, min: -40, max: 6513.5, decode: d => U16(d) / 10 - 40 },
  { pid: 0x3e, name: 'Catalyst temperature B1S2', unit: '°C', bytes: 2, min: -40, max: 6513.5, decode: d => U16(d) / 10 - 40 },
  { pid: 0x3f, name: 'Catalyst temperature B2S2', unit: '°C', bytes: 2, min: -40, max: 6513.5, decode: d => U16(d) / 10 - 40 },
  { pid: 0x42, name: 'Control module voltage', unit: 'V', bytes: 2, min: 0, max: 65.535, decode: d => U16(d) / 1000 },
  { pid: 0x43, name: 'Absolute load value', unit: '%', bytes: 2, min: 0, max: 25700, decode: d => U16(d) / 2.55 },
  { pid: 0x44, name: 'Commanded equivalence ratio', unit: 'λ', bytes: 2, min: 0, max: 2, decode: d => U16(d) / 32768 },
  { pid: 0x45, name: 'Relative throttle position', unit: '%', bytes: 1, min: 0, max: 100, decode: d => A(d) / 2.55 },
  { pid: 0x46, name: 'Ambient air temperature', unit: '°C', bytes: 1, min: -40, max: 215, decode: d => A(d) - 40 },
  { pid: 0x47, name: 'Absolute throttle position B', unit: '%', bytes: 1, min: 0, max: 100, decode: d => A(d) / 2.55 },
  { pid: 0x48, name: 'Absolute throttle position C', unit: '%', bytes: 1, min: 0, max: 100, decode: d => A(d) / 2.55 },
  { pid: 0x49, name: 'Accelerator pedal position D', unit: '%', bytes: 1, min: 0, max: 100, decode: d => A(d) / 2.55 },
  { pid: 0x4a, name: 'Accelerator pedal position E', unit: '%', bytes: 1, min: 0, max: 100, decode: d => A(d) / 2.55 },
  { pid: 0x4b, name: 'Accelerator pedal position F', unit: '%', bytes: 1, min: 0, max: 100, decode: d => A(d) / 2.55 },
  { pid: 0x4c, name: 'Commanded throttle actuator', unit: '%', bytes: 1, min: 0, max: 100, decode: d => A(d) / 2.55 },
  { pid: 0x4d, name: 'Time run with MIL on', unit: 'min', bytes: 2, min: 0, max: 65535, decode: d => U16(d) },
  { pid: 0x4e, name: 'Time since codes cleared', unit: 'min', bytes: 2, min: 0, max: 65535, decode: d => U16(d) },
  { pid: 0x51, name: 'Fuel type', unit: '', bytes: 1, min: 0, max: 255, decode: d => A(d) },
  { pid: 0x52, name: 'Ethanol fuel %', unit: '%', bytes: 1, min: 0, max: 100, decode: d => A(d) / 2.55 },
  { pid: 0x53, name: 'Absolute evap system vapor pressure', unit: 'kPa', bytes: 2, min: 0, max: 327.675, decode: d => U16(d) / 200 },
  { pid: 0x5a, name: 'Relative accelerator pedal position', unit: '%', bytes: 1, min: 0, max: 100, decode: d => A(d) / 2.55 },
  { pid: 0x5b, name: 'Hybrid battery pack remaining life', unit: '%', bytes: 1, min: 0, max: 100, decode: d => A(d) / 2.55 },
  { pid: 0x5c, name: 'Engine oil temperature', unit: '°C', bytes: 1, min: -40, max: 215, decode: d => A(d) - 40 },
  { pid: 0x5d, name: 'Fuel injection timing', unit: '°', bytes: 2, min: -210, max: 301.992, decode: d => S16(d) / 128 - 210 },
  { pid: 0x5e, name: 'Engine fuel rate', unit: 'L/h', bytes: 2, min: 0, max: 3212.75, decode: d => U16(d) / 20 },
  { pid: 0x61, name: 'Driver\'s demand engine torque', unit: '%', bytes: 1, min: -125, max: 130, decode: d => A(d) - 125 },
  { pid: 0x62, name: 'Actual engine torque', unit: '%', bytes: 1, min: -125, max: 130, decode: d => A(d) - 125 },
  { pid: 0x63, name: 'Engine reference torque', unit: 'Nm', bytes: 2, min: 0, max: 65535, decode: d => U16(d) },
]

export function pidById(pid: number): PidDef | undefined {
  return PIDS.find(p => p.pid === pid)
}

// Decode an OBD Mode 03 DTC frame into code strings like "P0301"
export function decodeDtcBytes(bytes: number[]): string[] {
  const out: string[] = []
  const sys = ['P', 'C', 'B', 'U']
  for (let i = 0; i + 1 < bytes.length; i += 2) {
    const a = bytes[i], b = bytes[i + 1]
    if (a === 0 && b === 0) continue
    out.push(`${sys[(a >> 6) & 3]}${(a >> 4) & 3}${a & 0xf}${(b >> 4) & 0xf}${b & 0xf}`)
  }
  return out
}

export const DTC_DESCRIPTIONS: Record<string, string> = {
  P0100: 'Mass or volume air flow circuit malfunction',
  P0101: 'Mass or volume air flow circuit range/performance',
  P0102: 'Mass or volume air flow circuit low input',
  P0103: 'Mass or volume air flow circuit high input',
  P0110: 'Intake air temperature sensor 1 circuit',
  P0113: 'Intake air temperature sensor 1 circuit high',
  P0115: 'Engine coolant temperature circuit malfunction',
  P0120: 'Throttle position sensor circuit malfunction',
  P0130: 'O2 sensor circuit malfunction (Bank 1 Sensor 1)',
  P0171: 'System too lean (Bank 1)',
  P0172: 'System too rich (Bank 1)',
  P0300: 'Random/multiple cylinder misfire detected',
  P0301: 'Cylinder 1 misfire detected',
  P0302: 'Cylinder 2 misfire detected',
  P0303: 'Cylinder 3 misfire detected',
  P0304: 'Cylinder 4 misfire detected',
  P0325: 'Knock sensor 1 circuit malfunction',
  P0335: 'Crankshaft position sensor A circuit',
  P0401: 'Exhaust gas recirculation flow insufficient',
  P0420: 'Catalyst system efficiency below threshold (Bank 1)',
  P0442: 'Evaporative emission system leak (small)',
  P0455: 'Evaporative emission system leak (large)',
  P0500: 'Vehicle speed sensor malfunction',
  C1201: 'ABS control system communication fault',
  U0100: 'Lost communication with ECM/PCM',
  U0121: 'Lost communication with ABS module',
}

export const FUEL_TYPES: Record<number, string> = {
  0: 'Not available', 1: 'Gasoline', 2: 'Methanol', 3: 'Ethanol', 4: 'Diesel',
  5: 'LPG', 6: 'CNG', 7: 'Propane', 8: 'Electric', 9: 'Bi-fuel gasoline',
  22: 'Flex-fuel (E85)', 23: 'Flex-fuel (E100)',
}
