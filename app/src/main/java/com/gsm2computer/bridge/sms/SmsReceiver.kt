package com.gsm2computer.bridge.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Forwards SMS_RECEIVED to the hub. The phone does not interpret the text.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return
        val from = messages.first().originatingAddress.orEmpty()
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        Log.i(TAG, "SMS received (${body.length} chars) — forwarding")
        SmsForwarder.forward(context, from, body)
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
