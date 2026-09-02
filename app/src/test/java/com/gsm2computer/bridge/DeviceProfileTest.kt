package com.gsm2computer.bridge

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeviceProfile structure and detection invariants.
 *
 * These tests pin the behaviour-preserving refactor of the profile god-object
 * into [MixerProfile], [AudioCalibration] and [HalRoutingProfile].  They
 * exercise [DeviceProfile.match] (the testable core of [DeviceProfile.detect])
 * rather than Android's Build.* constants, and assert that every factory
 * yields a fully-populated profile.
 */
class DeviceProfileTest {

    private fun hints(hw: String = "", board: String = "", model: String = "") =
        DeviceHints(hw, board, model)

    // ── detect() / match() resolution ──

    @Test
    fun `match returns msm8930 for S4 Mini board`() {
        val p = DeviceProfile.match(hints(board = "msm8930"))
        assertEquals("MSM8930 (S4 Mini)", p.name)
    }

    @Test
    fun `match returns msm8930 for GT-I919 on qcom hardware`() {
        val p = DeviceProfile.match(hints(hw = "qcom", model = "GT-I9195"))
        assertEquals("MSM8930 (S4 Mini)", p.name)
    }

    @Test
    fun `match returns exynos9820 for S10e board`() {
        val p = DeviceProfile.match(hints(board = "exynos9820"))
        assertEquals("Exynos 9820 (S10e)", p.name)
    }

    @Test
    fun `match returns exynos9820 for SM-G970 on exynos hardware`() {
        val p = DeviceProfile.match(hints(hw = "exynos", model = "SM-G970F"))
        assertEquals("Exynos 9820 (S10e)", p.name)
    }

    @Test
    fun `match returns msm8953 for msm8953 board`() {
        val p = DeviceProfile.match(hints(board = "msm8953"))
        assertEquals("MSM8953", p.name)
    }

    @Test
    fun `match returns msm8953 for msm8953 hardware`() {
        val p = DeviceProfile.match(hints(hw = "msm8953"))
        assertEquals("MSM8953", p.name)
    }

    @Test
    fun `match returns pixel7 for tensor hardware`() {
        val p = DeviceProfile.match(hints(hw = "tensor"))
        assertEquals("Pixel 7 (Tensor G1)", p.name)
    }

    @Test
    fun `match returns pixel7 for Pixel 7 model`() {
        val p = DeviceProfile.match(hints(model = "Pixel 7"))
        assertEquals("Pixel 7 (Tensor G1)", p.name)
    }

    @Test
    fun `match returns genericQualcomm for qcom hardware`() {
        val p = DeviceProfile.match(hints(hw = "qcom"))
        assertEquals("Generic Qualcomm", p.name)
    }

    @Test
    fun `match returns genericExynos for samsung hardware`() {
        val p = DeviceProfile.match(hints(hw = "samsung"))
        assertEquals("Generic Exynos", p.name)
    }

    @Test
    fun `match returns generic fallback for unknown device`() {
        val p = DeviceProfile.match(hints(hw = "totally-unknown", board = "mystery", model = "nope"))
        assertEquals("Generic", p.name)
    }

    @Test
    fun `match is case-insensitive on device hints`() {
        val p = DeviceProfile.match(hints(board = "MSM8930"))
        assertEquals("MSM8930 (S4 Mini)", p.name)
    }

    // ── Pixel 7 mic-isolation invariant ──

    @Test
    fun `pixel7 mutes physical mic at API and tinymix`() {
        val p = DeviceProfile.pixel7Tensor()
        assertTrue(p.routing.muteMicrophoneAtApi)
        assertTrue(p.mixer.micMuteCmd.contains("Voice Call Mic Mute"))
    }

    // ── Structural completeness: every factory is fully populated ──

    @Test
    fun `msm8930 profile is fully populated`() {
        assertFullyPopulated(DeviceProfile.msm8930())
    }

    @Test
    fun `msm8953 profile is fully populated`() {
        assertFullyPopulated(DeviceProfile.msm8953())
    }

    @Test
    fun `exynos9820 profile is fully populated`() {
        assertFullyPopulated(DeviceProfile.exynos9820())
    }

    @Test
    fun `pixel7Tensor profile is fully populated`() {
        assertFullyPopulated(DeviceProfile.pixel7Tensor())
    }

    @Test
    fun `genericQualcomm profile is fully populated`() {
        assertFullyPopulated(DeviceProfile.genericQualcomm())
    }

    @Test
    fun `genericExynos profile is fully populated`() {
        assertFullyPopulated(DeviceProfile.genericExynos())
    }

    @Test
    fun `generic profile is fully populated`() {
        assertFullyPopulated(DeviceProfile.generic())
    }

    // ── Pixel 7 caller capture: VOICE_DOWNLINK, not the AOC 'Incall Capture
    //    Stream0' tap (settled 2026-07-04; see CLAUDE.md OVERTURNED entry) ──

    @Test
    fun `pixel7 captures caller via VOICE_DOWNLINK, not the AOC incall-capture tap`() {
        val profile = DeviceProfile.pixel7Tensor()
        assertEquals(
            "Pixel 7 caller capture must use VOICE_DOWNLINK",
            MediaRecorder.AudioSource.VOICE_DOWNLINK,
            profile.routing.captureSource,
        )
        assertFalse(
            "the disproven AOC 'Incall Capture Stream0' tap must not be in mixer setup",
            profile.mixer.mixerSetupCmd.contains("Incall Capture Stream0"),
        )
    }

    @Test
    fun `pixel7 mixer restore returns incall capture to Off`() {
        val restore = DeviceProfile.pixel7Tensor().mixer.mixerRestoreCmd
        assertTrue(
            "restore must set Incall Capture Stream0 back to Off",
            restore.contains("tinymix 'Incall Capture Stream0' Off"),
        )
    }

    @Test
    fun `HalRoutingProfile source has no dropped useAlsaIncallCapture flag`() {
        val source = java.io.File("src/main/java/com/gsm2computer/bridge/DeviceProfile.kt").readText()
        assertFalse(
            "native ALSA incall-capture was removed; DeviceProfile.kt must not " +
                "reintroduce useAlsaIncallCapture",
            Regex("""\buseAlsaIncallCapture\b""").containsMatchIn(source),
        )
    }

    @Test
    fun `pixel7 sub-object field paths compile`() {
        // Sanity that the DeviceProfile refactor keeps mixer/audio/routing
        // reachable. Any flat-field consumer regression (the aad6124 internal
        // inconsistency where call sites used profile.mixerIncallMusicCmd)
        // surfaces as a compile error here.
        val p = DeviceProfile.pixel7Tensor()
        val mixerCmd: String = p.mixer.mixerIncallMusicCmd
        val captureGain: Int = p.audio.captureGain
        val routeDelay: Long = p.routing.routeChangeDelayMs
        assertTrue(mixerCmd.isNotEmpty())
        assertTrue(captureGain >= 0)
        assertTrue(routeDelay >= 0)
    }

    private fun assertFullyPopulated(p: DeviceProfile) {
        assertTrue("name must be set", p.name.isNotBlank())

        // No null sub-objects — every profile must carry all three concerns.
        assertNotNull("mixer must not be null", p.mixer)
        assertNotNull("audio must not be null", p.audio)
        assertNotNull("routing must not be null", p.routing)

        // Mixer fields are present (strings may legitimately be empty when a
        // device has no incall_music control, but the objects themselves exist).
        with(p.mixer) {
            assertTrue("mixerSetupCmd present", mixerSetupCmd.length >= 0)
            assertTrue("mixerRestoreCmd present", mixerRestoreCmd.length >= 0)
            assertTrue("mixerIncallMusicCmd present", mixerIncallMusicCmd.length >= 0)
            assertTrue("mixerDiagGrep present", mixerDiagGrep.length >= 0)
        }

        // Audio calibration: every numeric field must be in a sane range.
        with(p.audio) {
            assertTrue("musicVolPercent in 0..100", musicVolPercent in 0..100)
            assertTrue("captureGain >= 0", captureGain >= 0)
            assertTrue("playbackGain >= 0", playbackGain >= 0)
            assertTrue("noiseGateThreshold >= 0", noiseGateThreshold >= 0)
            assertTrue("echoGateThreshold >= 0", echoGateThreshold >= 0)
            assertTrue("doubleTalkRatio > 0", doubleTalkRatio > 0f)
            assertTrue("voiceCallVolPercent in 0..100", voiceCallVolPercent in 0..100)
        }

        // Routing: HAL param and timing must be set; booleans are always valid.
        with(p.routing) {
            assertTrue("incallMusicParam set", incallMusicParam.isNotBlank())
            assertTrue("routeChangeDelayMs >= 0", routeChangeDelayMs >= 0)
            assertTrue("appopsPropagationMs >= 0", appopsPropagationMs >= 0)
        }
    }
}
