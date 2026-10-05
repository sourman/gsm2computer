package com.gsm2computer.bridge.bridge

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.util.Log
import android.telecom.DisconnectCause
import com.gsm2computer.bridge.BridgeConfig
import com.gsm2computer.bridge.HubEndpoints
import com.gsm2computer.bridge.RootShell
import com.gsm2computer.bridge.audio.MicIsolationGuard
import com.gsm2computer.bridge.diag.CallTraceSampler
import com.gsm2computer.bridge.gsm.GsmCallManager
import com.gsm2computer.bridge.realtime.HubStreamClient
import com.gsm2computer.bridge.rtp.MediaTransport
import com.gsm2computer.bridge.rtp.RtpPacket
import com.gsm2computer.bridge.rtp.RtpSession

/**
 * Bridges GSM call audio to a remote hub over WebSocket ([MediaTransport]).
 *
 * Inbound flow: GSM rings → answer → open hub stream → full-duplex audio.
 *
 * One live call (ADR 0004). A waiting GSM leg is rejected; its teardown
 * must not hang up the bridged call or restore the mixer.
 */
class CallOrchestrator(
    private val context: Context,
) : GsmCallManager.Listener {

    private var activeSession: RtpSession? = null
    private var activeGsmCall: Call? = null
    @Volatile private var activeClient: HubStreamClient? = null
    private var callTrace: CallTraceSampler? = null
    @Volatile private var streamBridgePending = false
    @Volatile private var lastStateChangeTime = 0L
    @Volatile private var traceTelecomState: String = ""
    @Volatile private var traceDisconnect: String = ""

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
        LINK_HOLD,
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
        traceTelecomState = "DIALING"
        beginCallTrace()
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
        if (call === activeGsmCall) {
            return
        }
        if (bridgeState != BridgeState.IDLE) {
            Log.w(TAG, "Rejecting waiting GSM call from $number (live $bridgeState)")
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
        noteTelecom(call)
        beginCallTrace()
        listener?.onStateChanged(bridgeState, "GSM call from $number")

        streamBridgePending = true
        GsmCallManager.muteLocalEarpiece = true
        Thread({ GsmCallManager.answerCall(call) }, "AnswerGsm").start()
    }

    override fun onGsmCallActive(call: Call) {
        if (activeGsmCall != null && call !== activeGsmCall) {
            Log.w(TAG, "Ignoring STATE_ACTIVE for non-live GSM call")
            GsmCallManager.rejectCall(call)
            return
        }
        activeGsmCall = call
        noteTelecom(call)
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
        if (call !== activeGsmCall) {
            return
        }
        noteTelecom(call)
        if (state == Call.STATE_DISCONNECTED && bridgeState != BridgeState.IDLE) {
            tearDown("GSM call disconnected")
        }
    }

    override fun onGsmCallEnded(call: Call) {
        if (call !== activeGsmCall) {
            Log.i(TAG, "Waiting/rejected GSM call ended; live call unchanged")
            return
        }
        noteTelecom(call)
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
            linkGraceMs = cfg.hubLinkGraceMs,
            onTrace = { kind, fields -> callTrace?.event(kind, fields) },
        )
        activeClient = transport
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
            override fun onRtpTimeout(reason: String) {
                tearDown(reason.ifBlank { "Hub stream timeout" })
            }
            override fun onLinkHold(holding: Boolean, detail: String) {
                onHubLinkHold(holding, detail)
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
        val party = if (reason.startsWith("GSM call")) partyFromCause(activeGsmCall) else "local"
        Log.i(TAG, "Tearing down: $reason end=$party cause=$traceDisconnect")
        callTrace?.event(
            "teardown",
            mapOf(
                "reason" to reason,
                "end" to party,
                "disconnect_cause" to traceDisconnect,
                "telecom_state" to traceTelecomState,
                "call_state" to bridgeState.name,
            ),
        )
        bridgeState = BridgeState.TEARING_DOWN
        streamBridgePending = false

        try {
            activeSession?.stop()
            activeSession = null
            activeClient = null
            val gsm = activeGsmCall
            if (gsm != null) {
                try {
                    if (gsm.state != Call.STATE_DISCONNECTED) {
                        gsm.disconnect()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error disconnecting GSM: ${e.message}")
                }
            }
            activeGsmCall = null
        } finally {
            GsmCallManager.muteLocalEarpiece = false
            bridgeState = BridgeState.IDLE
            lastStateChangeTime = System.currentTimeMillis()
            stopCallTrace()
            listener?.onStateChanged(BridgeState.IDLE, reason)
        }
    }

    @Synchronized
    private fun onHubLinkHold(holding: Boolean, detail: String) {
        if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) return
        if (holding) {
            if (bridgeState != BridgeState.LINK_HOLD) {
                bridgeState = BridgeState.LINK_HOLD
                lastStateChangeTime = System.currentTimeMillis()
                listener?.onStateChanged(BridgeState.LINK_HOLD, detail)
            }
            return
        }
        if (bridgeState == BridgeState.LINK_HOLD || bridgeState == BridgeState.CONNECTING) {
            bridgeState = BridgeState.BRIDGED
            lastStateChangeTime = System.currentTimeMillis()
            listener?.onStateChanged(BridgeState.BRIDGED, detail)
        }
    }

    @Synchronized
    private fun beginCallTrace() {
        if (callTrace != null) return
        try {
            val cfg = resolveConfig()
            callTrace = CallTraceSampler.start(context, cfg.hubControlUrl) {
                mapOf(
                    "call_state" to bridgeState.name,
                    "telecom_state" to traceTelecomState,
                    "disconnect_cause" to traceDisconnect,
                    "ws_state" to (activeClient?.linkState ?: ""),
                    "ws_rtt_ms" to (activeClient?.lastRttMs?.takeIf { it >= 0L }),
                    "ws_session_id" to (activeClient?.sessionId?.takeIf { it.isNotBlank() }),
                )
            }
            callTrace?.event(
                "call_start",
                mapOf("grace_ms" to cfg.hubLinkGraceMs, "call_state" to bridgeState.name),
            )
        } catch (e: Exception) {
            Log.w(TAG, "call trace disabled: ${e.message}")
            callTrace = null
        }
    }

    @Synchronized
    private fun stopCallTrace() {
        val trace = callTrace
        callTrace = null
        try {
            trace?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "call trace stop failed: ${e.message}")
        }
    }

    private fun noteTelecom(call: Call?) {
        traceTelecomState = telecomLabel(call?.state ?: -1)
        traceDisconnect = disconnectLabel(call)
    }

    private fun partyFromCause(call: Call?): String {
        val code = try {
            call?.details?.disconnectCause?.code
        } catch (_: Exception) {
            null
        }
        return when (code) {
            DisconnectCause.LOCAL, DisconnectCause.CANCELED, DisconnectCause.MISSED -> "local"
            null, DisconnectCause.UNKNOWN -> "unknown"
            else -> "remote"
        }
    }

    @Synchronized
    private fun forceReset(reason: String) {
        try {
            activeSession?.stop()
        } catch (_: Exception) {}
        activeSession = null
        activeClient = null
        try {
            activeGsmCall?.disconnect()
        } catch (_: Exception) {}
        activeGsmCall = null
        streamBridgePending = false
        GsmCallManager.muteLocalEarpiece = false
        bridgeState = BridgeState.IDLE
        lastStateChangeTime = System.currentTimeMillis()
        callTrace?.event("teardown", mapOf("reason" to reason, "end" to "local"))
        stopCallTrace()
        listener?.onStateChanged(BridgeState.IDLE, reason)
    }

    private fun telecomLabel(state: Int): String = when (state) {
        Call.STATE_NEW -> "NEW"
        Call.STATE_RINGING -> "RINGING"
        Call.STATE_DIALING -> "DIALING"
        Call.STATE_ACTIVE -> "ACTIVE"
        Call.STATE_HOLDING -> "HOLDING"
        Call.STATE_DISCONNECTED -> "DISCONNECTED"
        Call.STATE_CONNECTING -> "CONNECTING"
        Call.STATE_DISCONNECTING -> "DISCONNECTING"
        Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
        else -> "UNKNOWN"
    }

    private fun disconnectLabel(call: Call?): String {
        val cause = try {
            call?.details?.disconnectCause
        } catch (_: Exception) {
            null
        } ?: return ""
        val name = when (cause.code) {
            DisconnectCause.UNKNOWN -> "UNKNOWN"
            DisconnectCause.ERROR -> "ERROR"
            DisconnectCause.LOCAL -> "LOCAL"
            DisconnectCause.REMOTE -> "REMOTE"
            DisconnectCause.CANCELED -> "CANCELED"
            DisconnectCause.MISSED -> "MISSED"
            DisconnectCause.REJECTED -> "REJECTED"
            DisconnectCause.BUSY -> "BUSY"
            DisconnectCause.RESTRICTED -> "RESTRICTED"
            DisconnectCause.OTHER -> "OTHER"
            DisconnectCause.CONNECTION_MANAGER_NOT_SUPPORTED -> "CONNECTION_MANAGER_NOT_SUPPORTED"
            DisconnectCause.ANSWERED_ELSEWHERE -> "ANSWERED_ELSEWHERE"
            DisconnectCause.CALL_PULLED -> "CALL_PULLED"
            else -> "CODE_${cause.code}"
        }
        val reason = cause.reason?.take(80).orEmpty()
        return if (reason.isEmpty()) name else "$name:$reason"
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
