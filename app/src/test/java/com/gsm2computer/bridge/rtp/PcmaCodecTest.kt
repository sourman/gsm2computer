package com.gsm2computer.bridge.rtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.PI

/**
 * G.711 A-law (PCMA) codec tests.
 *
 * PcmaCodec is a top-level object in PcmaCodec.kt (package
 * com.gsm2computer.bridge.rtp). It exposes encodeSample/decodeSample for single
 * samples plus byte-array encode/decode with 8k↔16k rate conversion.
 */
class PcmaCodecTest {

    private val testSamples = listOf(
        0.toShort(), 64.toShort(), 256.toShort(), 1024.toShort(),
        4096.toShort(), 8192.toShort(), 16384.toShort(), 30000.toShort(),
        (-64).toShort(), (-256).toShort(), (-1024).toShort(),
        (-4096).toShort(), (-30000).toShort()
    )

    // ── Single-sample round trip ─────────────────────────────────────────

    @Test
    fun encodeDecodeRoundTripsSignAndCoarseMagnitude() {
        for (pcm in testSamples) {
            val encoded = PcmaCodec.encodeSample(pcm)
            val decoded = PcmaCodec.decodeSample(encoded).toInt()
            val expectedSign = signOf(pcm.toInt())
            val actualSign = signOf(decoded)
            // pcm==0 decodes to the smallest A-law step (value 8), not 0;
            // only assert sign for non-zero inputs.
            if (pcm.toInt() != 0) {
                assertEquals("sign mismatch for pcm=$pcm", expectedSign, actualSign)
            }
            if (abs(pcm.toInt()) >= 1024) {
                val ratio = decoded.toDouble() / pcm.toDouble()
                // A-law quantisation step at 30k is ~512, so allow ±15% for
                // larger samples. Small samples have coarse quantisation and
                // are only sign-checked.
                assertTrue("magnitude drift for pcm=$pcm decoded=$decoded (ratio=$ratio)", ratio in 0.85..1.15)
            }
        }
    }

    @Test
    fun silenceByte0xD5DecodesToNearZero() {
        // 0xD5 is the A-law silence byte (encoded value of 0x0000 after the
        // XOR toggle). It must decode to 0 or the smallest step.
        val decoded = PcmaCodec.decodeSample(0xD5.toByte()).toInt()
        assertTrue("0xD5 decoded to $decoded, expected ~0", abs(decoded) <= 8)
    }

    @Test
    fun zeroEncodesTo0xD5() {
        val encoded = PcmaCodec.encodeSample(0).toInt() and 0xFF
        assertEquals(0xD5, encoded)
    }

    @Test
    fun fullScalePositiveDoesNotOverflow() {
        val encoded = PcmaCodec.encodeSample(Short.MAX_VALUE)
        val decoded = PcmaCodec.decodeSample(encoded).toInt()
        assertTrue("decoded $decoded should be positive and large", decoded > 24000)
    }

    @Test
    fun fullScaleNegativeDoesNotUnderflow() {
        val encoded = PcmaCodec.encodeSample(Short.MIN_VALUE)
        val decoded = PcmaCodec.decodeSample(encoded).toInt()
        assertTrue("decoded $decoded should be negative and large in magnitude", decoded < -24000)
    }

    // ── 8 kHz byte-array round trip ──────────────────────────────────────

    @Test
    fun encode8kDecode8kRoundTripsSign() {
        val pcm = sinePcmBytes(freqHz = 1000, sampleRate = 8000, durationMs = 40, amplitude = 8000)
        val encoded = PcmaCodec.encode8k(pcm)
        assertEquals(pcm.size / 2, encoded.size)
        val decoded = PcmaCodec.decode8k(encoded)
        assertEquals(pcm.size.toLong(), decoded.size.toLong())
        var signMatches = 0
        var total = 0
        var i = 0
        while (i < pcm.size - 1) {
            val orig = readLeShort(pcm, i)
            val dec = readLeShort(decoded, i)
            if (orig == 0 || signOf(orig) == signOf(dec)) signMatches++
            total++
            i += 2
        }
        val pct = signMatches * 100 / total
        assertTrue("sign agreement $pct% too low", pct > 90)
    }

    @Test
    fun decode8kOutputLengthIsInputTimesTwo() {
        val encoded = ByteArray(160) { 0xD5.toByte() }
        val decoded = PcmaCodec.decode8k(encoded)
        assertEquals(320.toLong(), decoded.size.toLong())
    }

    // ── 16 kHz rate conversion ───────────────────────────────────────────

    @Test
    fun encode16kHalvesSampleCount() {
        val pcm = sinePcmBytes(freqHz = 1000, sampleRate = 16000, durationMs = 40, amplitude = 8000)
        val encoded = PcmaCodec.encode(pcm)
        assertEquals("16k encode must downsample 2:1", (pcm.size / 4).toLong(), encoded.size.toLong())
    }

    @Test
    fun decode16kDoublesSampleCount() {
        val encoded = ByteArray(160) { 0xD5.toByte() }
        val decoded = PcmaCodec.decode(encoded)
        assertEquals("16k decode must upsample 1:2", (encoded.size * 4).toLong(), decoded.size.toLong())
    }

    @Test
    fun decode16kProducesLinearlyInterpolatedMidpoint() {
        // Two distinct non-zero samples: s0 then s1. The 16k decoder emits
        // s0, then (s0+s1)/2 as the interpolated midpoint before s1.
        val s0 = 10000
        val s1 = 20000
        val encoded = byteArrayOf(
            PcmaCodec.encodeSample(s0.toShort()),
            PcmaCodec.encodeSample(s1.toShort())
        )
        val decoded = PcmaCodec.decode(encoded)
        val out0 = readLeShort(decoded, 0)
        val outMid = readLeShort(decoded, 2)
        assertTrue("s0 not recovered: got $out0 expected ~$s0", abs(out0 - s0) <= 600)
        val expectedMid = (s0 + s1) / 2
        assertTrue("midpoint wrong: got $outMid expected ~$expectedMid", abs(outMid - expectedMid) <= 600)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun signOf(x: Int): Int = when {
        x > 0 -> 1
        x < 0 -> -1
        else -> 0
    }

    private fun sinePcmBytes(freqHz: Int, sampleRate: Int, durationMs: Int, amplitude: Int): ByteArray {
        val n = sampleRate * durationMs / 1000
        val buf = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val v = (amplitude * sin(2.0 * PI * freqHz * t)).toInt()
            buf.putShort(v.toShort())
        }
        return buf.array()
    }

    private fun readLeShort(bytes: ByteArray, offset: Int): Int {
        val lo = bytes[offset].toInt() and 0xFF
        val hi = bytes[offset + 1].toInt()
        return ((hi shl 8) or lo).toShort().toInt()
    }
}
