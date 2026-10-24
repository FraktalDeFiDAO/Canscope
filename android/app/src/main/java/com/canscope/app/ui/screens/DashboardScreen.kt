package com.canscope.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canscope.app.state.LinkViewModel
import com.canscope.app.ui.BarGauge
import com.canscope.app.ui.DialGauge
import com.canscope.app.ui.PanelBox
import com.canscope.app.ui.theme.*

@Composable
fun DashboardScreen(vm: LinkViewModel) {
    val values by vm.pidValues.collectAsState()
    val stats by vm.stats.collectAsState()
    val total by vm.totalFrames.collectAsState()
    val themeIdx by vm.themeIndex.collectAsState()

    var rpmHist by remember { mutableStateOf(listOf<Double>()) }
    var spdHist by remember { mutableStateOf(listOf<Double>()) }
    LaunchedEffect(Unit) {
        while (true) {
            rpmHist = (rpmHist + (values[0x0C] ?: 0.0)).takeLast(60)
            spdHist = (spdHist + (values[0x0D] ?: 0.0)).takeLast(60)
            kotlinx.coroutines.delay(200)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(170.dp), contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // theme picker
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("STYLE", color = InkFaint, fontFamily = Mono, fontSize = 9.sp,
                    letterSpacing = 2.sp, modifier = Modifier.padding(top = 8.dp))
                AppThemes.forEachIndexed { i, t ->
                    val sel = i == themeIdx
                    Text(
                        t.label,
                        color = if (sel) t.live else InkDim,
                        fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.6.sp,
                        modifier = Modifier
                            .border(1.dp, if (sel) t.live else Line)
                            .clickable { vm.setTheme(i) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        item(span = { GridItemSpan(2) }) {
            DialGauge(values[0x0C], 0.0, 8000.0, "Engine RPM", "rpm",
                warnFrom = 5500.0, critFrom = 6800.0, majorTicks = 8)
        }
        item(span = { GridItemSpan(2) }) {
            DialGauge(values[0x0D], 0.0, 240.0, "Vehicle Speed", "km/h",
                warnFrom = 140.0, critFrom = 200.0, majorTicks = 6)
        }
        item { DialGauge(values[0x05], -40.0, 140.0, "Coolant", "°C", warnFrom = 105.0, critFrom = 115.0) }
        item { DialGauge(values[0x42], 10.0, 16.0, "Voltage", "V", decimals = 1, warnFrom = 15.0, majorTicks = 3) }
        item { DialGauge(values[0x04], 0.0, 100.0, "Load", "%") }
        item { DialGauge(values[0x11], 0.0, 100.0, "Throttle", "%") }

        item { Sparkline("RPM — last 12s", rpmHist, 8000.0, Live) }
        item { Sparkline("Speed — last 12s", spdHist, 240.0, Ok) }

        item { BarGauge(values[0x0F], -40.0, 100.0, "Intake Air", "°C") }
        item { BarGauge(values[0x10], 0.0, 60.0, "MAF Rate", "g/s") }
        item { BarGauge(values[0x0B], 0.0, 255.0, "MAP", "kPa", 0) }
        item { BarGauge(values[0x0E], -20.0, 50.0, "Timing Adv", "°") }
        item { BarGauge(values[0x2F], 0.0, 100.0, "Fuel Level", "%") }
        item { BarGauge(values[0x5E], 0.0, 20.0, "Fuel Rate", "L/h") }
        item { BarGauge(values[0x5C], -40.0, 150.0, "Oil Temp", "°C", 1, warnFrom = 125.0) }
        item { BarGauge(values[0x06], -25.0, 25.0, "STFT B1", "%") }
        item { BarGauge(values[0x07], -25.0, 25.0, "LTFT B1", "%") }
        item { BarGauge(values[0x3C], 0.0, 900.0, "Catalyst B1S1", "°C", 0, warnFrom = 750.0) }
        item { BarGauge(values[0x33], 80.0, 110.0, "Baro", "kPa", 0) }
        item { BarGauge(values[0x62], -50.0, 100.0, "Actual Torque", "%") }

        item {
            PanelBox(Modifier, title = "Bus Vitals") {
                Column(Modifier.padding(12.dp)) {
                    Text("FRAMES $total", color = Ink, fontFamily = Mono, fontSize = 12.sp)
                    Text("IDS ${stats.size}", color = Ink, fontFamily = Mono, fontSize = 12.sp)
                    Text("LIVE PIDS ${values.size}", color = Ok, fontFamily = Mono, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Sparkline(title: String, data: List<Double>, max: Double, color: androidx.compose.ui.graphics.Color) {
    PanelBox(Modifier, title = title, trailing = data.lastOrNull()?.let { "%.0f".format(it) }) {
        Canvas(Modifier.fillMaxWidth().height(48.dp).padding(4.dp)) {
            if (data.size > 1) {
                val path = Path()
                data.forEachIndexed { i, v ->
                    val x = i / 59f * size.width
                    val y = size.height - (v / max).coerceIn(0.0, 1.0).toFloat() * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
            }
        }
    }
}
