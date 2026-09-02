package com.gsm2computer.bridge

import android.content.Context
import android.content.SharedPreferences

/**
 * Hub stream settings: where bridged call audio is sent (WebSocket transport).
 */
object BridgeConfig {

    const val PREFS_NAME = "gsm2computer"

    const val KEY_AUTOCONNECT = "autoconnect"
    const val KEY_STREAM_ENABLED = "stream_enabled"
    const val KEY_STREAM_TOKEN_URL = "stream_token_url"
    const val KEY_STREAM_MODEL = "stream_model"
    const val KEY_STREAM_VOICE = "stream_voice"

    const val DEFAULT_AUTOCONNECT = true
    val defaultStreamEnabled: Boolean get() = BuildConfig.DEFAULT_STREAM_ENABLED
    val defaultStreamTokenUrl: String get() = BuildConfig.DEFAULT_STREAM_TOKEN_URL
    val defaultStreamModel: String get() = BuildConfig.DEFAULT_STREAM_MODEL
    val defaultStreamVoice: String get() = BuildConfig.DEFAULT_STREAM_VOICE

    fun openPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun resolveAutoconnect(prefs: SharedPreferences): Boolean =
        if (prefs.contains(KEY_AUTOCONNECT)) {
            prefs.getBoolean(KEY_AUTOCONNECT, DEFAULT_AUTOCONNECT)
        } else {
            DEFAULT_AUTOCONNECT
        }

    fun resolveStreamTokenUrl(prefs: SharedPreferences): String =
        prefs.getString(KEY_STREAM_TOKEN_URL, null)?.takeIf { it.isNotBlank() }
            ?: defaultStreamTokenUrl

    fun resolveStreamEnabled(prefs: SharedPreferences): Boolean {
        val on = if (prefs.contains(KEY_STREAM_ENABLED)) {
            prefs.getBoolean(KEY_STREAM_ENABLED, defaultStreamEnabled)
        } else {
            defaultStreamEnabled
        }
        return on && resolveStreamTokenUrl(prefs).isNotBlank()
    }

    fun resolveStreamModel(prefs: SharedPreferences): String =
        prefs.getString(KEY_STREAM_MODEL, null)?.takeIf { it.isNotBlank() }
            ?: defaultStreamModel

    fun resolveStreamVoice(prefs: SharedPreferences): String =
        prefs.getString(KEY_STREAM_VOICE, null)?.takeIf { it.isNotBlank() }
            ?: defaultStreamVoice

    fun isConfigured(prefs: SharedPreferences): Boolean =
        resolveStreamEnabled(prefs)

    data class Resolved(
        val autoconnect: Boolean,
        val streamEnabled: Boolean,
        val streamTokenUrl: String,
        val streamModel: String,
        val streamVoice: String,
    )

    fun resolve(prefs: SharedPreferences): Resolved = Resolved(
        autoconnect = resolveAutoconnect(prefs),
        streamEnabled = resolveStreamEnabled(prefs),
        streamTokenUrl = resolveStreamTokenUrl(prefs),
        streamModel = resolveStreamModel(prefs),
        streamVoice = resolveStreamVoice(prefs),
    )
}
