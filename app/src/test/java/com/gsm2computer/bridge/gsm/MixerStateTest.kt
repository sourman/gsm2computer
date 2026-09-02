package com.gsm2computer.bridge.gsm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [GsmCallManager.mixerLooksStuck], the pure function that decides
 * whether a post-restore mixer probe still shows active-call state.
 *
 * This is the safety net for the confirmed Pixel 7 defect where a call
 * teardown left the mixer mutated at REST (Voice Call Mic Mute=On,
 * Incall Playback Stream0=On, EP2/EP6 TX Mixer INCALL_TX=On).
 * [mixerLooksStuck] is what makes that failure observable and triggers the
 * restore retry.  It is a pure function over the probe string, so it can be
 * unit tested without Android instrumentation or a real tinymix binary.
 */
class MixerStateTest {

    @Test
    fun `empty probe is not stuck`() {
        assertFalse(GsmCallManager.mixerLooksStuck(""))
    }

    @Test
    fun `all N_A controls are not stuck`() {
        val probe = """
            Voice Call Mic Mute=N/A
            Incall Mic Mute=N/A
            Incall Playback Stream0=N/A
            EP2 TX Mixer INCALL_TX=N/A
            EP6 TX Mixer INCALL_TX=N/A
        """.trimIndent()
        assertFalse(GsmCallManager.mixerLooksStuck(probe))
    }

    @Test
    fun `Pixel 7 active-call state is stuck`() {
        // The exact state observed at REST on the Pixel 7 after the
        // defective teardown: mic mutes ON, INCALL playback ON, EP mixers ON.
        val probe = """
            Voice Call Mic Mute=1
            Incall Mic Mute=1
            Incall Playback Stream0=1
            EP2 TX Mixer INCALL_TX=1
            EP6 TX Mixer INCALL_TX=1
        """.trimIndent()
        assertTrue(GsmCallManager.mixerLooksStuck(probe))
    }

    @Test
    fun `Pixel 7 restored idle state is not stuck`() {
        val probe = """
            Voice Call Mic Mute=0
            Incall Mic Mute=0
            Incall Playback Stream0=0
            EP2 TX Mixer INCALL_TX=0
            EP6 TX Mixer INCALL_TX=0
        """.trimIndent()
        assertFalse(GsmCallManager.mixerLooksStuck(probe))
    }

    @Test
    fun `single stuck mic mute is detected`() {
        val probe = "Voice Call Mic Mute=1"
        assertTrue(GsmCallManager.mixerLooksStuck(probe))
    }

    @Test
    fun `single stuck INCALL_TX mixer is detected`() {
        val probe = "EP6 TX Mixer INCALL_TX=1"
        assertTrue(GsmCallManager.mixerLooksStuck(probe))
    }

    @Test
    fun `MSM8930 active-call state with Voice Tx Mute is stuck`() {
        val probe = """
            Voice Tx Mute=1
            Incall_Music Audio Mixer MultiMedia1=1
            Incall_Music Audio Mixer MultiMedia2=1
        """.trimIndent()
        assertTrue(GsmCallManager.mixerLooksStuck(probe))
    }

    @Test
    fun `MSM8930 restored state with Voice Tx Mute 0 is not stuck`() {
        val probe = """
            Voice Tx Mute=0
            Incall_Music Audio Mixer MultiMedia1=0
            Incall_Music Audio Mixer MultiMedia2=0
        """.trimIndent()
        assertFalse(GsmCallManager.mixerLooksStuck(probe))
    }

    @Test
    fun `Voice Rx Device Mute 1 is stuck (Qualcomm)`() {
        val probe = "Voice Rx Device Mute=1"
        assertTrue(GsmCallManager.mixerLooksStuck(probe))
    }

    @Test
    fun `Voice Rx Device Mute 0 is not stuck`() {
        val probe = "Voice Rx Device Mute=0"
        assertFalse(GsmCallManager.mixerLooksStuck(probe))
    }

    @Test
    fun `Main Mic Switch 0 is stuck (generic Exynos mutes mic during call)`() {
        val probe = "Main Mic Switch=0"
        assertTrue(GsmCallManager.mixerLooksStuck(probe))
    }

    @Test
    fun `Main Mic Switch 1 is not stuck`() {
        val probe = "Main Mic Switch=1"
        assertFalse(GsmCallManager.mixerLooksStuck(probe))
    }

    @Test
    fun `mixed stuck and idle controls reports stuck`() {
        // Partial restore: some controls reverted, one didn't.
        val probe = """
            Voice Call Mic Mute=0
            Incall Mic Mute=0
            Incall Playback Stream0=1
            EP2 TX Mixer INCALL_TX=0
            EP6 TX Mixer INCALL_TX=0
        """.trimIndent()
        assertTrue(GsmCallManager.mixerLooksStuck(probe))
    }

    @Test
    fun `whitespace and trailing content tolerated`() {
        val probe = "  Voice Call Mic Mute = 1  \n  Incall Mic Mute=0"
        assertTrue(GsmCallManager.mixerLooksStuck(probe))
    }
}
