package com.canscope.app.state

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.canscope.app.link.*
import com.canscope.app.protocol.BusStatus
import com.canscope.app.protocol.CanFrame
import com.canscope.app.protocol.Dbc
import com.canscope.app.protocol.DbcParser
import com.canscope.app.protocol.IsoTp
import com.canscope.app.protocol.Protocol
import com.canscope.app.protocol.VinSniff
import com.canscope.app.protocol.pidById
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

enum class LinkMode { SIMULATOR, WIFI, BLUETOOTH }

data class FrameStats(val id: Long, val ext: Boolean, var lastData: ByteArray,
                      var count: Long, var lastSeen: Long, var rate: Double, var dir: CanFrame.Dir)

data class VehicleInfo(
    val vin: String? = null,
    val vinSource: String? = null, // "mode09" or "sniffed"
    val calibrationId: String? = null,
    val ecuName: String? = null,
)

class LinkViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("canscope", Context.MODE_PRIVATE)

    val mode = MutableStateFlow(LinkMode.valueOf(prefs.getString("mode", LinkMode.SIMULATOR.name)!!))
    val wsUrl = MutableStateFlow(prefs.getString("wsUrl", "ws://192.168.4.1:81/ws")!!)
    val bitrate = MutableStateFlow(prefs.getInt("bitrate", 500_000))
    val listenOnly = MutableStateFlow(prefs.getBoolean("listenOnly", false))

    val state = MutableStateFlow(LinkState.DISCONNECTED)
    val lastError = MutableStateFlow<String?>(null)

    // CAN traffic
    val frames = MutableStateFlow<List<CanFrame>>(emptyList()) // ring buffer, newest last
    val stats = MutableStateFlow<Map<Long, FrameStats>>(emptyMap())
    val totalFrames = MutableStateFlow(0L)
    val paused = MutableStateFlow(false)
    private var windowStart = System.currentTimeMillis()

    // OBD values: pid -> value
    val pidValues = MutableStateFlow<Map<Int, Double>>(emptyMap())
    val dtcCodes = MutableStateFlow<List<String>>(emptyList())
    /** Mode 07 pending + Mode 0A permanent DTCs */
    val pendingDtcs = MutableStateFlow<List<String>>(emptyList())
    val permanentDtcs = MutableStateFlow<List<String>>(emptyList())
    /** Mode 02 freeze-frame values: pid -> value at the moment the DTC set */
    val freezeFrames = MutableStateFlow<Map<Int, Double>>(emptyMap())
    /** Mode 06 on-board monitoring, raw (MID/TID decode is vehicle-specific) */
    val monitors = MutableStateFlow<List<Triple<Int, Int, ByteArray>>>(emptyList())

    // Mode 09 identity + Mode 01 support-bitmask discovery (ISO-TP decoded)
    val vehicle = MutableStateFlow(VehicleInfo())
    val supportedPids = MutableStateFlow<Set<Int>>(emptySet())

    // TWAI health from the firmware watchdog (BLE 0xFD packet / WS {type:'bus'})
    val busStatus = MutableStateFlow<BusStatus?>(null)

    // Loaded DBC for sniffer decoding
    val dbc = MutableStateFlow<Dbc?>(null)

    private var link: Link = SimLink(viewModelScope)

    init { observe(link) }

    private fun observe(l: Link) {
        viewModelScope.launch {
            l.state.collect {
                state.value = it
                if (it == LinkState.CONNECTED) {
                    retryDelay = 2_000L
                    discoverSupportedPids()
                    requestVehicleInfo()
                } else if (it == LinkState.DISCONNECTED || it == LinkState.ERROR) {
                    scheduleReconnect()
                }
            }
        }
        viewModelScope.launch { l.lastError.collect { lastError.value = it } }
        viewModelScope.launch { l.busStatus.collect { busStatus.value = it } }
        viewModelScope.launch {
            l.frames.collect { f -> onFrame(f) }
        }
        viewModelScope.launch {
            l.obdValues.collect { (pid, raw) ->
                val def = pidById(pid) ?: return@collect
                val v = try { def.decode(raw) } catch (e: Exception) { return@collect }
                pidValues.value = pidValues.value + (pid to v)
            }
        }
        viewModelScope.launch {
            l.dtcs.collect { pairs -> dtcCodes.value = Protocol.decodeDtcStrings(pairs) }
        }
    }

    // --- auto-reconnect with exponential backoff (2s -> 15s cap) ---
    private var manualDisconnect = false
    private var retryDelay = 2_000L
    private var retryJob: kotlinx.coroutines.Job? = null

    private fun scheduleReconnect() {
        if (manualDisconnect || mode.value == LinkMode.SIMULATOR || retryJob != null) return
        retryJob = viewModelScope.launch {
            delay(retryDelay)
            retryJob = null
            if (!manualDisconnect) connect()
        }
        retryDelay = (retryDelay * 2).coerceAtMost(15_000L)
    }

    private fun onFrame(f: CanFrame) {
        totalFrames.value += 1
        if (recording.value) {
            recordBuffer.add(f)
            recordedCount.value = recordBuffer.size
        }
        // OBD-II responses arrive as raw frames on 0x7E8..0x7EF; run them
        // through ISO-TP reassembly and dispatch by service (parity with web).
        if (!f.ext && f.id in 0x7E8..0x7EF) dispatchObdFrame(f)
        // Passive VIN detection; Mode 09 results take precedence over sniffed ones
        if (vehicle.value.vin == null || vehicle.value.vinSource == "sniffed") {
            VinSniff.feed(f.id, f.data.copyOf(f.dlc))?.let { hit ->
                vehicle.value = vehicle.value.copy(vin = hit.vin, vinSource = "sniffed")
            }
        }
        val map = stats.value.toMutableMap()
        val s = map[f.id]
        if (s == null) {
            map[f.id] = FrameStats(f.id, f.ext, f.data, 1, f.ts, 0.0, f.dir)
        } else {
            val dt = (f.ts - s.lastSeen) / 1000.0
            s.count += 1; s.lastData = f.data; s.dir = f.dir
            s.rate = if (dt > 0) s.rate * 0.8 + (1 / dt) * 0.2 else s.rate
            s.lastSeen = f.ts
        }
        stats.value = map
        if (!paused.value) {
            val list = frames.value + f
            frames.value = if (list.size > 500) list.drop(list.size - 500) else list
        }
    }

    /** ISO-TP reassembly + OBD service dispatch (0x41/0x42 values, 0x43/0x47/0x4A DTCs, 0x46 monitors, 0x49 info). */
    private fun dispatchObdFrame(f: CanFrame) {
        val res = IsoTp.feed(f.id, f.data.copyOf(f.dlc)) ?: return
        if (res.firstFrame) {
            // ISO 15765-2: answer first frames with flow control to the request ID
            txFrame(f.id - 8, IsoTp.flowControlFrame())
            return
        }
        if (!res.complete || res.data.size < 2) return
        val payload = res.data
        when (payload[0].toInt() and 0xFF) {
            0x41 -> {
                val pid = payload[1].toInt() and 0xFF
                if (pid % 0x20 == 0) {
                    // support bitmask for the next 0x20 PIDs
                    supportedPids.value = supportedPids.value +
                        IsoTp.decodeSupportedPids(pid, payload.copyOfRange(2, minOf(6, payload.size)))
                } else {
                    val def = pidById(pid) ?: return
                    val raw = payload.copyOfRange(2, payload.size)
                    try { pidValues.value = pidValues.value + (pid to def.decode(raw)) } catch (_: Exception) {}
                }
            }
            0x42 -> {
                // Mode 02 freeze frame: same PID layout as mode 01
                val pid = payload[1].toInt() and 0xFF
                if (pid % 0x20 != 0) {
                    val def = pidById(pid) ?: return
                    val raw = payload.copyOfRange(2, payload.size)
                    try { freezeFrames.value = freezeFrames.value + (pid to def.decode(raw)) } catch (_: Exception) {}
                }
            }
            0x43 -> dtcCodes.value = Protocol.decodeDtcStrings(dtcPairs(payload))
            0x44 -> dtcCodes.value = emptyList()
            0x46 -> {
                // on-board monitoring: [0x46, MID, TID..., raw...] — keep raw
                val list = monitors.value.toMutableList()
                var i = 1
                while (i + 4 < payload.size) {
                    list.add(Triple(payload[i].toInt() and 0xFF, payload[i + 1].toInt() and 0xFF,
                        payload.copyOfRange(i + 2, i + 5)))
                    i += 5
                }
                monitors.value = if (list.size > 64) list.takeLast(64) else list
            }
            0x47 -> pendingDtcs.value = Protocol.decodeDtcStrings(dtcPairs(payload))
            0x4A -> permanentDtcs.value = Protocol.decodeDtcStrings(dtcPairs(payload))
            0x49 -> {
                val infoType = payload[1].toInt() and 0xFF
                val text = IsoTp.decodeMode09(payload) ?: return
                vehicle.value = when (infoType) {
                    0x02 -> vehicle.value.copy(vin = text, vinSource = "mode09")
                    0x04 -> vehicle.value.copy(calibrationId = text)
                    0x0A -> vehicle.value.copy(ecuName = text)
                    else -> vehicle.value
                }
            }
        }
    }

    private fun dtcPairs(payload: ByteArray): List<Pair<Int, Int>> {
        val pairs = mutableListOf<Pair<Int, Int>>()
        var i = 2
        while (i + 1 < payload.size) {
            val hi = payload[i].toInt() and 0xFF
            val lo = payload[i + 1].toInt() and 0xFF
            if (hi != 0 || lo != 0) pairs.add(hi to lo)
            i += 2
        }
        return pairs
    }

    /** Generic OBD request: service + optional PID (mode 01/02 style). */
    fun requestMode(service: Int, pid: Int? = null) {
        if (mode.value == LinkMode.SIMULATOR) return
        if (pid == null) txFrame(0x7DF, byteArrayOf(0x01, service.toByte(), 0, 0, 0, 0, 0, 0))
        else txFrame(0x7DF, byteArrayOf(0x02, service.toByte(), pid.toByte(), 0, 0, 0, 0, 0))
    }

    fun readPendingDtcs() = requestMode(0x07)
    fun readPermanentDtcs() = requestMode(0x0A)
    fun readFreezeFrame(pid: Int = 0x02) = requestMode(0x02, pid)
    fun readMonitors() = requestMode(0x06)

    /** Mode 01 support-bitmask discovery: 0x00, 0x20, 0x40, 0x60. */
    fun discoverSupportedPids() {
        if (mode.value == LinkMode.SIMULATOR) return
        for (base in listOf(0x00, 0x20, 0x40, 0x60)) {
            txFrame(0x7DF, byteArrayOf(0x02, 0x01, base.toByte(), 0, 0, 0, 0, 0))
        }
    }

    /** Mode 09 vehicle info: VIN, calibration ID, ECU name. */
    fun requestVehicleInfo() {
        if (mode.value == LinkMode.SIMULATOR) return
        for (infoType in listOf(0x02, 0x04, 0x0A)) {
            txFrame(0x7DF, byteArrayOf(0x02, 0x09, infoType.toByte(), 0, 0, 0, 0, 0))
        }
    }

    /** Load a DBC file's text for sniffer decoding; null unloads. */
    fun loadDbc(text: String?) {
        dbc.value = text?.let { runCatching { DbcParser.parse(it) }.getOrNull() }
    }

    // ---------- session recording / playback / server ----------

    data class RemoteSession(val id: Long, val label: String, val vehicle: String?, val startedAt: String, val frameCount: Long)

    val serverUrl = MutableStateFlow(prefs.getString("serverUrl", "http://192.168.4.1:8787")!!)
    val serverOnline = MutableStateFlow<Boolean?>(null)
    val remoteSessions = MutableStateFlow<List<RemoteSession>>(emptyList())

    val recording = MutableStateFlow(false)
    val recordLabel = MutableStateFlow("")
    private val recordBuffer = ArrayList<CanFrame>()
    val recordedCount = MutableStateFlow(0)

    val playing = MutableStateFlow(false)
    val playLabel = MutableStateFlow("")
    val playProgress = MutableStateFlow(0f)
    private var playJob: kotlinx.coroutines.Job? = null

    fun startRecording() {
        recordBuffer.clear()
        recordedCount.value = 0
        if (recordLabel.value.isBlank()) {
            recordLabel.value = "Drive " + java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US)
                .format(java.util.Date())
        }
        recording.value = true
    }

    fun stopRecording() { recording.value = false }

    fun saveServerUrl() {
        prefs.edit().putString("serverUrl", serverUrl.value).apply()
    }

    /** Upload the recorded buffer to the canscope-server (session + chunked frames). */
    suspend fun uploadRecording(): String? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val client = okhttp3.OkHttpClient()
            val meta = org.json.JSONObject()
                .put("label", recordLabel.value.ifBlank { "Untitled" })
                .put("vehicle", vehicle.value.vin ?: "")
            val res = client.newCall(
                okhttp3.Request.Builder().url("${serverUrl.value}/api/sessions")
                    .post(meta.toString().toRequestBody("application/json".toMediaType())).build()
            ).execute()
            if (!res.isSuccessful) return@withContext "Server returned ${res.code}"
            val id = org.json.JSONObject(res.body!!.string()).getLong("id")
            // frames in chunks of 5000
            val snapshot = recordBuffer.toList()
            var off = 0
            while (off < snapshot.size) {
                val chunk = snapshot.subList(off, minOf(off + 5000, snapshot.size))
                val arr = org.json.JSONArray()
                for (f in chunk) {
                    arr.put(org.json.JSONObject().put("ts", f.ts).put("id", f.id).put("ext", f.ext)
                        .put("data", org.json.JSONArray(f.data.map { it.toInt() and 0xFF })))
                }
                val r2 = client.newCall(
                    okhttp3.Request.Builder().url("${serverUrl.value}/api/sessions/$id/frames")
                        .post(arr.toString().toRequestBody("application/json".toMediaType())).build()
                ).execute()
                if (!r2.isSuccessful) return@withContext "Frame upload failed: ${r2.code}"
                off += chunk.size
            }
            refreshSessions()
            null
        } catch (e: Exception) {
            e.message ?: "Upload failed"
        }
    }

    suspend fun refreshSessions() = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val client = okhttp3.OkHttpClient()
            val res = client.newCall(
                okhttp3.Request.Builder().url("${serverUrl.value}/api/sessions").build()
            ).execute()
            if (!res.isSuccessful) { serverOnline.value = false; return@withContext }
            val arr = org.json.JSONArray(res.body!!.string())
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RemoteSession(o.getLong("id"), o.optString("label"), o.optString("vehicle").ifBlank { null },
                    o.optString("started_at"), o.optLong("frame_count"))
            }
            remoteSessions.value = list
            serverOnline.value = true
        } catch (_: Exception) {
            serverOnline.value = false
            remoteSessions.value = emptyList()
        }
    }

    suspend fun deleteSession(id: Long) = withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            okhttp3.OkHttpClient().newCall(
                okhttp3.Request.Builder().url("${serverUrl.value}/api/sessions/$id").delete().build()
            ).execute().close()
        }
        refreshSessions()
    }

    /** Replay frames with their original timing into the live pipeline. */
    private fun playFrames(rows: List<CanFrame>, label: String) {
        playJob?.cancel()
        playJob = viewModelScope.launch {
            playing.value = true
            playLabel.value = label
            playProgress.value = 0f
            val t0 = rows.firstOrNull()?.ts ?: 0L
            val wall0 = System.currentTimeMillis()
            var i = 0
            while (i < rows.size) {
                val elapsed = System.currentTimeMillis() - wall0
                while (i < rows.size && rows[i].ts - t0 <= elapsed) {
                    onFrame(rows[i].copy(ts = System.currentTimeMillis()))
                    i++
                }
                playProgress.value = if (rows.isEmpty()) 1f else i.toFloat() / rows.size
                if (i < rows.size) delay(20)
            }
            playing.value = false
        }
    }

    fun playLocalRecording() {
        if (recordBuffer.isEmpty()) return
        playFrames(recordBuffer.toList(), recordLabel.value.ifBlank { "Local recording" })
    }

    suspend fun playRemote(s: RemoteSession): String? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val res = okhttp3.OkHttpClient().newCall(
                okhttp3.Request.Builder()
                    .url("${serverUrl.value}/api/sessions/${s.id}/frames?limit=500000").build()
            ).execute()
            if (!res.isSuccessful) return@withContext "Server returned ${res.code}"
            val arr = org.json.JSONArray(res.body!!.string())
            val rows = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val d = o.getJSONArray("data")
                CanFrame(
                    ts = o.getLong("ts"), id = o.getLong("id"), ext = o.optBoolean("ext"),
                    dlc = d.length(), data = ByteArray(d.length()) { d.getInt(it).toByte() },
                    dir = CanFrame.Dir.RX,
                )
            }
            playFrames(rows, s.label)
            null
        } catch (e: Exception) {
            e.message ?: "Playback failed"
        }
    }

    fun stopPlayback() {
        playJob?.cancel(); playJob = null
        playing.value = false
    }

    val busLoad: Double
        get() {
            val secs = (System.currentTimeMillis() - windowStart) / 1000.0
            return if (secs > 0) totalFrames.value / secs else 0.0
        }

    fun setMode(m: LinkMode) {
        mode.value = m
        prefs.edit().putString("mode", m.name).apply()
        if (state.value == LinkState.CONNECTED) viewModelScope.launch { connect() }
    }

    fun saveSettings() {
        prefs.edit()
            .putString("wsUrl", wsUrl.value)
            .putInt("bitrate", bitrate.value)
            .putBoolean("listenOnly", listenOnly.value)
            .apply()
    }

    suspend fun connect() {
        link.disconnect()
        manualDisconnect = false
        retryJob?.cancel(); retryJob = null
        link = when (mode.value) {
            LinkMode.SIMULATOR -> SimLink(viewModelScope)
            LinkMode.WIFI -> WsLink(viewModelScope).also { it.url = wsUrl.value }
            LinkMode.BLUETOOTH -> BleLink(getApplication(), viewModelScope)
        }
        observe(link)
        link.connect()
        if (link is BleLink || link is WsLink) applyLinkConfig()
        if (mode.value == LinkMode.SIMULATOR) {
            // seed identity so the Mode 09 pipeline is testable without a car
            vehicle.value = VehicleInfo(
                vin = "1HGCM82633A004352", vinSource = "mode09",
                ecuName = "SIM-ECU 2.4T", calibrationId = "SIM-37805-R70",
            )
            supportedPids.value = setOf(0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x1F,
                0x2C, 0x2F, 0x31, 0x33, 0x3C, 0x42, 0x43, 0x44, 0x45, 0x46, 0x49, 0x4A, 0x4C, 0x51, 0x5C, 0x5E, 0x61, 0x62, 0x63)
        }
    }

    fun disconnect() {
        manualDisconnect = true
        retryJob?.cancel(); retryJob = null
        busStatus.value = null
        link.disconnect()
    }

    fun txFrame(id: Long, data: ByteArray, ext: Boolean = false) = link.txFrame(id, data, ext)

    /** Send a pre-segmented ISO-TP frame sequence, paced 10 ms apart. */
    fun scopeSendIsoTp(id: Long, frames: List<ByteArray>, ext: Boolean) {
        viewModelScope.launch {
            frames.forEach { f ->
                link.txFrame(id, f, ext)
                delay(10)
            }
        }
    }
    fun readDtcs() = link.readDtcs()
    fun clearDtcs() = link.clearDtcs()

    fun applyLinkConfig() {
        val idx = when {
            bitrate.value <= 125_000 -> 0
            bitrate.value <= 250_000 -> 1
            bitrate.value <= 500_000 -> 2
            else -> 3
        }
        link.applyConfig(idx, listenOnly.value)
    }

    fun clearLog() {
        frames.value = emptyList()
        stats.value = emptyMap()
        totalFrames.value = 0
        windowStart = System.currentTimeMillis()
    }

    fun pid(pid: Int): Double? = pidValues.value[pid]
}
