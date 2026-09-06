package com.gsm2computer.bridge.rtp

/**
 * Pluggable audio wire for [RtpSession].
 *
 * The default wire is UDP/RTP to a SIP peer (handled inline in RtpSession).
 * A [MediaTransport] replaces that wire: outbound caller audio is handed to
 * [sendAudio], and inbound agent audio is delivered back through the [Sink]
 * the session installs in [start].
 *
 * Hub WebSocket: PCM s16le at the capture/playback rates ([configureWire]),
 * with 8 kHz μ-law still accepted so the browser simulator keeps working.
 */
interface MediaTransport {
    /** Open the transport and begin delivering inbound audio to [sink]. */
    fun start(sink: Sink)

    /**
     * Tell the hub what we will send and what we can play.
     * No-op on transports that do not negotiate (UDP).
     */
    fun configureWire(
        inputFormat: String,
        inputRate: Int,
        outputFormat: String,
        outputRate: Int,
    ) {
    }

    /** Send one frame of caller audio (PCM s16le or μ-law, per [configureWire]). */
    fun sendAudio(frame: ByteArray)

    /** Close the transport. */
    fun stop()

    /** Callbacks from the transport into the owning [RtpSession]. */
    interface Sink {
        /** Inbound agent audio. [format] is `audio/pcm` or `audio/pcmu`. */
        fun onAudio(frame: ByteArray, format: String, rate: Int)

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
