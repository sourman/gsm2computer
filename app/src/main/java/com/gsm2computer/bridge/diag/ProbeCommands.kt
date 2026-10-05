package com.gsm2computer.bridge.diag

import java.net.URI

/**
 * Reachability probes used by the on-call trace.
 *
 * Gateway and public probes are ICMP bound to the non-VPN interface so a
 * Tailscale tunnel cannot answer for the local router or the WAN. The hub
 * probe is a TCP connect on the default route, which is the tailnet path.
 */
object ProbeCommands {
    const val PUBLIC_IP = "1.1.1.1"

    fun ping(host: String, iface: String?): List<String> {
        val cmd = mutableListOf("/system/bin/ping", "-n", "-c", "1", "-W", "1")
        if (!iface.isNullOrBlank()) {
            cmd.add("-I")
            cmd.add(iface)
        }
        cmd.add(host)
        return cmd
    }

    /** Host and port of the hub control URL. Blank or unparseable input is null. */
    fun parseHubTarget(url: String): Pair<String, Int>? {
        val raw = url.trim()
        if (raw.isEmpty()) return null
        val normalized = when {
            raw.startsWith("ws://") -> "http://" + raw.removePrefix("ws://")
            raw.startsWith("wss://") -> "https://" + raw.removePrefix("wss://")
            raw.contains("://") -> raw
            else -> "http://$raw"
        }
        val uri = try {
            URI(normalized)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host ?: return null
        if (host.isBlank()) return null
        val port = when {
            uri.port > 0 -> uri.port
            uri.scheme == "https" -> 443
            else -> 80
        }
        return host to port
    }
}
