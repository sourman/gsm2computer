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
 * G.711 μ-law (PCMU) codec tests.
 *
 * PcmuCodec is a top-level object in PcmuCodec.kt (package
 * com.gsm2computer.bridge.rtp). It exposes encodeSample/decodeSample for single
 * samples plus byte-array encode8k/decode8k (8 kHz) and encode/decode (16k
 * with 2:1 down/upsample), mirroring PcmaCodec.
 */
class PcmuCodecTest {

    private val testSamples = listOf(
        0.toShort(), 100.toShort(), 1000.toShort(), 4096.toShort(),
        16000.toShort(), 30000.toShort(),
        (-100).toShort(), (-1000).toShort(), (-4096).toShort(),
        (-16000).toShort(), (-30000).toShort()
    )

    // ── Single-sample round trip ─────────────────────────────────────────

    @Test
    fun encodeDecodeRoundTripsSignAndCoarseMagnitude() {
        for (pcm in testSamples) {
            val encoded = PcmuCodec.encodeSample(pcm)
            val decoded = PcmuCodec.decodeSample(encoded).toInt()
            val expectedSign = signOf(pcm.toInt())
            val actualSign = signOf(decoded)
            assertEquals("sign mismatch for pcm=$pcm", expectedSign, actualSign)
            if (pcm.toInt() != 0) {
                val ratio = decoded.toDouble() / pcm.toDouble()
                assertTrue(
                    "magnitude drift for pcm=$pcm decoded=$decoded (ratio=$ratio)",
                    ratio in 0.85..1.15 || abs(decoded - pcm.toInt()) <= 512
                )
            }
        }
    }

    @Test
    fun silenceByte0xFFDecodesToNearZero() {
        // 0xFF is the μ-law silence byte (encoded value of zero after the
        // XOR toggle). It must decode to 0 or the smallest step.
        val decoded = PcmuCodec.decodeSample(0xFF.toByte()).toInt()
        assertTrue("0xFF decoded to $decoded, expected ~0", abs(decoded) <= 3)
    }

    @Test
    fun zeroEncodesTo0xFF() {
        val encoded = PcmuCodec.encodeSample(0).toInt() and 0xFF
        assertEquals(0xFF, encoded)
    }

    @Test
    fun fullScalePositiveDoesNotOverflow() {
        val encoded = PcmuCodec.encodeSample(Short.MAX_VALUE)
        val decoded = PcmuCodec.decodeSample(encoded).toInt()
        assertTrue("decoded $decoded should be positive and large", decoded > 24000)
    }

    @Test
    fun fullScaleNegativeDoesNotUnderflow() {
        val encoded = PcmuCodec.encodeSample(Short.MIN_VALUE)
        val decoded = PcmuCodec.decodeSample(encoded).toInt()
        assertTrue("decoded $decoded should be negative and large in magnitude", decoded < -24000)
    }

    // ── 8 kHz byte-array round trip ──────────────────────────────────────

    @Test
    fun encode8kDecode8kRoundTripsSign() {
        val pcm = sinePcmBytes(freqHz = 1000, sampleRate = 8000, durationMs = 40, amplitude = 8000)
        val encoded = PcmuCodec.encode8k(pcm)
        assertEquals(pcm.size / 2, encoded.size)
        val decoded = PcmuCodec.decode8k(encoded)
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
        val encoded = ByteArray(160) { 0xFF.toByte() }
        val decoded = PcmuCodec.decode8k(encoded)
        assertEquals(320.toLong(), decoded.size.toLong())
    }

    // ── 16 kHz rate conversion ───────────────────────────────────────────

    @Test
    fun encode16kHalvesSampleCount() {
        val pcm = sinePcmBytes(freqHz = 1000, sampleRate = 16000, durationMs = 40, amplitude = 8000)
        val encoded = PcmuCodec.encode(pcm)
        assertEquals("16k encode must downsample 2:1", (pcm.size / 4).toLong(), encoded.size.toLong())
    }

    @Test
    fun decode16kDoublesSampleCount() {
        val encoded = ByteArray(160) { 0xFF.toByte() }
        val decoded = PcmuCodec.decode(encoded)
        assertEquals("16k decode must upsample 1:2", (encoded.size * 4).toLong(), decoded.size.toLong())
    }

    @Test
    fun decode16kProducesLinearlyInterpolatedMidpoint() {
        val s0 = 10000
        val s1 = 20000
        val encoded = byteArrayOf(
            PcmuCodec.encodeSample(s0.toShort()),
            PcmuCodec.encodeSample(s1.toShort())
        )
        val decoded = PcmuCodec.decode(encoded)
        val out0 = readLeShort(decoded, 0)
        val outMid = readLeShort(decoded, 2)
        assertTrue("s0 not recovered: got $out0 expected ~$s0", abs(out0 - s0) <= 600)
        val expectedMid = (s0 + s1) / 2
        assertTrue("midpoint wrong: got $outMid expected ~$expectedMid", abs(outMid - expectedMid) <= 600)
    }

    // ── SNR ──────────────────────────────────────────────────────────────

    @Test
    fun sineRoundTripExceeds30DbSnr() {
        val pcm = sinePcmBytes(freqHz = 1000, sampleRate = 8000, durationMs = 100, amplitude = 20000)
        val encoded = PcmuCodec.encode8k(pcm)
        val decoded = PcmuCodec.decode8k(encoded)
        val snrDb = computeSnrDb(pcm, decoded)
        assertTrue("SNR $snrDb dB below 30 dB threshold", snrDb > 30.0)
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

    private fun computeSnrDb(original: ByteArray, decoded: ByteArray): Double {
        val n = Math.min(original.size, decoded.size) / 2
        var signalPower = 0.0
        var noisePower = 0.0
        for (i in 0 until n) {
            val o = readLeShort(original, i * 2)
            val d = readLeShort(decoded, i * 2)
            signalPower += o.toDouble() * o.toDouble()
            noisePower += (d - o).toDouble() * (d - o).toDouble()
        }
        if (noisePower == 0.0) return Double.POSITIVE_INFINITY
        val snr = signalPower / noisePower
        return 10.0 * kotlin.math.log10(snr)
    }
}
