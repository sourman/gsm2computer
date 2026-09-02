package com.gsm2computer.bridge

/**
 * Hub control-plane URL helpers.
 *
 * [hubControlUrl] is the Tailscale (or LAN) origin, e.g. `http://100.101.181.110:8787`.
 * Token, health, SMS, and the μ-law WebSocket upgrade all live on that host.
 * When the hub URL is blank the phone falls back to OpenAI Realtime.
 */
object HubEndpoints {

    const val OPENAI_REALTIME_WS = "wss://api.openai.com/v1/realtime"
    const val DEFAULT_HUB_CONTROL_URL = "http://100.101.181.110:8787"

    fun normalizeBase(url: String): String = url.trim().trimEnd('/')

    fun isCustomHub(hubControlUrl: String): Boolean =
        normalizeBase(hubControlUrl).isNotEmpty()

    /** Token mint: explicit override, else `{hub}/token`. */
    fun tokenUrl(hubControlUrl: String, streamTokenUrl: String): String {
        val explicit = streamTokenUrl.trim()
        if (explicit.isNotEmpty()) return explicit
        val hub = normalizeBase(hubControlUrl)
        return if (hub.isEmpty()) "" else "$hub/token"
    }

    /** SMS ingest: `{hub}/sms`. Empty when no hub is configured. */
    fun smsUrl(hubControlUrl: String): String {
        val hub = normalizeBase(hubControlUrl)
        return if (hub.isEmpty()) "" else "$hub/sms"
    }

    /**
     * WebSocket origin. Custom hub → `http`/`https` rewritten to `ws`/`wss`.
     * OpenAI fallback when hub URL is blank.
     */
    fun webSocketUrl(hubControlUrl: String): String {
        val hub = normalizeBase(hubControlUrl)
        if (hub.isEmpty()) return OPENAI_REALTIME_WS
        return toWebSocket(hub)
    }

    /** OpenAI WS needs `?model=`; a custom hub is upgraded on the same origin. */
    fun connectUrl(webSocketUrl: String, model: String, customHub: Boolean): String {
        if (customHub) return webSocketUrl
        val sep = if (webSocketUrl.contains('?')) '&' else '?'
        return "$webSocketUrl${sep}model=$model"
    }

    /**
     * Greeting (`response.create`) and OpenAI `session.update` are hub-owned
     * whenever a custom hub URL is set, unless [storedOverride] is present.
     */
    fun hubOwnedSession(hubControlUrl: String, storedOverride: Boolean?): Boolean =
        storedOverride ?: isCustomHub(hubControlUrl)

    fun smsJson(from: String, body: String, receivedAt: String): String =
        buildString {
            append('{')
            append("\"from\":").append(jsonString(from)).append(',')
            append("\"body\":").append(jsonString(body)).append(',')
            append("\"receivedAt\":").append(jsonString(receivedAt))
            append('}')
        }

    internal fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    private fun toWebSocket(httpUrl: String): String = when {
        httpUrl.startsWith("https://") -> "wss://" + httpUrl.removePrefix("https://")
        httpUrl.startsWith("http://") -> "ws://" + httpUrl.removePrefix("http://")
        httpUrl.startsWith("ws://") || httpUrl.startsWith("wss://") -> httpUrl
        else -> httpUrl
    }
}
