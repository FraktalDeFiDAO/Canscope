package com.canscope.app.protocol

// Passive VIN detection — Kotlin port of the web dashboard's lib/vinSniff.ts.
// Scans a rolling per-ID window of raw CAN traffic for 17-character VIN
// candidates and validates the ISO 3780 check digit. Mode 09 results always
// win over a sniffed candidate (handled in LinkViewModel).

enum class VinConfidence { CHECK_DIGIT, FORMAT }

data class VinHit(val vin: String, val confidence: VinConfidence, val sourceId: Long)

object VinSniff {

    private val WEIGHTS = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)
    private val TRANS = mapOf(
        'A' to 1, 'B' to 2, 'C' to 3, 'D' to 4, 'E' to 5, 'F' to 6, 'G' to 7, 'H' to 8,
        'J' to 1, 'K' to 2, 'L' to 3, 'M' to 4, 'N' to 5, 'P' to 7, 'R' to 9,
        'S' to 2, 'T' to 3, 'U' to 4, 'V' to 5, 'W' to 6, 'X' to 7, 'Y' to 8, 'Z' to 9,
    )
    private val VIN_RE = Regex("^[A-HJ-NPR-Z0-9]{17}$")

    private const val WINDOW_BYTES = 40
    private val windows = HashMap<Long, ArrayList<Int>>()

    /**
     * Validate a 17-char VIN candidate.
     * CHECK_DIGIT: position 9 matches the ISO 3780 weighted sum (North America).
     * FORMAT: valid charset, position 9 is 0/Z (EU/Asian vehicles often skip the digit).
     */
    fun validate(v: String): VinConfidence? {
        if (!VIN_RE.matches(v)) return null
        var sum = 0
        for (i in 0 until 17) {
            val c = v[i]
            val n = if (c in '0'..'9') c - '0' else TRANS[c] ?: return null
            sum += n * WEIGHTS[i]
        }
        val rem = sum % 11
        val expected = if (rem == 10) 'X' else '0' + rem
        return when {
            v[8] == expected -> VinConfidence.CHECK_DIGIT
            v[8] == '0' || v[8] == 'Z' -> VinConfidence.FORMAT
            else -> null
        }
    }

    /** Feed one raw frame; returns a validated VIN the first time one is seen. */
    @Synchronized
    fun feed(id: Long, data: ByteArray): VinHit? {
        val w = windows.getOrPut(id) { ArrayList() }
        for (b in data) w.add(b.toInt() and 0xFF)
        while (w.size > WINDOW_BYTES) w.removeAt(0)
        if (w.size < 17) return null

        for (start in w.size - 17 downTo 0) {
            var ok = true
            val sb = StringBuilder(17)
            for (i in start until start + 17) {
                val b = w[i]
                if (b < 0x30 || b > 0x5A) { ok = false; break }
                sb.append(b.toChar())
            }
            if (!ok) continue
            val s = sb.toString()
            val conf = validate(s)
            if (conf != null) {
                windows.remove(id)
                return VinHit(s, conf, id)
            }
        }
        return null
    }

    @Synchronized
    fun reset() = windows.clear()
}
