package com.canscope.app.protocol

// Minimal DBC file parser + signal decoder — Kotlin port of the web
// dashboard's lib/dbc.ts. Parses BO_ (message) and SG_ (signal) lines with
// Intel/Motorola byte order, signedness, factor/offset, and decodes live
// frames into physical values.

data class DbcSignal(
    val name: String,
    val startBit: Int,
    val length: Int,
    val littleEndian: Boolean,
    val signed: Boolean,
    val factor: Double,
    val offset: Double,
    val min: Double,
    val max: Double,
    val unit: String,
)

data class DbcMessage(
    val id: Long,
    val name: String,
    val dlc: Int,
    val sender: String,
    val signals: MutableList<DbcSignal> = mutableListOf(),
)

data class Dbc(val messages: Map<Long, DbcMessage>) {
    fun messageName(id: Long): String? = messages[id]?.name
}

object DbcParser {

    private val BO = Regex("""^BO_\s+(\d+)\s+(\w+)\s*:\s*(\d+)\s+(\w+)""")
    private val SG = Regex(
        """^SG_\s+(\w+)\s*:\s*(\d+)\|(\d+)@(\d)([+-])\s+\(([-\d.eE+]+),([-\d.eE+]+)\)\s+\[([-\d.eE+]+)\|([-\d.eE+]+)\]\s+"([^"]*)""""
    )

    fun parse(text: String): Dbc {
        val messages = LinkedHashMap<Long, DbcMessage>()
        var current: DbcMessage? = null
        for (line in text.lineSequence()) {
            val t = line.trim()
            BO.find(t)?.let { m ->
                current = DbcMessage(
                    id = m.groupValues[1].toLong(),
                    name = m.groupValues[2],
                    dlc = m.groupValues[3].toInt(),
                    sender = m.groupValues[4],
                )
                messages[current!!.id] = current!!
            }
            SG.find(t)?.let { m ->
                current?.signals?.add(
                    DbcSignal(
                        name = m.groupValues[1],
                        startBit = m.groupValues[2].toInt(),
                        length = m.groupValues[3].toInt(),
                        littleEndian = m.groupValues[4] == "1",
                        signed = m.groupValues[5] == "-",
                        factor = m.groupValues[6].toDouble(),
                        offset = m.groupValues[7].toDouble(),
                        min = m.groupValues[8].toDouble(),
                        max = m.groupValues[9].toDouble(),
                        unit = m.groupValues[10],
                    )
                )
            }
        }
        return Dbc(messages)
    }

    /** Extract the raw (unscaled) integer for a signal from a frame. */
    fun extractRaw(sig: DbcSignal, data: ByteArray): Long {
        var raw = 0L
        if (sig.littleEndian) {
            // Intel: little-endian, startBit is the LSB position
            for (i in 0 until sig.length) {
                val bitPos = sig.startBit + i
                val byte = bitPos / 8
                if (byte >= data.size) break
                val bit = (data[byte].toInt() shr (bitPos % 8)) and 1
                raw = raw or (bit.toLong() shl i)
            }
        } else {
            // Motorola (big-endian, sawtooth): startBit is the MSB position
            var bit = sig.startBit
            for (i in 0 until sig.length) {
                val byte = bit / 8
                val value = if (byte < data.size) (data[byte].toInt() shr (bit % 8)) and 1 else 0
                raw = (raw shl 1) or value.toLong()
                bit = if (bit % 8 == 0) bit + 15 else bit - 1
            }
        }
        return raw
    }

    /** Decode one signal to its physical value (factor/offset/signedness applied). */
    fun decodeSignal(sig: DbcSignal, data: ByteArray): Double {
        var raw = extractRaw(sig, data)
        if (sig.signed && (raw and (1L shl (sig.length - 1))) != 0L) {
            raw -= (1L shl sig.length)
        }
        return raw * sig.factor + sig.offset
    }

    /** Human-readable decode of every signal in a message. */
    fun decodeMessage(msg: DbcMessage, data: ByteArray): List<Pair<DbcSignal, Double>> =
        msg.signals.map { it to decodeSignal(it, data) }
}
