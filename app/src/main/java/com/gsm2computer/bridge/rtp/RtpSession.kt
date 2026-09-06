package com.gsm2computer.bridge.rtp

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioDeviceInfo
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.gsm2computer.bridge.DeviceProfile
import com.gsm2computer.bridge.RootShell
import com.gsm2computer.bridge.gsm.GsmCallManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RTP session: handles bidirectional audio between speaker/mic and remote RTP endpoint.
 *
 * On S4 Mini (MSM8930), VOICE_DOWNLINK routes through the physical mic
 * (HAL usecase incall-rec-downlink → voice-handset-mic).  Speaker mode
 * plays GSM caller audio through the speaker; the mic picks it up.
 *
 * Audio path:
 * - Capture: VOICE_DOWNLINK via mic → gain boost → encode → RTP → SIP agent.
 * - Playback: RTP → decode → AudioTrack (usage from profile).
 *   MSM8930: USAGE_MEDIA → STREAM_MUSIC.  incall_music_enabled=true
 *   injects STREAM_MUSIC into voice TX, bypassing modem AEC.
 *   Exynos 9820: USAGE_VOICE_COMMUNICATION → STREAM_VOICE_CALL.
 *   Samsung HAL may route this into the modem uplink directly.
 *
 * The Magisk module disables Android's audio concurrency restrictions.
 * Uses G.722 codec for wideband (16 kHz), falls back to PCMA (G.711 A-law).
 */
class RtpSession(
    private val context: Context,
    private val localPort: Int,
    private val remoteAddr: String,
    private val remotePort: Int,
    private val payloadType: Int = RtpPacket.PT_PCMA,
    // Optional pluggable wire. null → UDP/RTP to a SIP peer (default). Non-null
    // → the transport replaces the UDP wire (e.g. OpenAI Realtime WebSocket):
    // socket/receiveLoop/NAT-punch/DTMF are skipped, while capture-gating,
    // jitter buffer, codec and injection paths stay unchanged.
    private val transport: MediaTransport? = null
) {
    private val wsMode: Boolean get() = transport != null

    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // Codec
    private val g722Encoder = G722Codec()
    private val g722Decoder = G722Codec()

    // RTP state
    private var txSequence = 0
    private var txTimestamp = 0L
    private val txSsrc = (Math.random() * 0xFFFFFFFFL).toLong()

    // Symmetric RTP: latch onto the actual source address of received packets
    @Volatile private var latchedAddr: InetAddress? = null
    @Volatile private var latchedPort: Int = 0

    // Jitter buffer for received packets.  Capacity 8 (160ms) absorbs
    // network jitter without audible gaps.  The playback loop drains
    // excess above 5 (100ms) to bound latency — acceptable for a GSM
    // bridge that already has 100-200ms of inherent GSM latency.
    // Previous capacity=3 was too aggressive: any slight jitter caused
    // packet drops and choppy audio.
    //
    // WS transport mode uses a much larger buffer (≈10s): OpenAI streams a whole
    // response faster than real time in a burst, and the playback loop paces it
    // out at 20ms/frame. The drain-excess logic is disabled in WS mode so the
    // burst isn't discarded (see playbackLoop).
    private val jitterBuffer = ArrayBlockingQueue<ByteArray>(if (transport != null) 500 else 8)

    // RTP inactivity tracking
    @Volatile private var lastRtpReceivedTime = 0L
    private val rtpTimeoutMs = 30_000L  // 30 seconds with no RTP = dead call

    // Packet counters and audio diagnostics
    @Volatile var txPacketCount = 0L; private set
    @Volatile var rxPacketCount = 0L; private set
    @Volatile var playbackFrames = 0L; private set
    @Volatile var captureRms = 0; private set
    @Volatile var playbackRms = 0; private set
    @Volatile var rawCaptureRms = 0; private set  // Before echo gate — 0 means source is silent
    @Volatile var audioSourceName = "none"; private set
    @Volatile private var playbackUsageName = "MEDIA"
    @Volatile private var firstRxInfo = ""
    @Volatile private var firstTxInfo = ""
    private var lastFlowTxCount = 0L
    private var lastFlowRxCount = 0L


    // Capture and playback rates may differ.  VOICE_CALL on MSM8930
    // only initializes at 8 kHz; G.722 decoding outputs 16 kHz PCM.
    // WS mode takes the highest rate AudioRecord/AudioTrack will init.
    private var captureRate = 8000
    private var playbackRate = 8000
    @Volatile private var inboundFormat = "audio/pcmu"
    @Volatile private var inboundRate = 8000

    // Audio session ID from AudioRecord (for logging/diagnostics)
    private var audioSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE

    @Volatile private var currentSourceId: Int = -1
    private data class SourceConfig(val source: Int, val name: String, val rate: Int)

    /**
     * Capture configs for the profile's [captureSource] (VOICE_CALL by default,
     * VOICE_DOWNLINK on Pixel/Tensor).  For wideband codecs (G.722) 16 kHz is
     * tried before 8 kHz — that is sample-rate negotiation, NOT a source
     * fallback; the source itself never changes.
     */
    private fun buildCaptureConfigs(): List<SourceConfig> {
        val source = profile.routing.captureSource
        val name = when (source) {
            MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
            MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
            else -> "SRC$source"
        }
        val wideband = payloadType != RtpPacket.PT_PCMA && payloadType != RtpPacket.PT_PCMU
        return if (wsMode) {
            listOf(
                SourceConfig(source, "$name@48k", 48000),
                SourceConfig(source, "$name@16k", 16000),
                SourceConfig(source, name, 8000),
            )
        } else if (wideband) {
            listOf(
                SourceConfig(source, "$name@16k", 16000),
                SourceConfig(source, name, 8000)
            )
        } else {
            listOf(SourceConfig(source, name, 8000))
        }
    }
    // Silence detection — only counted during non-echo periods.  On Pixel/Tensor
    // (playbackToTelephony) VOICE_CALL reads near-zero during quiet GSM pauses.
    private val silenceRmsThreshold = 3
    private val silenceFrameLimit = 150   // ~3s of true silence before failing the call

    var listener: Listener? = null

    interface Listener {
        fun onRtpStarted()
        fun onRtpStopped()
        fun onRtpError(error: String)
        fun onRtpTimeout() {}  // No RTP received for rtpTimeoutMs
        fun onRtpStats(stats: String) {}  // Periodic detailed stats
    }

    fun start() {
        if (running.getAndSet(true)) return
        Log.i(TAG, "Starting RTP session: local=$localPort remote=$remoteAddr:$remotePort pt=$payloadType wsMode=$wsMode")

        // UDP wire only in SIP/RTP mode. WS transport mode has no local socket.
        if (!wsMode) {
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(localPort))
                    soTimeout = 100
                    receiveBufferSize = 262144
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind RTP socket on port $localPort: ${e.message}")
                running.set(false)
                listener?.onRtpError("Socket bind failed: ${e.message}")
                return
            }
        }

        if (!initAudio()) {
            running.set(false)
            return
        }

        if (wsMode) {
            notifyHubWire()
            transport?.start(transportSink)
        } else {
            // Send initial RTP keepalive to punch NAT pinhole before audio starts
            try {
                val remoteInet = InetAddress.getByName(remoteAddr)
                val silence = RtpPacket(payloadType, 0, 0, txSsrc, ByteArray(160)).encode()
                socket?.send(DatagramPacket(silence, silence.size, remoteInet, remotePort))
                Log.i(TAG, "Sent NAT punch-through packet to $remoteAddr:$remotePort")
            } catch (e: Exception) {
                Log.w(TAG, "NAT punch-through failed: ${e.message}")
            }
        }

        lastRtpReceivedTime = System.currentTimeMillis()
        if (!wsMode) Thread({ receiveLoop() }, "RTP-Recv-$localPort").start()
        Thread({ playbackLoop() }, "RTP-Play-$localPort").start()
        Thread({ captureInitAndLoop() }, "RTP-Capt-$localPort").start()
        Thread({ timeoutLoop() }, "RTP-Timeout-$localPort").start()

        listener?.onRtpStarted()
    }

    private fun notifyHubWire() {
        if (!wsMode) return
        Log.i(TAG, "hub wire pcm in=$captureRate out=$playbackRate")
        transport?.configureWire("audio/pcm", captureRate, "audio/pcm", playbackRate)
    }

    /**
     * Initialize AudioRecord and AudioTrack.
     *
     * AudioRecord: profile.routing.captureSource (VOICE_CALL = uplink+downlink
     * mixed; VOICE_DOWNLINK = caller-only, used on Pixel/Tensor).
     * AudioTrack: USAGE_MEDIA (STREAM_MUSIC) so that Qualcomm's
     * incall_music_enabled=true parameter injects it into voice TX (uplink).
     * USAGE_VOICE_COMMUNICATION maps to STREAM_VOICE_CALL which the HAL
     * does NOT inject via incall_music — that's why SIP→GSM was silent.
     */
    private fun initAudio(): Boolean {
        // Playback rate matches codec output rate.  G.722 decodes to 16 kHz.
        playbackRate = when {
            wsMode -> 48000
            payloadType == RtpPacket.PT_PCMA || payloadType == RtpPacket.PT_PCMU -> 8000
            else -> 16000
        }

        // Capture source comes from the device profile (VOICE_CALL by default,
        // VOICE_DOWNLINK on Pixel/Tensor).  The 8kHz/16kHz variants are
        // sample-rate negotiation, NOT a source fallback.
        val configs = buildCaptureConfigs()

        // Single attempt here.  Cold-boot HAL init latency is handled by the
        // bounded retry in captureInitAndLoop() — this method must not fall
        // back to other sources.
        reAssertAppOps()
        // 500ms propagation delay.  On cold boot, system services are all
        // starting simultaneously and AudioFlinger takes longer to see the
        // appops change.
        Thread.sleep(500)

        var record: AudioRecord? = null
        var usedRate = 8000

        for (cfg in configs) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    cfg.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) {
                    Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate}: invalid minBuf=$minBuf")
                    continue
                }
                val bufSize = minBuf.coerceAtLeast(cfg.rate / 50 * 2 * 2) // 40ms (two RTP frames)
                val rec = AudioRecord(
                    cfg.source, cfg.rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    record = rec
                    usedRate = cfg.rate
                    audioSourceName = cfg.name
                    currentSourceId = cfg.source
                    Log.i(TAG, "AudioRecord OK: ${cfg.name} @ ${cfg.rate}Hz (buf=$bufSize)")
                    break
                } else {
                    Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate}: state=${rec.state}")
                    rec.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate} failed: ${e.message}")
            }
        }

        if (record != null) {
            audioRecord = record
            audioSessionId = record.audioSessionId
            captureRate = usedRate

            // Diagnostic: check critical permissions and ABOX state
            logCaptureDiagnostics(record)
        }

        // Minimum buffer for lowest latency.  incall_music injects
        // AudioTrack output digitally into the modem uplink — there is no
        // acoustic speaker→mic path, so deep-buffer headroom is unnecessary.
        // Writing silence when the jitter buffer is empty prevents underruns.
        //
        // IMPORTANT: Do NOT use PERFORMANCE_MODE_LOW_LATENCY here.
        // Low-latency forces the HAL to use "low-latency-playback" usecase
        // which maps to MultiMedia5.  On MSM8930 (Galaxy S4 Mini), only
        // MultiMedia1 and MultiMedia2 have Incall_Music mixer controls.
        // MultiMedia5 has no incall_music mixer, so audio plays on the
        // earpiece but is NEVER injected into the modem uplink.
        // Using default (deep-buffer-playback → MultiMedia1) ensures the
        // Incall_Music Audio Mixer MultiMedia1 routes audio to the caller.
        val usage = if (profile.routing.playbackUsage >= 0) profile.routing.playbackUsage
                    else AudioAttributes.USAGE_MEDIA
        val contentType = if (usage == AudioAttributes.USAGE_VOICE_COMMUNICATION)
            AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC
        playbackUsageName = when (usage) {
            AudioAttributes.USAGE_MEDIA -> "MEDIA"
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            else -> "usage=$usage"
        }
        val playRates = if (wsMode) listOf(48000, 16000, 8000) else listOf(playbackRate)
        var track: AudioTrack? = null
        for (rate in playRates) {
            val minPlayBuf = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minPlayBuf <= 0) {
                Log.w(TAG, "AudioTrack @$rate: invalid minBuf=$minPlayBuf")
                continue
            }
            val candidate = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(contentType)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(minPlayBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack @$rate failed: ${e.message}")
                null
            }
            if (candidate != null && candidate.state == AudioTrack.STATE_INITIALIZED) {
                track = candidate
                playbackRate = rate
                Log.i(TAG, "AudioTrack OK @$rate Hz (buf=$minPlayBuf)")
                break
            }
            Log.w(TAG, "AudioTrack @$rate: state=${candidate?.state}")
            candidate?.release()
        }
        if (track == null) {
            Log.e(TAG, "AudioTrack failed at all rates")
            listener?.onRtpError("AudioTrack init failed")
            audioRecord?.release()
            audioRecord = null
            return false
        }
        audioTrack = track

        // Route playback to TYPE_TELEPHONY (modem TX uplink) on devices where
        // the audio HAL needs an active PCM stream on the telephony endpoint
        // (Pixel/Tensor aoc-snd-card).  Requires MODIFY_PHONE_STATE permission.
        if (profile.routing.playbackToTelephony) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val telephonyDev = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            if (telephonyDev != null) {
                val routed = track.setPreferredDevice(telephonyDev)
                Log.i(TAG, "AudioTrack routed to TYPE_TELEPHONY: $routed (id=${telephonyDev.id})")
            } else {
                Log.w(TAG, "TYPE_TELEPHONY device not found — playback will use default device")
            }
        }

        // No platform AEC — AudioTrack is on USAGE_MEDIA (different stream
        // from AudioRecord), so platform AEC can't reference it anyway.
        // For VOICE_DOWNLINK, AEC was over-canceling (capRMS dropped from 252 to ~5).
        // Echo cancellation is handled by Asterisk on the server side.

        Log.i(TAG, "Audio init: playRate=$playbackRate playUsage=$playbackUsageName capture=${if (audioRecord != null) audioSourceName else "deferred"} profile=${profile.name}")
        return true
    }

    /**
     * Initialize AudioRecord with retries, then run the capture loop.
     *
     * On cold boot, AudioFlinger refuses to create record tracks for
     * 10-30+ seconds after the voice call starts ("could not create record
     * track, status: -1").  The audio HAL needs time to fully initialize
     * the recording infrastructure after the modem voice path starts.
     *
     * Playback (SIP→GSM via incall_music) runs in a separate thread and
     * starts immediately.  This method retries capture init for up to 30s
     * so the caller hears the agent right away, even if the reverse
     * direction (GSM→SIP) takes longer to come up.
     *
     * NO fallback: if the configured capture source can't be initialized
     * or produces silence, the call is failed via [Listener.onRtpError] so
     * the orchestrator tears it down.
     */
    private fun captureInitAndLoop() {
        // Fast path: AudioRecord was already initialized in initAudio (warm boot)
        if (audioRecord != null) {
            if (captureLoop() || !running.get()) return
            failCapture("Capture source $audioSourceName produced silence — no fallback available, failing")
            return
        }

        // Cold boot: AudioRecord couldn't initialize in initAudio().  Retry the
        // SAME source with delays — cold-boot HAL init latency is real.  This is
        // retry, NOT a fallback: the configured source never changes.
        val configs = buildCaptureConfigs()

        val maxRetries = 15  // ~30s of cold-boot HAL init latency
        for (attempt in 1..maxRetries) {
            if (!running.get()) return

            reAssertAppOps()

            for (cfg in configs) {
                try {
                    val minBuf = AudioRecord.getMinBufferSize(
                        cfg.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minBuf <= 0) continue
                    val bufSize = minBuf.coerceAtLeast(cfg.rate / 50 * 2 * 2)
                    val rec = AudioRecord(
                        cfg.source, cfg.rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize
                    )
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        if (!running.get()) { rec.release(); return }
                        audioRecord = rec
                        audioSessionId = rec.audioSessionId
                        captureRate = cfg.rate
                        audioSourceName = cfg.name
                        currentSourceId = cfg.source
                        Log.i(TAG, "AudioRecord OK: ${cfg.name} @ ${cfg.rate}Hz (buf=$bufSize, attempt=$attempt)")
                        if (captureLoop() || !running.get()) return
                        failCapture("Capture source $audioSourceName produced silence — no fallback available, failing")
                        return
                    } else {
                        Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate}: state=${rec.state}")
                        rec.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord ${cfg.name}@${cfg.rate} failed: ${e.message}")
                }
            }

            if (attempt < maxRetries && running.get()) {
                Log.w(TAG, "Capture source unavailable (attempt $attempt/$maxRetries), retrying in 2s")
                try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
            }
        }

        failCapture("Capture source VOICE_CALL could not initialize after $maxRetries attempts (cold-boot HAL init failed) — failing")
    }

    /**
     * Report a fatal capture failure and signal the orchestrator to tear down
     * the call.  No fallback is attempted.
     */
    private fun failCapture(msg: String) {
        Log.e(TAG, msg)
        listener?.onRtpStats(msg)
        listener?.onRtpError(msg)
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        Log.i(TAG, "Stopping RTP session on port $localPort")

        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioRecord = null

        audioTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioTrack = null

        try { transport?.stop() } catch (_: Exception) {}

        captureTap?.close()
        captureTap = null

        socket?.close()
        socket = null
        jitterBuffer.clear()

        listener?.onRtpStopped()
    }

    /**
     * Sink for [MediaTransport] (WS mode): inbound agent audio is split into
     * 20 ms frames and queued into the same jitter buffer the UDP receive
     * loop would fill. PCM is s16le; μ-law is still accepted so a hub that
     * has not switched yet (and the simulator path) keeps working.
     */
    private val transportSink = object : MediaTransport.Sink {
        override fun onAudio(frame: ByteArray, format: String, rate: Int) {
            val fmt = format.ifBlank { "audio/pcmu" }
            val hz = if (rate > 0) rate else 8000
            if (fmt != inboundFormat || hz != inboundRate) {
                jitterBuffer.clear()
            }
            inboundFormat = fmt
            inboundRate = hz
            lastRtpReceivedTime = System.currentTimeMillis()
            val frameBytes = if (inboundFormat == "audio/pcm") {
                (inboundRate / 50 * 2).coerceAtLeast(2)
            } else {
                (inboundRate / 50).coerceAtLeast(1)
            }
            var off = 0
            while (off < frame.size) {
                val end = minOf(off + frameBytes, frame.size)
                val slice = frame.copyOfRange(off, end)
                off = end
                rxPacketCount++
                if (!jitterBuffer.offer(slice)) {
                    jitterBuffer.poll()
                    jitterBuffer.offer(slice)
                }
            }
        }

        override fun onFlushPlayback() {
            // Caller barged in — discard queued agent audio so we stop talking.
            jitterBuffer.clear()
        }

        override fun onStatus(msg: String) {
            Log.i(TAG, "transport: $msg")
            listener?.onRtpStats(msg)
        }

        override fun onError(msg: String) {
            Log.e(TAG, "transport error: $msg")
            listener?.onRtpStats("transport error: $msg")
            // Audio path is dead — tear the bridge down promptly (same path the
            // RTP inactivity timeout uses) rather than waiting out the timeout.
            listener?.onRtpTimeout()
        }
    }

    // ── Capture: VOICE_CALL → echo gate → gain → encode → RTP send ──

    // Audio parameters from device profile
    private val profile get() = GsmCallManager.profile
    private val captureGain get() = profile.audio.captureGain
    private val playbackGain get() = profile.audio.playbackGain

    // Double-talk detection: VOICE_CALL captures uplink+downlink from
    // the modem DSP.  The uplink contains the SIP agent's voice (injected
    // via incall_music).  Instead of a hard echo gate (which made the
    // bridge half-duplex — caller COMPLETELY silenced during agent speech),
    // we adaptively estimate the echo level and detect when the caller is
    // speaking simultaneously (double-talk / barge-in).
    //
    // How it works: incall_music injects AudioTrack digitally into the
    // modem uplink with a consistent gain ratio.  We track that ratio
    // (echoGainRatio = captureRMS / playbackRMS) during echo-only frames.
    // When captureRMS significantly exceeds the expected echo level,
    // the caller must be speaking — forward the audio (some echo leaks
    // but the caller is audible).  This enables full-duplex barge-in.
    private val echoGateThreshold get() = profile.audio.echoGateThreshold
    @Volatile private var currentPlaybackActive = false

    // Decaying echo reference: instead of a hard on/off echo gate, we
    // track a decaying playback RMS that persists through brief inter-word
    // gaps.  When the agent pauses between words (50-100ms), the echo
    // tail in the capture pipeline still has energy.  Without decay, the
    // gate flips off and the echo tail passes the noise gate, reaching
    // the agent as "caller speech" = false interruption.
    //
    // Decay factor 0.80 per 20ms frame:
    //   50ms (2-3 frames): ~64% of peak → echo tail suppressed
    //   100ms (5 frames):  ~33% → still suppressed
    //   200ms (10 frames): ~11% → fading, real speech passes easily
    //   300ms (15 frames): ~3%  → effectively zero
    // This allows barge-in after ~200ms while suppressing echo tails.
    private var decayingPlaybackRms = 0

    // Adaptive echo gain: ratio of capture RMS to playback RMS during
    // confirmed echo-only frames.  Updated via exponential moving average.
    // Initial 1.0 = assume 1:1 coupling; adapts to actual modem DSP gain.
    @Volatile private var echoGainRatio = 1.0f
    private var echoGainSamples = 0
    private val doubleTalkRatio get() = profile.audio.doubleTalkRatio

    // Noise gate: below this RMS, send silence instead of captured audio.
    // Threshold is device-specific (modem DSP noise floor varies).
    private val noiseGateThreshold get() = profile.audio.noiseGateThreshold

    // Diagnostic counters: track how frames are classified for stats logging
    @Volatile private var echoGatedFrames = 0L
    @Volatile private var noiseGatedFrames = 0L
    @Volatile private var forwardedFrames = 0L
    @Volatile private var doubleTalkFrames = 0L  // caller spoke during agent playback

    // CPU tracking: process CPU ticks at last sample for delta calculation
    private var lastCpuTicks = 0L
    private var lastCpuSampleMs = 0L

    /**
     * Capture loop: read from AudioRecord, gate, encode, send via RTP.
     * Returns true on normal exit (call ended), false if the source was
     * detected as silent (rawCapRMS near zero for 3 seconds).  A false
     * return is a hard failure — the caller fails the call, no fallback.
     */
    private fun captureLoop(): Boolean {
        val record = audioRecord ?: return false

        waitForCaptureWarmup()
        probeIncallCaptureHalOnce()
        notifyHubWire()

        record.startRecording()
        Log.i(
            TAG,
            "Capture started: source=$audioSourceName capRate=$captureRate session=$audioSessionId " +
                "gain=${captureGain}x profile=${profile.name} state=${record.recordingState}"
        )
        // Also report via RTP stats so it appears in the app log viewer
        listener?.onRtpStats(
            "Capture: source=$audioSourceName rate=$captureRate halRate=${record.sampleRate} " +
                "gain=${captureGain}x profile=${profile.name}"
        )
        captureTap = CaptureTap.open(
            context,
            audioSourceName,
            captureRate,
            record.sampleRate,
            captureGain,
            noiseGateThreshold,
            echoGateThreshold,
            profile.name,
            profile.routing.playbackToTelephony,
        )

        // Buffer: 20ms of PCM at the actual capture sample rate
        val samplesPerFrame = captureRate / 50  // 160 @ 8kHz, 320 @ 16kHz
        val pcmBuf = ByteArray(samplesPerFrame * 2)
        // No UDP destination in WS mode ("openai-ws" is not resolvable); the
        // transport owns the wire, so only resolve the RTP peer for the UDP path.
        val defaultRemoteInet = if (wsMode) null else InetAddress.getByName(remoteAddr)
        var silenceFrameCount = 0

        while (running.get()) {
            try {
                val read = record.read(pcmBuf, 0, pcmBuf.size)
                if (read <= 0) continue

                // Measure raw capture level BEFORE echo gate for diagnostics.
                // If rawCaptureRms=0, the audio source itself is silent
                // (HAL/modem not providing audio).  If rawCaptureRms>0 but
                // captureRms=0, the echo gate is suppressing.
                rawCaptureRms = pcmRms(pcmBuf)

                // Silence detection: only count during non-echo periods (when
                // decayingPlaybackRms <= echoGateThreshold).  incall_music echo
                // leaks back through VOICE_CALL capture, spiking rawCapRMS
                // above the threshold during agent speech — this would reset a
                // naive counter even though the source delivers NO caller audio.
                // By only counting non-echo frames, we accurately detect dead
                // sources regardless of whether the agent is speaking.
                //
                // NO fallback: when the limit is hit, stop and return false so
                // captureInitAndLoop() fails the call.  The caller never tries
                // an alternative source.
                // In WS mode caller silence is NORMAL — the caller is listening
                // to the agent, so an all-quiet downlink must NOT fail the call.
                // The dead-source guard only applies to the SIP/RTP path.
                val noEchoPeriod = !wsMode && decayingPlaybackRms <= echoGateThreshold
                if (noEchoPeriod) {
                    if (rawCaptureRms < silenceRmsThreshold) {
                        silenceFrameCount++
                        if (silenceFrameCount == 10 || silenceFrameCount == 20) {
                            Log.w(TAG, "Source $audioSourceName low audio (no-echo): rawCapRMS=$rawCaptureRms silence=${silenceFrameCount}/${silenceFrameLimit} frames")
                        }
                        if (silenceFrameCount >= silenceFrameLimit) {
                            Log.e(TAG, "Source $audioSourceName SILENT ($silenceFrameCount non-echo frames) — stopping capture")
                            try { record.stop() } catch (_: Exception) {}
                            record.release()
                            audioRecord = null
                            captureTap?.close()
                            captureTap = null
                            return false  // Signal caller to fail the call (no fallback)
                        }
                    } else {
                        silenceFrameCount = 0  // Source delivered audio during non-echo → working
                    }
                }
                // During echo periods: don't update counter (can't distinguish
                // caller audio from incall_music echo)

                // Double-talk-aware gating: replaces the old hard echo gate
                // that made the bridge half-duplex (caller completely silenced
                // during agent speech).  Now uses adaptive echo level estimation
                // to detect when the caller is speaking over the agent.
                //
                // VOICE_CALL captures uplink+downlink.  incall_music injects
                // the agent's voice digitally into the uplink with a consistent
                // gain ratio.  We track that ratio and detect when capture energy
                // exceeds the expected echo — that excess is the caller's voice.
                // Update decaying echo reference: tracks playback level
                // through brief inter-word gaps to suppress echo tails.
                if (currentPlaybackActive && playbackRms > 0) {
                    decayingPlaybackRms = playbackRms
                } else if (decayingPlaybackRms > 0) {
                    decayingPlaybackRms = (decayingPlaybackRms * 0.80).toInt()
                }

                val shouldForward: Boolean
                var noiseThis = false
                var echoThis = false
                // When playback routes to TYPE_TELEPHONY (modem TX), audio goes
                // directly to the GSM uplink — no acoustic speaker→mic echo.
                // Skip echo gate entirely and only apply noise gate.
                if (profile.routing.playbackToTelephony) {
                    if (rawCaptureRms < noiseGateThreshold) {
                        shouldForward = false
                        noiseGatedFrames++
                        noiseThis = true
                    } else {
                        shouldForward = true
                        forwardedFrames++
                    }
                } else if (decayingPlaybackRms > echoGateThreshold) {
                    // Agent is speaking (or echo tail still decaying) —
                    // check for double-talk (barge-in).
                    val expectedEcho = (echoGainRatio * decayingPlaybackRms).toInt()
                        .coerceAtLeast(noiseGateThreshold)
                    if (rawCaptureRms > (expectedEcho * doubleTalkRatio).toInt()) {
                        // Double-talk: caller speaking over agent — forward.
                        // Some echo leaks through but caller is audible.
                        shouldForward = true
                        doubleTalkFrames++
                    } else {
                        // Echo-only: agent speaking, caller silent — send silence.
                        // Update echo gain estimate from confirmed echo frames.
                        if (currentPlaybackActive && playbackRms > 500) {
                            val r = rawCaptureRms.toFloat() / playbackRms
                            echoGainRatio = echoGainRatio * 0.95f + r * 0.05f
                            echoGainSamples++
                        }
                        shouldForward = false
                        echoGatedFrames++
                        echoThis = true
                    }
                } else if (rawCaptureRms < noiseGateThreshold) {
                    // Noise gate: only modem digital noise, no caller speech.
                    shouldForward = false
                    noiseGatedFrames++
                    noiseThis = true
                } else {
                    // Caller speaking, agent silent — forward normally.
                    shouldForward = true
                }

                captureTap?.writePre(pcmBuf, read)
                if (shouldForward) {
                    // Apply gain boost
                    if (captureGain > 1) {
                        for (i in 0 until read / 2) {
                            val lo = pcmBuf[i * 2].toInt() and 0xFF
                            val hi = pcmBuf[i * 2 + 1].toInt()
                            val sample = ((hi shl 8) or lo) * captureGain
                            val clamped = sample.coerceIn(-32768, 32767)
                            pcmBuf[i * 2] = (clamped and 0xFF).toByte()
                            pcmBuf[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
                        }
                    }
                    captureRms = pcmRms(pcmBuf)
                    forwardedFrames++
                } else {
                    java.util.Arrays.fill(pcmBuf, 0, read, 0.toByte())
                    captureRms = 0
                }
                captureTap?.writePost(pcmBuf, read)
                captureTap?.noteFrame(
                    rawCaptureRms,
                    captureRms,
                    shouldForward,
                    noiseThis,
                    echoThis,
                    decayingPlaybackRms,
                )

                if (wsMode) {
                    if (txPacketCount < 3) {
                        Log.i(TAG, "TX#$txPacketCount pcm ${read}B @${captureRate}Hz rawRMS=$rawCaptureRms capRMS=$captureRms")
                        if (txPacketCount == 0L) firstTxInfo = "pcm ${captureRate}Hz capRMS=$captureRms"
                    }
                    transport?.sendAudio(pcmBuf.copyOf(read))
                    txPacketCount++
                } else {
                    // Encode based on codec and capture sample rate.
                    // G.722 expects 16 kHz PCM; if capture is 8 kHz, upsample first.
                    val encoded = when (payloadType) {
                        RtpPacket.PT_G722 -> {
                            val pcm16k = if (captureRate == 8000) upsample8kTo16k(pcmBuf) else pcmBuf
                            g722Encoder.encode(pcm16k)
                        }
                        RtpPacket.PT_PCMA -> {
                            if (captureRate == 8000) PcmaCodec.encode8k(pcmBuf)
                            else PcmaCodec.encode(pcmBuf)
                        }
                        RtpPacket.PT_PCMU -> {
                            if (captureRate == 8000) PcmuCodec.encode8k(pcmBuf)
                            else PcmuCodec.encode(pcmBuf)
                        }
                        else -> {
                            val pcm16k = if (captureRate == 8000) upsample8kTo16k(pcmBuf) else pcmBuf
                            g722Encoder.encode(pcm16k)
                        }
                    }

                    // Log first 3 packets with raw PCM + encoded for debugging
                    if (txPacketCount < 3) {
                        val hexHead = encoded.take(16).joinToString(" ") { "%02X".format(it) }
                        val pcmHex = pcmBuf.take(32).joinToString(" ") { "%02X".format(it) }
                        Log.i(TAG, "TX#$txPacketCount: rawRMS=$rawCaptureRms capRMS=$captureRms pcm=[$pcmHex] enc=[$hexHead]")
                        if (txPacketCount == 0L) firstTxInfo = "capRMS=$captureRms enc=$hexHead"
                    }

                    val destAddr = latchedAddr ?: defaultRemoteInet!!
                    val destPort = if (latchedAddr != null) latchedPort else remotePort

                    val packet = RtpPacket(payloadType, txSequence, txTimestamp, txSsrc, encoded)
                    val data = packet.encode()
                    socket?.send(DatagramPacket(data, data.size, destAddr, destPort))

                    txSequence = (txSequence + 1) and 0xFFFF
                    txPacketCount++
                    txTimestamp += 160 // 20ms at 8000Hz RTP clock
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Capture error: ${e.message}")
            }
        }
        captureTap?.close()
        captureTap = null
        return true  // Normal exit (call ended)
    }

    // ── Receive: RTP recv → jitter buffer ───────────────

    private fun receiveLoop() {
        val buf = ByteArray(4096)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket?.receive(packet) ?: break
                val rtp = RtpPacket.decode(buf, packet.length) ?: continue

                // Symmetric RTP: latch onto the actual source address
                if (latchedAddr == null) {
                    latchedAddr = packet.address
                    latchedPort = packet.port
                    Log.i(TAG, "Symmetric RTP: latched to ${packet.address.hostAddress}:${packet.port}")
                }

                lastRtpReceivedTime = System.currentTimeMillis()
                rxPacketCount++
                // Log first packet details for debugging
                if (rxPacketCount == 1L) {
                    val hexHead = rtp.payload.take(16).joinToString(" ") { "%02X".format(it) }
                    firstRxInfo = "pt=${rtp.payloadType} len=${rtp.payload.size} hex=$hexHead"
                    Log.i(TAG, "First RX: $firstRxInfo")
                }
                if (rtp.payloadType == payloadType || rtp.payloadType == RtpPacket.PT_PCMA || rtp.payloadType == RtpPacket.PT_G722) {
                    if (!jitterBuffer.offer(rtp.payload)) {
                        jitterBuffer.poll() // drop oldest
                        jitterBuffer.offer(rtp.payload)
                    }
                }
            } catch (_: SocketTimeoutException) {
                // normal
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Receive error: ${e.message}")
            }
        }
    }

    // ── RTP inactivity timeout ─────────────────────────

    private fun timeoutLoop() {
        // Early re-assertion at 3s: combat Android re-revoking RECORD_AUDIO
        // when screen is off, and re-toggle incall_music after speaker route
        // is fully settled (~1.5s after configureAudioBridge).
        try { Thread.sleep(3_000) } catch (_: InterruptedException) { return }
        if (running.get()) {
            reAssertAppOps()
            reToggleIncallMusic()
            reAssertCaptureRoute()
        }

        while (running.get()) {
            try {
                // 15s interval (was 5s) — each appops su call spawns a JVM
                // (~500ms on MSM8930).  15s is sufficient to catch screen-off
                // revocations while reducing CPU load by 3x.
                Thread.sleep(15_000)

                // Periodic appops re-assertion: Android's AppOpsService
                // re-revokes RECORD_AUDIO for background apps when screen
                // is off.  Re-asserting periodically keeps capture alive.
                reAssertAppOps()

                val micMuteCmd = profile.mixer.micMuteCmd
                if (micMuteCmd.isNotEmpty()) {
                    try {
                        val resolved = DeviceProfile.resolveCmd(micMuteCmd)
                        if (resolved.isNotEmpty()) RootShell.execForOutput(resolved, timeoutMs = 3000)
                    } catch (_: Exception) {}
                }
                reAssertCaptureRoute()

                // Log detailed stats every 5s
                val extraInfo = buildString {
                    if (firstRxInfo.isNotEmpty() && txPacketCount < 500) append(" [RX: $firstRxInfo]")
                    if (firstTxInfo.isNotEmpty() && txPacketCount < 500) append(" [TX: $firstTxInfo]")
                }
                // Include current volume state for debugging
                val volInfo = try {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.let {
                        val vc = it.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                        val vm = it.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val muted = it.isStreamMute(AudioManager.STREAM_VOICE_CALL)
                        val micMute = it.isMicrophoneMute
                        " vol:vc=$vc(m=$muted),mu=$vm mic=$micMute"
                    } ?: ""
                } catch (_: Exception) { "" }
                // Enhanced "TRX/REC" heartbeat logging for user visibility
                val flowStats = "AUDIO-FLOW: [GSM -> SIP: REC=${rawCaptureRms} TRX=${if(txPacketCount > lastFlowTxCount) "OK" else "IDLE"}] " +
                                "[SIP -> GSM: REC=${if(rxPacketCount > lastFlowRxCount) "OK" else "IDLE"} TRX=${playbackRms}]"
                lastFlowTxCount = txPacketCount
                lastFlowRxCount = rxPacketCount
                Log.i(TAG, flowStats)
                listener?.onRtpStats(flowStats)

                // CPU/memory/thread diagnostics
                val cpuInfo = getCpuStats()
                val stats = "RTP-STATS: tx=$txPacketCount rx=$rxPacketCount play=$playbackFrames " +
                        "capRMS=$captureRms rawCapRMS=$rawCaptureRms playRMS=$playbackRms src=$audioSourceName " +
                        "rate=${captureRate}/${playbackRate} jbuf=${jitterBuffer.size} " +
                        "gates:echo=$echoGatedFrames noise=$noiseGatedFrames fwd=$forwardedFrames dt=$doubleTalkFrames echoG=${"%.2f".format(echoGainRatio)}" +
                        "$cpuInfo$volInfo"
                Log.i(TAG, stats)


                // The inactivity timeout is UDP-only. In WS mode there is no
                // steady packet stream — the agent is silent during natural
                // conversational pauses — so this would false-fire. WS liveness
                // is covered by the socket's own onFailure/onClosed → onError,
                // and by GSM call-state teardown.
                if (!wsMode) {
                    val elapsed = System.currentTimeMillis() - lastRtpReceivedTime
                    if (elapsed > rtpTimeoutMs) {
                        Log.w(TAG, "RTP timeout: no packets received for ${elapsed / 1000}s")
                        listener?.onRtpTimeout()
                        break
                    }
                }
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    // ── Playback: jitter buffer → decode → speaker → mic → GSM uplink ──

    private fun playbackLoop() {
        val track = audioTrack ?: return

        // No prefill — the silence-frame loop below feeds the AudioTrack
        // continuously, preventing underruns.  Removing the old 20ms prefill
        // saves that much initial latency.
        track.play()
        Log.i(TAG, "Playback started (rate=$playbackRate usage=$playbackUsageName deepBuffer=true)")

        // CRITICAL: Set incall_music_enabled=true AFTER AudioTrack.play().
        // The Qualcomm HAL starts the incall-music usecase only when there
        // is an active STREAM_MUSIC output.  If set before AudioTrack exists,
        // the HAL routes through deep-buffer-playback instead of incall-music,
        // and the audio never reaches the voice TX (uplink).
        enableIncallMusic()
        // Set mixer controls via root — also handles Voice Tx Mute=0.
        // No separate ensureVoiceTxOpen() call here: the mixer thread below
        // already issues that command, and waiting for su -c was blocking
        // the playback thread for ~100ms.
        enableIncallMusicViaMixer()

        // Silence frame for when jitter buffer is empty — prevents underruns
        // that cause BUFFER TIMEOUT and AudioTrack disable/restart cycles.
        val silenceFrame = ByteArray(playbackRate * 2 / 50) // 20ms of silence

        // Track silence→speech transitions for fade-in.
        // Modem DSP AGC/DTX settles during silence; abrupt speech onset
        // gets over-amplified causing distortion at the start of each phrase.
        // A short fade-in smooths the transition.
        var wasPlayingSilence = true

        while (running.get()) {
            try {
                // Drain excess packets to bound latency.  When network
                // jitter causes burst arrivals, the buffer can accumulate.
                // Keep at most 5 (100ms) — enough headroom to absorb
                // jitter without audible gaps.  100ms is still well within
                // the GSM bridge's inherent latency budget.
                //
                // Disabled in WS mode: OpenAI delivers a whole response as a
                // fast burst that legitimately fills the (large) buffer, and it
                // is paced back out one 20ms frame per poll. Draining here would
                // throw away most of the agent's reply. Barge-in is handled by
                // onFlushPlayback() clearing the buffer instead.
                while (!wsMode && jitterBuffer.size > 5) {
                    jitterBuffer.poll()
                }

                val encoded = jitterBuffer.poll(18, TimeUnit.MILLISECONDS)
                if (encoded == null) {
                    // Write silence to keep AudioTrack fed and prevent underruns.
                    track.write(silenceFrame, 0, silenceFrame.size)
                    playbackRms = 0
                    currentPlaybackActive = false
                    wasPlayingSilence = true
                    continue
                }

                val pcm = if (wsMode) {
                    decodeHubFrame(encoded)
                } else when (payloadType) {
                    RtpPacket.PT_G722 -> g722Decoder.decodeToBytes(encoded)
                    RtpPacket.PT_PCMA -> {
                        if (playbackRate == 8000) PcmaCodec.decode8k(encoded)
                        else PcmaCodec.decode(encoded)
                    }
                    RtpPacket.PT_PCMU -> {
                        if (playbackRate == 8000) PcmuCodec.decode8k(encoded)
                        else PcmuCodec.decode(encoded)
                    }
                    else -> g722Decoder.decodeToBytes(encoded)
                }

                // Fade-in after silence: prevents modem DSP AGC spike that
                // causes distorted/harsh beginning of each agent phrase.
                // 5ms ramp (40 samples @ 8kHz) is enough to smooth the onset.
                if (wasPlayingSilence) {
                    applyFadeIn(pcm)
                    wasPlayingSilence = false
                }

                // Measure RMS BEFORE gain for the echo gate.  The echo gate
                // threshold was calibrated to raw codec output levels.  If
                // playbackGain > 1, measuring after gain would lower the
                // effective threshold, over-suppressing caller speech.
                val rawRms = pcmRms(pcm)
                currentPlaybackActive = rawRms > echoGateThreshold

                // Software gain: boost PCM before writing to AudioTrack.
                // This increases the digital level injected via incall_music
                // without changing STREAM_MUSIC volume (which clips at >14%).
                if (playbackGain > 1) {
                    for (i in 0 until pcm.size / 2) {
                        val lo = pcm[i * 2].toInt() and 0xFF
                        val hi = pcm[i * 2 + 1].toInt()
                        val sample = ((hi shl 8) or lo) * playbackGain
                        val clamped = sample.coerceIn(-32768, 32767)
                        pcm[i * 2] = (clamped and 0xFF).toByte()
                        pcm[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
                    }
                }

                playbackRms = if (playbackGain > 1) pcmRms(pcm) else rawRms
                track.write(pcm, 0, pcm.size)
                playbackFrames++
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Playback error: ${e.message}")
            }
        }
    }

    /**
     * Apply a 5ms linear fade-in to the start of a PCM buffer.
     * Smooths the silence→speech transition that otherwise causes
     * the modem's uplink AGC/DTX to over-amplify the first samples.
     */
    private fun applyFadeIn(pcm: ByteArray, fadeMs: Int = 5) {
        val fadeSamples = playbackRate * fadeMs / 1000
        val totalSamples = pcm.size / 2
        val count = fadeSamples.coerceAtMost(totalSamples)
        for (i in 0 until count) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            val faded = (sample.toLong() * (i + 1) / count).toInt().coerceIn(-32768, 32767)
            pcm[i * 2] = (faded and 0xFF).toByte()
            pcm[i * 2 + 1] = ((faded shr 8) and 0xFF).toByte()
        }
    }

    /**
     * Upsample 8 kHz PCM16 to 16 kHz using 4-tap Catmull-Rom interpolation.
     * Each input sample produces two output samples: the original sample
     * and a midpoint computed as (-s[i-1] + 9*s[i] + 9*s[i+1] - s[i+2]) / 16.
     *
     * This provides ~35 dB spectral image rejection vs ~6 dB for simple
     * linear interpolation, suppressing artifacts in the 4-8 kHz band that
     * G.722's upper sub-band would otherwise encode as spurious content.
     */
    private fun upsample8kTo16k(input: ByteArray): ByteArray {
        val n = input.size / 2
        val output = ByteArray(n * 4) // 2x samples, 2 bytes each

        // Read all input samples into an array for random access
        val s = IntArray(n)
        for (i in 0 until n) {
            val lo = input[i * 2].toInt() and 0xFF
            val hi = input[i * 2 + 1].toInt()
            s[i] = (hi shl 8) or lo
        }

        for (i in 0 until n) {
            // Even output: original sample (pass-through)
            val even = s[i]

            // Odd output: 4-tap Catmull-Rom midpoint interpolation
            val sm1 = if (i > 0) s[i - 1] else s[0]
            val s0 = s[i]
            val s1 = if (i + 1 < n) s[i + 1] else s[n - 1]
            val s2 = if (i + 2 < n) s[i + 2] else s[n - 1]
            val mid = ((-sm1 + 9 * s0 + 9 * s1 - s2 + 8) shr 4).coerceIn(-32768, 32767)

            output[i * 4] = (even and 0xFF).toByte()
            output[i * 4 + 1] = ((even shr 8) and 0xFF).toByte()
            output[i * 4 + 2] = (mid and 0xFF).toByte()
            output[i * 4 + 3] = ((mid shr 8) and 0xFF).toByte()
        }
        return output
    }

    /** Linear-interpolate PCM16le [src] from [srcRate] onto [dstRate]. */
    private fun resamplePcm16(src: ByteArray, srcRate: Int, dstRate: Int): ByteArray {
        if (srcRate <= 0 || dstRate <= 0 || src.size < 2) return src
        if (srcRate == dstRate) return src
        val srcSamples = src.size / 2
        val dstSamples = (srcSamples.toLong() * dstRate / srcRate).toInt().coerceAtLeast(1)
        val dst = ByteArray(dstSamples * 2)
        for (i in 0 until dstSamples) {
            val srcIndex = i.toDouble() * (srcSamples - 1).coerceAtLeast(0) / (dstSamples - 1).coerceAtLeast(1)
            val i0 = srcIndex.toInt().coerceIn(0, srcSamples - 1)
            val i1 = (i0 + 1).coerceAtMost(srcSamples - 1)
            val frac = srcIndex - i0
            val s0 = sampleAt(src, i0)
            val s1 = sampleAt(src, i1)
            val s = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767)
            dst[i * 2] = (s and 0xFF).toByte()
            dst[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return dst
    }

    private fun sampleAt(pcm: ByteArray, index: Int): Int {
        val lo = pcm[index * 2].toInt() and 0xFF
        val hi = pcm[index * 2 + 1].toInt()
        return (hi shl 8) or lo
    }

    private fun decodeHubFrame(encoded: ByteArray): ByteArray {
        val pcm = if (inboundFormat == "audio/pcm") {
            encoded
        } else {
            PcmuCodec.decode8k(encoded)
        }
        val srcRate = if (inboundFormat == "audio/pcm") inboundRate else 8000
        return resamplePcm16(pcm, srcRate, playbackRate)
    }

    /** Compute RMS level of PCM16 little-endian audio buffer (0-32767) */
    private fun pcmRms(pcm: ByteArray): Int {
        val sampleCount = pcm.size / 2
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

    /**
     * Set incall_music_enabled=true via AudioManager.
     * Must be called AFTER AudioTrack.play() so the HAL has an active
     * STREAM_MUSIC output to route through the incall-music usecase.
     *
     * Also re-enforces stream volumes here as a secondary safeguard.
     * GsmCallManager sets volumes in configureAudioBridge(), but Android's
     * AudioPolicyManager can reset them when the speaker route change
     * completes.  By the time we get here (after AudioTrack.play()),
     * the route change is long finished, so our volume sticks.
     *
     * On Samsung Exynos (ABOX HAL), the audio_hw_proxy should handle
     * incall_music_enabled internally by routing SIFS→NSRC→voice TX.
     * We also try Samsung-specific parameter names as fallbacks in case
     * the standard parameter is not implemented in this LineageOS build.
     */
    private fun enableIncallMusic() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.let {
                // Force a false→true transition to start the incall-music
                // usecase in the HAL.  configureAudioBridge() no longer
                // primes true (it caused problems: the earpiece→speaker
                // route change would tear down the voice path and lose the
                // incall-music state, then the second true here was a no-op).
                //
                // By this point:
                //  1. AudioTrack.play() has started (active STREAM_MUSIC output)
                //  2. Speaker route is settled (configureAudioBridge ran 1+ sec ago)
                //  3. restoreAudio from previous call set false (clean slate)
                //
                // The explicit false first ensures the HAL processes it as a
                // genuine state transition, even if some stale true leaked.
                // 50ms gap lets the HAL fully tear down before re-creating.
                val param = profile.routing.incallMusicParam
                if (param.isNotEmpty()) {
                    it.setParameters("${param}=false")
                    Thread.sleep(50)
                    it.setParameters("${param}=true")
                }

                // Samsung Exynos: additional HAL params for incall music injection.
                // Only when incallMusicParam is configured (empty = skip to avoid
                // breaking VOICE_CALL capture).
                // Samsung Exynos: additional HAL params for incall music.
                // These are "best effort" — on Exynos 9820, no visible mixer
                // effect is observed, but they may help on other Exynos devices.
                if (profile.name.contains("Exynos") && param.isNotEmpty()) {
                    it.setParameters("g_call_path=on")
                    it.setParameters("abox_incall_music=on")
                    it.setParameters("incall_music=1")
                }

                GsmCallManager.enforceVolumes(it)

                val msg = "incall_music: param=${param.ifEmpty { "NONE" }}, mode=${it.mode}" +
                    if (param.isNotEmpty() && profile.name.contains("Exynos")) " +g_call_path +abox_incall_music +incall_music=1" else ""
                Log.i(TAG, msg)
                listener?.onRtpStats(msg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set incall_music_enabled: ${e.message}")
            listener?.onRtpStats("incall_music FAILED: ${e.message}")
        }
    }

    /**
     * Fallback: use root (Magisk) to set incall_music mixer controls
     * directly via tinymix.  Device-specific commands from the profile.
     *
     * The bare 'tinymix' in profile commands is resolved to the
     * discovered full path via [DeviceProfile.resolveCmd].
     */
    private fun enableIncallMusicViaMixer() {
        val mixerCmd = profile.mixer.mixerIncallMusicCmd
        if (mixerCmd.isEmpty()) {
            Log.i(TAG, "Mixer: no incall_music commands for ${profile.name}")
            return
        }
        val resolvedMixerCmd = DeviceProfile.resolveCmd(mixerCmd)
        if (resolvedMixerCmd.isEmpty()) {
            val msg = "Mixer: tinymix not found — cannot set incall_music mixer"
            Log.e(TAG, msg)
            listener?.onRtpStats(msg)
            return
        }
        Thread({
            try {
                // Run incall_music mixer commands, then readback key controls (card 0)
                val bin = DeviceProfile.tinymixBin
                val cmd = if (profile.routing.isAbox) {
                    "$resolvedMixerCmd; " +
                    "echo 'NSRC1B:'; $bin 'ABOX NSRC1 Bridge' 2>&1; " +
                    "echo 'NSRC1:'; $bin 'ABOX NSRC1' 2>&1; " +
                    "echo 'NSRC0:'; $bin 'ABOX NSRC0' 2>&1; " +
                    "echo 'SPUS0:'; $bin 'ABOX SPUS OUT0' 2>&1"
                } else {
                    resolvedMixerCmd
                }
                val output = RootShell.execForOutput(cmd, timeoutMs = 8000)
                val msg = "Mixer incall_music: $output"
                Log.i(TAG, msg)
                listener?.onRtpStats(msg)
            } catch (e: Exception) {
                Log.w(TAG, "Mixer fallback failed: ${e.message}")
                listener?.onRtpStats("Mixer incall_music FAILED: ${e.message}")
            }
        }, "RTP-Mixer").start()
    }

    /**
     * Re-assert RECORD_AUDIO appops via root.  Android's AppOpsService
     * re-revokes this permission for background apps when the screen turns
     * off, killing VOICE_CALL capture (rawCapRMS drops to ~6).  Called
     * at 3s and then every 5s from timeoutLoop to keep capture alive.
     *
     * CRITICAL: Must use --uid flag to set the UID-level mode.
     * `appops set <pkg>` sets the package mode, but AudioFlinger checks
     * the UID mode (set by PermissionController).  UID mode overrides
     * package mode.  Without --uid, the command "succeeds" (exit=0) but
     * AudioFlinger still denies with "Request denied by app op: 27".
     */
    private fun reAssertAppOps() {
        try {
            val pkg = context.packageName
            val t0 = System.currentTimeMillis()
            // Use execForOutput to capture stderr/stdout from appops commands.
            // Previous approach hid all errors and put killall last (exit=1 always).
            // Now: appops get --uid is the LAST command so exit code is meaningful,
            // and all errors are captured via 2>&1.
            // AUTO_REVOKE_PERMISSIONS_IF_UNUSED: Android 11+ (API 30)
            // appops --uid flag: Android 10+ (API 29)
            val autoRevoke = if (Build.VERSION.SDK_INT >= 30)
                "appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>&1; " else ""
            val uidFlag = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
            val result = RootShell.execForOutput(
                "killall com.google.android.permissioncontroller 2>/dev/null; " +
                "killall com.android.permissioncontroller 2>/dev/null; " +
                "pm grant $pkg android.permission.RECORD_AUDIO 2>&1; " +
                autoRevoke +
                "appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                "appops set $pkg RECORD_AUDIO allow 2>&1; " +
                "killall com.google.android.permissioncontroller 2>/dev/null; " +
                "killall com.android.permissioncontroller 2>/dev/null; " +
                "appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
            )
            val elapsed = System.currentTimeMillis() - t0
            val allowed = result.contains("allow", ignoreCase = true)
            Log.i(TAG, "appops re-assert: [$result] ok=$allowed (${elapsed}ms)")

            if (!allowed) {
                // Fallback: try cmd appops (different IPC path to AppOpsService)
                val fb = RootShell.execForOutput(
                    "cmd appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops set $pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
                )
                Log.w(TAG, "appops fallback cmd: [$fb]")
            } else {
                Log.d(TAG, "appops RECORD_AUDIO verified: allow")
            }
        } catch (e: Exception) {
            Log.w(TAG, "appops re-assert failed: ${e.message}")
        }
    }

    private fun hasIncallCaptureRoute(): Boolean =
        profile.mixer.mixerSetupCmd.contains("Incall Capture Stream0'")

    /** Wait for async mixer setup before opening VOICE_CALL capture. */
    private fun waitForCaptureWarmup() {
        if (!hasIncallCaptureRoute()) return
        val warmupAfterMixerMs = 750L
        val mixerDone = GsmCallManager.mixerSetupCompletedAtMs
        val targetStart = if (mixerDone > 0) {
            mixerDone + warmupAfterMixerMs
        } else {
            System.currentTimeMillis() + profile.routing.routeChangeDelayMs + warmupAfterMixerMs
        }
        val waitMs = targetStart - System.currentTimeMillis()
        if (waitMs > 0) {
            Log.i(TAG, "Capture warm-up: waiting ${waitMs}ms for incall capture route")
            listener?.onRtpStats("Capture warm-up: ${waitMs}ms for capture route")
            try { Thread.sleep(waitMs.coerceAtMost(5000)) } catch (_: InterruptedException) { return }
        }
    }

    /** One-shot tinycap probe on audio_incall_cap PCM (Pixel 7 HAL diagnostics). */
    private fun probeIncallCaptureHalOnce() {
        if (!hasIncallCaptureRoute()) return
        try {
            val cmd = buildString {
                append("if [ ! -x /data/local/tmp/tinycap ]; then echo 'tinycap not found'; exit 0; fi; ")
                append("echo '=== incall capture HAL probe ==='; ")
                append("for d in /proc/asound/card*/pcm*c; do ")
                append("  [ -d \"\$d/sub0\" ] || continue; ")
                append("  name=\$(grep '^name:' \"\$d/info\" 2>/dev/null | head -1); ")
                append("  echo \"\$name\" | grep -qi incall_cap || continue; ")
                append("  card=\${d#/proc/asound/}; card=\${card%%/*}; cardnum=\${card#card}; ")
                append("  pcm=\${d##*/}; devnum=\${pcm#pcm}; devnum=\${devnum%c}; ")
                append("  s=\$(head -1 \$d/sub0/status 2>/dev/null); ")
                append("  echo \"probe \$card/\$pcm: \$name status=\$s\"; ")
                append("  ch=\$(grep '^channels:' \$d/sub0/hw_params 2>/dev/null | awk '{print \$2}'); ")
                append("  rate=\$(grep '^rate:' \$d/sub0/hw_params 2>/dev/null | awk '{print \$2}'); ")
                append("  timeout 2 /data/local/tmp/tinycap /data/local/tmp/incall_probe.raw ")
                append("-D \$cardnum -d \$devnum -c \${ch:-2} -r \${rate:-8000} -b 16 -p 160 -n 2 2>&1 | head -2; ")
                append("  f=/data/local/tmp/incall_probe.raw; ")
                append("  if [ -f \"\$f\" ]; then ")
                append("    nz=\$(od -An -tx1 \"\$f\" | tr ' ' '\\n' | grep -cv '^00\$\\|^\$'); ")
                append("    echo \"  non-zero bytes: \$nz\"; rm -f \"\$f\"; ")
                append("  fi; ")
                append("done")
            }
            val result = RootShell.execForOutput(cmd, timeoutMs = 8000)
            for (line in result.lines().filter { it.isNotBlank() }) {
                Log.i(TAG, "Diag: $line")
            }
            val summary = result.lines().firstOrNull { it.contains("non-zero") }
                ?: result.lines().lastOrNull { it.isNotBlank() }
            if (summary != null) listener?.onRtpStats("HAL probe: $summary")
        } catch (e: Exception) {
            Log.w(TAG, "Incall capture HAL probe failed: ${e.message}")
        }
    }

    /** Re-assert modem routing into incall capture (HAL may reset mid-call). */
    private fun reAssertCaptureRoute() {
        if (!hasIncallCaptureRoute()) return
        val resolved = DeviceProfile.resolveCmd(
            "tinymix 'Incall Capture Stream0' UL_DL 2>/dev/null"
        )
        if (resolved.isEmpty()) return
        try {
            val out = RootShell.execForOutput(resolved, timeoutMs = 3000)
            if (out.isNotBlank()) Log.d(TAG, "Capture route re-assert: $out")
        } catch (_: Exception) {}
    }

    /**
     * Re-toggle incall_music_enabled false→true as a safety net.
     * Called ~3s after RTP start when the speaker route change is
     * guaranteed to be complete.  Handles edge cases where the initial
     * toggle in enableIncallMusic() fired before the route settled.
     */
    private fun reToggleIncallMusic() {
        val param = profile.routing.incallMusicParam
        if (param.isEmpty()) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.let {
                it.setParameters("${param}=false")
                Thread.sleep(50)
                it.setParameters("${param}=true")
                GsmCallManager.enforceVolumes(it)
                Log.i(TAG, "${param} re-toggled (3s safety), mode=${it.mode}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "${param} re-toggle failed: ${e.message}")
        }
    }

    /**
     * Log diagnostic info about capture capability:
     * Phase 1: NSRC routing + bridge state
     * Phase 2: mixer_paths.xml incall_music voice path definitions
     * Phase 3: /proc/asound capture/playback PCM status
     * Phase 6: Delayed NSRC re-check (t+5s)
     * Phase 7: ALSA capture PCM probe (tinycap, if available)
     */
    private fun logCaptureDiagnostics(record: AudioRecord) {
        try {
            // Check CAPTURE_AUDIO_OUTPUT (system permission, not runtime)
            val hasCaptureOutput = context.checkCallingOrSelfPermission(
                "android.permission.CAPTURE_AUDIO_OUTPUT"
            ) == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Diag: CAPTURE_AUDIO_OUTPUT=$hasCaptureOutput " +
                "session=${record.audioSessionId} state=${record.state} " +
                "recState=${record.recordingState} source=$audioSourceName")
            listener?.onRtpStats("Diag: CAPTURE_AUDIO_OUTPUT=$hasCaptureOutput")

            // Comprehensive ABOX routing dump via root
            val tinymix = DeviceProfile.tinymixBin
            if (tinymix.isNotEmpty()) {
                Thread({
                    try {
                        // Phase 1: NSRC routing + bridge state (ABOX only)
                        if (profile.routing.isAbox) {
                            val routingCmd = buildString {
                                append("echo '=== NSRC routing ==='; ")
                                for (i in 0..2) {
                                    append("echo -n 'NSRC${i}='; $tinymix 'ABOX NSRC${i}' 2>/dev/null || echo 'N/A'; ")
                                    append("echo -n 'NSRC${i}_Bridge='; $tinymix 'ABOX NSRC${i} Bridge' 2>/dev/null || echo 'N/A'; ")
                                }
                                append("echo -n 'SoundType='; $tinymix 'ABOX Sound Type' 2>/dev/null || echo 'N/A'")
                            }
                            val routing = RootShell.execForOutput(routingCmd, timeoutMs = 8000)
                            for (line in routing.lines().filter { it.isNotBlank() }) {
                                Log.i(TAG, "Diag: $line")
                            }
                        }

                        // Phase 2: mixer_paths.xml — voice call & incall_music paths
                        val mixerPathsCmd = buildString {
                            append("echo '=== mixer_paths voice/incall ==='; ")
                            // Search for incall_music path definitions
                            append("for f in /vendor/etc/mixer_paths*.xml /vendor/etc/audio/mixer_paths*.xml; do ")
                            append("  if [ -f \"\$f\" ]; then ")
                            append("    echo \"--- \$f ---\"; ")
                            // Grep for incall_music path (if it exists, shows what controls to set)
                            append("    grep -B2 -A15 'incall.music\\|incallmusic' \"\$f\" 2>/dev/null | head -40; ")
                            // Also show voice_call path for comparison
                            append("    echo '--- voice_call path ---'; ")
                            append("    grep -B1 -A10 'name=\"voice-call\"\\|name=\"voice_call\"' \"\$f\" 2>/dev/null | head -30; ")
                            // Search for any path that mentions UAIF (modem TX)
                            append("    echo '--- UAIF paths ---'; ")
                            append("    grep -i 'uaif' \"\$f\" 2>/dev/null | head -20; ")
                            append("  fi; done")
                        }
                        val mixerPaths = RootShell.execForOutput(mixerPathsCmd, timeoutMs = 5000)
                        for (line in mixerPaths.lines().filter { it.isNotBlank() }) {
                            Log.i(TAG, "Diag: $line")
                        }

                        // Phase 3: Active capture/playback PCMs + hw_params (all cards)
                        val pcmCmd = buildString {
                            append("echo '=== capture PCMs (all cards) ==='; ")
                            append("for f in /proc/asound/card*/pcm*c/sub0/status; do ")
                            append("s=\$(head -1 \$f 2>/dev/null); d=\${f%/status}; ")
                            append("card=\${f#/proc/asound/}; card=\${card%%/*}; ")
                            append("pcm=\${d##*/}; ")
                            append("echo \"\$card/\$pcm: \$s\"; ")
                            append("if echo \"\$s\" | grep -q RUNNING; then ")
                            append("echo \"  hw: \$(cat \${d}/hw_params 2>/dev/null | head -5 | tr '\\n' ' ')\"; ")
                            append("name=\$(cat \${d%/sub0}/info 2>/dev/null | grep '^name:' | head -1); ")
                            append("[ -n \"\$name\" ] && echo \"  \$name\"; ")
                            append("fi; done; ")
                            // Also dump playback PCMs (all cards)
                            append("echo '=== playback PCMs (all cards) ==='; ")
                            append("for f in /proc/asound/card*/pcm*p/sub0/status; do ")
                            append("s=\$(head -1 \$f 2>/dev/null); d=\${f%/status}; ")
                            append("card=\${f#/proc/asound/}; card=\${card%%/*}; ")
                            append("pcm=\${d##*/}; ")
                            append("if echo \"\$s\" | grep -q RUNNING; then ")
                            append("echo \"\$card/\$pcm: \$s  hw: \$(cat \${d}/hw_params 2>/dev/null | head -3 | tr '\\n' ' ')\"; ")
                            append("fi; done")
                        }
                        val pcmResult = RootShell.execForOutput(pcmCmd, timeoutMs = 3000)
                        for (line in pcmResult.lines().filter { it.isNotBlank() }) {
                            Log.i(TAG, "Diag: $line")
                        }

                        // Phase 4-5 removed in v2.8.43 — routing map fully
                        // established (Madera codec, SLIMTX, HPOUT, DSP, ASRC).

                        // Phase 6: Delayed re-check (5s) — ABOX only
                        Thread.sleep(5000)
                        if (running.get() && profile.routing.isAbox) {
                            val recheck = buildString {
                                append("echo '=== NSRC re-check (t+5s) ==='; ")
                                for (i in 0..2) {
                                    append("echo -n 'NSRC${i}='; $tinymix 'ABOX NSRC${i}' 2>/dev/null || echo 'N/A'; ")
                                    append("echo -n 'NSRC${i}_Bridge='; $tinymix 'ABOX NSRC${i} Bridge' 2>/dev/null || echo 'N/A'; ")
                                }
                                append("echo -n 'SoundType='; $tinymix 'ABOX Sound Type' 2>/dev/null || echo 'N/A'")
                            }
                            val recheckResult = RootShell.execForOutput(recheck, timeoutMs = 8000)
                            for (line in recheckResult.lines().filter { it.isNotBlank() }) {
                                Log.i(TAG, "Diag: $line")
                            }
                        }

                        // Phase 7: Probe ALSA capture PCMs for non-zero audio data.
                        // All AudioRecord sources return silence (rawCapRMS=0) on this
                        // device.  This probe reads raw bytes from each RUNNING capture
                        // PCM across ALL cards to find which device has modem downlink
                        // audio.  Card 1 (aboxvdma) may carry modem voice data.
                        if (running.get()) {
                            val probeCmd = buildString {
                                append("echo '=== ALSA capture PCM probe (all cards) ==='; ")
                                append("if [ ! -x /data/local/tmp/tinycap ]; then ")
                                append("  echo 'tinycap not found'; ")
                                append("else ")
                                // Iterate all capture PCMs across all cards
                                append("for d in /proc/asound/card*/pcm*c; do ")
                                append("  [ -d \"\$d/sub0\" ] || continue; ")
                                append("  s=\$(head -1 \$d/sub0/status 2>/dev/null); ")
                                append("  card=\${d#/proc/asound/}; card=\${card%%/*}; ")
                                append("  cardnum=\${card#card}; ")
                                append("  pcm=\${d##*/}; devnum=\${pcm#pcm}; devnum=\${devnum%c}; ")
                                append("  name=\$(cat \$d/info 2>/dev/null | grep '^name:' | head -1 | cut -d: -f2-); ")
                                append("  echo \"\$card/\$pcm:\$name status=\$s\"; ")
                                append("  if echo \"\$s\" | grep -q RUNNING; then ")
                                // Parse actual hw_params for this PCM
                                append("    ch=\$(cat \$d/sub0/hw_params 2>/dev/null | grep '^channels:' | awk '{print \$2}'); ")
                                append("    rate=\$(cat \$d/sub0/hw_params 2>/dev/null | grep '^rate:' | awk '{print \$2}'); ")
                                append("    fmt=\$(cat \$d/sub0/hw_params 2>/dev/null | grep '^format:' | awk '{print \$2}'); ")
                                append("    bits=16; echo \"\$fmt\" | grep -q S32 && bits=32; ")
                                append("    echo \"  hw: fmt=\$fmt ch=\$ch rate=\$rate bits=\$bits\"; ")
                                // Capture 1 second of raw audio with actual params
                                append("    timeout 2 /data/local/tmp/tinycap /data/local/tmp/probe_\${cardnum}_\${devnum}.raw ")
                                append("-D \$cardnum -d \$devnum -c \${ch:-2} -r \${rate:-48000} -b \${bits} -p 480 -n 4 2>&1 | head -2; ")
                                append("    f=/data/local/tmp/probe_\${cardnum}_\${devnum}.raw; ")
                                append("    if [ -f \"\$f\" ] && [ -s \"\$f\" ]; then ")
                                append("      sz=\$(wc -c < \"\$f\"); ")
                                append("      nz=\$(od -An -tx1 \"\$f\" | tr ' ' '\\n' | grep -cv '^00\$\\|^\$'); ")
                                append("      echo \"  probe: \${sz}B, \${nz} non-zero bytes\"; ")
                                append("      rm -f \"\$f\"; ")
                                append("    else ")
                                append("      echo '  probe: no data captured'; ")
                                append("    fi; ")
                                append("  fi; ")
                                append("done; ")
                                append("fi")
                            }
                            val probeResult = RootShell.execForOutput(probeCmd, timeoutMs = 30000)
                            for (line in probeResult.lines().filter { it.isNotBlank() }) {
                                Log.i(TAG, "Diag: $line")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Diag routing check failed: ${e.message}")
                    }
                }, "CaptureDiag").start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Capture diagnostics failed: ${e.message}")
        }
    }

    /**
     * Read CPU/memory/thread stats for diagnostics.
     * - System load from /proc/loadavg (1/5/15 min averages; >2.0 = overloaded on dual-core)
     * - Process CPU% from /proc/self/stat utime+stime delta over sample interval
     * - JVM heap usage and thread count
     */
    private fun getCpuStats(): String {
        return try {
            val sb = StringBuilder()

            // /proc/loadavg is blocked by SELinux (proc_loadavg) on priv_app.
            // Process CPU% — read utime(14) + stime(15) from /proc/self/stat
            // These are in clock ticks (typically 100 Hz on ARM).
            try {
                val statFields = java.io.File("/proc/self/stat").readText().trim()
                    .substringAfter(") ")  // skip past "(comm) "
                    .split(" ")
                // Fields after ") ": index 0=state, ..., 11=utime, 12=stime
                val utime = statFields.getOrNull(11)?.toLongOrNull() ?: 0L
                val stime = statFields.getOrNull(12)?.toLongOrNull() ?: 0L
                val totalTicks = utime + stime
                val nowMs = System.currentTimeMillis()

                if (lastCpuSampleMs > 0) {
                    val tickDelta = totalTicks - lastCpuTicks
                    val msDelta = nowMs - lastCpuSampleMs
                    if (msDelta > 0) {
                        // Convert ticks to ms (100 ticks/sec = 10ms/tick)
                        val cpuMs = tickDelta * 10
                        val cpuPct = (cpuMs * 100) / msDelta
                        sb.append(" cpu=${cpuPct}%")
                    }
                }
                lastCpuTicks = totalTicks
                lastCpuSampleMs = nowMs
            } catch (_: Exception) {}

            // Thread count and JVM heap
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val maxMb = rt.maxMemory() / (1024 * 1024)
            val threads = Thread.activeCount()
            sb.append(" mem=${usedMb}/${maxMb}M thr=$threads")

            sb.toString()
        } catch (_: Exception) { "" }
    }

    companion object {
        private const val TAG = "RtpSession"
    }
}
