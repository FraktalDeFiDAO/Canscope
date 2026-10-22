package com.canscope.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canscope.app.link.LinkState
import com.canscope.app.state.LinkViewModel
import com.canscope.app.ui.PanelBox
import com.canscope.app.ui.StatusLed
import com.canscope.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SessionsScreen(vm: LinkViewModel) {
    val recording by vm.recording.collectAsState()
    val recordLabel by vm.recordLabel.collectAsState()
    val recordedCount by vm.recordedCount.collectAsState()
    val playing by vm.playing.collectAsState()
    val playLabel by vm.playLabel.collectAsState()
    val playProgress by vm.playProgress.collectAsState()
    val serverUrl by vm.serverUrl.collectAsState()
    val serverOnline by vm.serverOnline.collectAsState()
    val sessions by vm.remoteSessions.collectAsState()
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refreshSessions() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {

        PanelBox(Modifier.fillMaxWidth(), title = "Record Session") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    recordLabel, { vm.recordLabel.value = it },
                    label = { Text("LABEL", fontFamily = Mono, fontSize = 10.sp) },
                    textStyle = LocalTextStyle.current.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!recording) {
                        OutlinedButton(
                            onClick = { vm.startRecording() },
                            enabled = state == LinkState.CONNECTED,
                        ) { Text("● RECORD", color = Crit, fontFamily = Mono, fontSize = 11.sp) }
                    } else {
                        OutlinedButton(onClick = { vm.stopRecording() }) {
                            Text("■ STOP — $recordedCount FRAMES", color = Warn, fontFamily = Mono, fontSize = 11.sp)
                        }
                    }
                    if (!recording && recordedCount > 0) {
                        OutlinedButton(onClick = { vm.playLocalRecording() }) {
                            Text("PLAY", color = Ok, fontFamily = Mono, fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                message = "Uploading…"
                                message = vm.uploadRecording() ?: "Uploaded $recordedCount frames"
                            }
                        }) { Text("UPLOAD", color = Live, fontFamily = Mono, fontSize = 11.sp) }
                    }
                }
                message?.let { Text(it.uppercase(), color = Ok, fontFamily = Mono, fontSize = 10.sp) }
            }
        }

        if (playing) {
            PanelBox(Modifier.fillMaxWidth(), title = "Playing — $playLabel") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { playProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = Live, trackColor = Line,
                    )
                    OutlinedButton(onClick = { vm.stopPlayback() }) {
                        Text("STOP", color = Crit, fontFamily = Mono, fontSize = 10.sp)
                    }
                }
            }
        }

        PanelBox(Modifier.fillMaxWidth(), title = "Storage Server") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    serverUrl, { vm.serverUrl.value = it },
                    label = { Text("SERVER URL (CANSCOPE-SERVER)", fontFamily = Mono, fontSize = 10.sp) },
                    textStyle = LocalTextStyle.current.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusLed(when (serverOnline) { null -> Line; true -> Ok; false -> Crit })
                    Text(
                        when (serverOnline) { null -> "NOT CHECKED"; true -> "SERVER ONLINE"; false -> "SERVER UNREACHABLE" },
                        color = InkDim, fontFamily = Mono, fontSize = 10.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.saveServerUrl(); scope.launch { vm.refreshSessions() } }) {
                        Text("REFRESH", color = InkDim, fontFamily = Mono, fontSize = 10.sp)
                    }
                }
            }
        }

        PanelBox(Modifier.fillMaxWidth(), title = "Session Library") {
            if (sessions.isEmpty()) {
                Text("NO SESSIONS — RECORD A DRIVE AND UPLOAD IT",
                    color = InkFaint, fontFamily = Mono, fontSize = 10.sp,
                    modifier = Modifier.padding(16.dp))
            } else {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    sessions.forEach { s ->
                        Row(Modifier.fillMaxWidth().padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(s.label, color = Ink, fontSize = 13.sp)
                                Text("${s.startedAt} · ${s.frameCount} frames",
                                    color = InkFaint, fontFamily = Mono, fontSize = 9.sp)
                            }
                            TextButton(onClick = {
                                scope.launch { message = vm.playRemote(s) }
                            }) { Text("PLAY", color = Ok, fontFamily = Mono, fontSize = 10.sp) }
                            TextButton(onClick = { scope.launch { vm.deleteSession(s.id) } }) {
                                Text("DELETE", color = InkDim, fontFamily = Mono, fontSize = 10.sp)
                            }
                        }
                        HorizontalDivider(color = Line)
                    }
                }
            }
        }
    }
}
