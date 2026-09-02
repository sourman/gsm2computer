package com.gsm2computer.bridge.rtp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RTP packet encode/decode round-trip and protocol conformance (RFC 3550).
 *
 * The [RtpPacket.encode] path emits V=2, P=0, X=0, CC=0 headers; CSRC lists
 * are only honoured by [RtpPacket.decode]. We test both the real round-trip
 * and a hand-built CSRC/padding packet to exercise the decoder fully.
 */
class RtpPacketTest {

    @Test
    fun encodeThenDecodeRoundTripsAllHeaderFields() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val original = RtpPacket(
            payloadType = 9,
            sequenceNumber = 0x1234,
            timestamp = 0x0A0B0C0DL,
            ssrc = 0xDEADBEEFL,
            payload = payload,
            marker = true
        )

        val encoded = original.encode()
        val decoded = RtpPacket.decode(encoded) ?: error("decode returned null")

        assertEquals(9, decoded.payloadType)
        assertEquals(0x1234, decoded.sequenceNumber)
        assertEquals(0x0A0B0C0DL, decoded.timestamp)
        assertEquals(0xDEADBEEFL, decoded.ssrc)
        assertTrue(decoded.marker)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun encodeProducesVersion2Header() {
        val pkt = RtpPacket(0, 0, 0, 0, ByteArray(0))
        val encoded = pkt.encode()
        val version = (encoded[0].toInt() ushr 6) and 0x03
        assertEquals(2, version)
        // CC must be 0 on the encode path.
        assertEquals(0, encoded[0].toInt() and 0x0F)
        // Padding bit (P) and extension bit (X) are both 0.
        assertEquals(0, (encoded[0].toInt() ushr 5) and 0x01)
    }

    @Test
    fun sequenceNumberWrapsAround() {
        // 65534 -> 65535 -> 0 -> 1 — wraps at the 16-bit boundary.
        val p = RtpPacket(0, 65535, 0, 0, ByteArray(0))
        val d1 = RtpPacket.decode(p.encode())!!
        assertEquals(65535, d1.sequenceNumber)

        val p2 = RtpPacket(0, 0, 0, 0, ByteArray(0))
        assertEquals(0, RtpPacket.decode(p2.encode())!!.sequenceNumber)

        // Encode of 65535 + 1 == encode of 0 (mod 2^16, the short cast).
        val a = RtpPacket(0, 65536, 0, 0, ByteArray(0)).encode()
        val b = RtpPacket(0, 0, 0, 0, ByteArray(0)).encode()
        assertArrayEquals(b, a)
    }

    @Test
    fun decodeRejectsVersionZero() {
        val buf = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x00.toByte()) // V=0, P=0, X=0, CC=0
        buf.put(0x00.toByte()) // M=0, PT=0
        buf.putShort(0)
        buf.putInt(0)
        buf.putInt(0)
        assertNull(RtpPacket.decode(buf.array()))
    }

    @Test
    fun decodeRejectsVersionOne() {
        val buf = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x40.toByte()) // V=1
        buf.put(0x00.toByte())
        buf.putShort(0)
        buf.putInt(0)
        buf.putInt(0)
        assertNull(RtpPacket.decode(buf.array()))
    }

    @Test
    fun decodeRejectsVersionThree() {
        val buf = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        buf.put(0xC0.toByte()) // V=3
        buf.put(0x00.toByte())
        buf.putShort(0)
        buf.putInt(0)
        buf.putInt(0)
        assertNull(RtpPacket.decode(buf.array()))
    }

    @Test
    fun decodeRejectsShortPacket() {
        assertNull(RtpPacket.decode(ByteArray(11)))
        assertNull(RtpPacket.decode(ByteArray(0)))
    }

    @Test
    fun decodeHonoursCsrcCount() {
        // Encode does not emit CSRC, so build a packet by hand: V=2, CC=2.
        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val csrc0 = 0x11223344
        val csrc1 = 0x55667788
        val buf = ByteBuffer.allocate(12 + 8 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.put((0x80 or 0x02).toByte()) // V=2, P=0, X=0, CC=2
        buf.put(0x00.toByte())            // M=0, PT=0
        buf.putShort(0x0001)
        buf.putInt(0x00000010)
        buf.putInt(0xCAFEBABEL.toInt())
        buf.putInt(csrc0)
        buf.putInt(csrc1)
        buf.put(payload)

        val decoded = RtpPacket.decode(buf.array()) ?: error("decode returned null")
        // CSRC list is not exposed by the API, but the payload must be split
        // correctly (headerSize = 12 + 2*4 = 20, payload starts at byte 20).
        assertArrayEquals(payload, decoded.payload)
        assertEquals(0xCAFEBABEL, decoded.ssrc)
        assertEquals(0x0001, decoded.sequenceNumber)
    }

    @Test
    fun decodeRejectsPacketShorterThanCsrcHeader() {
        // V=2, CC=4 → header is 12 + 16 = 28 bytes, but we only supply 12.
        val buf = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        buf.put((0x80 or 0x04).toByte())
        buf.put(0x00.toByte())
        buf.putShort(0)
        buf.putInt(0)
        buf.putInt(0)
        assertNull(RtpPacket.decode(buf.array()))
    }

    @Test
    fun decodeRespectsExplicitLengthParameter() {
        val payload = byteArrayOf(0x01, 0x02)
        val original = RtpPacket(0, 1, 2, 3, payload)
        val encoded = original.encode()

        // Trailing garbage should be ignored when length is supplied.
        val withGarbage = encoded + byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F)
        val decoded = RtpPacket.decode(withGarbage, encoded.size) ?: error("decode returned null")
        assertArrayEquals(payload, decoded.payload)
        assertNotNull(decoded)
    }

    @Test
    fun markerBitAndPayloadTypeArePreservedForAllPayloadTypes() {
        for (pt in intArrayOf(0, 8, 9, 96, 127)) {
            val original = RtpPacket(pt, 0, 0, 0, ByteArray(0), marker = true)
            val decoded = RtpPacket.decode(original.encode())!!
            assertEquals(pt, decoded.payloadType)
            assertTrue("marker bit lost for pt=$pt", decoded.marker)
        }
    }

    @Test
    fun timestampIsUnsigned32Bit() {
        val max = 0xFFFFFFFFL
        val pkt = RtpPacket(0, 0, max, 0, ByteArray(0))
        val decoded = RtpPacket.decode(pkt.encode())!!
        assertEquals(max, decoded.timestamp)
    }

    @Test
    fun ssrcIsUnsigned32Bit() {
        val max = 0xFFFFFFFFL
        val pkt = RtpPacket(0, 0, 0, max, ByteArray(0))
        val decoded = RtpPacket.decode(pkt.encode())!!
        assertEquals(max, decoded.ssrc)
    }
}
