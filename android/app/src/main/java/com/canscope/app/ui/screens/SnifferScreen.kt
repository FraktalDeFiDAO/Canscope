package com.canscope.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.canscope.app.protocol.DbcParser
import com.canscope.app.state.LinkViewModel
import com.canscope.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SnifferScreen(vm: LinkViewModel) {
    val frames by vm.frames.collectAsState()
    val stats by vm.stats.collectAsState()
    val paused by vm.paused.collectAsState()
    var filter by remember { mutableStateOf("") }
    var byId by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val dbc by vm.dbc.collectAsState()
    val ctx = LocalContext.current
    val dbcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u ->
            runCatching {
                ctx.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { vm.loadDbc(it) }
        }
    }

    LaunchedEffect(frames.size) {
        if (!byId && frames.isNotEmpty()) listState.animateScrollToItem(frames.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(byId.not(), { byId = false }, label = { Text("LOG", fontFamily = Mono, fontSize = 10.sp) })
            FilterChip(byId, { byId = true }, label = { Text("BY ID", fontFamily = Mono, fontSize = 10.sp) })
            OutlinedTextField(
                filter, { filter = it }, Modifier.weight(1f).height(48.dp),
                placeholder = { Text("ID FILTER", fontFamily = Mono, fontSize = 10.sp, color = InkFaint) },
                textStyle = LocalTextStyle.current.copy(fontFamily = Mono, fontSize = 12.sp, color = Ink),
                singleLine = true,
            )
            TextButton({ vm.paused.value = !paused }) {
                Text(if (paused) "RESUME" else "PAUSE", color = if (paused) Warn else InkDim, fontFamily = Mono, fontSize = 10.sp)
            }
            TextButton({ vm.clearLog() }) { Text("CLEAR", color = InkDim, fontFamily = Mono, fontSize = 10.sp) }
            if (dbc == null) {
                TextButton({ dbcPicker.launch(arrayOf("*/*")) }) {
                    Text("LOAD DBC", color = Live, fontFamily = Mono, fontSize = 10.sp)
                }
            } else {
                TextButton({ vm.loadDbc(null) }) { Text("DBC ✕", color = Warn, fontFamily = Mono, fontSize = 10.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (!byId) {
            val shown = frames.filter { filter.isBlank() || "%X".format(it.id).contains(filter.uppercase()) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(shown) { f ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(fmt.format(Date(f.ts)), color = InkFaint, fontFamily = Mono, fontSize = 9.sp)
                            Text(if (f.dir == com.canscope.app.protocol.CanFrame.Dir.TX) "TX" else "RX",
                                color = if (f.dir == com.canscope.app.protocol.CanFrame.Dir.TX) Mag else Live, fontFamily = Mono, fontSize = 9.sp)
                            Text("%03X".format(f.id) + if (f.ext) "x" else "", color = Ink, fontFamily = Mono, fontSize = 9.sp)
                            Text("[%d]".format(f.dlc), color = InkDim, fontFamily = Mono, fontSize = 9.sp)
                            Text(f.data.joinToString(" ") { "%02X".format(it) }, color = Ok, fontFamily = Mono, fontSize = 9.sp)
                        }
                        dbc?.messages?.get(f.id)?.let { msg ->
                            val decoded = DbcParser.decodeMessage(msg, f.data)
                                .joinToString("  ") { (sig, v) -> "${sig.name}=${"%.2f".format(v).trimEnd('0').trimEnd('.')}${sig.unit}" }
                            Text(decoded, color = Warn, fontFamily = Mono, fontSize = 9.sp,
                                modifier = Modifier.padding(start = 84.dp))
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(stats.values.sortedByDescending { it.count }) { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("%03X".format(s.id), color = Live, fontFamily = Mono, fontSize = 11.sp)
                        dbc?.messageName(s.id)?.let { Text(it, color = Warn, fontFamily = Mono, fontSize = 10.sp) }
                        Text("${s.count}", color = Ink, fontFamily = Mono, fontSize = 11.sp)
                        Text("%.1f Hz".format(s.rate), color = InkDim, fontFamily = Mono, fontSize = 11.sp)
                        Text(s.lastData.joinToString(" ") { "%02X".format(it) }, color = InkDim, fontFamily = Mono, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
