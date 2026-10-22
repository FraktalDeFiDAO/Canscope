package com.canscope.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canscope.app.link.LinkState
import com.canscope.app.protocol.IsoTp
import com.canscope.app.protocol.PIDS
import com.canscope.app.state.LinkViewModel
import com.canscope.app.ui.PanelBox
import com.canscope.app.ui.theme.*

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TransmitScreen(vm: LinkViewModel) {
    var idHex by remember { mutableStateOf("7DF") }
    var dataHex by remember { mutableStateOf("02 01 0C 00 00 00 00 00") }
    var ext by remember { mutableStateOf(false) }
    var isotp by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    val state by vm.state.collectAsState()
    val connected = state == LinkState.CONNECTED

    val presets = PIDS.filter { it.pid in listOf(0x0C, 0x0D, 0x05, 0x0F, 0x11, 0x10, 0x42, 0x2F) }

    LazyColumn(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            PanelBox(Modifier.fillMaxWidth(), title = "Transmit Raw Frame") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(idHex, { idHex = it }, label = { Text("CAN ID (HEX)", fontFamily = Mono, fontSize = 10.sp) },
                        textStyle = LocalTextStyle.current.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(dataHex, { dataHex = it }, label = { Text("DATA BYTES (HEX, MAX 8)", fontFamily = Mono, fontSize = 10.sp) },
                        textStyle = LocalTextStyle.current.copy(fontFamily = Mono), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row {
                        Checkbox(ext, { ext = it })
                        Text("EXTENDED (29-BIT) ID", color = InkDim, fontFamily = Mono, fontSize = 10.sp)
                    }
                    Row {
                        Checkbox(isotp, { isotp = it })
                        Text("ISO-TP — SEGMENT LONG PAYLOADS (FF/CF)", color = InkDim, fontFamily = Mono, fontSize = 10.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            val id = idHex.toLongOrNull(16)
                            val parts = dataHex.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
                            val bytes = parts.mapNotNull { it.toIntOrNull(16) }.map { it.toByte() }.toByteArray()
                            val maxLen = if (isotp) 4095 else 8
                            when {
                                id == null || id < 0 || id > 0x1FFFFFFF -> feedback = "Invalid CAN ID"
                                parts.size != bytes.size || bytes.isEmpty() || bytes.size > maxLen ->
                                    feedback = if (isotp) "Data must be 1–4095 hex bytes" else "Data must be 1–8 hex bytes"
                                isotp -> {
                                    val frames = IsoTp.segment(bytes)
                                    vm.scopeSendIsoTp(id, frames, ext)
                                    feedback = "Sent ISO-TP: ${frames.size} frames (${bytes.size} B)"
                                }
                                else -> { vm.txFrame(id, bytes, ext); feedback = "Sent 0x%X".format(id) }
                            }
                        }, enabled = connected) { Text("SEND FRAME", color = Live, fontFamily = Mono, fontSize = 11.sp) }
                        if (feedback.isNotEmpty()) Text(feedback, color = Ok, fontFamily = Mono, fontSize = 11.sp)
                    }
                    if (!connected) Text("CONNECT FIRST", color = Warn, fontFamily = Mono, fontSize = 10.sp)
                }
            }
        }
        item {
            PanelBox(Modifier.fillMaxWidth(), title = "OBD-II Request Presets") {
                FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { p ->
                        OutlinedButton(onClick = {
                            idHex = "7DF"; dataHex = "02 01 %02X 00 00 00 00 00".format(p.pid); ext = false
                        }) { Text("0x%02X %s".format(p.pid, p.name), fontFamily = Mono, fontSize = 9.sp, color = InkDim) }
                    }
                }
            }
        }
        item {
            PanelBox(Modifier.fillMaxWidth(), title = "Safety") {
                Text(
                    "OBD-II 0x7DF requests are read-only and safe. Injecting arbitrary frames onto a live powertrain bus can interfere with steering, braking and throttle ECUs. Test writes on a bench rig first.",
                    color = InkDim, fontSize = 13.sp, modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}
