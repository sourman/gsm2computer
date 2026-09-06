package com.gsm2computer.bridge.rtp

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * On-device PCM tap for the next GSM diagnosis pass.
 *
 * Writes under Android/data/…/files/call-taps/<utc>/:
 *   pre-gate.wav   — raw AudioRecord, before noise/echo gate and gain
 *   post-gate.wav  — after noise/echo gate and gain (zeros = gated)
 *   seconds.jsonl  — one JSON object per second of gate/RMS stats
 *   meta.json      — source, rate, gain, totals
 */
class CaptureTap private constructor(
    private val dir: File,
    private val meta: JSONObject,
    rate: Int,
) {
    private val pre = PcmWav(File(dir, "pre-gate.wav"), rate)
    private val post = PcmWav(File(dir, "post-gate.wav"), rate)
    private val seconds = File(dir, "seconds.jsonl").bufferedWriter()
    private var closed = false

    private var frames = 0
    private var secRawSq = 0.0
    private var secPostSq = 0.0
    private var secFwd = 0
    private var secNoise = 0
    private var secEcho = 0
    private var secPlay = 0
    private var totalFwd = 0L
    private var totalNoise = 0L
    private var totalEcho = 0L
    private var prePeak = 0
    private var postPeak = 0
    private var preBytes = 0L
    private val startedAtMs = android.os.SystemClock.elapsedRealtime()

    @Synchronized
    fun writePre(pcm: ByteArray, nbytes: Int) {
        if (closed) return
        pre.write(pcm, nbytes)
        preBytes += nbytes
        prePeak = maxOf(prePeak, peakAbs(pcm, nbytes))
    }

    @Synchronized
    fun writePost(pcm: ByteArray, nbytes: Int) {
        if (closed) return
        post.write(pcm, nbytes)
        postPeak = maxOf(postPeak, peakAbs(pcm, nbytes))
    }

    @Synchronized
    fun noteFrame(rawRms: Int, postRms: Int, forwarded: Boolean, noiseGated: Boolean, echoGated: Boolean, playRms: Int) {
        if (closed) return
        frames++
        secRawSq += rawRms.toDouble() * rawRms
        secPostSq += postRms.toDouble() * postRms
        secPlay += playRms
        when {
            forwarded -> { secFwd++; totalFwd++ }
            echoGated -> { secEcho++; totalEcho++ }
            noiseGated -> { secNoise++; totalNoise++ }
            else -> { secNoise++; totalNoise++ }
        }
        if (frames % 50 == 0) flushSecond()
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        if (frames % 50 != 0) flushSecond()
        seconds.close()
        pre.close()
        post.close()
        val elapsedS = (android.os.SystemClock.elapsedRealtime() - startedAtMs) / 1000.0
        val preSamples = preBytes / 2.0
        val impliedHz = if (elapsedS > 0.2) preSamples / elapsedS else 0.0
        val total = totalFwd + totalNoise + totalEcho
        meta.put("seconds", frames / 50.0)
        meta.put("wall_seconds", elapsedS)
        meta.put("pre_samples", preSamples.toLong())
        meta.put("implied_hz", impliedHz)
        meta.put("frames", frames)
        meta.put("fwd", totalFwd)
        meta.put("noise_gated", totalNoise)
        meta.put("echo_gated", totalEcho)
        meta.put("fwd_frac", if (total == 0L) 0.0 else totalFwd.toDouble() / total)
        meta.put("pre_peak", prePeak / 32768.0)
        meta.put("post_peak", postPeak / 32768.0)
        meta.put("dir", dir.absolutePath)
        File(dir, "meta.json").writeText(meta.toString(2) + "\n")
        Log.i(TAG, "capture tap closed ${dir.absolutePath} fwd=$totalFwd noise=$totalNoise echo=$totalEcho")
    }

    private fun flushSecond() {
        val n = 50.0
        val t = frames / 50.0
        seconds.write(
            JSONObject()
                .put("t", t)
                .put("raw_rms", kotlin.math.sqrt(secRawSq / n).toInt())
                .put("post_rms", kotlin.math.sqrt(secPostSq / n).toInt())
                .put("fwd", secFwd)
                .put("noise", secNoise)
                .put("echo", secEcho)
                .put("play_rms", secPlay / 50)
                .toString()
        )
        seconds.write("\n")
        seconds.flush()
        secRawSq = 0.0
        secPostSq = 0.0
        secFwd = 0
        secNoise = 0
        secEcho = 0
        secPlay = 0
    }

    companion object {
        private const val TAG = "CaptureTap"
        private const val KEEP = 8

        fun open(
            context: Context,
            source: String,
            rate: Int,
            halRate: Int,
            gain: Int,
            noiseGate: Int,
            echoGate: Int,
            profile: String,
            telephonyPlayback: Boolean,
        ): CaptureTap {
            val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "call-taps")
            root.mkdirs()
            prune(root)
            val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())
            val dir = File(root, stamp)
            dir.mkdirs()
            val meta = JSONObject()
                .put("id", stamp)
                .put("source", source)
                .put("rate", rate)
                .put("hal_sample_rate", halRate)
                .put("capture_gain", gain)
                .put("noise_gate", noiseGate)
                .put("echo_gate", echoGate)
                .put("profile", profile)
                .put("playback_to_telephony", telephonyPlayback)
            Log.i(TAG, "capture tap dir ${dir.absolutePath}")
            return CaptureTap(dir, meta, rate)
        }

        private fun prune(root: File) {
            val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: return
            for (old in dirs.drop(KEEP)) {
                old.listFiles()?.forEach { it.delete() }
                old.delete()
            }
        }

        private fun peakAbs(pcm: ByteArray, nbytes: Int): Int {
            var peak = 0
            var i = 0
            while (i + 1 < nbytes) {
                val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
                val v = s.toShort().toInt()
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
                i += 2
            }
            return peak
        }
    }
}

private class PcmWav(path: File, private val rate: Int) {
    private val raf = RandomAccessFile(path, "rw")
    private var dataBytes = 0

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    fun write(pcm: ByteArray, nbytes: Int) {
        if (nbytes <= 0) return
        raf.write(pcm, 0, nbytes)
        dataBytes += nbytes
    }

    fun close() {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(rate)
        header.putInt(rate * 2)
        header.putShort(2) // block align
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataBytes)
        raf.seek(0)
        raf.write(header.array())
        raf.close()
    }
}
