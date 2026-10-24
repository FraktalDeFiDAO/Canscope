package com.canscope.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canscope.app.state.PERF_TARGETS
import com.canscope.app.state.PerfPhase
import com.canscope.app.state.LinkViewModel
import com.canscope.app.ui.PanelBox
import com.canscope.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Torque-style performance timers: 0–60, ¼ mile, braking tests, best times. */
@Composable
fun PerfScreen(vm: LinkViewModel) {
    val values by vm.pidValues.collectAsState()
    val phase by vm.perfPhase.collectAsState()
    val targetIdx by vm.perfTargetIndex.collectAsState()
    val elapsed by vm.perfElapsed.collectAsState()
    val results by vm.perfResults.collectAsState()
    val best by vm.perfBest.collectAsState()

    val speed = values[0x0D] ?: 0.0
    val target = PERF_TARGETS[targetIdx.coerceIn(0, PERF_TARGETS.lastIndex)]

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        // target picker
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(PERF_TARGETS.size) { i ->
                val t = PERF_TARGETS[i]
                val sel = i == targetIdx
                Text(
                    t.label.uppercase(), color = if (sel) Live else InkDim,
                    fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.2.sp,
                    modifier = Modifier.border(1.dp, if (sel) Live else Line)
                        .clickable { vm.setPerfTarget(i) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        PanelBox(Modifier.fillMaxWidth(), title = target.label,
            trailing = best[target.label]?.let { "BEST %.2fs".format(it) }) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when (phase) {
                        PerfPhase.IDLE -> "STANDBY"
                        PerfPhase.ARMED -> if (target.fromKmh <= 1.0) "READY — LAUNCH WHEN MOVING"
                            else "READY — ENTER AT ${target.fromKmh.toInt()} KM/H"
                        PerfPhase.RUNNING -> "RUNNING"
                    },
                    color = when (phase) {
                        PerfPhase.IDLE -> InkFaint
                        PerfPhase.ARMED -> Ok
                        PerfPhase.RUNNING -> Live
                    },
                    fontFamily = Mono, fontSize = 12.sp, letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "%.2f".format(elapsed),
                    color = if (phase == PerfPhase.RUNNING) Live else Ink,
                    fontFamily = Mono, fontSize = 56.sp,
                )
                Text("SECONDS", color = InkFaint, fontFamily = Mono, fontSize = 9.sp, letterSpacing = 3.sp)
                Spacer(Modifier.height(8.dp))
                Text("SPEED %.0f KM/H".format(speed), color = InkDim, fontFamily = Mono, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (phase == PerfPhase.IDLE) {
                        Text("ARM", color = Ok, fontFamily = Mono, fontSize = 12.sp, letterSpacing = 2.sp,
                            modifier = Modifier.border(1.dp, Ok).clickable { vm.armPerf() }
                                .padding(horizontal = 18.dp, vertical = 10.dp))
                    } else {
                        Text("CANCEL", color = Crit, fontFamily = Mono, fontSize = 12.sp, letterSpacing = 2.sp,
                            modifier = Modifier.border(1.dp, Crit).clickable { vm.cancelPerf() }
                                .padding(horizontal = 18.dp, vertical = 10.dp))
                    }
                    Text("RESET BEST", color = InkDim, fontFamily = Mono, fontSize = 12.sp, letterSpacing = 2.sp,
                        modifier = Modifier.border(1.dp, Line).clickable { vm.resetPerfBest() }
                            .padding(horizontal = 18.dp, vertical = 10.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        Text("RESULTS", color = InkDim, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.8.sp)
        Spacer(Modifier.height(4.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(results) { r ->
                val isBest = best[r.target]?.let { kotlin.math.abs(it - r.seconds) < 0.001 } == true
                Row(
                    Modifier.fillMaxWidth().border(1.dp, if (isBest) Ok else Line)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(r.target, color = Ink, fontFamily = Mono, fontSize = 11.sp)
                    Text(
                        "%.2fs".format(r.seconds) + if (isBest) "  ★ BEST" else "",
                        color = if (isBest) Ok else InkDim, fontFamily = Mono, fontSize = 11.sp,
                    )
                    Text(
                        SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date(r.ts)),
                        color = InkFaint, fontFamily = Mono, fontSize = 9.sp,
                    )
                }
            }
        }
    }
}
