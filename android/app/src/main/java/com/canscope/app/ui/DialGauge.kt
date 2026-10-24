package com.canscope.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canscope.app.ui.theme.*
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Torque-style round dial gauge: bezel ring, numbered scale, colored
 * warn/crit zones, swept needle and a digital readout in the hub.
 */
@Composable
fun DialGauge(
    value: Double?, min: Double, max: Double, label: String, unit: String,
    warnFrom: Double? = null, critFrom: Double? = null, decimals: Int = 0,
    majorTicks: Int = 6,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val pct = if (value == null || value.isNaN()) 0.0
        else ((value - min) / (max - min)).coerceIn(0.0, 1.0)
    val needleColor = when {
        value == null -> InkFaint
        critFrom != null && value >= critFrom -> Crit
        warnFrom != null && value >= warnFrom -> Warn
        else -> Live
    }

    Column(modifier.border(1.dp, Line)) {
        // header
        Row(
            Modifier.fillMaxWidth().border(1.dp, Line).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label.uppercase(), color = InkDim, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.6.sp)
            Text(unit.uppercase(), color = InkFaint, fontFamily = Mono, fontSize = 9.sp)
        }
        Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                val d = min(size.width, size.height)
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = d / 2f * 0.92f
                val start = 135f
                val sweep = 270f
                fun angle(f: Double) = Math.toRadians((start + sweep * f - 90).toDouble())

                // bezel
                drawCircle(Line2, r * 1.04f, Offset(cx, cy), style = Stroke(width = 2.dp.toPx()))
                drawCircle(Panel2, r * 1.005f, Offset(cx, cy))

                // warn / crit zones on the outer rim
                val zoneStyle = Stroke(width = 4.dp.toPx())
                if (warnFrom != null) {
                    val f0 = ((warnFrom - min) / (max - min)).coerceIn(0.0, 1.0)
                    val f1 = if (critFrom != null) ((critFrom - min) / (max - min)).coerceIn(0.0, 1.0) else 1.0
                    if (f1 > f0) drawArc(Warn, start + (sweep * f0).toFloat(), (sweep * (f1 - f0)).toFloat(), false,
                        topLeft = Offset(cx - r * 0.98f, cy - r * 0.98f), size = Size(r * 1.96f, r * 1.96f), style = zoneStyle)
                }
                if (critFrom != null) {
                    val f0 = ((critFrom - min) / (max - min)).coerceIn(0.0, 1.0)
                    if (f0 < 1.0) drawArc(Crit, start + (sweep * f0).toFloat(), (sweep * (1.0 - f0)).toFloat(), false,
                        topLeft = Offset(cx - r * 0.98f, cy - r * 0.98f), size = Size(r * 1.96f, r * 1.96f), style = zoneStyle)
                }

                // scale arc
                drawArc(Line2, start, sweep, false,
                    topLeft = Offset(cx - r * 0.88f, cy - r * 0.88f), size = Size(r * 1.76f, r * 1.76f),
                    style = Stroke(width = 1.5.dp.toPx()))

                // ticks + numbers
                val minorPerMajor = 5
                val total = majorTicks * minorPerMajor
                for (i in 0..total) {
                    val f = i / total.toDouble()
                    val a = angle(f)
                    val major = i % minorPerMajor == 0
                    val rOuter = r * 0.88f
                    val rInner = rOuter - (if (major) 8.dp else 4.dp).toPx()
                    drawLine(
                        if (major) InkDim else Line2,
                        Offset((cx + rInner * cos(a)).toFloat(), (cy + rInner * sin(a)).toFloat()),
                        Offset((cx + rOuter * cos(a)).toFloat(), (cy + rOuter * sin(a)).toFloat()),
                        strokeWidth = (if (major) 2.dp else 1.dp).toPx(),
                    )
                    if (major) {
                        val tickVal = min + (max - min) * f
                        val txt = if (max - min >= 100) "%.0f".format(tickVal / (if (max >= 1000) 1000.0 else 1.0))
                            else "%.0f".format(tickVal)
                        val lr = r * 0.62f
                        val m = textMeasurer.measure(txt, TextStyle(color = InkFaint, fontFamily = Mono, fontSize = 9.sp))
                        drawText(m, topLeft = Offset(
                            (cx + lr * cos(a)).toFloat() - m.size.width / 2f,
                            (cy + lr * sin(a)).toFloat() - m.size.height / 2f))
                    }
                }
                if (max >= 1000) {
                    val m = textMeasurer.measure("x1000", TextStyle(color = InkFaint, fontFamily = Mono, fontSize = 8.sp))
                    drawText(m, topLeft = Offset(cx - m.size.width / 2f, cy - r * 0.42f))
                }

                // needle
                val na = angle(pct)
                drawLine(needleColor, Offset(cx, cy),
                    Offset((cx + r * 0.80f * cos(na)).toFloat(), (cy + r * 0.80f * sin(na)).toFloat()),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                // needle tail
                drawLine(needleColor, Offset(cx, cy),
                    Offset((cx - r * 0.18f * cos(na)).toFloat(), (cy - r * 0.18f * sin(na)).toFloat()),
                    strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                // hub
                drawCircle(Panel, r * 0.14f, Offset(cx, cy))
                drawCircle(Line2, r * 0.14f, Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))
                drawCircle(needleColor, 3.dp.toPx(), Offset(cx, cy))

                // digital readout
                val txt = if (value == null || value.isNaN()) "—"
                    else String.format(Locale.US, "%.${decimals}f", value)
                val m = textMeasurer.measure(txt, TextStyle(color = needleColor, fontFamily = Mono,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold))
                drawText(m, topLeft = Offset(cx - m.size.width / 2f, cy + r * 0.30f))
            }
        }
    }
}
