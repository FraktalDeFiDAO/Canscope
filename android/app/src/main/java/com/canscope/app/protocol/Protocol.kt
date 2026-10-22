package com.canscope.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One CAN frame, shared by every link (BLE / WS / simulator). */
data class CanFrame(
    val ts: Long = System.currentTimeMillis(),
    val id: Long,
    val ext: Boolean,
    val dlc: Int,
    val data: ByteArray,
    val dir: Dir = Dir.RX,
) {
    enum class Dir { RX, TX }
    override fun equals(other: Any?) = other is CanFrame && other.ts == ts && other.id == id
    override fun hashCode() = (ts xor id).toInt()
}

/** GATT UUIDs — identical to the web dashboard and the ESP32 firmware. */
object BleUuid {
    const val SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val CHAR_TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e" // notify, ESP32 -> app
    const val CHAR_RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e" // write,  app -> ESP32
}

data class BusStatus(val state: String, val txErrors: Int, val rxErrors: Int)

/** 13-byte binary BLE packet: [u32le id|ext<<31, u8 dlc, 8x data]. Commands start with 0xFF. */
object Protocol {
    const val CMD: Byte = 0xFF.toByte()
    const val CMD_DTC_READ: Byte = 0x01
    const val CMD_DTC_CLEAR: Byte = 0x02
    const val CMD_PID: Byte = 0x03
    const val CMD_BITRATE: Byte = 0x10
    const val CMD_LISTEN: Byte = 0x11
    const val DTC_PKT: Byte = 0xFE.toByte()

    fun packFrame(id: Long, ext: Boolean, data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
        val rawId = (id and 0x7FFFFFFFL) or (if (ext) 0x80000000L else 0L)
        buf.putInt(rawId.toInt())
        buf.put(data.size.coerceAtMost(8).toByte())
        val padded = ByteArray(8)
        data.copyInto(padded, 0, 0, data.size.coerceAtMost(8))
        buf.put(padded)
        return buf.array()
    }

    fun unpackFrame(b: ByteArray): CanFrame? {
        if (b.size < 13) return null
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val rawId = buf.int.toLong() and 0xFFFFFFFFL
        val dlc = (buf.get().toInt() and 0xFF).coerceAtMost(8)
        val data = ByteArray(dlc)
        System.arraycopy(b, 5, data, 0, dlc)
        return CanFrame(id = rawId and 0x7FFFFFFFL, ext = rawId and 0x80000000L != 0L, dlc = dlc, data = data)
    }

    /** DTC notification: [0xFE, count, hi,lo pairs...] */
    fun unpackDtcs(b: ByteArray): List<Pair<Int, Int>> {
        if (b.size < 2 || b[0] != DTC_PKT) return emptyList()
        val count = b[1].toInt() and 0xFF
        val out = ArrayList<Pair<Int, Int>>(count)
        var i = 2
        while (i + 1 < b.size && out.size < count) {
            out.add((b[i].toInt() and 0xFF) to (b[i + 1].toInt() and 0xFF))
            i += 2
        }
        return out
    }

    fun cmd(vararg bytes: Int) = byteArrayOf(CMD, *bytes.map { it.toByte() }.toByteArray())

    /** Decode raw DTC word pairs into "P0171"-style strings. */
    fun decodeDtcStrings(pairs: List<Pair<Int, Int>>): List<String> {
        val sys = charArrayOf('P', 'C', 'B', 'U')
        return pairs.mapNotNull { (a, bb) ->
            if (a == 0 && bb == 0) null
            else "%c%d%X%X%X".format(sys[(a shr 6) and 3], (a shr 4) and 3, a and 0xF, (bb shr 4) and 0xF, bb and 0xF)
        }
    }
}
