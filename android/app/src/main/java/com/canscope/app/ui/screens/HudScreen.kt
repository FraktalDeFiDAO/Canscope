package com.canscope.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canscope.app.state.LinkViewModel
import com.canscope.app.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Heads-up display: giant speed readout for windshield reflection.
 * Tap MIRROR to flip the image horizontally; screen stays on while open.
 */
@Composable
fun HudScreen(vm: LinkViewModel) {
    val values by vm.pidValues.collectAsState()
    var mirrored by remember { mutableStateOf(true) }
    var clock by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        while (true) { clock = fmt.format(Date()); delay(1_000) }
    }

    val speed = values[0x0D]
    val rpm = values[0x0C]
    val coolant = values[0x05]
    val volts = values[0x42]
    val speedColor = when {
        speed == null -> InkFaint
        speed >= 200 -> Crit
        speed >= 140 -> Warn
        else -> Live
    }

    Box(Modifier.fillMaxSize().background(Color.Black).keepScreenOn()) {
        // mirrored content
        Column(
            Modifier.fillMaxSize().graphicsLayer { scaleX = if (mirrored) -1f else 1f }
                .padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock, color = InkDim, fontFamily = Mono, fontSize = 20.sp)
                Text(
                    if (coolant == null) "—" else "%.0f°C".format(coolant),
                    color = if ((coolant ?: 0.0) >= 105) Crit else InkDim,
                    fontFamily = Mono, fontSize = 20.sp,
                )
                Text(
                    if (volts == null) "—" else "%.1fV".format(volts),
                    color = InkDim, fontFamily = Mono, fontSize = 20.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    if (speed == null) "—" else "%.0f".format(speed),
                    color = speedColor, fontFamily = Mono, fontSize = 150.sp, fontWeight = FontWeight.Bold,
                )
                Text("KM/H", color = InkFaint, fontFamily = Mono, fontSize = 22.sp,
                    letterSpacing = 4.sp, modifier = Modifier.padding(start = 12.dp, bottom = 26.dp))
            }
            Spacer(Modifier.height(16.dp))
            // RPM bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("RPM", color = InkFaint, fontFamily = Mono, fontSize = 14.sp, letterSpacing = 3.sp)
                Spacer(Modifier.width(12.dp))
                Canvas(Modifier.weight(1f).height(14.dp)) {
                    drawRect(Line)
                    val f = ((rpm ?: 0.0) / 8000.0).coerceIn(0.0, 1.0).toFloat()
                    val c = when {
                        (rpm ?: 0.0) >= 6800 -> Crit
                        (rpm ?: 0.0) >= 5500 -> Warn
                        else -> Live
                    }
                    drawRect(c, size = size.copy(width = size.width * f))
                }
                Spacer(Modifier.width(12.dp))
                Text(if (rpm == null) "—" else "%.0f".format(rpm),
                    color = InkDim, fontFamily = Mono, fontSize = 20.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
        // controls (not mirrored)
        Row(
            Modifier.align(Alignment.BottomEnd).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "MIRROR", color = if (mirrored) Live else InkDim, fontFamily = Mono,
                fontSize = 10.sp, letterSpacing = 2.sp,
                modifier = Modifier.border(1.dp, if (mirrored) Live else Line)
                    .clickable { mirrored = !mirrored }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
