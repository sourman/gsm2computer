package com.gsm2computer.bridge.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeCommandsTest {

    @Test
    fun gatewayPingBindsTheUnderlayInterface() {
        assertEquals(
            listOf("/system/bin/ping", "-n", "-c", "1", "-W", "1", "-I", "wlan0", "192.168.1.1"),
            ProbeCommands.ping("192.168.1.1", "wlan0"),
        )
    }

    @Test
    fun hubPingFollowsTheRoutingTable() {
        assertEquals(
            listOf("/system/bin/ping", "-n", "-c", "1", "-W", "1", "100.119.126.42"),
            ProbeCommands.ping("100.119.126.42", null),
        )
    }

    @Test
    fun hubUrlParsesHostAndPort() {
        assertEquals(
            "hub-cup.mining-ling.ts.net" to 8787,
            ProbeCommands.parseHubTarget("http://hub-cup.mining-ling.ts.net:8787"),
        )
        assertEquals(
            "hub.example" to 443,
            ProbeCommands.parseHubTarget("wss://hub.example/socket"),
        )
        assertNull(ProbeCommands.parseHubTarget("  "))
    }
}
