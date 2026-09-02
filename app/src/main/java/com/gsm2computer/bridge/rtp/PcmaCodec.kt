package com.gsm2computer.bridge.rtp

/**
 * ITU-T G.711 A-law (PCMA) codec — RTP payload type 8.
 *
 * Narrowband (8 kHz), adequate for basic voice. Stateless: encode/decode
 * carry no predictor state, so a single shared instance serves any number
 * of concurrent calls. Wrapped by [PcmaCodecAdapter] for the [Codec]
 * interface; also used directly by tests.
 */
object PcmaCodec {
    private val ALAW_ENCODE_TABLE = intArrayOf(
        1, 1, 2, 2, 3, 3, 3, 3,
        4, 4, 4, 4, 4, 4, 4, 4,
        5, 5, 5, 5, 5, 5, 5, 5,
        5, 5, 5, 5, 5, 5, 5, 5,
        6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7
    )

    fun encodeSample(pcm: Short): Byte {
        var sample = pcm.toInt()
        val sign = (sample shr 8) and 0x80
        if (sign != 0) sample = -sample
        if (sample > 32635) sample = 32635

        val exponent = if (sample >= 256) ALAW_ENCODE_TABLE[(sample shr 8) and 0x7F]
        else ALAW_ENCODE_TABLE[(sample shr 4) and 0x1F].let { if (sample < 16) 0 else it }

        val mantissa = if (exponent > 0) (sample shr (exponent + 3)) and 0x0F else (sample shr 4) and 0x0F
        val encoded = (sign or (exponent shl 4) or mantissa) xor 0xD5
        return encoded.toByte()
    }

    fun decodeSample(alaw: Byte): Short {
        var value = (alaw.toInt() and 0xFF) xor 0xD5
        val sign = value and 0x80
        val exponent = (value shr 4) and 7
        var mantissa = value and 0x0F

        mantissa = (mantissa shl 4) + 8
        if (exponent > 0) mantissa = (mantissa + 256) shl (exponent - 1)

        return if (sign != 0) (-mantissa).toShort() else mantissa.toShort()
    }

    /** Encode 8kHz PCM16 to A-law directly (no sample rate conversion). */
    fun encode8k(pcm: ByteArray): ByteArray {
        val sampleCount = pcm.size / 2
        val out = ByteArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort()
            out[i] = encodeSample(sample)
        }
        return out
    }

    /** Encode 16kHz PCM16 to A-law with 2:1 averaging downsample.
     *  Averages each pair of adjacent samples before encoding, acting as
     *  a simple low-pass filter that prevents aliasing artifacts from
     *  high frequencies folding back into the 4kHz output band. */
    fun encode(pcm: ByteArray): ByteArray {
        val sampleCount = pcm.size / 2
        val outSize = sampleCount / 2
        val out = ByteArray(outSize)
        var outIdx = 0
        for (i in 0 until sampleCount step 2) {
            val lo0 = pcm[i * 2].toInt() and 0xFF
            val hi0 = pcm[i * 2 + 1].toInt()
            val s0 = (hi0 shl 8) or lo0
            val s1 = if (i + 1 < sampleCount) {
                val lo1 = pcm[(i + 1) * 2].toInt() and 0xFF
                val hi1 = pcm[(i + 1) * 2 + 1].toInt()
                (hi1 shl 8) or lo1
            } else s0
            val avg = ((s0 + s1) / 2).toShort()
            out[outIdx++] = encodeSample(avg)
            if (outIdx >= outSize) break
        }
        return out
    }

    /** Decode A-law to 8kHz PCM16 directly (no sample rate conversion). */
    fun decode8k(alaw: ByteArray): ByteArray {
        val out = ByteArray(alaw.size * 2)
        var outIdx = 0
        for (byte in alaw) {
            val sample = decodeSample(byte)
            out[outIdx++] = (sample.toInt() and 0xFF).toByte()
            out[outIdx++] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Decode A-law to 16kHz PCM16 with 1:2 linear-interpolation upsampling.
     *  Each input sample produces two output samples: the original and a
     *  midpoint interpolated toward the next sample.  Eliminates the
     *  aliasing/zipper noise from the previous zero-order hold approach. */
    fun decode(alaw: ByteArray): ByteArray {
        val out = ByteArray(alaw.size * 4)
        var outIdx = 0
        for (i in alaw.indices) {
            val s0 = decodeSample(alaw[i]).toInt()
            val s1 = if (i + 1 < alaw.size) decodeSample(alaw[i + 1]).toInt() else s0
            val mid = (s0 + s1) / 2
            out[outIdx++] = (s0 and 0xFF).toByte()
            out[outIdx++] = ((s0 shr 8) and 0xFF).toByte()
            out[outIdx++] = (mid and 0xFF).toByte()
            out[outIdx++] = ((mid shr 8) and 0xFF).toByte()
        }
        return out
    }
}
