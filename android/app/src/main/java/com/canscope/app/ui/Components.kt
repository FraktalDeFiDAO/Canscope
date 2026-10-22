package com.canscope.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canscope.app.ui.theme.*
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun PanelBox(modifier: Modifier = Modifier, title: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.border(1.dp, Line)) {
        Row(
            Modifier.fillMaxWidth().border(1.dp, Line).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title.uppercase(), color = InkDim, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.8.sp)
            if (trailing != null) Text(trailing, color = InkFaint, fontFamily = Mono, fontSize = 9.sp)
        }
        content()
    }
}

@Composable
fun StatusLed(status: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp)) {
        drawCircle(color = status, radius = size.minDimension / 2, style = Stroke(width = 1.5.dp.toPx()))
        drawCircle(color = status, radius = size.minDimension / 4)
    }
}

@Composable
fun ArcGauge(
    value: Double?, min: Double, max: Double, label: String, unit: String,
    warnFrom: Double? = null, critFrom: Double? = null, decimals: Int = 0,
    modifier: Modifier = Modifier,
) {
    val pct = if (value == null || value.isNaN()) 0.0
        else ((value - min) / (max - min)).coerceIn(0.0, 1.0)
    val color = when {
        value == null -> InkFaint
        critFrom != null && value >= critFrom -> Crit
        warnFrom != null && value >= warnFrom -> Warn
        else -> Live
    }
    PanelBox(modifier, title = label, trailing = "${min.toInt()}–${max.toInt()}") {
        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(160f / 118f)) {
                val w = size.width
                val cx = w / 2; val cy = size.height * 0.66f
                val r = min(w, size.height * 1.35f) / 2 * 0.82f
                val start = 135f; val sweep = 270f
                val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                // track
                drawArc(Line, start, sweep, false, topLeft = Offset(cx - r, cy - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2), style = stroke)
                // value arc
                if (pct > 0) drawArc(color, start, (sweep * pct).toFloat(), false,
                    topLeft = Offset(cx - r, cy - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2), style = stroke)
                // ticks
                for (i in 0..24) {
                    val deg = start + sweep * i / 24
                    val a = Math.toRadians((deg - 90).toDouble())
                    val major = i % 6 == 0
                    val r1 = r + if (major) 6.dp.toPx() else 3.dp.toPx()
                    val r2 = r + 1.dp.toPx()
                    drawLine(
                        if (major) InkFaint else Line2,
                        Offset((cx + r2 * cos(a)).toFloat(), (cy + r2 * sin(a)).toFloat()),
                        Offset((cx + r1 * cos(a)).toFloat(), (cy + r1 * sin(a)).toFloat()),
                        strokeWidth = if (major) 1.5.dp.toPx() else 1.dp.toPx(),
                    )
                }
                // needle
                val na = Math.toRadians((start + sweep * pct - 90))
                drawLine(color, Offset(cx, cy),
                    Offset((cx + (r - 14.dp.toPx()) * cos(na)).toFloat(), (cy + (r - 14.dp.toPx()) * sin(na)).toFloat()),
                    strokeWidth = 2.dp.toPx())
                drawCircle(color, 4.dp.toPx(), Offset(cx, cy))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 46.dp)) {
                Text(
                    if (value == null || value.isNaN()) "——" else String.format(Locale.US, "%.${decimals}f", value),
                    color = Ink, fontFamily = Mono, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(unit.uppercase(), color = InkFaint, fontFamily = Mono, fontSize = 8.sp, letterSpacing = 2.sp)
            }
        }
    }
}

@Composable
fun BarGauge(
    value: Double?, min: Double, max: Double, label: String, unit: String,
    decimals: Int = 1, warnFrom: Double? = null, critFrom: Double? = null,
    modifier: Modifier = Modifier,
) {
    val pct = if (value == null || value.isNaN()) 0f
        else (((value - min) / (max - min)) * 100).coerceIn(0.0, 100.0).toFloat()
    val color = when {
        value == null -> InkFaint
        critFrom != null && value >= critFrom -> Crit
        warnFrom != null && value >= warnFrom -> Warn
        else -> Ok
    }
    Column(modifier.border(1.dp, Line).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label.uppercase(), color = InkDim, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.8.sp)
            Text(unit, color = InkFaint, fontFamily = Mono, fontSize = 9.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (value == null || value.isNaN()) "——" else String.format(Locale.US, "%.${decimals}f", value),
            color = color, fontFamily = Mono, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).border(0.dp, Color.Transparent)) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Line)
                drawRect(color, size = androidx.compose.ui.geometry.Size(size.width * pct / 100f, size.height))
            }
        }
    }
}
