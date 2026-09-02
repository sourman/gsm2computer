package com.gsm2computer.bridge.bridge

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.util.Log
import com.gsm2computer.bridge.BridgeConfig
import com.gsm2computer.bridge.HubEndpoints
import com.gsm2computer.bridge.RootShell
import com.gsm2computer.bridge.audio.MicIsolationGuard
import com.gsm2computer.bridge.gsm.GsmCallManager
import com.gsm2computer.bridge.realtime.HubStreamClient
import com.gsm2computer.bridge.rtp.MediaTransport
import com.gsm2computer.bridge.rtp.RtpPacket
import com.gsm2computer.bridge.rtp.RtpSession

/**
 * Bridges GSM call audio to a remote hub over WebSocket ([MediaTransport]).
 *
 * Inbound flow: GSM rings → answer → open hub stream → full-duplex audio.
 */
class CallOrchestrator(
    private val context: Context,
) : GsmCallManager.Listener {

    private var activeSession: RtpSession? = null
    private var activeGsmCall: Call? = null
    @Volatile private var streamBridgePending = false
    @Volatile private var lastStateChangeTime = 0L

    @Volatile var bridgeState: BridgeState = BridgeState.IDLE
        private set

    @Volatile var listener: OrchestratorListener? = null

    interface OrchestratorListener {
        fun onStateChanged(state: BridgeState, info: String)
        fun onError(error: String)
        fun onStreamStats(stats: String) {}
    }

    enum class BridgeState {
        IDLE,
        GSM_RINGING,
        CONNECTING,
        BRIDGED,
        GSM_DIALING,
        TEARING_DOWN,
    }

    private fun resolveConfig(): BridgeConfig.Resolved =
        BridgeConfig.resolve(BridgeConfig.openPrefs(context))

    fun start() {
        GsmCallManager.listener = this
        Log.i(TAG, "CallOrchestrator started")
    }

    fun stop() {
        tearDown("Orchestrator stopped")
        GsmCallManager.listener = null
    }

    fun initiateDiallerCall(number: String) {
        if (bridgeState != BridgeState.IDLE) {
            val staleMs = System.currentTimeMillis() - lastStateChangeTime
            if (staleMs > STALE_STATE_TIMEOUT_MS) {
                forceReset("Stale state: $bridgeState for ${staleMs / 1000}s")
            } else {
                listener?.onError("Busy — cannot dial")
                return
            }
        }
        if (!resolveConfig().streamEnabled) {
            listener?.onError("Hub stream URL not configured")
            return
        }
        bridgeState = BridgeState.GSM_DIALING
        lastStateChangeTime = System.currentTimeMillis()
        listener?.onStateChanged(bridgeState, "Dialing $number")
        GsmCallManager.muteLocalEarpiece = true
        GsmCallManager.makeCall(context, number)
        Thread({
            Thread.sleep(GSM_DIAL_TIMEOUT_MS)
            if (bridgeState == BridgeState.GSM_DIALING) {
                tearDown("GSM dial timeout")
            }
        }, "GSM-Dial-Timeout").start()
    }

    override fun onIncomingGsmCall(call: Call, number: String) {
        if (bridgeState != BridgeState.IDLE) {
            GsmCallManager.rejectCall(call)
            return
        }
        if (!resolveConfig().streamEnabled) {
            Log.w(TAG, "Rejecting GSM call — hub stream not configured")
            GsmCallManager.rejectCall(call)
            listener?.onError("Hub stream URL not configured")
            return
        }

        bridgeState = BridgeState.GSM_RINGING
        activeGsmCall = call
        lastStateChangeTime = System.currentTimeMillis()
        listener?.onStateChanged(bridgeState, "GSM call from $number")

        streamBridgePending = true
        GsmCallManager.muteLocalEarpiece = true
        Thread({ GsmCallManager.answerCall(call) }, "AnswerGsm").start()
    }

    override fun onGsmCallActive(call: Call) {
        activeGsmCall = call
        if (streamBridgePending) {
            streamBridgePending = false
            Thread({ startStreamBridge() }, "Stream-Start").start()
            return
        }
        if (bridgeState == BridgeState.GSM_DIALING) {
            Thread({ startStreamBridge() }, "Stream-Start").start()
        }
    }

    override fun onGsmCallStateChanged(call: Call, state: Int) {
        if (activeGsmCall == null && bridgeState != BridgeState.IDLE) {
            activeGsmCall = call
        }
        if (state == Call.STATE_DISCONNECTED && bridgeState != BridgeState.IDLE) {
            tearDown("GSM call disconnected")
        }
    }

    override fun onGsmCallEnded(call: Call) {
        if (bridgeState != BridgeState.IDLE) {
            tearDown("GSM call ended")
        }
    }

    private fun startStreamBridge() {
        val cfg = resolveConfig()
        Log.i(
            TAG,
            "Opening hub stream (hub=${cfg.hubControlUrl} token=${cfg.streamTokenUrl} " +
                "model=${cfg.streamModel} voice=${cfg.streamVoice} hubOwned=${cfg.hubOwnedSession})",
        )
        bridgeState = BridgeState.CONNECTING
        listener?.onStateChanged(bridgeState, "Connecting to hub")

        val transport = HubStreamClient(
            tokenUrl = cfg.streamTokenUrl,
            webSocketUrl = HubEndpoints.webSocketUrl(cfg.hubControlUrl),
            model = cfg.streamModel,
            voice = cfg.streamVoice,
            instructions = HubStreamClient.DEFAULT_INSTRUCTIONS,
            hubOwnedSession = cfg.hubOwnedSession,
        )
        startAudioPump(RtpPacket.PT_PCMU, transport)

        if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) {
            return
        }
        bridgeState = BridgeState.BRIDGED
        listener?.onStateChanged(bridgeState, "Bridged to hub")
    }

    private fun startAudioPump(payloadType: Int, transport: MediaTransport) {
        forceAllowRecordAudio()

        val guard = MicIsolationGuard(context, GsmCallManager.profile)
        when (val iso = guard.verify { msg -> listener?.onStreamStats(msg) }) {
            is MicIsolationGuard.MicIsolationResult.NotIsolated -> {
                val err = "Mic not isolated (${"%.1f".format(iso.rmsDb)} dBFS)"
                listener?.onError(err)
                tearDown(err)
                return
            }
            MicIsolationGuard.MicIsolationResult.Isolated -> { }
        }

        activeSession?.stop()
        val session = RtpSession(context, 0, "hub-stream", 0, payloadType, transport)
        session.listener = object : RtpSession.Listener {
            override fun onRtpStarted() {
                Log.i(TAG, "Audio pump started")
            }
            override fun onRtpStopped() {
                Log.i(TAG, "Audio pump stopped")
            }
            override fun onRtpError(error: String) {
                listener?.onError("Audio: $error")
            }
            override fun onRtpTimeout() {
                tearDown("Hub stream timeout")
            }
            override fun onRtpStats(stats: String) {
                listener?.onStreamStats(stats)
            }
        }
        session.start()
        activeSession = session
    }

    @Synchronized
    private fun tearDown(reason: String) {
        if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) return
        bridgeState = BridgeState.TEARING_DOWN
        streamBridgePending = false
        Log.i(TAG, "Tearing down: $reason")

        try {
            activeSession?.stop()
            activeSession = null
            activeGsmCall?.let { call ->
                try {
                    call.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error disconnecting GSM: ${e.message}")
                }
            }
            activeGsmCall = null
        } finally {
            GsmCallManager.muteLocalEarpiece = false
            bridgeState = BridgeState.IDLE
            lastStateChangeTime = System.currentTimeMillis()
            listener?.onStateChanged(BridgeState.IDLE, reason)
        }
    }

    @Synchronized
    private fun forceReset(reason: String) {
        try {
            activeSession?.stop()
        } catch (_: Exception) {}
        activeSession = null
        try {
            activeGsmCall?.disconnect()
        } catch (_: Exception) {}
        activeGsmCall = null
        streamBridgePending = false
        GsmCallManager.muteLocalEarpiece = false
        bridgeState = BridgeState.IDLE
        lastStateChangeTime = System.currentTimeMillis()
        listener?.onStateChanged(BridgeState.IDLE, reason)
    }

    private fun forceAllowRecordAudio() {
        try {
            val pkg = context.packageName
            val autoRevoke = if (Build.VERSION.SDK_INT >= 30)
                "appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>&1; " else ""
            val uidFlag = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
            val result = RootShell.execForOutput(
                "killall com.google.android.permissioncontroller 2>/dev/null; " +
                    "killall com.android.permissioncontroller 2>/dev/null; " +
                    "pm grant $pkg android.permission.RECORD_AUDIO 2>&1; " +
                    autoRevoke +
                    "appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                    "appops set $pkg RECORD_AUDIO allow 2>&1; " +
                    "appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
            )
            if (!result.contains("allow", ignoreCase = true)) {
                RootShell.execForOutput(
                    "cmd appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                        "cmd appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "appops force-allow failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CallOrchestrator"
        private const val GSM_DIAL_TIMEOUT_MS = 45_000L
        private const val STALE_STATE_TIMEOUT_MS = 60_000L
    }
}
