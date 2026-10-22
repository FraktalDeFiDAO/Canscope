package com.canscope.app.link

import com.canscope.app.protocol.BusStatus
import com.canscope.app.protocol.CanFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Wi-Fi WebSocket link to the ESP32 AP (default ws://192.168.4.1:81/ws). JSON line protocol. */
class WsLink(private val scope: CoroutineScope) : Link {

    override val state = MutableStateFlow(LinkState.DISCONNECTED)
    override val frames = MutableSharedFlow<CanFrame>(extraBufferCapacity = 512)
    override val obdValues = MutableSharedFlow<Pair<Int, ByteArray>>(extraBufferCapacity = 128)
    override val dtcs = MutableSharedFlow<List<Pair<Int, Int>>>(extraBufferCapacity = 8)
    override val busStatus = MutableStateFlow<BusStatus?>(null)
    override val lastError = MutableStateFlow<String?>(null)

    var url = "ws://192.168.4.1:81/ws"
    private var ws: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    override suspend fun connect() {
        lastError.value = null
        state.value = LinkState.CONNECTING
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                state.value = LinkState.CONNECTED
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try { handle(JSONObject(text)) } catch (_: Exception) { /* malformed */ }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lastError.value = t.message ?: "WebSocket error"
                state.value = LinkState.ERROR
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                state.value = LinkState.DISCONNECTED
            }
        })
    }

    private fun handle(m: JSONObject) {
        when (m.optString("type")) {
            "frame" -> {
                val arr = m.getJSONArray("data")
                val data = ByteArray(arr.length()) { arr.getInt(it).toByte() }
                scope.launch {
                    frames.emit(CanFrame(id = m.getLong("id"), ext = m.optBoolean("ext"),
                        dlc = m.optInt("dlc", data.size), data = data))
                }
            }
            "obd" -> {
                if (m.optInt("mode") == 1) {
                    val arr = m.getJSONArray("data")
                    val raw = ByteArray(arr.length()) { arr.getInt(it).toByte() }
                    scope.launch { obdValues.emit(m.getInt("pid") to raw) }
                }
            }
            "dtc" -> {
                val arr = m.getJSONArray("codes")
                val pairs = (0 until arr.length()).map { i ->
                    val c = arr.getJSONArray(i)
                    c.getInt(0) to c.getInt(1)
                }
                scope.launch { dtcs.emit(pairs) }
            }
            "bus" -> {
                busStatus.value = BusStatus(
                    m.optString("state", "running"),
                    m.optInt("txErrors"), m.optInt("rxErrors"),
                )
            }
        }
    }

    private fun send(obj: JSONObject) { ws?.send(obj.toString()) }

    override fun disconnect() {
        ws?.close(1000, "bye")
        ws = null
        state.value = LinkState.DISCONNECTED
    }

    override fun txFrame(id: Long, data: ByteArray, ext: Boolean) {
        send(JSONObject().put("type", "tx").put("id", id).put("ext", ext)
            .put("data", JSONArray(data.map { it.toInt() and 0xFF })))
        scope.launch { frames.emit(CanFrame(id = id, ext = ext, dlc = data.size, data = data, dir = CanFrame.Dir.TX)) }
    }

    override fun readDtcs() = send(JSONObject().put("type", "cmd").put("cmd", "dtc-read"))
    override fun clearDtcs() = send(JSONObject().put("type", "cmd").put("cmd", "dtc-clear"))
    override fun applyConfig(bitrateIdx: Int, listenOnly: Boolean) {
        val bps = listOf(125_000, 250_000, 500_000, 1_000_000)[bitrateIdx.coerceIn(0, 3)]
        send(JSONObject().put("type", "cfg").put("bitrate", bps).put("listen_only", listenOnly))
    }
}
