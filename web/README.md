# CANScope Web Dashboard

Vue 3 + TypeScript + Vite + Tailwind CSS 4 + Pinia dashboard for the CANScope ESP32 CAN bus diagnostic tool.

## Features

- Live gauge dashboard (RPM, speed, coolant + 15 secondary PIDs, sparklines)
- OBD-II Mode 01 PID explorer with SAE J1979 decode database
- CAN sniffer: live frame log, per-ID stats, DBC file decoding
- Frame transmit with ISO-TP segmentation (up to 4095-byte payloads)
- DTC read/clear (modes 03/04), pending (07), permanent (0A), freeze frame (02), monitors (06)
- Mode 09 vehicle identity (VIN / ECU name / calibration) + passive VIN sniffing (ISO 3780)
- Session recording, local playback, upload to a canscope-server
- Transports: built-in simulator, WebSocket (ESP32 Wi-Fi bridge), Web Bluetooth / native BLE
- Auto-reconnect with backoff, TWAI bus-off status, hardware acceptance filters

## Develop

```sh
npm install
npm run dev      # dev server
npm run build    # production build to dist/
npx vitest run   # unit tests (ISO-TP, OBD, DBC, VIN)
```

Hash-based routing — the app works from any static host, including the ESP32's flash.
