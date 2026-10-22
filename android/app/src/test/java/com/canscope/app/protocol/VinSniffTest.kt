package com.canscope.app.protocol

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VinSniffTest {

    private val vin = "1HGCM82633A004352" // check-digit-valid

    @Before
    fun reset() = VinSniff.reset()

    @Test
    fun `validates a check-digit VIN`() {
        assertEquals(VinConfidence.CHECK_DIGIT, VinSniff.validate(vin))
    }

    @Test
    fun `rejects a wrong check digit`() {
        assertNull(VinSniff.validate("1HGCM82643A004352"))
    }

    @Test
    fun `rejects illegal characters`() {
        assertNull(VinSniff.validate("1HGCM82633A00435I"))
        assertNull(VinSniff.validate("1HGCM82633A00435O"))
        assertNull(VinSniff.validate("1HGCM82633A00435Q"))
    }

    @Test
    fun `accepts EU-style VINs at format confidence`() {
        val v = vin.toCharArray(); v[8] = '0'
        assertEquals(VinConfidence.FORMAT, VinSniff.validate(String(v)))
    }

    @Test
    fun `detects a VIN split across three frames`() {
        assertNull(VinSniff.feed(0x4D2, "1HGCM826".toByteArray()))
        assertNull(VinSniff.feed(0x4D2, "33A00435".toByteArray()))
        val hit = VinSniff.feed(0x4D2, "2".toByteArray())
        assertEquals(vin, hit?.vin)
        assertEquals(VinConfidence.CHECK_DIGIT, hit?.confidence)
        assertEquals(0x4D2L, hit?.sourceId)
    }

    @Test
    fun `tolerates non-ASCII padding around the VIN`() {
        VinSniff.feed(0x123, byteArrayOf(0x00, 0xFF.toByte(), 0x10, 0x02))
        val hit = VinSniff.feed(0x123, vin.toByteArray() + byteArrayOf(0))
        assertEquals(vin, hit?.vin)
    }

    @Test
    fun `keeps per-ID windows independent`() {
        VinSniff.feed(0x111, "1HGCM826".toByteArray())
        assertNull(VinSniff.feed(0x222, "33A00435".toByteArray()))
    }
}
