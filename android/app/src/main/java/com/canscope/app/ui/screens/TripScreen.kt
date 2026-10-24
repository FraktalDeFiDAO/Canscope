package com.canscope.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canscope.app.state.LinkViewModel
import com.canscope.app.ui.PanelBox
import com.canscope.app.ui.theme.*

/** Torque-style trip computer: distance, fuel economy, speed stats. */
@Composable
fun TripScreen(vm: LinkViewModel) {
    val trip by vm.tripStats.collectAsState()
    val values by vm.pidValues.collectAsState()
    var useMpg by remember { mutableStateOf(false) }

    fun econ(v: Double): String =
        if (useMpg) (if (v > 0.01) "%.1f".format(235.214 / v) else "—")
        else "%.1f".format(v)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(170.dp), contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (useMpg) "MPG" else "L/100KM",
                    color = Live, fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.6.sp,
                    modifier = Modifier.border(1.dp, Live).clickable { useMpg = !useMpg }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "RESET TRIP", color = Crit, fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.6.sp,
                    modifier = Modifier.border(1.dp, Crit).clickable { vm.resetTrip() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        item { TripTile("Distance", "%.2f".format(trip.distanceKm), "km", Live) }
        item { TripTile("Fuel used", "%.2f".format(trip.fuelUsedL), "L", Warn) }
        item { TripTile("Instant economy", econ(trip.instantL100), if (useMpg) "mpg" else "L/100", Ok) }
        item { TripTile("Avg economy", econ(trip.avgL100), if (useMpg) "mpg" else "L/100", Ok) }
        item { TripTile("Avg speed", "%.0f".format(trip.avgSpeedKmh), "km/h", Ink) }
        item { TripTile("Max speed", "%.0f".format(trip.maxSpeedKmh), "km/h", Ink) }
        item {
            TripTile("Drive time", "%d:%02d:%02d".format(
                trip.movingSecs / 3600, trip.movingSecs % 3600 / 60, trip.movingSecs % 60), "h:m:s", Ink)
        }
        item { TripTile("Speed now", "%.0f".format(values[0x0D] ?: 0.0), "km/h", Live) }
        item {
            TripTile("Fuel rate now", "%.1f".format(values[0x5E] ?: 0.0), "L/h", Warn)
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Fuel use comes from the fuel-rate PID (0x5E); on cars without it, it is estimated from MAF (stoichiometric 14.7:1). Economy needs a few hundred metres of driving to settle.",
                color = InkFaint, fontFamily = Mono, fontSize = 8.sp, lineHeight = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun TripTile(label: String, value: String, unit: String, color: androidx.compose.ui.graphics.Color) {
    PanelBox(Modifier, title = label, trailing = unit) {
        Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
            Text(value, color = color, fontFamily = Mono, fontSize = 26.sp)
        }
    }
}
