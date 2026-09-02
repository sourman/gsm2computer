package com.gsm2computer.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubEndpointsTest {

    private val hub = "http://100.101.181.110:8787"

    @Test
    fun tokenUrlUsesExplicitOverride() {
        assertEquals(
            "https://worker.example/token",
            HubEndpoints.tokenUrl(hub, "https://worker.example/token"),
        )
    }

    @Test
    fun tokenUrlFallsBackToHubTokenPath() {
        assertEquals("$hub/token", HubEndpoints.tokenUrl("$hub/", "  "))
    }

    @Test
    fun tokenUrlEmptyWhenNothingConfigured() {
        assertEquals("", HubEndpoints.tokenUrl("", ""))
    }

    @Test
    fun smsUrlAppendsSmsPathAndStripsSlash() {
        assertEquals("$hub/sms", HubEndpoints.smsUrl("$hub/"))
    }

    @Test
    fun smsUrlEmptyWithoutHub() {
        assertEquals("", HubEndpoints.smsUrl("   "))
    }

    @Test
    fun webSocketUrlRewritesHttpToWs() {
        assertEquals("ws://100.101.181.110:8787", HubEndpoints.webSocketUrl(hub))
    }

    @Test
    fun webSocketUrlRewritesHttpsToWss() {
        assertEquals(
            "wss://hub.example:8787",
            HubEndpoints.webSocketUrl("https://hub.example:8787/"),
        )
    }

    @Test
    fun webSocketUrlFallsBackToOpenAi() {
        assertEquals(HubEndpoints.OPENAI_REALTIME_WS, HubEndpoints.webSocketUrl(""))
    }

    @Test
    fun connectUrlOmitsModelOnCustomHub() {
        assertEquals(
            "ws://100.101.181.110:8787",
            HubEndpoints.connectUrl("ws://100.101.181.110:8787", "gpt-realtime", true),
        )
    }

    @Test
    fun connectUrlAppendsModelForOpenAi() {
        assertEquals(
            "${HubEndpoints.OPENAI_REALTIME_WS}?model=gpt-realtime",
            HubEndpoints.connectUrl(HubEndpoints.OPENAI_REALTIME_WS, "gpt-realtime", false),
        )
    }

    @Test
    fun hubOwnedSessionDefaultsTrueForCustomHub() {
        assertTrue(HubEndpoints.hubOwnedSession(hub, null))
        assertFalse(HubEndpoints.hubOwnedSession("", null))
        assertFalse(HubEndpoints.hubOwnedSession(hub, false))
        assertTrue(HubEndpoints.hubOwnedSession("", true))
    }

    @Test
    fun smsJsonHasRequiredKeysAndEscapes() {
        val json = HubEndpoints.smsJson("+15551212", "say \"hi\"\nnow", "2026-09-02T17:00:00Z")
        assertEquals(
            """{"from":"+15551212","body":"say \"hi\"\nnow","receivedAt":"2026-09-02T17:00:00Z"}""",
            json,
        )
    }

    @Test
    fun isCustomHubIgnoresWhitespace() {
        assertFalse(HubEndpoints.isCustomHub("  "))
        assertTrue(HubEndpoints.isCustomHub(hub))
    }

    @Test
    fun defaultConstantMatchesBuildConfig() {
        assertEquals(HubEndpoints.DEFAULT_HUB_CONTROL_URL, BuildConfig.DEFAULT_HUB_CONTROL_URL)
    }
}
