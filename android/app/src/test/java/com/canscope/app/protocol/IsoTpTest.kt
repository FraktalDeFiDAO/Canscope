package com.canscope.app.protocol

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class IsoTpTest {

    // 20-byte payload: 49 02 01 + 17 ASCII chars of a VIN
    private val vin = "1HGCM82633A004352"
    private val payload = byteArrayOf(0x49, 0x02, 0x01) + vin.toByteArray(Charsets.US_ASCII)

    @Test
    fun `single frame passes through`() {
        val r = IsoTp.feed(0x7E8, byteArrayOf(0x04, 0x41, 0x0C, 0x1A, 0xF8.toByte(), 0, 0, 0))
        assertNotNull(r)
        assertTrue(r!!.complete)
        assertArrayEquals(byteArrayOf(0x41, 0x0C, 0x1A, 0xF8.toByte()), r.data)
    }

    @Test
    fun `first frame requests flow control`() {
        val r = IsoTp.feed(0x7E8, byteArrayOf(0x10, 0x14) + payload.copyOfRange(0, 6))
        assertNotNull(r)
        assertTrue(r!!.firstFrame)
        assertFalse(r.complete)
        assertArrayEquals(byteArrayOf(0x30, 0, 0, 0, 0, 0, 0, 0), IsoTp.flowControlFrame())
    }

    @Test
    fun `multi-frame VIN reassembles`() {
        IsoTp.feed(0x7E8, byteArrayOf(0x10, payload.size.toByte()) + payload.copyOfRange(0, 6))
        IsoTp.feed(0x7E8, byteArrayOf(0x21) + payload.copyOfRange(6, 13))
        val done = IsoTp.feed(0x7E8, byteArrayOf(0x22) + payload.copyOfRange(13, 20))
        assertNotNull(done)
        assertTrue(done!!.complete)
        assertEquals(vin, IsoTp.decodeMode09(done.data))
    }

    @Test
    fun `lost consecutive frame drops the transfer`() {
        IsoTp.feed(0x7E9, byteArrayOf(0x10, 0x14, 0x49, 0x02, 0x01, 0x41, 0x42, 0x43))
        assertNull(IsoTp.feed(0x7E9, byteArrayOf(0x23, 1, 2, 3, 4, 5, 6, 7))) // wrong seq
    }

    @Test
    fun `segmentation round-trips through reassembly`() {
        val original = ByteArray(25) { (it * 7 and 0xFF).toByte() }
        val frames = IsoTp.segment(original)
        var out: ByteArray? = null
        for (f in frames) {
            val r = IsoTp.feed(0x7E0, f)
            if (r?.complete == true) out = r.data
        }
        assertArrayEquals(original, out)
    }

    @Test
    fun `short payloads segment to one single frame`() {
        val frames = IsoTp.segment(byteArrayOf(0x01, 0x0C))
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x0C, 0, 0, 0, 0, 0), frames[0])
    }

    @Test
    fun `supported-pid bitmask decodes`() {
        val s = IsoTp.decodeSupportedPids(0x00, byteArrayOf(0x80.toByte(), 0, 0, 0x01))
        assertTrue(s.contains(0x01))
        assertTrue(s.contains(0x20))
        assertFalse(s.contains(0x02))
    }
}
