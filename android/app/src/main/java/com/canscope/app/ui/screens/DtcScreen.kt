package com.canscope.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canscope.app.link.LinkState
import com.canscope.app.protocol.DTC_DESCRIPTIONS
import com.canscope.app.state.LinkViewModel
import com.canscope.app.ui.PanelBox
import com.canscope.app.ui.theme.*

@Composable
fun DtcScreen(vm: LinkViewModel) {
    val codes by vm.dtcCodes.collectAsState()
    val pending by vm.pendingDtcs.collectAsState()
    val permanent by vm.permanentDtcs.collectAsState()
    val freeze by vm.freezeFrames.collectAsState()
    val monitors by vm.monitors.collectAsState()
    val state by vm.state.collectAsState()
    val connected = state == LinkState.CONNECTED
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            PanelBox(Modifier.fillMaxWidth(), title = "Diagnostic Trouble Codes") {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ vm.readDtcs() }, enabled = connected) {
                        Text("READ (MODE 03)", color = Live, fontFamily = Mono, fontSize = 10.sp)
                    }
                    OutlinedButton({ vm.readPendingDtcs() }, enabled = connected) {
                        Text("PENDING (07)", color = Warn, fontFamily = Mono, fontSize = 10.sp)
                    }
                    OutlinedButton({ vm.readPermanentDtcs() }, enabled = connected) {
                        Text("PERMANENT (0A)", color = Mag, fontFamily = Mono, fontSize = 10.sp)
                    }
                    OutlinedButton({ confirmClear = true }, enabled = connected && codes.isNotEmpty()) {
                        Text("CLEAR (04)", color = Crit, fontFamily = Mono, fontSize = 10.sp)
                    }
                }
                if (codes.isEmpty()) {
                    Text("NO STORED DTCS — MIL OFF", color = Ok, fontFamily = Mono, fontSize = 11.sp,
                        modifier = Modifier.padding(24.dp))
                }
            }
        }

        items(codes) { code ->
            PanelBox(Modifier.fillMaxWidth(), title = code) {
                Column(Modifier.padding(12.dp)) {
                    Text(DTC_DESCRIPTIONS[code] ?: "No description in local database", color = Ink, fontSize = 13.sp)
                    Text(
                        when (code.first()) {
                            'P' -> "POWERTRAIN"; 'C' -> "CHASSIS"; 'B' -> "BODY"; else -> "NETWORK"
                        },
                        color = InkFaint, fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.4.sp,
                    )
                }
            }
        }

        if (pending.isNotEmpty()) {
            item {
                PanelBox(Modifier.fillMaxWidth(), title = "Pending DTCs — Mode 07") {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        pending.forEach { c ->
                            Text("$c — ${DTC_DESCRIPTIONS[c] ?: "no description"}", color = Warn, fontFamily = Mono, fontSize = 11.sp)
                        }
                        Text("DETECTED BUT NOT YET CONFIRMED — MAY SELF-CLEAR",
                            color = InkFaint, fontFamily = Mono, fontSize = 9.sp)
                    }
                }
            }
        }

        if (permanent.isNotEmpty()) {
            item {
                PanelBox(Modifier.fillMaxWidth(), title = "Permanent DTCs — Mode 0A") {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        permanent.forEach { c ->
                            Text("$c — ${DTC_DESCRIPTIONS[c] ?: "no description"}", color = Mag, fontFamily = Mono, fontSize = 11.sp)
                        }
                        Text("CANNOT BE CLEARED — THE ECU CLEARS IT AFTER SELF-HEALING",
                            color = InkFaint, fontFamily = Mono, fontSize = 9.sp)
                    }
                }
            }
        }

        item {
            PanelBox(Modifier.fillMaxWidth(), title = "Freeze Frame — Mode 02") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton({ vm.readFreezeFrame() }, enabled = connected) {
                        Text("READ", color = Live, fontFamily = Mono, fontSize = 10.sp)
                    }
                    if (freeze.isEmpty()) {
                        Text("SENSOR SNAPSHOT WHEN THE DTC SET", color = InkFaint, fontFamily = Mono, fontSize = 9.sp)
                    } else {
                        freeze.forEach { (pid, v) ->
                            Text("PID 0x%02X  %.1f".format(pid, v), color = Ink, fontFamily = Mono, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        item {
            PanelBox(Modifier.fillMaxWidth(), title = "On-Board Monitoring — Mode 06") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton({ vm.readMonitors() }, enabled = connected) {
                        Text("READ", color = Live, fontFamily = Mono, fontSize = 10.sp)
                    }
                    if (monitors.isEmpty()) {
                        Text("MISFIRE / CATALYST / O₂ TEST COUNTERS — RAW VALUES", color = InkFaint, fontFamily = Mono, fontSize = 9.sp)
                    } else {
                        monitors.take(32).forEach { (mid, tid, raw) ->
                            Text("MID %02X TID %02X  %s".format(mid, tid,
                                raw.joinToString(" ") { "%02X".format(it) }),
                                color = Ink, fontFamily = Mono, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all DTCs?", fontFamily = Mono, color = Crit) },
            text = { Text("Mode 04 erases stored codes, freeze-frame data and readiness monitors. The light may return if the fault is still present.", color = InkDim) },
            confirmButton = {
                TextButton({ vm.clearDtcs(); confirmClear = false }) { Text("CLEAR CODES", color = Crit, fontFamily = Mono) }
            },
            dismissButton = {
                TextButton({ confirmClear = false }) { Text("CANCEL", color = InkDim, fontFamily = Mono) }
            },
            containerColor = Panel,
        )
    }
}
