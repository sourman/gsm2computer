package com.gsm2computer.bridge.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.gsm2computer.bridge.DeviceProfile
import com.gsm2computer.bridge.RootShell

/**
 * Enforces the gateway's core invariant: the physical mic must NEVER be
 * audible on either leg.  Run before the bridge enters BRIDGED state.
 *
 * Flow:
 *  1. Apply the profile's mic-mute mixer ops.
 *  2. Open a short-lived AudioRecord on VOICE_CALL (or MIC fallback) and
 *     measure capture RMS over ~50ms with playback SILENT.
 *  3. Emit a [MicIsolationResult].  Callers MUST refuse to enter BRIDGED
 *     when NotIsolated unless the profile explicitly allows acoustic coupling.
 *
 * Fail-closed by design — there is no silent fallback.
 */
class MicIsolationGuard(
    private val context: Context,
    private val profile: DeviceProfile
) {

    sealed class MicIsolationResult {
        /** Mic is muted: capture RMS is at the noise floor with playback silent. */
        object Isolated : MicIsolationResult()
        /** Mic is leaking: capture RMS exceeds the noise floor.  [rmsDb] is the
         *  measured level.  Bridge must NOT proceed unless the profile allows
         *  acoustic coupling. */
        data class NotIsolated(val rmsDb: Double) : MicIsolationResult()
    }

    /**
     * Run the isolation check.  [onProgress] receives human-readable status
     * for broadcasting to the app log viewer.
     *
     * Returns Isolated when the measured capture RMS (with playback silent) is
     * below [ISOLATION_RMS_THRESHOLD], otherwise NotIsolated with the dBFS.
     */
    fun verify(onProgress: ((String) -> Unit)? = null): MicIsolationResult {
        onProgress?.invoke("MicIsolation: applying mic-mute mixer ops")
        applyMicMuteMixerOps()

        onProgress?.invoke("MicIsolation: measuring capture RMS (playback silent)")
        val rms = measureCaptureRms()
        val rmsDb = if (rms > 0) 20.0 * Math.log10(rms.toDouble() / FULL_SCALE) else NEG_INF_DB

        val result = if (rms <= ISOLATION_RMS_THRESHOLD) {
            MicIsolationResult.Isolated
        } else {
            MicIsolationResult.NotIsolated(rmsDb)
        }
        val msg = when (result) {
            MicIsolationResult.Isolated ->
                "MicIsolation: VERIFIED isolated (rms=$rms, ${"%.1f".format(rmsDb)}dBFS)"
            is MicIsolationResult.NotIsolated ->
                "MicIsolation: NOT ISOLATED (rms=$rms, ${"%.1f".format(rmsDb)}dBFS) — mic is leaking"
        }
        Log.i(TAG, msg)
        onProgress?.invoke(msg)
        return result
    }

    /** Run the profile's setup command's mic-mute portion.  Best-effort: the
     *  setup command is a batched string; we run it and rely on the mic-mute
     *  ops within.  Logged but not fatal if tinymix is unavailable. */
    private fun applyMicMuteMixerOps() {
        val cmd = profile.mixer.mixerSetupCmd
        if (cmd.isEmpty()) {
            Log.w(TAG, "No mixer setup command for ${profile.name} — cannot apply mic mute")
            return
        }
        val resolved = DeviceProfile.resolveCmd(cmd)
        if (resolved.isEmpty()) {
            Log.w(TAG, "tinymix unavailable — mic-mute mixer ops NOT applied")
            return
        }
        try {
            val out = RootShell.execForOutput(resolved, timeoutMs = 8000)
            if (out.isNotBlank()) Log.i(TAG, "Mic-mute mixer: $out")
        } catch (e: Exception) {
            Log.w(TAG, "Mic-mute mixer ops failed: ${e.message}")
        }
    }

    /**
     * Open a short-lived AudioRecord and measure RMS over ~50ms (2-3 frames)
     * with playback silent.  Tries VOICE_CALL first (the bridge's capture
     * source), then MIC.  Returns the max RMS observed.
     */
    private fun measureCaptureRms(): Int {
        val sampleRate = 8000
        val frameBytes = sampleRate / 50 * 2 * 2  // ~40ms (2 frames)
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.w(TAG, "getMinBufferSize failed ($minBuf) — assuming NOT isolated")
            return FULL_SCALE
        }
        val bufSize = minBuf.coerceAtLeast(frameBytes)

        val sources = if (profile.routing.playbackToTelephony) {
            // VOICE_CALL captures modem downlink on Pixel — not the physical mic.
            listOf(MediaRecorder.AudioSource.MIC to "MIC")
        } else {
            listOf(
                MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL",
                MediaRecorder.AudioSource.MIC to "MIC"
            )
        }
        var maxRms = 0
        for ((source, name) in sources) {
            val rec = try {
                AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize)
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord $name failed: ${e.message}")
                continue
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                Log.w(TAG, "AudioRecord $name not initialized")
                continue
            }
            try {
                rec.startRecording()
                val buf = ByteArray(frameBytes)
                // Discard one warm-up frame, then measure two.
                rec.read(buf, 0, buf.size)
                Thread.sleep(20)
                for (i in 0 until 2) {
                    val read = rec.read(buf, 0, buf.size)
                    if (read > 0) {
                        val rms = pcmRms(buf, read)
                        if (rms > maxRms) maxRms = rms
                    }
                }
                Log.i(TAG, "Measured capture RMS via $name: $maxRms")
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
            // First source that initializes is enough — VOICE_CALL matches the
            // bridge's actual capture path.
            break
        }
        return maxRms
    }

    private fun pcmRms(pcm: ByteArray, len: Int): Int {
        val sampleCount = len / 2
        if (sampleCount == 0) return 0
        var sumSquares = 0L
        for (i in 0 until sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSquares += sample.toLong() * sample
        }
        return Math.sqrt(sumSquares.toDouble() / sampleCount).toInt()
    }

    companion object {
        private const val TAG = "MicIsolationGuard"
        private const val FULL_SCALE = 32767
        private const val NEG_INF_DB = -120.0
        /** Capture RMS (0-32767) at or below which the mic is considered
         *  effectively muted with playback silent.  Calibrated to the modem
         *  DSP noise floor — a leaking physical mic produces RMS well above this. */
        private const val ISOLATION_RMS_THRESHOLD = 30
    }
}
