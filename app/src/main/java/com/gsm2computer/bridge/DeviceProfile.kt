package com.gsm2computer.bridge

import android.media.AudioAttributes
import android.media.MediaRecorder
import android.os.Build
import android.util.Log

/**
 * Device-specific audio profile.  Each supported device has different
 * mixer controls, volume calibration, and audio HAL behavior.
 *
 * The profile is split into three cohesive sub-objects by concern:
 *  - [MixerProfile]        — opaque shell-command blobs (executed via RootShell)
 *  - [AudioCalibration]    — DSP / gain / gate thresholds (the numbers)
 *  - [HalRoutingProfile]   — audio-HAL behaviour flags and timing
 *
 * The mixer commands are shell strings executed via RootShell in a
 * single `su` call.  Tinymix control names and default volumes are
 * entirely SoC/codec-specific.
 *
 * Auto-detection uses Build.HARDWARE and Build.BOARD.  Unknown devices
 * fall back to [generic] which skips mixer hacks and relies on
 * Android APIs only.
 */
data class DeviceProfile(
    val name: String,
    val mixer: MixerProfile,
    val audio: AudioCalibration,
    val routing: HalRoutingProfile,
) {
    companion object {
        private const val TAG = "DeviceProfile"

        /**
         * Resolved path to tinymix binary.  Empty string if not found.
         *
         * Checked paths (in order):
         *   /data/local/tmp/tinymix   (Magisk module installs here — permissive SELinux)
         *   /vendor/bin/tinymix
         *   /system/bin/tinymix       (Magisk overlay — may lack +x or SELinux context)
         *   /system/xbin/tinymix
         *
         * Falls back to `which tinymix` in the root shell PATH.
         */
        private var _tinymixBin: String? = null
        val tinymixBin: String
            get() {
                val current = _tinymixBin
                if (!current.isNullOrEmpty()) return current
                val discovered = discoverTinymix()
                if (discovered.isNotEmpty()) _tinymixBin = discovered
                return discovered
            }


        private fun discoverTinymix(): String {
            val paths = listOf(
                "/data/local/tmp/tinymix",
                "/vendor/bin/tinymix",
                "/system/bin/tinymix",
                "/system/xbin/tinymix",
            )
            try {
                // Batch-check all paths in a single su call for speed
                val checks = paths.joinToString("; ") { p ->
                    "[ -x '$p' ] && echo 'FOUND:$p'"
                }
                val result = RootShell.execForOutput(
                    "$checks; which tinymix 2>/dev/null | head -1",
                    timeoutMs = 3000
                )
                // Check explicit path matches first
                for (line in result.lines()) {
                    if (line.startsWith("FOUND:")) {
                        val path = line.removePrefix("FOUND:")
                        Log.i(TAG, "tinymix found: $path")
                        return path
                    }
                }
                // Check 'which' output (last line)
                val whichResult = result.lines().lastOrNull { it.startsWith("/") }?.trim()
                if (!whichResult.isNullOrEmpty()) {
                    Log.i(TAG, "tinymix found via which: $whichResult")
                    return whichResult
                }
            } catch (e: Exception) {
                Log.w(TAG, "tinymix discovery error: ${e.message}")
            }

            Log.e(TAG, "tinymix NOT FOUND on device! ABOX/ALSA mixer controls will not work. " +
                "Push a static arm64 tinymix binary to /data/local/tmp/tinymix (chmod 755)")
            return ""
        }

        /**
         * Dump available ALSA mixer controls for diagnostics.
         * Called once on first audio bridge setup.  Returns the dump for logging.
         */
        fun discoverMixerControls(): String {
            return try {
                val sb = StringBuilder()
                val bin = tinymixBin
                if (bin.isNotEmpty()) {
                    val aboxScan = if (detect().routing.isAbox) {
                        "echo '=== NSRC/Bridge state ==='; " +
                        "for i in 0 1 2 3 4; do " +
                        "  echo -n \"NSRC\${i}=\"; $bin \"ABOX NSRC\${i}\" 2>/dev/null || echo 'N/A'; " +
                        "  echo -n \"NSRC\${i}_Bridge=\"; $bin \"ABOX NSRC\${i} Bridge\" 2>/dev/null || echo 'N/A'; " +
                        "done; " +
                        "echo -n 'SoundType='; $bin 'ABOX Sound Type' 2>/dev/null || echo 'N/A'; "
                    } else ""

                    val result = RootShell.execForOutput(
                        "echo '=== ALSA cards ==='; cat /proc/asound/cards 2>/dev/null; " +
                        aboxScan +
                        "echo '=== total controls ==='; $bin 2>&1 | wc -l",
                        timeoutMs = 8000
                    )
                    sb.appendLine(result)
                } else {
                    sb.appendLine("=== tinymix not available ===")
                    val info = RootShell.execForOutput(
                        "cat /proc/asound/card0/id 2>/dev/null; " +
                        "cat /proc/asound/card1/id 2>/dev/null",
                        timeoutMs = 3000
                    )
                    sb.appendLine(info)
                }
                sb.toString()
            } catch (e: Exception) {
                "Mixer discovery failed: ${e.message}"
            }
        }

        /**
         * Replace bare 'tinymix' in a command string with the resolved
         * full path.  Returns empty string if tinymix is not available.
         */
        fun resolveCmd(cmd: String): String {
            if (cmd.isEmpty()) return ""
            val bin = tinymixBin
            if (bin.isEmpty()) return ""
            return cmd.replace("tinymix", bin)
        }

        /** Auto-detect the device and return the appropriate profile. */
        fun detect(): DeviceProfile {
            Log.i(TAG, "Detecting device: hw=${Build.HARDWARE.lowercase()} board=${Build.BOARD.lowercase()} " +
                "model=${Build.MODEL} device=${Build.DEVICE}")
            return match(DeviceHints(Build.HARDWARE, Build.BOARD, Build.MODEL)).also {
                Log.i(TAG, "Selected profile: ${it.name}")
            }
        }

        /**
         * Resolve [hints] to a profile.  Split out from [detect] so the
         * matching logic is unit-testable without Android's Build constants.
         *
         * The `when` order is load-bearing — first match wins.  A GT-I919 on
         * qcom hardware must hit msm8930 before the generic-Qualcomm
         * fallthrough, and an SM-G970 on exynos must hit exynos9820 before
         * the generic-Exynos fallthrough.  Do not reorder.
         */
        internal fun match(hints: DeviceHints): DeviceProfile = with(hints) {
            when {
                // Samsung Galaxy S4 Mini (MSM8930 / WCD9304)
                board.contains("msm8930") || hw.contains("qcom") && model.contains("gt-i919") ->
                    msm8930()

                // Samsung Galaxy S10e Exynos (Exynos 9820)
                board.contains("exynos9820") || hw.contains("exynos") && model.contains("sm-g970") ->
                    exynos9820()

                // Xiaomi/Generic MSM8953 (Snapdragon 625)
                board.contains("msm8953") || hw.contains("msm8953") ->
                    msm8953()

                // Google Pixel 7 (Tensor G1 / aoc-snd-card)
                hw.contains("tensor") || model.contains("pixel 7") ->
                    pixel7Tensor()

                // Generic Qualcomm — try incall_music, skip WCD9304-specific controls
                hw.contains("qcom") || hw.contains("qualcomm") ->
                    genericQualcomm()

                // Generic Samsung Exynos
                hw.contains("exynos") || hw.contains("samsung") ->
                    genericExynos()

                // Unknown device — minimal mixer interaction
                else -> generic()
            }
        }

        // ── Known device profiles ──

        /** Samsung Galaxy S4 Mini (MSM8930 / WCD9304 codec) */
        fun msm8930() = DeviceProfile(
            name = "MSM8930 (S4 Mini)",
            mixer = MixerProfile(
                mixerSetupCmd = buildString {
                    // Voice Rx mute (silence speaker)
                    append("tinymix 'Voice Rx Device Mute' 1 2>/dev/null; ")
                    append("tinymix 'Voice Rx Mute' 1 2>/dev/null; ")
                    append("tinymix 'Voip Rx Device Mute' 1 2>/dev/null; ")
                    // Voice Tx: keep open for incall_music injection
                    append("tinymix 'Voice Tx Mute' 0 2>/dev/null; ")
                    // Full mic mute: disconnect DEC MUX, zero DEC/ADC volumes, cut MICBIAS
                    append("tinymix 'DEC1 MUX' 'ZERO' 2>/dev/null; ")
                    append("tinymix 'DEC2 MUX' 'ZERO' 2>/dev/null; ")
                    append("tinymix 'DEC3 MUX' 'ZERO' 2>/dev/null; ")
                    append("tinymix 'DEC1 Volume' 0 2>/dev/null; ")
                    append("tinymix 'DEC2 Volume' 0 2>/dev/null; ")
                    append("tinymix 'DEC3 Volume' 0 2>/dev/null; ")
                    append("tinymix 'DEC4 Volume' 0 2>/dev/null; ")
                    append("tinymix 'ADC1 Volume' 0 2>/dev/null; ")
                    append("tinymix 'ADC2 Volume' 0 2>/dev/null; ")
                    append("tinymix 'ADC3 Volume' 0 2>/dev/null; ")
                    append("tinymix 'MICBIAS1 CAPLESS Switch' 0 2>/dev/null; ")
                    append("tinymix 'MICBIAS2 CAPLESS Switch' 0 2>/dev/null; ")
                    append("tinymix 'MICBIAS3 CAPLESS Switch' 0 2>/dev/null; ")
                    append("tinymix 'Voice Tx Device Mute' 1 2>/dev/null; ")
                    // Disable echo reference
                    append("tinymix 291 0 2>/dev/null; ")
                    // Enable incall_music mixer
                    append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 1 2>/dev/null; ")
                    append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1 2>/dev/null; ")
                    // Speaker codec-level mute
                    append("tinymix 'RX3 Digital Volume' 0 2>/dev/null; ")
                    append("tinymix 'SPK DRV Volume' 0 2>/dev/null; ")
                    append("tinymix 'Speaker Boost Volume' 0 2>/dev/null; ")
                    append("tinymix 'LINEOUT1 Volume' 0 2>/dev/null; ")
                    append("tinymix 'LINEOUT2 Volume' 0 2>/dev/null")
                },
                mixerRestoreCmd = buildString {
                    // Unmute voice RX (restore speaker audio)
                    append("tinymix 'Voice Rx Device Mute' 0 2>/dev/null; ")
                    append("tinymix 'Voice Rx Mute' 0 2>/dev/null; ")
                    append("tinymix 'Voip Rx Device Mute' 0 2>/dev/null; ")
                    // Unmute voice TX (was muted during bridge to block physical mic).
                    // CRITICAL: must restore to 0 or subsequent calls can't set up
                    // the voice TX path (S4 Mini: "second call never answered").
                    append("tinymix 'Voice Tx Device Mute' 0 2>/dev/null; ")
                    append("tinymix 'Voice Tx Mute' 0 2>/dev/null; ")
                    // Reset incall_music mixer — prevents stale state on next call.
                    // HAL may not reset these automatically on call end.
                    append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 0 2>/dev/null; ")
                    append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 0 2>/dev/null; ")
                    // Restore mic path: DEC MUX back to ADC, MICBIAS re-enabled
                    append("tinymix 'DEC1 MUX' 'ADC1' 2>/dev/null; ")
                    append("tinymix 'DEC2 MUX' 'ADC2' 2>/dev/null; ")
                    append("tinymix 'DEC3 MUX' 'ADC3' 2>/dev/null; ")
                    append("tinymix 'MICBIAS1 CAPLESS Switch' 1 2>/dev/null; ")
                    append("tinymix 'MICBIAS2 CAPLESS Switch' 1 2>/dev/null; ")
                    append("tinymix 'MICBIAS3 CAPLESS Switch' 1 2>/dev/null; ")
                    // Restore volumes
                    append("tinymix 'DEC1 Volume' 84 2>/dev/null; ")
                    append("tinymix 'DEC2 Volume' 84 2>/dev/null; ")
                    append("tinymix 'DEC3 Volume' 84 2>/dev/null; ")
                    append("tinymix 'DEC4 Volume' 84 2>/dev/null; ")
                    append("tinymix 'ADC1 Volume' 100 2>/dev/null; ")
                    append("tinymix 'ADC2 Volume' 100 2>/dev/null; ")
                    append("tinymix 'ADC3 Volume' 12 2>/dev/null; ")
                    append("tinymix 'RX3 Digital Volume' 68 2>/dev/null; ")
                    append("tinymix 'SPK DRV Volume' 8 2>/dev/null; ")
                    append("tinymix 'Speaker Boost Volume' 5 2>/dev/null; ")
                    append("tinymix 'LINEOUT1 Volume' 100 2>/dev/null; ")
                    append("tinymix 'LINEOUT2 Volume' 100 2>/dev/null; ")
                    // Restore echo reference
                    append("tinymix 291 1 2>/dev/null")
                },
                mixerIncallMusicCmd = buildString {
                    append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 1 2>/dev/null; ")
                    append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1 2>/dev/null; ")
                    append("tinymix 'Voice Tx Mute' 0 2>/dev/null; ")
                    append("sleep 1; ")
                    append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 1 2>/dev/null; ")
                    append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1 2>/dev/null")
                },
                mixerDiagGrep = "tinymix 2>&1 | grep -iE '(" +
                    "Voice Rx|Voice Tx|Incall_Music|EC_REF|" +
                    "DEC[1-4] Vol|DEC[1-4] MUX|ADC[1-3] Vol|MICBIAS|" +
                    "RX[0-9].*[Vv]ol|SPK DRV|LINEOUT[12] Vol|SLIM TX[0-9]" +
                    ")'",
            ),
            audio = AudioCalibration(
                musicVolPercent = 14,
                captureGain = 2,
                playbackGain = 2,
                noiseGateThreshold = 500,
                echoGateThreshold = 300,
                doubleTalkRatio = 1.5f,
            ),
            routing = HalRoutingProfile(
                requireSpeakerMode = true,
                incallMusicParam = "incall_music_enabled",
                voiceDownlinkWorks = false,
                routeChangeDelayMs = 700,
                appopsPropagationMs = 500,
                isAbox = false,
            ),
        )

        /** Samsung Galaxy S10e (Exynos 9820 / Cirrus Logic CS47L93 Madera codec)
         *
         *  Audio HAL: Samsung audio_hw_proxy (ABOX DSP)
         *  ALSA cards:
         *    0: beyondmadera (CS47L93 Madera + ALL ABOX controls, 1267 total)
         *    1: aboxvdma (ABOX Virtual DMA — ZERO mixer controls!)
         *    2: aboxdump (debug)
         *
         *  ABOX routing during voice call (v2.8.40-41 diagnostics):
         *    NSRC0=UAIF0(8) Bridge=On  — mic/codec → modem TX
         *    NSRC1=UAIF0(8) Bridge=On  — mic/codec → modem TX (redundant)
         *    NSRC2=SIFS0     Bridge=Off — playback mixer (not bridged)
         *    SIFS1 → UAIF1 → CS35L41 speaker amp (speaker playback)
         *    RDMA0 → VPCMOUT_DAI0 (modem downlink playback)
         *    VPCMIN_DAI0 → WDMA1 (modem uplink capture)
         *    ABOX Sound Type=VOICE, Audio Mode=IN_CALL
         *
         *  UAIF interfaces:
         *    UAIF0 = CODEC (Madera CS47L93) — mic/codec I2S
         *    UAIF1 = Speaker AMP (CS35L41)  — speaker playback
         *    UAIF2 = FM Radio, UAIF3 = Bluetooth
         *
         *  v2.8.42 TX injection strategy — NSRC bridge rerouting:
         *    Reroute NSRC0 from UAIF0 (mic) to SIFS0 (playback mixer).
         *    Bridge is already On (set by telephony HAL), so the bridged
         *    audio source changes from mic to AudioTrack playback.
         *    AudioTrack uses USAGE_MEDIA (routes through SPUS → SIFS0).
         *    This replaces physical mic with SIP agent audio in modem TX.
         *    v2.8.42 confirmed: caller heard audio (overdriven loud noise).
         *    Enum names work: 'SIFS0' sets correctly, 'UAIF0' restores.
         *
         *  v2.8.43-44: Only rerouted NSRC0 — caller silent (modem uses NSRC1).
         *  v2.8.45: Reroute NSRC0+NSRC1→SIFS0 (both to playback mixer).
         *    SIFS0=AudioTrack only (no modem downlink → no feedback).
         *    v2.8.42 noise was NSRC1→SIFS1 (speaker=modem downlink feedback).
         */
        fun msm8953() = DeviceProfile(
            name = "MSM8953",
            mixer = MixerProfile(
                mixerSetupCmd = buildString {
                    // Do NOT mute Voice Rx Device Mute! That kills VOICE_CALL capture completely on msm8953.
                    // Mute the speaker output codec explicitly instead so we don't hear caller audio.
                    append("tinymix 'RX1 Digital Volume' 0 2>/dev/null; ")
                    append("tinymix 'RX2 Digital Volume' 0 2>/dev/null; ")
                    append("tinymix 'RX3 Digital Volume' 0 2>/dev/null; ")
                    append("tinymix 'RX7 Digital Volume' 0 2>/dev/null; ")
                    append("tinymix 'SPK DRV Volume' 0 2>/dev/null; ")

                    // Mute physical microphones (Decimators/ADCs)
                    append("tinymix 'DEC1 Volume' 0 2>/dev/null; ")
                    append("tinymix 'DEC2 Volume' 0 2>/dev/null; ")
                    append("tinymix 'DEC3 Volume' 0 2>/dev/null; ")
                    append("tinymix 'DEC4 Volume' 0 2>/dev/null; ")
                    append("tinymix 'DEC5 Volume' 0 2>/dev/null; ")
                    append("tinymix 'ADC1 Volume' 0 2>/dev/null; ")
                    append("tinymix 'ADC2 Volume' 0 2>/dev/null; ")
                    append("tinymix 'ADC3 Volume' 0 2>/dev/null; ")

                    // Leave Voice Tx unmuted for incall_music injection
                    append("tinymix 'Voice Tx Mute' 0 2>/dev/null; ")
                    // Enable incall_music mixer
                    append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 1 2>/dev/null; ")
                    append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1 2>/dev/null")

                },
                mixerRestoreCmd = buildString {
                    append("tinymix 'Voice Tx Mute' 0 2>/dev/null; ")
                    append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 0 2>/dev/null; ")
                    append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 0 2>/dev/null; ")

                    // Restore physical microphones (Middle-ground default ~84)
                    append("tinymix 'DEC1 Volume' 84 2>/dev/null; ")
                    append("tinymix 'DEC2 Volume' 84 2>/dev/null; ")
                    append("tinymix 'ADC1 Volume' 84 2>/dev/null; ")
                    append("tinymix 'ADC2 Volume' 84 2>/dev/null; ")

                    // Restore physical speaker volumes
                    append("tinymix 'RX1 Digital Volume' 84 2>/dev/null; ")
                    append("tinymix 'RX2 Digital Volume' 84 2>/dev/null; ")
                    append("tinymix 'RX3 Digital Volume' 84 2>/dev/null; ")
                    append("tinymix 'SPK DRV Volume' 1 2>/dev/null")
                },
                mixerIncallMusicCmd = buildString {
                    append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 1 2>/dev/null; ")
                    append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1 2>/dev/null; ")
                    append("tinymix 'Voice Tx Mute' 0 2>/dev/null")
                },
                mixerDiagGrep = "tinymix 2>&1 | grep -iE '(voice|incall|music|multimedia|rx|dec|adc)'",
            ),
            audio = AudioCalibration(
                musicVolPercent = 100,
                captureGain = 2,
                playbackGain = 2,
                noiseGateThreshold = 350,
                echoGateThreshold = 300,
                doubleTalkRatio = 1.5f,
                voiceCallVolPercent = 0,
            ),
            routing = HalRoutingProfile(
                requireSpeakerMode = true,
                incallMusicParam = "incall_music_enabled",
                voiceDownlinkWorks = false,
                playbackUsage = AudioAttributes.USAGE_MEDIA,
                routeChangeDelayMs = 0,
                appopsPropagationMs = 300,
            ),
        )

        /** Samsung Galaxy S10e Exynos (Exynos 9820) */
        fun exynos9820() = DeviceProfile(
            name = "Exynos 9820 (S10e)",
            // ABOX mixer controls — exact names from /vendor/etc/mixer_paths.xml.
            // The control names are CASE SENSITIVE — "Bridge" not "BRIDGE".
            // ALL ABOX controls are on card 0 (default) — no -D flag needed.
            // v2.8.49: Attempted speaker mute via CS35L41/Madera/ABOX controls.
            // v2.8.50: SPK_SCAN confirmed NO speaker amp controls exist on this
            // LineageOS firmware.  Only HPOUT* (headphone) volumes are exposed.
            // ABOX UAIF1 SPK=RESERVED disconnected the speaker amp I2S bus which
            // KILLED the entire voice path (caller heard nothing).  The CS35L41
            // amp is controlled through missing kernel control (ABOX Spk AmpL Power).
            // No ALSA-level speaker mute is possible on this firmware.
            // Keep discovery-only scan for diagnostics.
            mixer = MixerProfile(
                mixerSetupCmd = buildString {
                    append("echo 'SPK_SCAN:'; tinymix 2>&1 | grep -iE '(spk|speaker|amp.*(switch|gain|vol)|out[1-6][lr].*(switch|vol))' | head -20")
                },
                // Restore NSRC0 and NSRC1 to UAIF0 (mic/codec → modem TX) on call end.
                // v2.8.42 confirmed enum names work — 'SIFS0' sets correctly.
                mixerRestoreCmd = buildString {
                    append("tinymix 'ABOX NSRC0' 'UAIF0' 2>/dev/null; ")
                    append("tinymix 'ABOX NSRC1' 'UAIF0' 2>/dev/null")
                },
                // NSRC bridge rerouting: change NSRC0+NSRC1 source from UAIF0 (mic)
                // to SIFS0 (playback mixer).  Bridge is already On (set by HAL),
                // so this injects AudioTrack audio into modem TX.
                // v2.8.42: NSRC0→SIFS0 + NSRC1→SIFS1 = loud noise (SIFS1 has modem
                //   downlink via speaker path → feedback loop).
                // v2.8.43-44: NSRC0→SIFS0 only = silent (modem reads NSRC1, not NSRC0).
                // v2.8.45: NSRC0+NSRC1→SIFS0 = clean injection, no feedback.
                //   SIFS0 has only playback mixer (AudioTrack), NOT modem downlink
                //   (which goes through SIFS1→UAIF1→speaker amp).
                mixerIncallMusicCmd = buildString {
                    // Reroute NSRC0+NSRC1 → SIFS0 (playback mixer → modem TX bridge)
                    append("tinymix 'ABOX NSRC0' 'SIFS0' 2>/dev/null; ")
                    append("tinymix 'ABOX NSRC1' 'SIFS0' 2>/dev/null; ")
                    append("sleep 0.1; ")
                    // Verify readback
                    append("echo 'NSRC_REROUTE:'; ")
                    append("echo -n 'NSRC0='; tinymix 'ABOX NSRC0' 2>/dev/null; ")
                    append("echo -n 'NSRC1='; tinymix 'ABOX NSRC1' 2>/dev/null; ")
                    append("echo -n 'NSRC0_Bridge='; tinymix 'ABOX NSRC0 Bridge' 2>/dev/null; ")
                    append("echo -n 'NSRC1_Bridge='; tinymix 'ABOX NSRC1 Bridge' 2>/dev/null")
                },
                // Diagnostic: NSRC/bridge state only (routing map fully understood).
                mixerDiagGrep = buildString {
                    append("echo '=== NSRC/Bridge ==='; ")
                    append("tinymix 2>&1 | grep -iE '(nsrc|bridge)' | head -20")
                },
            ),
            audio = AudioCalibration(
                musicVolPercent = 40,  // v2.8.45@30%+2x=audible but quiet. Raise for clarity.
                captureGain = 10,      // VOICE_RECOGNITION captures very quietly (rawCapRMS~2)
                playbackGain = 2,      // 40%+2x = moderate, SIFS0 only = no feedback
                noiseGateThreshold = 20,  // Lower gate: VOICE_RECOGNITION raw level is ~2-50
                echoGateThreshold = 300,
                doubleTalkRatio = 1.5f,
                voiceCallVolPercent = 0,   // Minimum — vol=12 caused acoustic echo
            ),
            routing = HalRoutingProfile(
                // Speaker mode REQUIRED for capture on this device.  In earpiece
                // mode, ALL AudioRecord sources return rawCapRMS=0.
                requireSpeakerMode = true,
                // Samsung incall_music HAL param.  Set via enableIncallMusic()
                // AFTER capture is established (not in configureAudioBridge).
                // This gives AudioRecord time to lock onto its PCM device before
                // the HAL re-routes for incall_music.
                incallMusicParam = "incall_music_enabled",
                voiceDownlinkWorks = true,
                // USAGE_MEDIA: Routes through SPUS → SIFS0/SIFS1 (normal playback).
                // NSRC bridge rerouting captures from SIFS into modem TX.
                // v2.8.41 proved USAGE_VOICE_COMMUNICATION does NOT inject into
                // modem TX — HAL ignores it.  USAGE_MEDIA is correct because we
                // need audio on SIFS where the rerouted NSRC bridge can capture it.
                playbackUsage = -1,  // default = USAGE_MEDIA
                routeChangeDelayMs = 500,
                appopsPropagationMs = 300,
                isAbox = true,
            ),
        )

        /** Google Pixel 7 (Tensor G1 / aoc-snd-card)
         *
         *  Audio framework: AoC (Audio over CROS) — 596 tinymix controls.
         *  Separate INCALL pipeline for modem audio:
         *    INCALL_TX = audio to modem (uplink / GSM TX)
         *    INCALL_RX = audio from modem (downlink / GSM RX)
         *    EP1-EP7 = audio endpoints (playback/capture)
         *
         *  SIP→GSM bridge strategy:
         *    1. Enable Incall Playback Stream0 (opens INCALL TX path)
         *    2. Route playback EPs to INCALL_TX via EPx TX Mixer INCALL_TX
         *    3. Mute physical mic (Voice Call Mic Mute + Incall Mic Mute)
         *    4. Set incall_music_enabled=true (HAL may assist with routing)
         *
         *  The AudioTrack (USAGE_MEDIA → STREAM_MUSIC) plays through one of
         *  the EPs.  Routing that EP's TX to INCALL_TX bridges the audio
         *  into the modem uplink — same principle as Qualcomm's
         *  Incall_Music Audio Mixer MultiMedia1.
         */
        fun pixel7Tensor() = DeviceProfile(
            name = "Pixel 7 (Tensor G1)",
            mixer = MixerProfile(
                mixerSetupCmd = buildString {
                    // Mute physical mic
                    append("tinymix 'Voice Call Mic Mute' 1 2>/dev/null; ")
                    append("tinymix 'Incall Mic Mute' 1 2>/dev/null; ")
                    // Caller capture uses AudioRecord(VOICE_DOWNLINK) via the
                    // framework incall-record path (routing.captureSource) — NOT
                    // the AOC 'Incall Capture Stream0' tap.  That tap was
                    // measured (2026-07-04) to read all-zero on VoLTE under every
                    // UL/DL/UL_DL value (AoC Modem Sink Channel Bitmap=0: the
                    // modem's VoLTE audio never reaches the AOC DSP), so it is no
                    // longer set here.  A BCR control test proved VOICE_DOWNLINK
                    // captures the far-end cleanly on this device over VoLTE with
                    // the injection routing below active.  See CLAUDE.md.
                    // Enable INCALL playback stream (opens modem TX path)
                    append("tinymix 'Incall Playback Stream0' 1 2>/dev/null; ")
                    // Route EP6 TX to INCALL_TX (EP6 = deep-buffer-playback from mixer_paths.xml)
                    append("tinymix 'EP6 TX Mixer INCALL_TX' 1 2>/dev/null; ")
                    // Also route EP2 (low-latency-playback) for coverage
                    append("tinymix 'EP2 TX Mixer INCALL_TX' 1 2>/dev/null")
                },
                mixerRestoreCmd = buildString {
                    append("tinymix 'Voice Call Mic Mute' 0 2>/dev/null; ")
                    append("tinymix 'Incall Mic Mute' 0 2>/dev/null; ")
                    append("tinymix 'Incall Playback Stream0' 0 2>/dev/null; ")
                    append("tinymix 'EP6 TX Mixer INCALL_TX' 0 2>/dev/null; ")
                    append("tinymix 'EP2 TX Mixer INCALL_TX' 0 2>/dev/null; ")
                    append("tinymix 'INCALL_RX Mixer EP5' 0 2>/dev/null; ")
                    // Return incall capture path to rest (no DL routing after teardown)
                    append("tinymix 'Incall Capture Stream0' Off 2>/dev/null")
                },
                mixerIncallMusicCmd = buildString {
                    // Re-enable INCALL path after AudioTrack.play()
                    append("tinymix 'Incall Playback Stream0' 1 2>/dev/null; ")
                    // EP6 = deep-buffer-playback (USAGE_MEDIA AudioTrack)
                    append("tinymix 'EP6 TX Mixer INCALL_TX' 1 2>/dev/null; ")
                    append("tinymix 'EP2 TX Mixer INCALL_TX' 1 2>/dev/null")
                },
                mixerDiagGrep = "tinymix 2>&1 | grep -iE '(INCALL|Incall|Voice Call|EP[1-6].*Mixer)'",
                micMuteCmd = "tinymix 'Voice Call Mic Mute' 1 2>/dev/null; tinymix 'Incall Mic Mute' 1 2>/dev/null",
            ),
            audio = AudioCalibration(
                musicVolPercent = 100,
                captureGain = 4,
                playbackGain = 2,
                voiceCallVolPercent = 70,
                noiseGateThreshold = 3,   // UL capture on aoc-snd-card is quiet; 3 passes speech, gates noise
                echoGateThreshold = 300,
                doubleTalkRatio = 1.5f,
            ),
            routing = HalRoutingProfile(
                // TYPE_TELEPHONY + INCALL_TX tinymix routes SIP playback into modem
                // uplink; speaker mode is not required (and stereo speaker leaks
                // OpenAI to one ear only).
                requireSpeakerMode = false,
                incallMusicParam = "incall_music_enabled",
                // VOICE_DOWNLINK is the caller-capture source on this device
                // (framework incall-record path); proven on VoLTE 2026-07-04.
                voiceDownlinkWorks = true,
                captureSource = MediaRecorder.AudioSource.VOICE_DOWNLINK,
                routeChangeDelayMs = 500,
                appopsPropagationMs = 500,
                playbackToTelephony = true,
                isAbox = false,
                muteMicrophoneAtApi = true,
            ),
        )

        /** Generic Qualcomm device — tries incall_music, generic controls */
        fun genericQualcomm() = DeviceProfile(
            name = "Generic Qualcomm",
            mixer = MixerProfile(
                mixerSetupCmd = buildString {
                    append("tinymix 'Voice Rx Device Mute' 1 2>/dev/null; ")
                    append("tinymix 'Voice Tx Mute' 0 2>/dev/null; ")
                    append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 1 2>/dev/null; ")
                    append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1 2>/dev/null")
                },
                mixerRestoreCmd = buildString {
                    append("tinymix 'Voice Rx Device Mute' 0 2>/dev/null")
                },
                mixerIncallMusicCmd = buildString {
                    append("tinymix 'Incall_Music Audio Mixer MultiMedia1' 1 2>/dev/null; ")
                    append("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1 2>/dev/null; ")
                    append("tinymix 'Voice Tx Mute' 0 2>/dev/null")
                },
                mixerDiagGrep = "tinymix 2>&1 | grep -iE '(voice|incall|music|multimedia)'",
            ),
            audio = AudioCalibration(
                musicVolPercent = 20,
                captureGain = 1,
                playbackGain = 2,
                noiseGateThreshold = 350,
                echoGateThreshold = 300,
                doubleTalkRatio = 1.5f,
            ),
            routing = HalRoutingProfile(
                requireSpeakerMode = true,
                incallMusicParam = "incall_music_enabled",
                voiceDownlinkWorks = false,
                routeChangeDelayMs = 500,
                appopsPropagationMs = 300,
                isAbox = false,
            ),
        )

        /** Generic Samsung Exynos — minimal mixer, rely on Android APIs */
        fun genericExynos() = DeviceProfile(
            name = "Generic Exynos",
            mixer = MixerProfile(
                mixerSetupCmd = buildString {
                    append("tinymix 'Main Mic Switch' 0 2>/dev/null; ")
                    append("tinymix 'Sub Mic Switch' 0 2>/dev/null; ")
                    append("tinymix 'Third Mic Switch' 0 2>/dev/null")
                },
                mixerRestoreCmd = buildString {
                    append("tinymix 'Main Mic Switch' 1 2>/dev/null; ")
                    append("tinymix 'Sub Mic Switch' 1 2>/dev/null")
                },
                mixerIncallMusicCmd = "",  // May not have incall_music
                mixerDiagGrep = "tinymix 2>&1 | grep -iE '(mic|spk|voice|abox)'",
            ),
            audio = AudioCalibration(
                musicVolPercent = 20,
                captureGain = 1,
                playbackGain = 2,
                noiseGateThreshold = 300,
                echoGateThreshold = 300,
                doubleTalkRatio = 1.5f,
            ),
            routing = HalRoutingProfile(
                requireSpeakerMode = true,
                incallMusicParam = "incall_music_enabled",
                voiceDownlinkWorks = true,
                routeChangeDelayMs = 500,
                appopsPropagationMs = 300,
                isAbox = true,
            ),
        )

        /** Unknown device — no mixer hacks, pure Android APIs */
        fun generic() = DeviceProfile(
            name = "Generic",
            mixer = MixerProfile(
                mixerSetupCmd = "",
                mixerRestoreCmd = "",
                mixerIncallMusicCmd = "",
                mixerDiagGrep = "tinymix 2>&1 | head -30",
            ),
            audio = AudioCalibration(
                musicVolPercent = 20,
                captureGain = 1,
                playbackGain = 2,
                noiseGateThreshold = 300,
                echoGateThreshold = 300,
                doubleTalkRatio = 1.5f,
            ),
            routing = HalRoutingProfile(
                requireSpeakerMode = true,
                incallMusicParam = "incall_music_enabled",
                voiceDownlinkWorks = true,
                routeChangeDelayMs = 500,
                appopsPropagationMs = 300,
                isAbox = false,
            ),
        )
    }
}

/**
 * Opaque shell-command blobs executed via RootShell in a single `su` call.
 * Tinymix control names and default volumes are entirely SoC/codec-specific.
 * Kept as raw strings — a typed MixerOp model is a separate, larger change.
 */
data class MixerProfile(
    /** Shell command to set up audio bridge (mute speaker, mute mic,
     *  enable incall_music, etc.).  All 2>/dev/null for missing controls. */
    val mixerSetupCmd: String,

    /** Shell command to restore mixer state when call ends. */
    val mixerRestoreCmd: String,

    /** Shell command to enable incall_music mixer (called from RtpSession
     *  after AudioTrack.play() when route is settled). */
    val mixerIncallMusicCmd: String,

    /** Grep pattern for diagnostic tinymix dump. */
    val mixerDiagGrep: String,

    /** Re-assert physical mic mute during an active bridge (tinymix only).
     *  Empty = skip.  Pixel/Tensor: HAL can un-mute the mic mid-call. */
    val micMuteCmd: String = "",
)

/**
 * Per-device audio DSP numbers: software gains, stream-volume percentages,
 * and echo/noise-gate thresholds applied to the captured/played PCM.
 */
data class AudioCalibration(
    /** STREAM_MUSIC volume as percent of max.  Controls incall_music
     *  injection level into the modem uplink. */
    val musicVolPercent: Int,

    /** Software gain for captured audio (before encoding). */
    val captureGain: Int,

    /** Software gain for playback audio (agent → caller). */
    val playbackGain: Int,

    /** RMS below this = modem DSP noise, send silence. */
    val noiseGateThreshold: Int,

    /** RMS above this = playback active for echo gate. */
    val echoGateThreshold: Int,

    /** Capture must exceed expectedEcho * this to count as barge-in. */
    val doubleTalkRatio: Float,

    /** Voice call stream volume as percentage of max (0=minimum non-zero).
     *  Controls the caller's voice volume on the speaker.
     *  MSM8930: 0 (muted via tinymix muteVoiceRx).
     *  Exynos 9820: higher value needed for mic-based acoustic capture. */
    val voiceCallVolPercent: Int = 0,
)

/**
 * Audio-HAL behaviour flags, routing preferences, and timing knobs.  These
 * govern how the bridge interacts with the platform audio HAL rather than
 * raw DSP levels or mixer commands.
 */
data class HalRoutingProfile(
    /** Speaker mode required for incall_music to work. */
    val requireSpeakerMode: Boolean,

    /** Whether VOICE_DOWNLINK returns real audio on this device. */
    val voiceDownlinkWorks: Boolean,

    /** AudioRecord source for capturing the caller (modem RX / downlink) that
     *  is forwarded to the SIP side.
     *
     *  Default VOICE_CALL = uplink+downlink mixed by the HAL.  On devices where
     *  it works this is fine, but it also captures our own injected SIP audio
     *  (potential echo) and, on Pixel/Tensor VoLTE, reads all-zero because the
     *  framework's incall-record route isn't the AOC capture tap the mixer
     *  setup was pointing at.
     *
     *  VOICE_DOWNLINK = caller-only, via the standard AudioFlinger incall-record
     *  path (no ALSA/AOC tap needed).  Verified 2026-07-04 on Pixel 7 / Android
     *  16 over a live VoLTE call: downlink RMS ≈ −14.7 dB (clean far-end speech)
     *  with the injection mixer routing active — see CLAUDE.md and the BCR
     *  control test.  Caller-only capture also means the injected SIP leg cannot
     *  echo back into the uplink. */
    val captureSource: Int = MediaRecorder.AudioSource.VOICE_CALL,

    /** HAL parameter name for incall_music (set via AudioManager.setParameters). */
    val incallMusicParam: String,

    /** Delay (ms) after speaker route change before mixer setup. */
    val routeChangeDelayMs: Long,

    /** Delay (ms) for appops propagation on cold boot. */
    val appopsPropagationMs: Long,

    /** AudioTrack usage attribute for playback (agent→caller).
     *  USAGE_MEDIA (default): Maps to STREAM_MUSIC.  Works with Qualcomm
     *  incall_music mixer (MultiMedia1/2) for digital modem TX injection.
     *  USAGE_VOICE_COMMUNICATION: Maps to STREAM_VOICE_CALL.  May trigger
     *  Samsung Exynos HAL to route playback into the voice call TX path.
     *  -1 = use USAGE_MEDIA (default). */
    val playbackUsage: Int = -1,

    /** Route playback AudioTrack to TYPE_TELEPHONY device (modem TX uplink).
     *  Required on Pixel/Tensor aoc-snd-card where tinymix INCALL controls
     *  alone don't bridge media audio into the modem path. The AOC DSP needs
     *  an active PCM stream on the telephony endpoint.
     *  Requires MODIFY_PHONE_STATE permission (granted via Magisk priv-app). */
    val playbackToTelephony: Boolean = false,

    /** Whether this is a Samsung ABOX (Exynos) device. */
    val isAbox: Boolean = false,

    /** Mute the physical mic via AudioManager.isMicrophoneMute during bridge.
     *  Samsung Exynos must stay false (HAL treats it as full uplink mute).
     *  Pixel/Tensor: safe when SIP playback routes via INCALL_TX tinymix. */
    val muteMicrophoneAtApi: Boolean = false,
)

/**
 * Lowercased device identifiers used for profile matching.  Wraps the raw
 * Build.* strings so [DeviceProfile.match] is unit-testable without Android.
 */
class DeviceHints(
    hardware: String,
    board: String,
    model: String,
) {
    val hw: String = hardware.lowercase()
    val board: String = board.lowercase()
    val model: String = model.lowercase()
}
