package com.gsm2computer.bridge.sms

import android.content.Context
import android.util.Log
import com.gsm2computer.bridge.BridgeConfig
import com.gsm2computer.bridge.HubEndpoints
import com.gsm2computer.bridge.service.GatewayService
import com.gsm2computer.bridge.util.RedactingLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * POSTs inbound SMS to the hub control API. Does not parse the body —
 * the hub owns command handling.
 *
 * Runs on a background thread so [SmsReceiver] never blocks the GSM bridge.
 */
object SmsForwarder {

    private const val TAG = "SmsForwarder"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun forward(context: Context, from: String, body: String) {
        val hub = BridgeConfig.resolveHubControlUrl(BridgeConfig.openPrefs(context))
        val url = HubEndpoints.smsUrl(hub)
        if (url.isEmpty()) {
            Log.w(TAG, "SMS skipped — hub control URL not set")
            GatewayService.appendLog(context, "SMS skipped — hub control URL not set")
            return
        }
        val receivedAt = Instant.now().toString()
        val app = context.applicationContext
        Thread({ post(app, url, from, body, receivedAt) }, "Sms-Forward").start()
    }

    private fun post(context: Context, url: String, from: String, body: String, receivedAt: String) {
        val payload = HubEndpoints.smsJson(from, body, receivedAt)
        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    RedactingLogger.i(TAG, "SMS forwarded from $from → $url")
                    GatewayService.appendLog(context, "SMS forwarded from $from")
                } else {
                    val snippet = resp.body?.string().orEmpty().take(160)
                    val msg = "SMS forward failed: HTTP ${resp.code} $snippet"
                    Log.e(TAG, msg)
                    GatewayService.appendLog(context, msg)
                }
            }
        } catch (e: Exception) {
            val msg = "SMS forward failed: ${e.message}"
            Log.e(TAG, msg)
            GatewayService.appendLog(context, msg)
        }
    }
}
