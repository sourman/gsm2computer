package com.gsm2computer.bridge.sms

import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import com.gsm2computer.bridge.BridgeConfig
import com.gsm2computer.bridge.HubEndpoints
import com.gsm2computer.bridge.service.GatewayService
import com.gsm2computer.bridge.util.RedactingLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Polls the hub SMS outbox and sends via [SmsManager]. Runs off the audio
 * WebSocket thread — [GatewayService] calls [pollOnce] from a dedicated loop.
 */
object SmsOutboxPoller {

    private const val TAG = "SmsOutbox"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun pollOnce(context: Context) {
        val hub = BridgeConfig.resolveHubControlUrl(BridgeConfig.openPrefs(context))
        val url = HubEndpoints.smsOutboxUrl(hub)
        if (url.isEmpty()) return
        if (context.checkSelfPermission(android.Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "SEND_SMS not granted — skip outbox poll")
            return
        }
        val req = Request.Builder().url(url).get().build()
        val items: JSONArray
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "outbox GET HTTP ${resp.code}")
                    return
                }
                val root = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
                items = root.optJSONArray("items") ?: JSONArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "outbox GET failed: ${e.message}")
            return
        }
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val to = item.optString("to")
            val body = item.optString("body")
            if (id.isBlank() || to.isBlank()) continue
            sendAndAck(context, hub, id, to, body)
        }
    }

    private fun sendAndAck(context: Context, hub: String, id: String, to: String, body: String) {
        val ackUrl = HubEndpoints.smsOutboxAckUrl(hub, id)
        try {
            sendSms(to, body)
            RedactingLogger.i(TAG, "SMS sent to $to")
            GatewayService.appendLog(context, "SMS sent to $to")
            ack(ackUrl, HubEndpoints.outboxAckJson("sent"))
        } catch (e: Exception) {
            val msg = "SMS send failed: ${e.message}"
            Log.e(TAG, msg)
            GatewayService.appendLog(context, msg)
            ack(ackUrl, HubEndpoints.outboxAckJson("failed", e.message.orEmpty()))
        }
    }

    private fun sendSms(to: String, body: String) {
        val sms = contextSmsManager()
        val parts = sms.divideMessage(body)
        if (parts.size <= 1) {
            sms.sendTextMessage(to, null, body, null, null)
        } else {
            sms.sendMultipartTextMessage(to, null, parts, null, null)
        }
    }

    @Suppress("DEPRECATION")
    private fun contextSmsManager(): SmsManager = SmsManager.getDefault()

    private fun ack(url: String, payload: String) {
        if (url.isEmpty()) return
        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "outbox ack HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "outbox ack failed: ${e.message}")
        }
    }
}
