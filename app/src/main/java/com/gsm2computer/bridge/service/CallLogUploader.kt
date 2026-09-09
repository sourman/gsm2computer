package com.gsm2computer.bridge.service

import android.content.Context
import android.util.Log
import com.gsm2computer.bridge.BridgeConfig
import com.gsm2computer.bridge.HubEndpoints
import com.gsm2computer.bridge.util.RedactingLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * POSTs a bridged-call record to the hub after hangup. Hub fills
 * switchboard_mode / tap_summary from last_call_tap when omitted.
 */
object CallLogUploader {

    private const val TAG = "CallLogUploader"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun post(
        context: Context,
        incoming: Boolean,
        number: String,
        startedAtMs: Long,
        durationSec: Long,
    ) {
        val hub = BridgeConfig.resolveHubControlUrl(BridgeConfig.openPrefs(context))
        val url = HubEndpoints.callsUrl(hub)
        if (url.isEmpty()) return
        val payload = HubEndpoints.callJson(
            direction = if (incoming) "in" else "out",
            number = number,
            startedAt = Instant.ofEpochMilli(startedAtMs).toString(),
            durationSec = durationSec,
        )
        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    RedactingLogger.i(TAG, "call record posted $number")
                    GatewayService.appendLog(context, "Call record uploaded")
                } else {
                    val snippet = resp.body?.string().orEmpty().take(160)
                    Log.w(TAG, "call record HTTP ${resp.code} $snippet")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "call record failed: ${e.message}")
        }
    }
}
