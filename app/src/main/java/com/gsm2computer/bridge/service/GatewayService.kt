package com.gsm2computer.bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.gsm2computer.bridge.BridgeConfig
import com.gsm2computer.bridge.BuildConfig
import com.gsm2computer.bridge.MainActivity
import com.gsm2computer.bridge.MicCapabilityGuard
import com.gsm2computer.bridge.R
import com.gsm2computer.bridge.RootShell
import com.gsm2computer.bridge.bridge.CallOrchestrator
import com.gsm2computer.bridge.gsm.GsmCallManager
import kotlin.concurrent.thread

/**
 * Foreground service: keeps the GSM bridge ready and routes calls to the hub stream.
 */
class GatewayService : Service() {

    private var orchestrator: CallOrchestrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var onlineSince = 0L
    private var incomingCalls = 0
    private var incomingDurationSec = 0L
    private var outgoingCalls = 0
    private var outgoingDurationSec = 0L
    private var currentCallStart = 0L
    private var currentCallIncoming = true
    private var currentCallNumber = ""

    @Volatile private var stopped = false
    private var notifStatusText = "Ready"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        RootShell.init()
        Log.i(TAG, "GatewayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopGateway()
            ACTION_RELOAD_STATS -> reloadStats()
            ACTION_DIAL -> dialFromDialler(intent)
            ACTION_RELAUNCH_FROM_FG -> startGateway(forceRestart = true)
            else -> startGateway(forceRestart = intent?.action == ACTION_RELAUNCH_FROM_FG)
        }
        return START_STICKY
    }

    private fun dialFromDialler(intent: Intent?) {
        val number = intent?.getStringExtra(EXTRA_NUMBER) ?: return
        orchestrator?.initiateDiallerCall(number)
            ?: broadcastLog("ERROR: Bridge not running — cannot dial")
    }

    private fun reloadStats() {
        val totals = CallLogStore.getTotals(this)
        incomingCalls = totals.inCalls
        incomingDurationSec = totals.inDurationSec
        outgoingCalls = totals.outCalls
        outgoingDurationSec = totals.outDurationSec
        broadcastStatus(orchestrator?.bridgeState?.name ?: "IDLE", "Stats reloaded")
    }

    private fun startGateway(forceRestart: Boolean = false) {
        if (!forceRestart && !stopped && orchestrator != null) {
            val state = orchestrator?.bridgeState ?: CallOrchestrator.BridgeState.IDLE
            broadcastStatus(state.name, if (state == CallOrchestrator.BridgeState.IDLE) "Ready" else state.name)
            return
        }

        notifStatusText = "Ready"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(NotifState.OK, "Ready"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(NotifState.OK, "Ready"))
        }

        if (!forceRestart) {
            MicCapabilityGuard.startMonitor(this) { isCallActive() }
            if (MicCapabilityGuard.requestRelaunchIfNeeded(this, "service-start", inCall = isCallActive())) {
                return
            }
        }

        orchestrator?.stop()
        orchestrator = null
        stopped = false

        val cfg = BridgeConfig.resolve(BridgeConfig.openPrefs(this))
        if (!cfg.streamEnabled) {
            broadcastLog("ERROR: Hub control URL not configured")
            broadcastStatus("ERROR", "Missing hub URL")
            stopSelf()
            return
        }

        onlineSince = System.currentTimeMillis()
        val totals = CallLogStore.getTotals(this)
        incomingCalls = totals.inCalls
        incomingDurationSec = totals.inDurationSec
        outgoingCalls = totals.outCalls
        outgoingDurationSec = totals.outDurationSec
        currentCallStart = 0L

        acquireLocks()

        thread(name = "bridge-init") {
            forceAllowRecordAudio()
            initOrchestrator(cfg)
        }
    }

    private fun initOrchestrator(cfg: BridgeConfig.Resolved) {
        if (stopped) return

        val orch = CallOrchestrator(this)
        orch.listener = object : CallOrchestrator.OrchestratorListener {
            override fun onStateChanged(state: CallOrchestrator.BridgeState, info: String) {
                Log.i(TAG, "Bridge: $state - $info")

                when (state) {
                    CallOrchestrator.BridgeState.GSM_RINGING -> {
                        currentCallIncoming = true
                        currentCallNumber = info.removePrefix("GSM call from ")
                    }
                    CallOrchestrator.BridgeState.GSM_DIALING -> {
                        currentCallIncoming = false
                        currentCallNumber = info.removePrefix("Dialing ")
                    }
                    else -> {}
                }

                if (state == CallOrchestrator.BridgeState.BRIDGED && currentCallStart == 0L) {
                    currentCallStart = System.currentTimeMillis()
                    if (currentCallIncoming) incomingCalls++ else outgoingCalls++
                }
                if (state == CallOrchestrator.BridgeState.IDLE && currentCallStart != 0L) {
                    val dur = (System.currentTimeMillis() - currentCallStart) / 1000
                    if (currentCallIncoming) incomingDurationSec += dur else outgoingDurationSec += dur
                    CallLogStore.addEntry(
                        this@GatewayService,
                        CallLogEntry(
                            direction = if (currentCallIncoming) "IN" else "OUT",
                            number = currentCallNumber,
                            timestamp = currentCallStart,
                            durationSec = dur
                        )
                    )
                    currentCallStart = 0L
                    currentCallNumber = ""
                }

                val (notifState, statusText) = when (state) {
                    CallOrchestrator.BridgeState.IDLE -> NotifState.OK to "Ready"
                    CallOrchestrator.BridgeState.GSM_DIALING -> NotifState.OK to "Dialing"
                    CallOrchestrator.BridgeState.BRIDGED -> NotifState.OK to "In call"
                    CallOrchestrator.BridgeState.GSM_RINGING,
                    CallOrchestrator.BridgeState.CONNECTING,
                    CallOrchestrator.BridgeState.TEARING_DOWN -> NotifState.OK to "In call"
                }
                updateNotification(notifState, statusText)
                broadcastStatus(state.name, info)
            }

            override fun onError(error: String) {
                broadcastLog("ERROR: $error")
                broadcastStatus("ERROR", error)
            }

            override fun onStreamStats(stats: String) {
                broadcastLog("STREAM: $stats")
            }
        }
        orchestrator = orch
        GsmCallManager.logCallback = { msg -> broadcastLog("AUDIO: $msg") }
        orch.start()
        val dest = cfg.hubControlUrl.ifBlank { cfg.streamTokenUrl }
        broadcastLog("[v${BuildConfig.VERSION_NAME}] Bridge ready → $dest")
        broadcastStatus("IDLE", "Ready for calls")
    }

    private fun isCallActive(): Boolean {
        val state = orchestrator?.bridgeState ?: return false
        return state != CallOrchestrator.BridgeState.IDLE
    }

    private fun stopGateway() {
        if (stopped) return
        stopped = true
        onlineSince = 0L
        orchestrator?.stop()
        orchestrator = null
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        broadcastStatus("STOPPED", "Bridge stopped")
    }

    override fun onDestroy() {
        stopGateway()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private enum class NotifState { OK, WARN, ERROR }

    private fun buildNotification(state: NotifState = NotifState.OK, statusText: String = notifStatusText): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val icon = when (state) {
            NotifState.OK -> R.drawable.ic_notif_check
            NotifState.WARN -> R.drawable.ic_notif_warning
            NotifState.ERROR -> R.drawable.ic_notif_cross
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(statusText)
            .setSmallIcon(icon)
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
            .apply { flags = flags or Notification.FLAG_NO_CLEAR }
    }

    private fun updateNotification(state: NotifState = NotifState.OK, statusText: String? = null) {
        if (statusText != null) notifStatusText = statusText
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gsm2computer:bridge").apply { acquire() }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "gsm2computer:wifi").apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun broadcastStatus(state: String, info: String) {
        sendBroadcast(
            Intent(STATUS_ACTION).apply {
                setPackage(packageName)
                putExtra("state", state)
                putExtra("info", info)
                putExtra("online_since", onlineSince)
                putExtra("in_calls", incomingCalls)
                putExtra("in_duration", incomingDurationSec)
                putExtra("out_calls", outgoingCalls)
                putExtra("out_duration", outgoingDurationSec)
            }
        )
    }

    private fun broadcastLog(msg: String) {
        appendLog(this, msg)
    }

    private fun forceAllowRecordAudio() {
        try {
            val pkg = packageName
            val maxWaitMs = 90_000L
            val waitStart = System.currentTimeMillis()
            val uidFlag = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
            while (System.currentTimeMillis() - waitStart < maxWaitMs) {
                val probe = RootShell.execForOutput("appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1")
                if (!probe.contains("Can't find service", ignoreCase = true)) break
                Thread.sleep(3_000)
            }
            val autoRevoke = if (Build.VERSION.SDK_INT >= 30)
                "appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>&1; " else ""
            RootShell.execForOutput(
                "pm grant $pkg android.permission.RECORD_AUDIO 2>&1; " +
                    autoRevoke +
                    "appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                    "appops set $pkg RECORD_AUDIO allow 2>&1"
            )
        } catch (e: Exception) {
            Log.w(TAG, "appops force-allow failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "GatewayService"
        private const val LOG_BUFFER_SIZE = 200
        val logBuffer = mutableListOf<String>()

        fun drainLogBuffer(): List<String> = synchronized(logBuffer) {
            logBuffer.toList().also { logBuffer.clear() }
        }

        /** Append to the in-app log from anywhere (SMS forwarder, etc.). */
        fun appendLog(context: Context, msg: String) {
            synchronized(logBuffer) {
                logBuffer.add(msg)
                if (logBuffer.size > LOG_BUFFER_SIZE) logBuffer.removeAt(0)
            }
            context.sendBroadcast(
                Intent(LOG_ACTION).apply {
                    setPackage(context.packageName)
                    putExtra("msg", msg)
                }
            )
        }

        const val CHANNEL_ID = "gsm2computer_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.gsm2computer.bridge.START"
        const val ACTION_STOP = "com.gsm2computer.bridge.STOP"
        const val ACTION_RELOAD_STATS = "com.gsm2computer.bridge.RELOAD_STATS"
        const val ACTION_DIAL = "com.gsm2computer.bridge.DIAL"
        const val ACTION_RELAUNCH_FROM_FG = "com.gsm2computer.bridge.RELAUNCH_FROM_FG"
        const val EXTRA_NUMBER = "number"
        const val STATUS_ACTION = "com.gsm2computer.bridge.STATUS"
        const val LOG_ACTION = "com.gsm2computer.bridge.LOG"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, GatewayService::class.java).apply { action = ACTION_START }
            )
        }

        fun relaunchFromForeground(context: Context) {
            context.startForegroundService(
                Intent(context, GatewayService::class.java).apply { action = ACTION_RELAUNCH_FROM_FG }
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, GatewayService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
