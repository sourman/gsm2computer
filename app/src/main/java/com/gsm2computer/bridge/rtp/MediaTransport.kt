package com.gsm2computer.bridge.rtp

/**
 * Pluggable audio wire for [RtpSession].
 *
 * The default wire is UDP/RTP to a SIP peer (handled inline in RtpSession).
 * A [MediaTransport] replaces that wire: outbound caller audio (8 kHz μ-law,
 * G.711) is handed to [sendAudio], and inbound agent audio is delivered back
 * through the [Sink] the session installs in [start]. RtpSession keeps its
 * capture-gating, injection, jitter-buffer and codec paths unchanged — only
 * the "where do bytes go / come from" seam is swapped.
 *
 * The current implementation is [com.gsm2computer.bridge.realtime.HubStreamClient],
 * which speaks the OpenAI Realtime WebSocket protocol (input_audio_buffer.append /
 * response.output_audio.delta) in g711_ulaw — the same 8 kHz μ-law the gateway
 * already uses for PCMU, so no resampling is needed.
 */
interface MediaTransport {
    /** Open the transport and begin delivering inbound audio to [sink]. */
    fun start(sink: Sink)

    /** Send one frame of caller audio (8 kHz μ-law) to the far end. */
    fun sendAudio(mulaw: ByteArray)

    /** Close the transport. */
    fun stop()

    /** Callbacks from the transport into the owning [RtpSession]. */
    interface Sink {
        /** Inbound agent audio (8 kHz μ-law) — queued for playback/injection. */
        fun onAudio(mulaw: ByteArray)

        /**
         * The far end signalled the caller started speaking (barge-in). Drop any
         * queued agent audio so the caller isn't talked over by stale playback.
         */
        fun onFlushPlayback()

        /** Transport-level status line for the in-app log/stats viewer. */
        fun onStatus(msg: String)

        /** Fatal transport error — the session should tear the call down. */
        fun onError(msg: String)
    }
}
