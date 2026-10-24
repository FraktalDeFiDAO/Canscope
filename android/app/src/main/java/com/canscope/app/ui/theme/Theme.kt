package com.canscope.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** A complete dashboard palette — Torque-style switchable themes. */
data class AppTheme(
    val id: String,
    val label: String,
    val bg: Color, val panel: Color, val panel2: Color,
    val line: Color, val line2: Color,
    val ink: Color, val inkDim: Color, val inkFaint: Color,
    val live: Color, val ok: Color, val warn: Color, val crit: Color, val mag: Color,
)

val THEME_MISSION = AppTheme(
    id = "mission", label = "MISSION",
    bg = Color(0xFF050810), panel = Color(0xFF0C1220), panel2 = Color(0xFF111A2B),
    line = Color(0xFF1A2540), line2 = Color(0xFF243356),
    ink = Color(0xFFCBC6B9), inkDim = Color(0xFF7D8AA5), inkFaint = Color(0xFF4C5A75),
    live = Color(0xFF22D3EE), ok = Color(0xFF34D399), warn = Color(0xFFFBBF24),
    crit = Color(0xFFF87171), mag = Color(0xFFE879F9),
)

/** Classic Torque look: warm amber dials on charcoal. */
val THEME_TORQUE = AppTheme(
    id = "torque", label = "TORQUE",
    bg = Color(0xFF0A0805), panel = Color(0xFF161009), panel2 = Color(0xFF1F1810),
    line = Color(0xFF332717), line2 = Color(0xFF4A3A22),
    ink = Color(0xFFE8DCC4), inkDim = Color(0xFFA89878), inkFaint = Color(0xFF6B5C42),
    live = Color(0xFFFFB300), ok = Color(0xFF9CCC65), warn = Color(0xFFFF7043),
    crit = Color(0xFFEF5350), mag = Color(0xFFBA68C8),
)

/** All-red cockpit for night driving — preserves night vision. */
val THEME_NIGHT = AppTheme(
    id = "night", label = "NIGHT",
    bg = Color(0xFF050000), panel = Color(0xFF120404), panel2 = Color(0xFF1C0808),
    line = Color(0xFF331010), line2 = Color(0xFF4D1818),
    ink = Color(0xFFE8B8B8), inkDim = Color(0xFFA06868), inkFaint = Color(0xFF6B4040),
    live = Color(0xFFFF4444), ok = Color(0xFFCC3333), warn = Color(0xFFFF7744),
    crit = Color(0xFFFF2222), mag = Color(0xFFDD5577),
)

/** Green-phosphor CRT style. */
val THEME_PHOSPHOR = AppTheme(
    id = "phosphor", label = "PHOSPHOR",
    bg = Color(0xFF020602), panel = Color(0xFF04100A), panel2 = Color(0xFF081810),
    line = Color(0xFF0E2A1C), line2 = Color(0xFF163D28),
    ink = Color(0xFFC8E8D0), inkDim = Color(0xFF6FA884), inkFaint = Color(0xFF40684E),
    live = Color(0xFF33FF88), ok = Color(0xFF66FF99), warn = Color(0xFFDDFA5C),
    crit = Color(0xFFFF5C5C), mag = Color(0xFF5CFAE0),
)

val AppThemes = listOf(THEME_MISSION, THEME_TORQUE, THEME_NIGHT, THEME_PHOSPHOR)

/** Global theme selection (index into [AppThemes]); set from LinkViewModel.themeIndex. */
object ThemeState {
    var index by mutableIntStateOf(0)
    val current: AppTheme get() = AppThemes[index.coerceIn(0, AppThemes.lastIndex)]
}

// Palette accessors — read the active theme so existing call sites recompose on switch.
val Bg get() = ThemeState.current.bg
val Panel get() = ThemeState.current.panel
val Panel2 get() = ThemeState.current.panel2
val Line get() = ThemeState.current.line
val Line2 get() = ThemeState.current.line2
val Ink get() = ThemeState.current.ink
val InkDim get() = ThemeState.current.inkDim
val InkFaint get() = ThemeState.current.inkFaint
val Live get() = ThemeState.current.live
val Ok get() = ThemeState.current.ok
val Warn get() = ThemeState.current.warn
val Crit get() = ThemeState.current.crit
val Mag get() = ThemeState.current.mag

val Mono = FontFamily.Monospace

@Composable
fun CANScopeTheme(content: @Composable () -> Unit) {
    val t = ThemeState.current
    val scheme = darkColorScheme(
        background = t.bg, surface = t.panel, surfaceVariant = t.panel2,
        primary = t.live, secondary = t.ok, error = t.crit,
        onBackground = t.ink, onSurface = t.ink, onPrimary = t.bg,
        outline = t.line,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
