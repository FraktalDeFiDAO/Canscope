package com.canscope.app.link

import com.canscope.app.protocol.BusStatus
import com.canscope.app.protocol.CanFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Built-in virtual ECU — same behavior as the web simulator. Emits raw
 * traffic frames plus inverse-encoded Mode 01 responses so the ViewModel
 * decodes them through the same PID database as real hardware.
 */
class SimLink(private val scope: CoroutineScope) : Link {

    override val state = MutableStateFlow(LinkState.DISCONNECTED)
    override val frames = MutableSharedFlow<CanFrame>(extraBufferCapacity = 512)
    override val obdValues = MutableSharedFlow<Pair<Int, ByteArray>>(extraBufferCapacity = 128)
    override val dtcs = MutableSharedFlow<List<Pair<Int, Int>>>(extraBufferCapacity = 8)
    override val busStatus = MutableStateFlow<BusStatus?>(null)
    override val lastError = MutableStateFlow<String?>(null)

    private var running = false
    private var dtcList = listOf(0x01 to 0x71, 0x04 to 0x20) // P0171, P0420
    private var t = 0.0

    // inverse encoders for the PIDs the sim produces
    private fun enc(pid: Int, v: Double): ByteArray = when (pid) {
        0x05, 0x0F, 0x46, 0x5C -> byteArrayOf((v + 40).toInt().toByte())
        0x0C -> { val x = (v * 4).toInt(); byteArrayOf((x shr 8).toByte(), x.toByte()) }
        0x0D, 0x0B, 0x33 -> byteArrayOf(v.toInt().toByte())
        0x04, 0x11, 0x2C, 0x2F, 0x45, 0x49, 0x4A, 0x4C -> byteArrayOf((v * 2.55).toInt().toByte())
        0x06, 0x07 -> byteArrayOf(((v + 100) * 1.28).toInt().toByte())
        0x0E -> byteArrayOf(((v + 64) * 2).toInt().toByte())
        0x10 -> { val x = (v * 100).toInt(); byteArrayOf((x shr 8).toByte(), x.toByte()) }
        0x1F, 0x31, 0x63 -> { val x = v.toInt(); byteArrayOf((x shr 8).toByte(), x.toByte()) }
        0x42 -> { val x = (v * 1000).toInt(); byteArrayOf((x shr 8).toByte(), x.toByte()) }
        0x43 -> { val x = (v * 2.55).toInt(); byteArrayOf((x shr 8).toByte(), x.toByte()) }
        0x44 -> { val x = (v * 32768).toInt(); byteArrayOf((x shr 8).toByte(), x.toByte()) }
        0x3C -> { val x = ((v + 40) * 10).toInt(); byteArrayOf((x shr 8).toByte(), x.toByte()) }
        0x5E -> { val x = (v * 20).toInt(); byteArrayOf((x shr 8).toByte(), x.toByte()) }
        0x61, 0x62 -> byteArrayOf((v + 125).toInt().toByte())
        else -> byteArrayOf()
    }

    override suspend fun connect() {
        lastError.value = null
        state.value = LinkState.CONNECTED
        running = true
        scope.launch {
            while (isActive && running) {
                tick()
                delay(100)
            }
        }
    }

    private fun frame(id: Long, vararg b: Int) =
        CanFrame(id = id, ext = false, dlc = b.size, data = b.map { it.toByte() }.toByteArray())

    private suspend fun tick() {
        t += 0.1
        val phase = (sin(t / 7) + 1) / 2
        val burst = max(0.0, sin(t / 2.3)).pow(3)
        val rpm = 800 + phase * 900 + burst * 3800 + Random.nextDouble() * 60
        val speed = max(0.0, (rpm - 800) / 42 + Random.nextDouble() * 2)

        frames.emit(frame(0x0C1, ((rpm * 4).toInt() shr 8) and 0xFF, (rpm * 4).toInt() and 0xFF,
            min(255.0, rpm / 30).toInt(), 0, 0, 0, 0, 0))
        frames.emit(frame(0x2B0, speed.toInt() and 0xFF, (speed.toInt() shr 8) and 0xFF, 0, 0, 0, 0, 0, 0))
        frames.emit(frame(0x0C2, (127 + sin(t / 1.8) * 90).toInt(), 0, 0, 0, 0, 0, 0, 0))
        if (Random.nextDouble() > 0.5) {
            val v = (speed * 16).toInt()
            frames.emit(frame(0x1A0, v and 0xFF, v shr 8, v and 0xFF, v shr 8, v and 0xFF, v shr 8, v and 0xFF, v shr 8))
        }
        if (Random.nextDouble() > 0.7) {
            frames.emit(frame(0x321, min(6.0, 1 + speed / 22).toInt(), (rpm / 8).toInt() and 0xFF, 0x80, 0, 0, 0, 0, 0))
        }

        val load = min(95.0, 8 + (rpm - 700) / 60 + Random.nextDouble() * 3)
        val throttle = min(100.0, max(0.0, (rpm - 780) / 45))
        val stft = sin(t / 3) * 4 + (Random.nextDouble() - 0.5)
        val vals = listOf(
            0x04 to load, 0x05 to 88 + sin(t / 40) * 3, 0x06 to stft, 0x07 to stft * 0.6,
            0x0B to max(20.0, 35 + load * 0.55), 0x0C to rpm, 0x0D to speed,
            0x0E to 8 + (rpm - 800) / 160, 0x0F to 24 + speed / 12, 0x10 to 2.1 + rpm / 220,
            0x11 to throttle, 0x1F to t, 0x2C to if (speed > 20) 14.0 else 0.0, 0x2F to 62.0,
            0x31 to 1284.0, 0x33 to 101.0, 0x3C to 420 + rpm / 12, 0x42 to 14.2,
            0x43 to load + 6, 0x44 to 1.0, 0x45 to throttle * 0.97, 0x46 to 20.0,
            0x49 to throttle, 0x4A to throttle * 0.5, 0x4C to throttle, 0x51 to 1.0,
            0x5C to 96.0, 0x5E to 0.9 + (rpm / 1000) * (load / 100) * 8,
            0x61 to load, 0x62 to load - 4, 0x63 to 320.0,
        )
        for ((pid, v) in vals) obdValues.emit(pid to enc(pid, v))
    }

    override fun disconnect() { running = false; state.value = LinkState.DISCONNECTED }
    override fun txFrame(id: Long, data: ByteArray, ext: Boolean) {
        scope.launch { frames.emit(CanFrame(id = id, ext = ext, dlc = data.size, data = data, dir = CanFrame.Dir.TX)) }
    }
    override fun readDtcs() { scope.launch { dtcs.emit(dtcList) } }
    override fun clearDtcs() { dtcList = emptyList(); scope.launch { dtcs.emit(dtcList) } }
    override fun applyConfig(bitrateIdx: Int, listenOnly: Boolean) { /* no-op in sim */ }
}
