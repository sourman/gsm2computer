package com.gsm2computer.bridge.rtp

/**
 * ITU-T G.711 μ-law (PCMU) codec — RTP payload type 0.
 *
 * Narrowband (8 kHz), 8 bits per sample, 160 bytes per 20ms frame.
 * Mirrors the PcmaCodec structure (encode8k/encode/decode8k/decode) so it
 * drops into the same encode/decode switch sites in RtpSession.
 */
object PcmuCodec {

    /**
     * Encode a single 16-bit PCM sample to μ-law (ITU-T G.711).
     * Uses biased (segment) encoding per the reference algorithm.
     */
    fun encodeSample(pcm: Short): Byte {
        var sample = pcm.toInt()
        val sign = (sample shr 8) and 0x80
        if (sign != 0) sample = -sample
        if (sample > 32635) sample = 32635
        // Bias by 0x84 so quantizer thresholds fall on segment boundaries.
        sample += 0x84
        if (sample > 32635) sample = 32635

        // Segment = position of the highest set bit above the segment-0 base.
        var exponent = 0
        while (exponent < 7 && (sample shr (exponent + 8)) != 0) {
            exponent++
        }
        val mantissa = (sample shr (exponent + 3)) and 0x0F
        val raw = (sign and 0x80) or (exponent shl 4) or mantissa
        return (raw xor 0xFF).toByte()
    }

    /** Decode a single μ-law byte to 16-bit PCM. */
    fun decodeSample(pcmu: Byte): Short {
        val b = (pcmu.toInt() and 0xFF) xor 0xFF
        val sign = b and 0x80
        val exponent = (b shr 4) and 0x07
        val mantissa = b and 0x0F
        var sample = ((mantissa shl 3) + 0x84) shl exponent
        sample -= 0x84
        return if (sign != 0) (-sample).toShort() else sample.toShort()
    }

    /** Encode 8kHz PCM16 to μ-law directly (no sample rate conversion). */
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

    /** Encode 16kHz PCM16 to μ-law with 2:1 averaging downsample (mirrors PcmaCodec.encode). */
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

    /** Decode μ-law to 8kHz PCM16 directly (no sample rate conversion). */
    fun decode8k(pcmu: ByteArray): ByteArray {
        val out = ByteArray(pcmu.size * 2)
        var outIdx = 0
        for (byte in pcmu) {
            val sample = decodeSample(byte)
            out[outIdx++] = (sample.toInt() and 0xFF).toByte()
            out[outIdx++] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Decode μ-law to 16kHz PCM16 with 1:2 linear-interpolation upsampling (mirrors PcmaCodec.decode). */
    fun decode(pcmu: ByteArray): ByteArray {
        val out = ByteArray(pcmu.size * 4)
        var outIdx = 0
        for (i in pcmu.indices) {
            val s0 = decodeSample(pcmu[i]).toInt()
            val s1 = if (i + 1 < pcmu.size) decodeSample(pcmu[i + 1]).toInt() else s0
            val mid = (s0 + s1) / 2
            out[outIdx++] = (s0 and 0xFF).toByte()
            out[outIdx++] = ((s0 shr 8) and 0xFF).toByte()
            out[outIdx++] = (mid and 0xFF).toByte()
            out[outIdx++] = ((mid shr 8) and 0xFF).toByte()
        }
        return out
    }
}
