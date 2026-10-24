package com.canscope.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.canscope.app.link.LinkState
import com.canscope.app.state.LinkMode
import com.canscope.app.state.LinkViewModel
import com.canscope.app.ui.StatusLed
import com.canscope.app.ui.screens.*
import com.canscope.app.ui.theme.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CANScopeTheme {
                val vm: LinkViewModel = viewModel()
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()

                // sync persisted theme choice into the palette
                val themeIdx by vm.themeIndex.collectAsState()
                SideEffect { ThemeState.index = themeIdx }

                // BLE runtime permissions (Android 12+) — requested once up front.
                val permLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        permLauncher.launch(arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                        ))
                    }
                    vm.connect() // auto-connect (simulator by default)
                }

                Scaffold(
                    containerColor = Bg,
                    topBar = { TopBar(nav, vm, onConnect = { scope.launch { vm.connect() } }) },
                ) { padding ->
                    NavHost(nav, startDestination = "dashboard", modifier = Modifier.padding(padding)) {
                        composable("dashboard") { DashboardScreen(vm) }
                        composable("graphs") { GraphsScreen(vm) }
                        composable("hud") { HudScreen(vm) }
                        composable("trip") { TripScreen(vm) }
                        composable("perf") { PerfScreen(vm) }
                        composable("pids") { PidExplorerScreen(vm) }
                        composable("sniffer") { SnifferScreen(vm) }
                        composable("transmit") { TransmitScreen(vm) }
                        composable("dtc") { DtcScreen(vm) }
                        composable("sessions") { SessionsScreen(vm) }
                        composable("connection") { ConnectionScreen(vm) }
                    }
                }
            }
        }
    }
}

private val tabs = listOf(
    "dashboard" to "GAUGES",
    "graphs" to "GRAPHS",
    "hud" to "HUD",
    "trip" to "TRIP",
    "perf" to "PERF",
    "pids" to "PIDS",
    "sniffer" to "SNIFFER",
    "transmit" to "TX",
    "dtc" to "DTC",
    "sessions" to "LOGS",
    "connection" to "LINK",
)

@Composable
private fun TopBar(nav: NavHostController, vm: LinkViewModel, onConnect: () -> Unit) {
    val state by vm.state.collectAsState()
    val mode by vm.mode.collectAsState()
    val dtcs by vm.dtcCodes.collectAsState()
    val current = nav.currentBackStackEntryAsState().value?.destination?.route

    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("CAN", color = Ink, fontFamily = Mono, fontSize = 16.sp, letterSpacing = 3.sp)
            Text("SCOPE", color = Live, fontFamily = Mono, fontSize = 16.sp, letterSpacing = 3.sp)
            Spacer(Modifier.width(12.dp))
            Text(mode.name.take(3), color = InkFaint, fontFamily = Mono, fontSize = 9.sp)
            Spacer(Modifier.width(6.dp))
            StatusLed(when (state) {
                LinkState.CONNECTED -> Ok; LinkState.CONNECTING -> Warn
                LinkState.ERROR -> Crit; LinkState.DISCONNECTED -> Line
            })
            if (dtcs.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text("MIL", color = Crit, fontFamily = Mono, fontSize = 9.sp)
                Spacer(Modifier.width(4.dp))
                StatusLed(Crit)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { if (state == LinkState.CONNECTED) vm.disconnect() else onConnect() }) {
                Text(
                    if (state == LinkState.CONNECTED) "DISCONNECT" else "CONNECT",
                    color = if (state == LinkState.CONNECTED) Crit else Ok,
                    fontFamily = Mono, fontSize = 11.sp,
                )
            }
        }
        ScrollableTabRow(
            selectedTabIndex = tabs.indexOfFirst { it.first == current }.coerceAtLeast(0),
            containerColor = Panel, contentColor = Live, edgePadding = 0.dp,
        ) {
            tabs.forEach { (route, label) ->
                Tab(
                    selected = current == route,
                    onClick = { nav.navigate(route) { launchSingleTop = true; popUpTo("dashboard") { saveState = true } } },
                    text = { Text(label, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.4.sp,
                        color = if (current == route) Live else InkDim) },
                )
            }
        }
    }
}
