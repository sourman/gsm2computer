package com.gsm2computer.bridge.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Once-a-second call trace. Runs on its own threads and never touches the
 * audio path. Failures here are logged and skipped; they do not end the call.
 *
 * Log directory (survives reboot, readable with adb root):
 *   {externalFilesDir}/call-trace/call-trace.jsonl
 * which on a Pixel is
 *   /storage/emulated/0/Android/data/com.gsm2computer.bridge/files/call-trace/call-trace.jsonl
 */
class CallTraceSampler private constructor(
    private val context: Context,
    private val log: CallTraceLog,
    private val extra: () -> Map<String, Any?>,
    private val hubUrl: String,
    private val intervalMs: Long,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val probes: ExecutorService = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "Call-Trace-Probe").apply { isDaemon = true }
    }

    private var lastTx: Long? = null
    private var lastRx: Long? = null
    private var lastTxErr: Long? = null

    fun event(kind: String, fields: Map<String, Any?> = emptyMap()) {
        try {
            log.event(kind, fields)
        } catch (e: Exception) {
            Log.w(TAG, "trace event $kind failed: ${e.message}")
        }
    }

    fun start() {
        if (running) return
        running = true
        registerNetworkCallback()
        thread = Thread({ loop() }, "Call-Trace").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        try {
            thread?.join(500)
        } catch (_: InterruptedException) {
        }
        unregisterNetworkCallback()
        probes.shutdownNow()
    }

    private fun loop() {
        while (running && !Thread.currentThread().isInterrupted) {
            val started = System.currentTimeMillis()
            try {
                log.event("sample", collect(), started)
            } catch (e: Exception) {
                Log.w(TAG, "trace sample failed: ${e.message}")
            }
            val remain = intervalMs - (System.currentTimeMillis() - started)
            if (remain > 0) {
                try {
                    Thread.sleep(remain)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun collect(): Map<String, Any?> {
        val fields = LinkedHashMap<String, Any?>()
        try {
            fields.putAll(extra())
        } catch (e: Exception) {
            fields["extra_error"] = e.message ?: e.javaClass.simpleName
        }
        val under = underlay()
        fields.putAll(under)
        fields.putAll(wifi(under["probe_iface"] as? String))
        fields.putAll(cell())
        fields.putAll(probes(under))
        return fields
    }

    private fun underlay(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            out["network_transports"] = "unknown"
            return out
        }
        return try {
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            val transports = ArrayList<String>(4)
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.add("WIFI")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.add("CELLULAR")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.add("ETHERNET")
                if (vpn) transports.add("VPN")
            }
            var probeNet: Network? = null
            for (net in cm.allNetworks) {
                val c = cm.getNetworkCapabilities(net) ?: continue
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    probeNet = net
                    break
                }
            }
            if (probeNet == null) {
                for (net in cm.allNetworks) {
                    val c = cm.getNetworkCapabilities(net) ?: continue
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        probeNet = net
                        break
                    }
                }
            }
            val lp = probeNet?.let { cm.getLinkProperties(it) }
            val gateway = lp?.routes
                ?.firstOrNull { route ->
                    route.isDefaultRoute && route.gateway?.hostAddress?.let { host ->
                        host.isNotEmpty() && host != "0.0.0.0" && host != "::"
                    } == true
                }
                ?.gateway
                ?.hostAddress
            out["network_transports"] = if (transports.isEmpty()) "none" else transports.joinToString("+")
            out["vpn_active"] = vpn
            out["network_id"] = active?.toString()
            out["probe_iface"] = lp?.interfaceName
            out["probe_gateway"] = gateway
            out
        } catch (e: Exception) {
            out["network_error"] = e.message ?: e.javaClass.simpleName
            out
        }
    }

    private fun wifi(probeIface: String?): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val info = wm?.connectionInfo
            if (info != null) {
                out["wifi_rssi"] = info.rssi
                val bssid = info.bssid
                if (!bssid.isNullOrBlank() && bssid != "02:00:00:00:00:00") {
                    out["wifi_bssid"] = bssid
                }
                out["wifi_link_mbps"] = info.linkSpeed
            }
        } catch (e: Exception) {
            out["wifi_error"] = e.message ?: e.javaClass.simpleName
        }
        val iface = when {
            probeIface?.startsWith("wlan") == true -> probeIface
            File("/sys/class/net/wlan0").isDirectory -> "wlan0"
            !probeIface.isNullOrBlank() -> probeIface
            else -> "wlan0"
        }
        val tx = readCounter(iface, "tx_packets")
        val rx = readCounter(iface, "rx_packets")
        val txErr = readCounter(iface, "tx_errors")
        val rxErr = readCounter(iface, "rx_errors")
        val retries = readCounter(iface, "tx_retries") ?: readCounter(iface, "tx_failed")
        if (tx != null) out["wifi_tx_packets"] = tx
        if (rx != null) out["wifi_rx_packets"] = rx
        if (txErr != null) out["wifi_tx_errors"] = txErr
        if (rxErr != null) out["wifi_rx_errors"] = rxErr
        if (retries != null) out["wifi_tx_retries"] = retries
        if (tx != null && lastTx != null) out["wifi_tx_delta"] = tx - lastTx!!
        if (rx != null && lastRx != null) out["wifi_rx_delta"] = rx - lastRx!!
        if (txErr != null && lastTxErr != null) out["wifi_tx_err_delta"] = txErr - lastTxErr!!
        lastTx = tx
        lastRx = rx
        lastTxErr = txErr
        return out
    }

    private fun cell(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return out
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                out["cell_rat"] = ratName(tm.dataNetworkType)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val strength = tm.signalStrength
                if (strength != null) {
                    out["cell_level"] = strength.level
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val dbm = strength.cellSignalStrengths.firstOrNull()?.dbm
                        if (dbm != null && dbm != CellInfo.UNAVAILABLE && dbm < 0) {
                            out["cell_dbm"] = dbm
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            out["cell_error"] = "permission"
        } catch (e: Exception) {
            out["cell_error"] = e.message ?: e.javaClass.simpleName
        }
        return out
    }

    private fun probes(under: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val iface = under["probe_iface"] as? String
        val gateway = under["probe_gateway"] as? String
        val hub = ProbeCommands.parseHubTarget(hubUrl)
        val gwResult = submitProbe {
            if (gateway.isNullOrBlank()) {
                ProbeResult(false, null, "no gateway")
            } else {
                icmp(gateway, iface)
            }
        }
        val pubResult = submitProbe { icmp(ProbeCommands.PUBLIC_IP, iface) }
        val hubResult = submitProbe {
            if (hub == null) {
                ProbeResult(false, null, "no hub url")
            } else {
                tcp(hub.first, hub.second)
            }
        }
        putProbe(out, "probe_gateway", gwResult)
        putProbe(out, "probe_public", pubResult)
        putProbe(out, "probe_hub", hubResult)
        out["probe_public_target"] = ProbeCommands.PUBLIC_IP
        if (hub != null) out["probe_hub_target"] = "${hub.first}:${hub.second}"
        return out
    }

    private fun submitProbe(block: () -> ProbeResult): ProbeResult {
        val task = probes.submit(Callable { block() })
        return try {
            task.get(PROBE_BUDGET_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            task.cancel(true)
            ProbeResult(false, null, e.javaClass.simpleName)
        }
    }

    private fun putProbe(out: MutableMap<String, Any?>, prefix: String, result: ProbeResult) {
        out["${prefix}_ok"] = result.ok
        if (result.rttMs != null) out["${prefix}_ms"] = result.rttMs
        if (!result.error.isNullOrBlank()) out["${prefix}_error"] = result.error
    }

    private fun icmp(host: String, iface: String?): ProbeResult {
        val proc = try {
            ProcessBuilder(ProbeCommands.ping(host, iface))
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            return ProbeResult(false, null, e.javaClass.simpleName)
        }
        return try {
            val finished = proc.waitFor(PROBE_BUDGET_MS, TimeUnit.MILLISECONDS)
            val text = proc.inputStream.bufferedReader().use { it.readText() }
            if (!finished) {
                proc.destroyForcibly()
                return ProbeResult(false, null, "timeout")
            }
            if (proc.exitValue() != 0) {
                return ProbeResult(false, null, "exit ${proc.exitValue()}")
            }
            val rtt = Regex("""time[=<]([0-9.]+)""").find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ProbeResult(true, rtt?.toLong(), null)
        } catch (e: Exception) {
            try {
                proc.destroyForcibly()
            } catch (_: Exception) {
            }
            ProbeResult(false, null, e.javaClass.simpleName)
        }
    }

    private fun tcp(host: String, port: Int): ProbeResult {
        val started = System.nanoTime()
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), PROBE_BUDGET_MS.toInt())
            val ms = (System.nanoTime() - started) / 1_000_000L
            ProbeResult(true, ms, null)
        } catch (e: Exception) {
            ProbeResult(false, null, e.javaClass.simpleName)
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                event("net_event", mapOf("callback" to "onAvailable", "network" to network.toString()))
            }

            override fun onLost(network: Network) {
                event("net_event", mapOf("callback" to "onLost", "network" to network.toString()))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val transports = ArrayList<String>(3)
                if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.add("WIFI")
                if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.add("CELLULAR")
                if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports.add("VPN")
                event(
                    "net_event",
                    mapOf(
                        "callback" to "onCapabilitiesChanged",
                        "network" to network.toString(),
                        "transports" to transports.joinToString("+"),
                    ),
                )
            }
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            callback = cb
        } catch (e: Exception) {
            Log.w(TAG, "network callback not registered: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = callback ?: return
        callback = null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    private data class ProbeResult(val ok: Boolean, val rttMs: Long?, val error: String?)

    companion object {
        private const val TAG = "CallTrace"
        private const val PROBE_BUDGET_MS = 800L

        fun start(
            context: Context,
            hubUrl: String,
            extra: () -> Map<String, Any?>,
        ): CallTraceSampler {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(base, CallTraceLog.DIR_NAME)
            val sampler = CallTraceSampler(context, CallTraceLog(dir), extra, hubUrl, 1_000L)
            sampler.start()
            Log.i(TAG, "call trace ${dir.absolutePath}/${CallTraceLog.FILE_NAME}")
            return sampler
        }

        private fun readCounter(iface: String, name: String): Long? {
            return try {
                File("/sys/class/net/$iface/statistics/$name").readText().trim().toLongOrNull()
            } catch (_: Exception) {
                null
            }
        }

        private fun ratName(type: Int): String = when (type) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "NR"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "unknown"
            else -> "other:$type"
        }
    }
}
