package com.canscope.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// Same palette as the web dashboard.
val Bg = Color(0xFF050810)
val Panel = Color(0xFF0C1220)
val Panel2 = Color(0xFF111A2B)
val Line = Color(0xFF1A2540)
val Line2 = Color(0xFF243356)
val Ink = Color(0xFFCBC6B9)
val InkDim = Color(0xFF7D8AA5)
val InkFaint = Color(0xFF4C5A75)
val Live = Color(0xFF22D3EE)
val Ok = Color(0xFF34D399)
val Warn = Color(0xFFFBBF24)
val Crit = Color(0xFFF87171)
val Mag = Color(0xFFE879F9)

val Mono = FontFamily.Monospace

private val scheme = darkColorScheme(
    background = Bg, surface = Panel, surfaceVariant = Panel2,
    primary = Live, secondary = Ok, error = Crit,
    onBackground = Ink, onSurface = Ink, onPrimary = Bg,
    outline = Line,
)

@Composable
fun CANScopeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
