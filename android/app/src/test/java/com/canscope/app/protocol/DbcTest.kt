package com.canscope.app.protocol

import org.junit.Assert.*
import org.junit.Test

class DbcTest {

    private val dbcText = """
        BO_ 512 EngineData: 8 ECU1
         SG_ RPM : 0|16@1+ (0.25,0) [0|16383.75] "rpm"
         SG_ Temp : 16|8@1- (1,-40) [-40|215] "degC"
        BO_ 288 VehicleSpeed: 8 ECU2
         SG_ Speed : 0|16@1+ (0.01,0) [0|655.35] "km/h"
    """.trimIndent()

    @Test
    fun `parses messages and signals`() {
        val dbc = DbcParser.parse(dbcText)
        assertEquals(2, dbc.messages.size)
        val msg = dbc.messages[512L]!!
        assertEquals("EngineData", msg.name)
        assertEquals(2, msg.signals.size)
        assertEquals("RPM", msg.signals[0].name)
        assertEquals(0.25, msg.signals[0].factor, 1e-9)
        assertTrue(msg.signals[1].signed)
    }

    @Test
    fun `decodes a little-endian unsigned signal`() {
        val dbc = DbcParser.parse(dbcText)
        val rpm = dbc.messages[512L]!!.signals[0]
        // 8000 rpm -> raw 32000 = 0x7D00
        val v = DbcParser.decodeSignal(rpm, byteArrayOf(0x00, 0x7D, 0, 0, 0, 0, 0, 0))
        assertEquals(8000.0, v, 0.001)
    }

    @Test
    fun `decodes a signed signal with offset`() {
        val dbc = DbcParser.parse(dbcText)
        val temp = dbc.messages[512L]!!.signals[1]
        // byte 2 = 0x00 -> 0 * 1 - 40 = -40
        assertEquals(-40.0, DbcParser.decodeSignal(temp, byteArrayOf(0, 0, 0x00, 0, 0, 0, 0, 0)), 0.001)
        // 0xFF = -1 signed -> -41
        assertEquals(-41.0, DbcParser.decodeSignal(temp, byteArrayOf(0, 0, 0xFF.toByte(), 0, 0, 0, 0, 0)), 0.001)
    }

    @Test
    fun `decodes motorola big-endian signals`() {
        val dbc = DbcParser.parse("BO_ 100 M: 2 E\n SG_ B : 7|8@0+ (1,0) [0|255] \"\"")
        val sig = dbc.messages[100L]!!.signals[0]
        // byte 0 = 0x5A -> Motorola startBit 7, len 8 = whole first byte
        assertEquals(0x5A.toDouble(), DbcParser.decodeSignal(sig, byteArrayOf(0x5A, 0)), 0.001)
    }
}
