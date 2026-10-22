package com.canscope.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canscope.app.link.LinkState
import com.canscope.app.state.LinkMode
import com.canscope.app.state.LinkViewModel
import com.canscope.app.ui.PanelBox
import com.canscope.app.ui.StatusLed
import com.canscope.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ConnectionScreen(vm: LinkViewModel) {
    val mode by vm.mode.collectAsState()
    val state by vm.state.collectAsState()
    val wsUrl by vm.wsUrl.collectAsState()
    val bitrate by vm.bitrate.collectAsState()
    val listenOnly by vm.listenOnly.collectAsState()
    val error by vm.lastError.collectAsState()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {

        PanelBox(Modifier.fillMaxWidth(), title = "Data Source") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    LinkMode.SIMULATOR to "Built-in virtual ECU — no hardware needed",
                    LinkMode.WIFI to "ESP32 Wi-Fi WebSocket (AP or your LAN)",
                    LinkMode.BLUETOOTH to "Bluetooth LE direct to the ESP32",
                ).forEach { (m, desc) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = mode == m, onClick = { vm.setMode(m) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == m, onClick = { vm.setMode(m) })
                        Column {
                            Text(m.name, color = if (mode == m) Live else Ink, fontFamily = Mono, fontSize = 11.sp)
                            Text(desc, color = InkDim, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        if (mode == LinkMode.WIFI) {
            PanelBox(Modifier.fillMaxWidth(), title = "WebSocket") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        wsUrl, { vm.wsUrl.value = it },
                        label = { Text("ESP32 WS URL", fontFamily = Mono, fontSize = 10.sp) },
                        textStyle = LocalTextStyle.current.copy(fontFamily = Mono),
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                }
            }
        }

        if (mode != LinkMode.SIMULATOR) {
            PanelBox(Modifier.fillMaxWidth(), title = "Bus Config") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("BITRATE", color = InkDim, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.8.sp)
                    listOf(125_000, 250_000, 500_000, 1_000_000).forEach { b ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(bitrate == b, onClick = { vm.bitrate.value = b; vm.saveSettings() })
                            Text("${b / 1000} kbit/s${if (b == 500_000) "  (OBD-II standard)" else ""}",
                                color = Ink, fontFamily = Mono, fontSize = 11.sp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(listenOnly, { vm.listenOnly.value = it; vm.saveSettings() })
                        Text("LISTEN-ONLY MODE", color = InkDim, fontFamily = Mono, fontSize = 10.sp)
                    }
                }
            }
        }

        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    if (state == LinkState.CONNECTED) vm.disconnect() else vm.connect()
                }
            }) {
                Text(
                    if (state == LinkState.CONNECTED) "DISCONNECT" else "CONNECT",
                    color = if (state == LinkState.CONNECTED) Crit else Ok, fontFamily = Mono, fontSize = 11.sp,
                )
            }
            StatusLed(when (state) {
                LinkState.CONNECTED -> Ok; LinkState.CONNECTING -> Warn
                LinkState.ERROR -> Crit; LinkState.DISCONNECTED -> Line
            })
            Text(state.name, color = InkDim, fontFamily = Mono, fontSize = 10.sp)
        }
        error?.let { Text(it.uppercase(), color = Crit, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.padding(4.dp)) }

        // Vehicle identity (Mode 09 via ISO-TP) + PID discovery count
        val vehicle by vm.vehicle.collectAsState()
        val supported by vm.supportedPids.collectAsState()
        val bus by vm.busStatus.collectAsState()
        PanelBox(Modifier.fillMaxWidth(), title = "Vehicle Identity (Mode 09)") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "VIN" to (vehicle.vin?.let { v -> v + if (vehicle.vinSource == "sniffed") "  (sniffed)" else "" } ?: "—"),
                    "ECU NAME" to (vehicle.ecuName ?: "—"),
                    "CAL ID" to (vehicle.calibrationId ?: "—"),
                    "PIDS" to if (supported.isEmpty()) "not discovered" else "${supported.size} supported (bitmask)",
                ).forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(k, color = InkDim, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.6.sp,
                            modifier = Modifier.width(88.dp))
                        Text(v, color = Ink, fontFamily = Mono, fontSize = 12.sp)
                    }
                }
                bus?.let { b ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusLed(if (b.state == "running") Ok else if (b.state == "recovering") Warn else Crit)
                        Text("BUS ${b.state.uppercase()} · TX ERR ${b.txErrors} · RX ERR ${b.rxErrors}",
                            color = if (b.state == "running") Ok else Crit, fontFamily = Mono, fontSize = 10.sp)
                    }
                }
                if (state == LinkState.CONNECTED && mode != LinkMode.SIMULATOR) {
                    OutlinedButton(onClick = { vm.discoverSupportedPids(); vm.requestVehicleInfo() }) {
                        Text("RE-QUERY", color = Live, fontFamily = Mono, fontSize = 10.sp)
                    }
                }
            }
        }

        PanelBox(Modifier.fillMaxWidth(), title = "Wiring — ESP32 ↔ SN65HVD230 ↔ OBD-II") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf(
                    "GPIO 5 → CTX" to Live, "GPIO 4 → CRX" to Ok, "3V3 → VCC" to InkDim,
                    "GND → GND + Rs" to InkDim, "CANH → OBD-II pin 6" to Warn,
                    "CANL → OBD-II pin 14" to Warn, "Pin 16 = 12V — never connect!" to Crit,
                ).forEach { (t, c) -> Text(t, color = c, fontFamily = Mono, fontSize = 11.sp) }
            }
        }
    }
}
