package com.canscope.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canscope.app.protocol.PIDS
import com.canscope.app.state.LinkViewModel
import com.canscope.app.ui.StatusLed
import com.canscope.app.ui.theme.*
import java.util.Locale

@Composable
fun PidExplorerScreen(vm: LinkViewModel) {
    val values by vm.pidValues.collectAsState()
    var query by remember { mutableStateOf("") }
    var liveOnly by remember { mutableStateOf(false) }

    val rows = PIDS.filter { p ->
        (!liveOnly || values.containsKey(p.pid)) &&
        (query.isBlank() || p.name.lowercase().contains(query.lowercase()) ||
            "%02x".format(p.pid).contains(query.lowercase()))
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                query, { query = it }, Modifier.weight(1f).height(48.dp),
                placeholder = { Text("FILTER BY NAME OR HEX…", fontFamily = Mono, fontSize = 10.sp, color = InkFaint) },
                textStyle = LocalTextStyle.current.copy(fontFamily = Mono, fontSize = 12.sp, color = Ink),
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(liveOnly, { liveOnly = it })
                Text("LIVE", color = InkDim, fontFamily = Mono, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("OBD-II MODE 01 — ${PIDS.size} PIDS", color = InkDim, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.8.sp)
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.pid }) { p ->
                val v = values[p.pid]
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("0x%02X".format(p.pid), color = Live, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.width(48.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = Ink, fontSize = 12.sp)
                        Text("${p.min}–${p.max} ${p.unit}", color = InkFaint, fontFamily = Mono, fontSize = 9.sp)
                    }
                    Text(
                        v?.let { String.format(Locale.US, if (kotlin.math.abs(it) >= 100) "%.0f" else "%.2f", it) } ?: "——",
                        color = if (v != null) Ok else InkFaint, fontFamily = Mono, fontSize = 13.sp,
                    )
                    Spacer(Modifier.width(10.dp))
                    StatusLed(if (v != null) Ok else Line)
                }
                HorizontalDivider(color = Line)
            }
        }
    }
}
