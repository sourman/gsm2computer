package com.gsm2computer.bridge.realtime

import android.util.Base64
import android.util.Log
import com.gsm2computer.bridge.rtp.MediaTransport
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI Realtime WebSocket transport (GA API, 2026).
 *
 * Connects the gateway's caller audio directly to OpenAI Realtime over a
 * WebSocket — no SIP, no Twilio, no SignalWire, no call_id handshake. The
 * telephony-native g711_ulaw (8 kHz μ-law) audio format is used in both
 * directions, matching the gateway's existing PCMU path, so no resampling is
 * involved.
 *
 * Auth: the device never holds the real API key. It fetches a short-lived
 * ephemeral client secret (`ek_...`) from the bridge-worker `/token` endpoint,
 * then authenticates the WebSocket with `Authorization: Bearer <ek_...>`.
 * (Both the ephemeral-over-WS and header-auth paths are verified working.)
 *
 * Protocol (confirmed against a live round-trip):
 *   → session.update  { audio.input.format={type:"audio/pcmu"}, turn_detection:server_vad,
 *                        audio.output.format={type:"audio/pcmu"}, voice }
 *   → input_audio_buffer.append { audio: base64(μ-law) }   (streamed, server VAD auto-commits)
 *   ← response.output_audio.delta { delta: base64(μ-law) }  (agent speech back)
 *   ← input_audio_buffer.speech_started                     (caller barge-in → flush playback)
 */
class HubStreamClient(
    private val tokenUrl: String,
    private val model: String,
    private val voice: String,
    private val instructions: String,
) : MediaTransport {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // WS: no read timeout
        .pingInterval(20, TimeUnit.SECONDS)      // keep the socket alive
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var sink: MediaTransport.Sink? = null
    @Volatile private var closed = false

    override fun start(sink: MediaTransport.Sink) {
        this.sink = sink
        // Token fetch is a blocking network call — run off the caller's thread.
        Thread({ connect(sink) }, "OpenAI-WS-Connect").start()
    }

    // Model actually used for the WS connect — the one the token was minted for
    // (returned by /token), which may differ from the configured default. The
    // ephemeral session's model and the ?model= param must agree.
    @Volatile private var connectModel = model

    private fun connect(sink: MediaTransport.Sink) {
        val token = try {
            fetchEphemeralToken()
        } catch (e: Exception) {
            sink.onError("token fetch failed: ${e.message}")
            return
        }
        if (closed) return
        Log.i(TAG, "Minted ephemeral token ${token.take(8)}…, opening WS (model=$connectModel voice=$voice)")

        val url = "$WS_BASE?model=$connectModel"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .build()

        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WS OPEN — sending session.update")
                sink.onStatus("OpenAI WS connected ($voice)")
                webSocket.send(sessionUpdateJson())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleEvent(text, sink)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closed) return
                val code = response?.code ?: -1
                Log.e(TAG, "WS failure code=$code: ${t.message}")
                sink.onError("OpenAI WS failure (code=$code): ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS CLOSED code=$code reason=$reason")
                if (!closed) sink.onError("OpenAI WS closed ($code $reason)")
            }
        })
    }

    /** POST bridge-worker /token → { value: "ek_...", model: "...", ... }. */
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
            // Connect with the model the token was minted for, so the WS ?model=
            // matches the ephemeral session's model.
            json.optString("model").takeIf { it.isNotBlank() }?.let { connectModel = it }
            return value
        }
    }

    private fun handleEvent(text: String, sink: MediaTransport.Sink) {
        val type = try {
            JSONObject(text).optString("type")
        } catch (_: Exception) {
            return
        }
        when {
            // GA event: agent audio back, base64 μ-law in "delta".
            type == "response.output_audio.delta" -> {
                val b64 = JSONObject(text).optString("delta")
                if (b64.isNotEmpty()) {
                    try {
                        sink.onAudio(Base64.decode(b64, Base64.DEFAULT))
                    } catch (e: Exception) {
                        Log.w(TAG, "audio delta decode failed: ${e.message}")
                    }
                }
            }
            // Caller started speaking — drop queued agent audio (barge-in).
            type == "input_audio_buffer.speech_started" -> sink.onFlushPlayback()
            type == "session.updated" -> {
                Log.i(TAG, "session.updated — μ-law + server_vad active")
                sink.onStatus("OpenAI session ready")
                // Greet first: the caller is silent (listening), so server VAD
                // won't trigger a turn. Kick off an initial response so the
                // caller hears the agent immediately; VAD handles later turns.
                ws?.send(JSONObject().put("type", "response.create").toString())
            }
            type == "error" -> {
                val err = JSONObject(text).optJSONObject("error")?.toString() ?: text
                Log.e(TAG, "WS error event: ${err.take(300)}")
                sink.onStatus("OpenAI error: ${err.take(160)}")
            }
            type == "response.output_audio_transcript.done" -> {
                val t = JSONObject(text).optString("transcript")
                if (t.isNotBlank()) Log.i(TAG, "agent said: $t")
            }
        }
    }

    override fun sendAudio(mulaw: ByteArray) {
        val socket = ws ?: return
        val b64 = Base64.encodeToString(mulaw, Base64.NO_WRAP)
        val msg = JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", b64)
            .toString()
        socket.send(msg)
    }

    override fun stop() {
        closed = true
        try { ws?.close(1000, "call ended") } catch (_: Exception) {}
        ws = null
        try { http.dispatcher.executorService.shutdown() } catch (_: Exception) {}
    }

    /**
     * GA session config: μ-law both ways, Marin voice, server VAD (which
     * auto-commits and triggers responses — the client never sends
     * input_audio_buffer.commit / response.create manually).
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
        private const val WS_BASE = "wss://api.openai.com/v1/realtime"

        const val DEFAULT_INSTRUCTIONS =
            "You are a friendly voice assistant on a phone call bridged from a " +
                "cellular caller. Greet the caller briefly, then converse naturally " +
                "and keep responses short. Do not hang up."
    }
}
