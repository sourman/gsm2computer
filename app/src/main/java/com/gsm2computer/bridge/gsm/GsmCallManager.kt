package com.gsm2computer.bridge.gsm

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import com.gsm2computer.bridge.DeviceProfile
import com.gsm2computer.bridge.RootShell

/**
 * GSM call manager: answers/makes/hangs up GSM calls, tracks state.
 *
 * Calls are controlled through the InCallService (GsmCallService).
 * Audio routing uses device-specific mixer controls via [DeviceProfile].
 *
 * SIP→GSM: AudioTrack (USAGE_MEDIA / deep-buffer) → incall_music →
 * HAL injects STREAM_MUSIC digitally into voice TX (uplink).
 *
 * GSM→SIP: VOICE_CALL capture provides digital uplink+downlink audio.
 *
 * ALL tinymix commands are batched into a single su call to minimise
 * JVM spawning on low-end devices.
 */
object GsmCallManager {

    private const val TAG = "GsmCallManager"

    /** Active device profile — initialized on first use. */
    val profile: DeviceProfile by lazy { DeviceProfile.detect() }

    // The bridged GSM call only. A waiting/rejected leg must not replace this.
    @Volatile var activeCall: Call? = null; private set
    @Volatile var activeCallState: Int = Call.STATE_NEW; private set
    @Volatile var inCallService: InCallService? = null; private set

    @Volatile var listener: Listener? = null

    /** Optional callback for routing important audio diagnostics to the
     *  app log viewer (Settings tab).  Set by GatewayService. */
    @Volatile var logCallback: ((String) -> Unit)? = null

    /** Set when [batchMixerSetup] finishes; RtpSession waits past this before capture. */
    @Volatile var mixerSetupCompletedAtMs: Long = 0

    /**
     * Relay mode: silence the gateway's own earpiece so the caller's voice isn't
     * audible on the gateway phone. In WS-direct mode the gateway needs no local
     * call audio (caller is captured via VOICE_DOWNLINK, the agent is injected
     * via STREAM_MUSIC/incall_music), so STREAM_VOICE_CALL is forced to 0. Set by
     * the orchestrator before answering; reset on call end.
     */
    @Volatile var muteLocalEarpiece = false

    /** Log to both Android logcat AND the app log viewer. */
    private fun appLog(msg: String) {
        Log.i(TAG, msg)
        logCallback?.invoke(msg)
    }

    interface Listener {
        /** Incoming GSM call ringing — caller number provided */
        fun onIncomingGsmCall(call: Call, number: String)
        /** GSM call connected (active) */
        fun onGsmCallActive(call: Call)
        /** GSM call state changed */
        fun onGsmCallStateChanged(call: Call, state: Int)
        /** GSM call ended */
        fun onGsmCallEnded(call: Call)
    }

    // ── InCallService callbacks ─────────────────────────

    fun onCallAdded(call: Call, service: InCallService) {
        inCallService = service
        val waiting = isWaitingGsmCall(activeCall, call)
        if (!waiting) {
            activeCall = call
            activeCallState = call.state
        }

        val number = call.details?.handle?.schemeSpecificPart ?: "unknown"

        when (call.state) {
            Call.STATE_RINGING -> {
                Log.i(TAG, "Incoming GSM call from $number waiting=$waiting")
                // Silence the ringtone immediately — this is a gateway device,
                // not a user-facing phone.  The call will be auto-answered
                // once the SIP leg is established.
                try {
                    val am = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "Ringer silence failed: ${e.message}")
                }
                if (listener != null) {
                    listener?.onIncomingGsmCall(call, number)
                } else if (!waiting) {
                    notifyStandaloneDialer(service, number)
                }
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                if (waiting) {
                    Log.w(TAG, "Ignoring outgoing GSM $number; live call already tracked")
                    return
                }
                Log.i(TAG, "Outgoing GSM call to $number")
            }
            Call.STATE_ACTIVE -> {
                if (waiting) {
                    Log.w(TAG, "Second GSM call $number went ACTIVE; not stealing mixer")
                    listener?.onIncomingGsmCall(call, number)
                    return
                }
                Log.i(TAG, "GSM call active: $number")
                configureAudioBridge()
                listener?.onGsmCallActive(call)
            }
        }
    }

    fun onCallRemoved(call: Call) {
        if (isWaitingGsmCall(activeCall, call)) {
            Log.i(TAG, "Non-live GSM call removed; live call unchanged")
            return
        }
        Log.i(TAG, "GSM call removed")
        if (activeCall === call) {
            activeCall = null
            activeCallState = Call.STATE_DISCONNECTED
        }
        restoreAudio()
        listener?.onGsmCallEnded(call)
    }

    fun onCallStateChanged(call: Call, state: Int) {
        val waiting = isWaitingGsmCall(activeCall, call)
        if (!waiting) {
            activeCallState = state
        }

        when (state) {
            Call.STATE_RINGING -> {
                // Handle calls that arrive as STATE_NEW in onCallAdded and
                // transition to RINGING via the callback.  Without this,
                // the orchestrator never learns about the incoming call.
                val number = call.details?.handle?.schemeSpecificPart ?: "unknown"
                Log.i(TAG, "GSM call ringing: $number (via state change) waiting=$waiting")
                if (listener != null) {
                    listener?.onIncomingGsmCall(call, number)
                } else if (!waiting) {
                    inCallService?.let { notifyStandaloneDialer(it, number) }
                }
            }
            Call.STATE_ACTIVE -> {
                if (waiting) {
                    Log.w(TAG, "Ignoring STATE_ACTIVE for non-live GSM call")
                    return
                }
                Log.i(TAG, "GSM call active")
                configureAudioBridge()
                listener?.onGsmCallActive(call)
            }
            Call.STATE_DISCONNECTED -> {
                if (waiting) {
                    Log.i(TAG, "Non-live GSM call disconnected; live call unchanged")
                    return
                }
                Log.i(TAG, "GSM call disconnected")
                listener?.onGsmCallEnded(call)
                if (activeCall === call) {
                    activeCall = null
                }
            }
        }
        if (!waiting) {
            listener?.onGsmCallStateChanged(call, state)
        }
    }

    // ── Call control ────────────────────────────────────

    /** Answer a ringing GSM call */
    fun answerCall(call: Call? = activeCall) {
        call?.let {
            Log.i(TAG, "Answering GSM call")
            it.answer(it.details.videoState)
        }
    }

    private fun notifyStandaloneDialer(context: Context, number: String) {
        // Gateway off — launch MainActivity as a standalone dialer
        val intent = Intent(context, Class.forName("com.gsm2computer.bridge.MainActivity")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("incoming_call", true)
            putExtra("number", number)
        }
        context.startActivity(intent)
    }

    /** Reject a ringing GSM call */
    fun rejectCall(call: Call? = activeCall) {
        call?.let {
            Log.i(TAG, "Rejecting GSM call")
            it.reject(false, "")
        }
    }

    /** Hang up active GSM call */
    fun hangupCall(call: Call? = activeCall) {
        call?.let {
            Log.i(TAG, "Hanging up GSM call")
            it.disconnect()
        }
    }

    /** Place outgoing GSM call via the SIM */
    fun makeCall(context: Context, destination: String) {
        Log.i(TAG, "Making GSM call to $destination")
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$destination"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ── Standalone Dialer Controls ──────────────────────

    fun setSpeakerMode(enabled: Boolean) {
        if (enabled) {
            inCallService?.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
            Log.i(TAG, "Standalone: Speaker route selected")
        } else {
            val supported = inCallService?.callAudioState?.supportedRouteMask ?: 0
            val newRoute = when {
                (supported and CallAudioState.ROUTE_WIRED_HEADSET) != 0 -> CallAudioState.ROUTE_WIRED_HEADSET
                (supported and CallAudioState.ROUTE_BLUETOOTH) != 0 -> CallAudioState.ROUTE_BLUETOOTH
                else -> CallAudioState.ROUTE_EARPIECE
            }
            inCallService?.setAudioRoute(newRoute)
            Log.i(TAG, "Standalone: Earpiece/Headset route selected ($newRoute)")
        }
    }

    fun setMuteMode(muted: Boolean) {
        inCallService?.setMuted(muted)
        Log.i(TAG, "Standalone: Mute state set to $muted")
    }

    fun playDtmfTone(c: Char) {
        activeCall?.playDtmfTone(c)
    }

    fun stopDtmfTone() {
        activeCall?.stopDtmfTone()
    }

    /** Music volume percent — from device profile. */
    val MUSIC_VOL_PERCENT: Int get() = profile.audio.musicVolPercent

    /** Run mixer discovery once on first audio bridge setup. */
    @Volatile private var discoveryDone = false

    private fun runMixerDiscovery() {
        if (discoveryDone) return
        discoveryDone = true
        Thread({
            try {
                val discovery = DeviceProfile.discoverMixerControls()
                for (line in discovery.lines()) {
                    if (line.isNotBlank()) Log.i(TAG, "MixerDiscovery: $line")
                }
                // Send summary to app log viewer (not the full dump)
                val cards = discovery.lines()
                    .filter { it.contains("[") && it.contains("]") && it.contains(":") }
                    .joinToString(", ") { it.trim() }
                val tinymix = if (DeviceProfile.tinymixBin.isNotEmpty())
                    DeviceProfile.tinymixBin else "NOT FOUND"
                appLog("ALSA: tinymix=$tinymix cards=[$cards]")
            } catch (e: Exception) {
                appLog("Mixer discovery failed: ${e.message}")
            }
        }, "MixerDiscovery").start()
    }

    /** Configure audio for GSM↔SIP bridge using the active device profile. */
    private fun configureAudioBridge() {
        if (listener == null) return // Standalone mode: let Android handle audio routing natively
        
        mixerSetupCompletedAtMs = 0
        try {
            // Run ABOX/ALSA discovery on first call for diagnostics
            runMixerDiscovery()

            inCallService?.let { service ->
                val audioManager = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

                // Samsung HAL params (incall_music_enabled, g_call_path, etc.)
                // are NOT set here — they fire in RtpSession.enableIncallMusic()
                // AFTER the AudioRecord is running.  Setting them before capture
                // kills VOICE_CALL capture (confirmed v2.8.33).

                if (profile.routing.requireSpeakerMode) {
                    service.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                }

                audioManager?.let { am ->
                    // Samsung Exynos HAL treats API mic mute as full uplink mute
                    // (blocks incall_music injection).  Pixel/Tensor use tinymix +
                    // INCALL_TX and can safely mute the physical mic here.
                    if (profile.routing.muteMicrophoneAtApi) {
                        am.isMicrophoneMute = true
                    } else {
                        am.isMicrophoneMute = false
                    }
                    enforceVolumes(am)

                    // Delay mixer/volume setup until speaker route change settles.
                    Thread({
                        try {
                            Thread.sleep(profile.routing.routeChangeDelayMs)
                            enforceVolumes(am)
                            batchMixerSetup()
                        } catch (_: Exception) {}
                    }, "VolEnforce").start()

                    // Samsung Exynos re-route dance REMOVED (v2.8.39):
                    // v2.8.38 tried earpiece→speaker re-route at t=3s to force HAL
                    // voice path recreation with incall_music ausage.  Results:
                    //   - Audio moved from speaker to earpiece and STAYED there
                    //   - 300ms delay was insufficient for route to settle
                    //   - No incall_music mixer controls exist on Exynos 9820 anyway
                    //     (confirmed: 1267 tinymix controls, zero match incall/inject)
                    //   - No ausage config files on this firmware
                    // The re-route served no purpose and broke speaker mode.

                    val tinymixStatus = if (DeviceProfile.tinymixBin.isNotEmpty()) "available" else "NOT FOUND"
                    val route = if (profile.routing.requireSpeakerMode) "speaker" else "earpiece"
                    appLog("Audio bridge: $route, mode=${am.mode}, tinymix=$tinymixStatus, profile=${profile.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure audio: ${e.message}")
        }
    }

    /** Set audio stream volumes for the GSM↔SIP bridge.
     *  Called multiple times: immediately, after delayed route change,
     *  and from RtpSession as a secondary safeguard. */
    fun enforceVolumes(am: AudioManager) {
        // Clear any stale ADJUST_MUTE flag from a previous call.
        // CRITICAL: Do NOT use ADJUST_MUTE on STREAM_VOICE_CALL — on
        // MSM8930 it kills the incall_music injection path, preventing
        // the agent's audio from reaching the GSM caller.  Speaker
        // silencing is handled by muteVoiceRx() at the ALSA mixer level.
        try {
            am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: SecurityException) {}
        // Voice call volume: controls caller's voice on speaker.
        // MSM8930: minimum (1) — speaker silenced by muteVoiceRx via tinymix.
        // Exynos 9820: 80% — no muteVoiceRx, need loud speaker for mic capture.
        // Volume=0 can confuse audio policy into treating call as inactive.
        try {
            val vcVol = if (muteLocalEarpiece) {
                // Relay mode: mute the local earpiece entirely. Capture uses
                // VOICE_DOWNLINK and injection uses STREAM_MUSIC, so nothing here
                // depends on STREAM_VOICE_CALL being audible.
                0
            } else if (profile.audio.voiceCallVolPercent > 0) {
                val maxVc = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                (maxVc * profile.audio.voiceCallVolPercent / 100).coerceAtLeast(1)
            } else {
                1
            }
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, vcVol, 0)
        } catch (_: SecurityException) {}
        // Music stream controls incall_music injection level into
        // the modem uplink.  Lower value = quieter speaker + quieter
        // agent voice for the GSM caller.
        val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val musicVol = (maxMusic * MUSIC_VOL_PERCENT / 100).coerceAtLeast(1)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, musicVol, 0)
        // Read back actual values to confirm they stuck
        val actualVoice = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        val actualMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val muted = am.isStreamMute(AudioManager.STREAM_VOICE_CALL)
        appLog("Vol: voice=$actualVoice(m=$muted), music=$actualMusic/$maxMusic(target=$musicVol)")
    }

    /** Restore audio state when call ends */
    private fun restoreAudio() {
        muteLocalEarpiece = false
        if (listener == null) return // Standalone mode

        try {
            mixerSetupCompletedAtMs = 0
            // Single su call to restore all mixer controls
            batchMixerRestore()

            inCallService?.let { service ->
                service.setAudioRoute(CallAudioState.ROUTE_EARPIECE)

                val audioManager = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.let { am ->
                    am.isMicrophoneMute = false
                    // Clear incall_music HAL parameter for clean state on next call
                    if (profile.routing.incallMusicParam.isNotEmpty()) {
                        am.setParameters("${profile.routing.incallMusicParam}=false")
                    }

                    // Unmute voice call stream and restore volume for normal phone use
                    try {
                        am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
                    } catch (_: SecurityException) {}
                    try {
                        val maxVc = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                        am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVc * 2 / 3).coerceAtLeast(1), 0)
                    } catch (_: SecurityException) {}
                    Log.i(TAG, "Audio restored: earpiece, VoiceRx unmuted, echoRef=SLIM_RX, incall_music=false")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore audio: ${e.message}")
        }
    }

    /**
     * Set up mixer controls for audio bridge using the device profile.
     * All commands batched into a single su call for efficiency.
     *
     * Commands reference bare 'tinymix' — [DeviceProfile.resolveCmd] replaces
     * it with the discovered full path at runtime.
     */
    fun batchMixerSetup() {
        if (profile.mixer.mixerSetupCmd.isEmpty()) {
            appLog("Mixer: no commands for ${profile.name}")
            return
        }
        val resolvedSetup = DeviceProfile.resolveCmd(profile.mixer.mixerSetupCmd)
        if (resolvedSetup.isEmpty()) {
            appLog("Mixer: tinymix NOT FOUND — cannot set controls for ${profile.name}")
            return
        }
        try {
            val bin = DeviceProfile.tinymixBin
            
            // Step 1: Readback BEFORE — see what HAL set during call setup
            // Only on Samsung ABOX devices to avoid "control not found" noise on Qualcomm
            if (profile.routing.isAbox) {
                val before = RootShell.execForOutput(buildString {
                    append("echo 'NSRC0B:'; $bin 'ABOX NSRC0 Bridge' 2>&1; ")
                    append("echo 'NSRC1B:'; $bin 'ABOX NSRC1 Bridge' 2>&1; ")
                    append("echo 'NSRC0:'; $bin 'ABOX NSRC0' 2>&1; ")
                    append("echo 'NSRC1:'; $bin 'ABOX NSRC1' 2>&1; ")
                    append("echo 'SPUS0:'; $bin 'ABOX SPUS OUT0' 2>&1")
                }, timeoutMs = 8000)
                appLog("Mixer BEFORE: $before")
            }
            // Step 2: Run mixer setup commands (all ABOX controls on card 0)
            // Use execForOutput to capture discovery/diagnostic output from setup commands
            val setupOutput = RootShell.execForOutput(resolvedSetup, timeoutMs = 8000)
            if (setupOutput.isNotBlank()) appLog("Mixer setup: $setupOutput")
            mixerSetupCompletedAtMs = System.currentTimeMillis()

            // Step 3: Readback AFTER — verify controls were actually changed
            if (profile.routing.isAbox) {
                val readback = RootShell.execForOutput(buildString {
                    append("echo 'NSRC0B:'; $bin 'ABOX NSRC0 Bridge' 2>&1; ")
                    append("echo 'NSRC1B:'; $bin 'ABOX NSRC1 Bridge' 2>&1; ")
                    append("echo 'NSRC2B:'; $bin 'ABOX NSRC2 Bridge' 2>&1; ")
                    append("echo 'NSRC0:'; $bin 'ABOX NSRC0' 2>&1; ")
                    append("echo 'NSRC1:'; $bin 'ABOX NSRC1' 2>&1; ")
                    append("echo 'SPUS0:'; $bin 'ABOX SPUS OUT0' 2>&1")
                }, timeoutMs = 10000)
                appLog("Mixer AFTER: $readback")
            }
        } catch (e: Exception) {
            appLog("Mixer setup FAILED: ${e.message}")
        }
    }

    /** Restore mixer state when call ends using the device profile. */
    fun batchMixerRestore() {
        if (profile.mixer.mixerRestoreCmd.isEmpty()) {
            Log.i(TAG, "batchMixerRestore: no mixer commands for ${profile.name}")
            return
        }
        val resolvedRestore = DeviceProfile.resolveCmd(profile.mixer.mixerRestoreCmd)
        if (resolvedRestore.isEmpty()) {
            Log.i(TAG, "batchMixerRestore: tinymix not found, skipping")
            return
        }
        try {
            RootShell.exec(resolvedRestore)
            appLog("Mixer restored")
        } catch (e: Exception) {
            appLog("Mixer restore FAILED: ${e.message}")
        }
    }

    /** Check if a GSM call is currently active */
    val isCallActive: Boolean
        get() = activeCall != null && activeCallState == Call.STATE_ACTIVE

    /** Get current call number */
    val currentNumber: String?
        get() = activeCall?.details?.handle?.schemeSpecificPart

    /**
     * Decide whether a post-restore tinymix readback [probe] still reflects an
     * active-call mixer state instead of the idle default. Used to detect a
     * defective teardown (e.g. the Pixel 7 defect that left mic mutes ON and
     * INCALL playback/mixers ON at REST) so [batchMixerRestore] can be retried.
     *
     * Pure over the probe string, so it is unit-testable without a device.
     * A control reports "stuck" if any watched control holds its in-call value:
     *  - mute/enable controls (Voice Tx/Rx Mute, INCALL playback, EP TX mixers,
     *    Incall_Music mixers) are stuck when =1
     *  - Main Mic Switch (generic Exynos) is stuck when =0 (mic disabled)
     *  - =N/A or a missing control never counts as stuck
     */
    fun mixerLooksStuck(probe: String): Boolean {
        if (probe.isBlank()) return false
        // name → value that indicates the call path is still up.
        // Mute/inject controls are active at 1; the Exynos mic switch is off (0) during a call.
        val stuckWhenOne = setOf(
            "Voice Call Mic Mute",
            "Incall Mic Mute",
            "Incall Playback Stream0",
            "EP2 TX Mixer INCALL_TX",
            "EP6 TX Mixer INCALL_TX",
            "Voice Tx Mute",
            "Voice Rx Device Mute",
            "Incall_Music Audio Mixer MultiMedia1",
            "Incall_Music Audio Mixer MultiMedia2",
        )
        val stuckWhenZero = setOf("Main Mic Switch")

        val values = HashMap<String, String>()
        // tolerate "Name = value", "Name=value", leading/trailing spaces, multiple lines
        val regex = Regex("""^\s*([^=\n]+?)\s*=\s*([^\n]*?)\s*$""")
        for (line in probe.lines()) {
            val m = regex.matchEntire(line) ?: continue
            values[m.groupValues[1]] = m.groupValues[2]
        }

        for ((control, value) in values) {
            if (value == "N/A") continue
            if (control in stuckWhenOne && value == "1") return true
            if (control in stuckWhenZero && value == "0") return true
        }
        return false
    }
}

/** True when [candidate] is a different object than the live call (call-waiting). */
internal fun isWaitingGsmCall(live: Any?, candidate: Any): Boolean =
    live != null && live !== candidate

