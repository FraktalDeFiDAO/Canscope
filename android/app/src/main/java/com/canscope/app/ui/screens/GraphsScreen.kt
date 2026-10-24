package com.canscope.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canscope.app.protocol.pidById
import com.canscope.app.state.LinkViewModel
import com.canscope.app.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Locale

/** Graphable PIDs in a sensible default order. */
private val GRAPH_PIDS = listOf(
    0x0C, 0x0D, 0x05, 0x04, 0x11, 0x10, 0x0B, 0x0E, 0x2F, 0x5E, 0x42, 0x5C, 0x0F, 0x3C,
)

/** Torque-style live graphs: plot up to 4 PIDs over the last ~48 s. */
@Composable
fun GraphsScreen(vm: LinkViewModel) {
    val values by vm.pidValues.collectAsState()
    var selected by remember { mutableStateOf(setOf(0x0C, 0x0D)) }
    var history by remember { mutableStateOf(mapOf<Int, List<Double>>()) }

    LaunchedEffect(selected) {
        while (true) {
            val next = history.toMutableMap()
            for (pid in selected) {
                val h = (next[pid] ?: emptyList()) + (values[pid] ?: 0.0)
                next[pid] = h.takeLast(240)
            }
            history = next
            delay(200)
        }
    }

    val colors = listOf(Live, Ok, Warn, Mag)

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        // PID picker
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(GRAPH_PIDS) { pid ->
                val def = pidById(pid) ?: return@items
                val sel = pid in selected
                val idx = selected.toList().indexOf(pid)
                Text(
                    def.name.uppercase(Locale.US).take(18),
                    color = if (sel) colors[idx.coerceIn(0, 3)] else InkDim,
                    fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.2.sp,
                    modifier = Modifier
                        .border(1.dp, if (sel) colors[idx.coerceIn(0, 3)] else Line)
                        .clickable {
                            selected = if (sel) selected - pid
                                else if (selected.size < 4) selected + pid else selected
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // legend
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            selected.toList().forEachIndexed { i, pid ->
                val def = pidById(pid) ?: return@forEachIndexed
                val h = history[pid] ?: emptyList()
                val cur = values[pid]
                Column {
                    Text(def.name.take(16), color = colors[i], fontFamily = Mono, fontSize = 9.sp)
                    Text(
                        (cur?.let { "%.1f".format(it) } ?: "—") +
                            "  min ${h.minOrNull()?.let { "%.1f".format(it) } ?: "—"}" +
                            "  max ${h.maxOrNull()?.let { "%.1f".format(it) } ?: "—"}",
                        color = InkDim, fontFamily = Mono, fontSize = 9.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // plot
        Canvas(Modifier.fillMaxWidth().weight(1f).border(1.dp, Line)) {
            val w = size.width; val h = size.height
            // grid: 4 horizontal + 6 vertical
            for (i in 1..3) drawLine(Line, Offset(0f, h * i / 4), Offset(w, h * i / 4), 1f)
            for (i in 1..5) drawLine(Line, Offset(w * i / 6, 0f), Offset(w * i / 6, h), 1f)

            selected.toList().forEachIndexed { i, pid ->
                val def = pidById(pid) ?: return@forEachIndexed
                val data = history[pid] ?: return@forEachIndexed
                if (data.size < 2) return@forEachIndexed
                val span = (def.max - def.min).takeIf { it > 0 } ?: 1.0
                val path = Path()
                data.forEachIndexed { j, v ->
                    val x = j / 239f * w
                    val y = h - ((v - def.min) / span).coerceIn(0.0, 1.0).toFloat() * h
                    if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, colors[i], style = Stroke(width = 2.dp.toPx()))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("LAST 48 s — each trace normalised to its PID range; tap chips to toggle (max 4)",
            color = InkFaint, fontFamily = Mono, fontSize = 8.sp)
    }
}
