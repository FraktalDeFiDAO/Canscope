package com.canscope.app.protocol

// ISO-TP (ISO 15765-2) multi-frame reassembly — Kotlin port of the web
// dashboard's lib/isotp.ts. OBD responses that exceed 6 data bytes (VIN,
// calibration ID, ECU name, long DTC lists) arrive as a first frame (0x1_)
// followed by consecutive frames (0x2_); single frames (0x0_) carry
// everything at once.

private data class Assembly(
    val total: Int,
    var buf: ByteArray,
    var seq: Int,
    var lastTouched: Long,
)

object IsoTp {

    private val assemblies = HashMap<Long, Assembly>()
    private const val TIMEOUT_MS = 1_000L

    class Result(
        val data: ByteArray,
        val complete: Boolean,
        /** true when this was a First Frame — the caller must answer with a
         *  flow-control frame to the request ID (response id − 8). */
        val firstFrame: Boolean = false,
    )

    /** Flow-control frame: block size 0, no separation time. */
    fun flowControlFrame(): ByteArray = byteArrayOf(0x30, 0, 0, 0, 0, 0, 0, 0)

    /**
     * Segment an outbound payload into ISO-TP frames (SF, or FF + CFs).
     * Sequence numbers wrap mod 16; CFs are zero-padded.
     */
    fun segment(payload: ByteArray): List<ByteArray> {
        if (payload.size <= 7) {
            return listOf(byteArrayOf(payload.size.toByte()) + payload + ByteArray(7 - payload.size))
        }
        val frames = ArrayList<ByteArray>()
        frames += byteArrayOf(
            (0x10 or ((payload.size shr 8) and 0x0F)).toByte(),
            (payload.size and 0xFF).toByte(),
        ) + payload.copyOfRange(0, 6)
        var seq = 1
        var off = 6
        while (off < payload.size) {
            val chunk = payload.copyOfRange(off, minOf(off + 7, payload.size))
            frames += byteArrayOf((0x20 or (seq % 16)).toByte()) + chunk + ByteArray(7 - chunk.size)
            seq++
            off += 7
        }
        return frames
    }

    /** Feed one raw CAN frame from an OBD response ID (0x7E8..0x7EF).
     *  Returns a Result on first frames and completed transfers, null otherwise. */
    @Synchronized
    fun feed(id: Long, data: ByteArray): Result? {
        gc()
        if (data.isEmpty()) return null
        return when (data[0].toInt() shr 4) {
            0x0 -> { // single frame
                val len = data[0].toInt() and 0x0F
                Result(data.sliceArray(1..minOf(len, data.size - 1)), complete = true)
            }
            0x1 -> { // first frame
                val total = ((data[0].toInt() and 0x0F) shl 8) or (data[1].toInt() and 0xFF)
                assemblies[id] = Assembly(
                    total,
                    data.sliceArray(2 until data.size),
                    1,
                    System.currentTimeMillis(),
                )
                Result(ByteArray(0), complete = false, firstFrame = true)
            }
            0x2 -> { // consecutive frame
                val a = assemblies[id] ?: return null
                val seq = data[0].toInt() and 0x0F
                if (seq != a.seq % 16) { assemblies.remove(id); return null } // dropped frame
                a.buf += data.sliceArray(1 until data.size)
                a.seq += 1
                a.lastTouched = System.currentTimeMillis()
                if (a.buf.size >= a.total) {
                    assemblies.remove(id)
                    Result(a.buf.sliceArray(0 until a.total), complete = true)
                } else null
            }
            else -> null // flow-control (0x3_) and unknown PCI types
        }
    }

    private fun gc() {
        val now = System.currentTimeMillis()
        assemblies.entries.removeAll { now - it.value.lastTouched > TIMEOUT_MS }
    }

    /** Decode a complete Mode 09 payload into text (VIN / cal ID / ECU name). */
    fun decodeMode09(data: ByteArray): String? {
        if (data.size < 2 || data[0].toInt() != 0x49) return null
        // payload: [0x49, infoType, itemCount, data...]
        val body = data.sliceArray(3 until data.size)
        val text = String(body, Charsets.US_ASCII).trim { it.code < 0x20 }
        return text.ifEmpty { null }
    }

    /** Expand a Mode 01 support bitmask (response to PID 0x00/0x20/0x40/0x60). */
    fun decodeSupportedPids(base: Int, bytes: ByteArray): Set<Int> {
        val out = HashSet<Int>()
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            for (bit in 0 until 8) {
                if (b and (0x80 shr bit) != 0) out.add(base + 1 + i * 8 + bit)
            }
        }
        return out
    }
}
