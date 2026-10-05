package com.gsm2computer.bridge.realtime

import android.util.Base64
import android.util.Log
import com.gsm2computer.bridge.HubEndpoints
import com.gsm2computer.bridge.diag.HubLinkGrace
import com.gsm2computer.bridge.rtp.MediaTransport
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * WebSocket transport for the GSM bridge.
 *
 * A mid-call socket failure does not end the GSM call. This client keeps
 * retrying, with backoff, until [linkGraceMs] expires or [stop] runs because
 * the GSM call itself ended. Close code 1000 is a real hangup and is not
 * retried. Silence is what the caller hears during the gap.
 *
 * Custom hub: token from `{hub}/token`, WebSocket upgrade on the same host.
 * Audio is PCM s16le at the rates from [configureWire]. Appends without
 * format/rate stay 8 kHz μ-law so the browser simulator keeps working.
 */
class HubStreamClient(
    private val tokenUrl: String,
    private val webSocketUrl: String,
    private val model: String,
    private val voice: String,
    private val instructions: String,
    private val hubOwnedSession: Boolean = false,
    private val linkGraceMs: Long = HubLinkGrace.DEFAULT_GRACE_MS,
    private val onTrace: (kind: String, fields: Map<String, Any?>) -> Unit = { _, _ -> },
) : MediaTransport {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val grace = HubLinkGrace(graceMs = linkGraceMs)

    @Volatile private var ws: WebSocket? = null
    @Volatile private var sink: MediaTransport.Sink? = null
    @Volatile private var closed = false
    @Volatile private var inputFormat = "audio/pcm"
    @Volatile private var inputRate = 8000
    @Volatile private var outputFormat = "audio/pcm"
    @Volatile private var outputRate = 48000

    @Volatile var linkState: String = "idle"
        private set

    @Volatile var lastRttMs: Long = -1L
        private set

    @Volatile var sessionId: String = ""
        private set

    // Model actually used for the WS connect — the one the token was minted for
    // (returned by /token), which may differ from the configured default. The
    // ephemeral session's model and the ?model= param must agree (OpenAI path).
    @Volatile private var connectModel = model
    private var sessionThread: Thread? = null
    private var pingThread: Thread? = null

    override fun start(sink: MediaTransport.Sink) {
        this.sink = sink
        linkState = "connecting"
        sessionThread = Thread({ sessionLoop() }, "Hub-WS").apply {
            isDaemon = true
            start()
        }
        pingThread = Thread({ pingLoop() }, "Hub-WS-Ping").apply {
            isDaemon = true
            start()
        }
    }

    private fun sessionLoop() {
        var pending: String? = null
        try {
            while (!closed) {
                if (pending != null) {
                    if (pending.startsWith(FATAL_PREFIX)) {
                        fail(pending.removePrefix(FATAL_PREFIX))
                        return
                    }
                    if (linkGraceMs <= 0L) {
                        fail(pending)
                        return
                    }
                    val now = System.currentTimeMillis()
                    grace.markLost(now)
                    val delay = grace.retryDelayMs(now)
                    if (delay == null) {
                        fail("hub link grace expired after ${linkGraceMs}ms: $pending")
                        return
                    }
                    linkState = "hold"
                    sink?.onLinkHold(true, pending)
                    trace(
                        "reconnect",
                        mapOf(
                            "attempt" to grace.attempt,
                            "delay_ms" to delay,
                            "message" to pending,
                            "session_id" to sessionId,
                        ),
                    )
                    if (delay > 0 && !sleepMs(delay)) return
                    if (closed) return
                }
                val err = try {
                    openAndHold()
                } catch (e: Exception) {
                    "hub link error: ${e.message}"
                }
                if (closed || err == null) return
                pending = err
            }
        } catch (e: Exception) {
            if (!closed) fail("hub link loop failed: ${e.message}")
        }
    }

    /**
     * Block until this socket dies.
     * Null means the client was stopped. A [FATAL_PREFIX] result is not retried.
     */
    private fun openAndHold(): String? {
        linkState = "connecting"
        val token = try {
            fetchEphemeralToken()
        } catch (e: Exception) {
            val msg = "token fetch failed: ${e.message}"
            trace("ws_failure", mapOf("code" to -1, "message" to msg))
            return msg
        }
        if (closed) return null
        Log.i(TAG, "Minted ephemeral token ${token.take(8)}…, opening WS (model=$connectModel voice=$voice hubOwned=$hubOwnedSession session=$sessionId)")

        val opened = AtomicBoolean(false)
        val cleanClose = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val failure = AtomicReference<String?>(null)
        val done = CountDownLatch(1)

        fun finish(message: String?, clean: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            if (clean) cleanClose.set(true)
            failure.set(message)
            done.countDown()
        }

        val url = HubEndpoints.connectUrl(webSocketUrl, connectModel, hubOwnedSession)
        val builder = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
        if (sessionId.isNotBlank()) {
            builder.addHeader("X-Gsm-Call-Session", sessionId)
        }

        val socket = http.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                ws = webSocket
                linkState = "open"
                grace.markRestored()
                sink?.onLinkHold(false, "hub ws open")
                trace("ws_open", mapOf("session_id" to sessionId, "http" to response.code))
                if (hubOwnedSession) {
                    Log.i(TAG, "WS OPEN — hub owns session (skip session.update / greeting)")
                    sendClientAudio(webSocket)
                    sink?.onStatus("Hub WS connected")
                } else {
                    Log.i(TAG, "WS OPEN — sending session.update")
                    sink?.onStatus("OpenAI WS connected ($voice)")
                    webSocket.send(sessionUpdateJson())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleEvent(text, sink)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== ws && opened.get()) return
                val code = response?.code ?: -1
                val msg = "Hub WS failure (code=$code): ${t.message}"
                Log.e(TAG, "WS failure code=$code: ${t.message}")
                if (closed) {
                    finish(null, false)
                    return
                }
                trace(
                    "ws_failure",
                    mapOf(
                        "code" to code,
                        "message" to (t.message ?: ""),
                        "exception" to t.javaClass.simpleName,
                    ),
                )
                finish(msg, false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS CLOSED code=$code reason=$reason")
                if (closed) {
                    finish(null, false)
                    return
                }
                trace("ws_closed", mapOf("code" to code, "reason" to reason, "local" to false))
                if (code == 1000) {
                    finish("$FATAL_PREFIX Hub WS closed ($code $reason)", true)
                } else {
                    finish("Hub WS closed ($code $reason)", false)
                }
            }
        })
        ws = socket

        while (!closed) {
            try {
                if (done.await(1, TimeUnit.SECONDS)) break
            } catch (_: InterruptedException) {
                if (closed) return null
            }
            if (closed) {
                try {
                    socket.close(1000, "call ended")
                } catch (_: Exception) {
                }
                return null
            }
        }
        if (closed) return null
        linkState = "hold"
        ws = null
        try {
            socket.cancel()
        } catch (_: Exception) {
        }
        if (cleanClose.get()) {
            return failure.get() ?: "${FATAL_PREFIX}Hub WS closed (1000)"
        }
        return failure.get() ?: "Hub WS failure"
    }

    private fun pingLoop() {
        while (!closed) {
            if (!sleepMs(5_000)) return
            if (closed || linkState != "open") continue
            val socket = ws ?: continue
            val sentAt = System.currentTimeMillis()
            try {
                socket.send(JSONObject().put("type", "client.ping").put("t", sentAt).toString())
            } catch (_: Exception) {
            }
        }
    }

    private fun fail(message: String) {
        if (closed) return
        linkState = "closed"
        Log.e(TAG, message)
        trace("ws_give_up", mapOf("message" to message, "session_id" to sessionId))
        sink?.onError(message)
    }

    private fun trace(kind: String, fields: Map<String, Any?>) {
        try {
            onTrace(kind, fields)
        } catch (e: Exception) {
            Log.w(TAG, "trace $kind failed: ${e.message}")
        }
    }

    private fun sleepMs(delay: Long): Boolean {
        return try {
            Thread.sleep(delay)
            !closed
        } catch (_: InterruptedException) {
            false
        }
    }

    /** POST /token → { value: "ek_...", model: "...", ... }. */
    private fun fetchEphemeralToken(): String {
        val req = Request.Builder()
            .url(tokenUrl)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${body.take(200)}")
            val json = JSONObject(body)
            val value = json.optString("value")
            if (value.isBlank()) throw RuntimeException("no token in response: ${body.take(200)}")
            json.optString("model").takeIf { it.isNotBlank() }?.let { connectModel = it }
            return value
        }
    }

    private fun handleEvent(text: String, sink: MediaTransport.Sink?) {
        if (sink == null) return
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        when (val type = json.optString("type")) {
            "response.output_audio.delta" -> {
                val b64 = json.optString("delta")
                if (b64.isNotEmpty()) {
                    try {
                        val fmt = json.optString("format").ifBlank { "audio/pcmu" }
                        val rate = json.optInt("rate", 8000)
                        sink.onAudio(Base64.decode(b64, Base64.DEFAULT), fmt, rate)
                    } catch (e: Exception) {
                        Log.w(TAG, "audio delta decode failed: ${e.message}")
                    }
                }
            }
            "input_audio_buffer.speech_started" -> sink.onFlushPlayback()
            "session.updated" -> {
                val id = json.optJSONObject("session")?.optString("id").orEmpty()
                if (id.isNotBlank()) sessionId = id
                Log.i(TAG, "session.updated id=$sessionId — wire $inputFormat/$inputRate in, $outputFormat/$outputRate out")
                sink.onStatus("Hub session ready")
                sendClientAudio(ws)
                if (!hubOwnedSession) {
                    ws?.send(JSONObject().put("type", "response.create").toString())
                }
            }
            "client.pong" -> {
                val sentAt = json.optLong("t", 0L)
                if (sentAt > 0L) {
                    lastRttMs = (System.currentTimeMillis() - sentAt).coerceAtLeast(0L)
                }
            }
            "error" -> {
                val err = json.optJSONObject("error")?.toString() ?: text
                Log.e(TAG, "WS error event: ${err.take(300)}")
                sink.onStatus("Hub error: ${err.take(160)}")
            }
            "response.output_audio_transcript.done" -> {
                val said = json.optString("transcript")
                if (said.isNotBlank()) Log.i(TAG, "agent said: $said")
            }
            else -> {
                if (type.isNotBlank() && type != "client.ping") {
                    Log.d(TAG, "ws event $type")
                }
            }
        }
    }

    override fun configureWire(
        inputFormat: String,
        inputRate: Int,
        outputFormat: String,
        outputRate: Int,
    ) {
        this.inputFormat = inputFormat
        this.inputRate = inputRate
        this.outputFormat = outputFormat
        this.outputRate = outputRate
        sendClientAudio(ws)
    }

    override fun sendAudio(frame: ByteArray) {
        val socket = ws ?: return
        if (linkState != "open") return
        val b64 = Base64.encodeToString(frame, Base64.NO_WRAP)
        val msg = JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", b64)
            .put("format", inputFormat)
            .put("rate", inputRate)
            .toString()
        socket.send(msg)
    }

    private fun sendClientAudio(socket: WebSocket?) {
        if (socket == null) return
        val input = JSONObject().put("format", inputFormat).put("rate", inputRate)
        val output = JSONObject().put("format", outputFormat).put("rate", outputRate)
        val msg = JSONObject()
            .put("type", "client.audio")
            .put("input", input)
            .put("output", output)
            .toString()
        socket.send(msg)
        Log.i(TAG, "client.audio in=$inputFormat/$inputRate out=$outputFormat/$outputRate")
    }

    override fun stop() {
        if (closed) return
        closed = true
        linkState = "closed"
        trace("ws_closed", mapOf("code" to 1000, "reason" to "call ended", "local" to true))
        sessionThread?.interrupt()
        pingThread?.interrupt()
        try {
            ws?.close(1000, "call ended")
        } catch (_: Exception) {
        }
        ws = null
        try {
            http.dispatcher.executorService.shutdown()
        } catch (_: Exception) {
        }
    }

    /**
     * GA session config: μ-law both ways, configured voice, server VAD.
     * Only used on the OpenAI path; a custom hub owns instructions/greeting.
     */
    private fun sessionUpdateJson(): String {
        val inputFmt = JSONObject().put("type", "audio/pcmu")
        val outputFmt = JSONObject().put("type", "audio/pcmu")
        val input = JSONObject()
            .put("format", inputFmt)
            .put("turn_detection", JSONObject().put("type", "server_vad"))
        val output = JSONObject()
            .put("format", outputFmt)
            .put("voice", voice)
        val audio = JSONObject().put("input", input).put("output", output)
        val session = JSONObject()
            .put("type", "realtime")
            .put("model", connectModel)
            .put("output_modalities", org.json.JSONArray().put("audio"))
            .put("audio", audio)
            .put("instructions", instructions)
        return JSONObject().put("type", "session.update").put("session", session).toString()
    }

    companion object {
        private const val TAG = "HubStream"
        private const val FATAL_PREFIX = "fatal:"

        const val DEFAULT_INSTRUCTIONS =
            "You are a friendly voice assistant on a phone call bridged from a " +
                "cellular caller. Greet the caller briefly, then converse naturally " +
                "and keep responses short. Do not hang up."
    }
}
