package com.canscope.app.link

import com.canscope.app.protocol.BusStatus
import com.canscope.app.protocol.CanFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** Common contract for BLE, WebSocket and simulator links — mirrors the web stores. */
interface Link {
    val state: MutableStateFlow<LinkState>
    val frames: MutableSharedFlow<CanFrame>          // inbound + echoed tx frames
    val obdValues: MutableSharedFlow<Pair<Int, ByteArray>> // pid -> raw data bytes
    val dtcs: MutableSharedFlow<List<Pair<Int, Int>>>
    val busStatus: MutableStateFlow<BusStatus?>  // TWAI health (BLE 0xFD / WS {type:'bus'})
    val lastError: MutableStateFlow<String?>

    suspend fun connect()
    fun disconnect()
    fun txFrame(id: Long, data: ByteArray, ext: Boolean = false)
    fun readDtcs()
    fun clearDtcs()
    fun applyConfig(bitrateIdx: Int, listenOnly: Boolean)
}
