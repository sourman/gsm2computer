package com.gsm2computer.bridge.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RedactingLogger] phone-number redaction.
 *
 * The debug/release gating is flipped via [RedactingLogger.setDebugForTest] so
 * both branches can be exercised from a single test build (production gating
 * mirrors [com.gsm2computer.bridge.BuildConfig.DEBUG]).
 */
class RedactingLoggerTest {

    @After
    fun tearDown() {
        // Restore the production default so test ordering cannot leak state.
        RedactingLogger.setDebugForTest(false)
    }

    // ── Release: phone numbers are masked to last four ───────────────────

    @Test
    fun release_redactsInternationalNumberWithPlusToLastFour() {
        RedactingLogger.setDebugForTest(false)
        assertEquals(
            "Incoming GSM call from …1234",
            RedactingLogger.redact("Incoming GSM call from +14155551234")
        )
    }

    @Test
    fun release_redactsUsParenthesisedNumberToLastFour() {
        RedactingLogger.setDebugForTest(false)
        assertEquals(
            "Caller: …1234 connected",
            RedactingLogger.redact("Caller: (415) 555-1234 connected")
        )
    }

    @Test
    fun release_redactsNationalNumberWithDashesToLastFour() {
        RedactingLogger.setDebugForTest(false)
        assertEquals(
            "Dialing …5678",
            RedactingLogger.redact("Dialing 029-555-5678")
        )
    }

    @Test
    fun release_redactsMultipleNumbersIndependently() {
        RedactingLogger.setDebugForTest(false)
        assertEquals(
            "From …1234 to …5678",
            RedactingLogger.redact("From +14155551234 to +4420718385678")
        )
    }

    // ── Debug: no redaction ──────────────────────────────────────────────

    @Test
    fun debug_passesThroughUnchanged() {
        RedactingLogger.setDebugForTest(true)
        val msg = "Incoming GSM call from +14155551234"
        assertEquals(msg, RedactingLogger.redact(msg))
    }

    // ── IP addresses, ports, and other digit runs are NOT mangled ───────

    @Test
    fun release_doesNotRedactIpv4Address() {
        RedactingLogger.setDebugForTest(false)
        assertEquals(
            "RTP ready: 142.55.0.10:5004",
            RedactingLogger.redact("RTP ready: 142.55.0.10:5004")
        )
    }

    @Test
    fun release_doesNotRedactLocalIpv4Address() {
        RedactingLogger.setDebugForTest(false)
        assertEquals(
            "Local IP: 192.168.1.42",
            RedactingLogger.redact("Local IP: 192.168.1.42")
        )
    }

    @Test
    fun release_doesNotRedactRtpPortNumber() {
        RedactingLogger.setDebugForTest(false)
        assertEquals(
            "SIP INVITE sent to Asterisk (caller=…1234, rtp=34568)",
            RedactingLogger.redact("SIP INVITE sent to Asterisk (caller=+14155551234, rtp=34568)")
        )
    }

    @Test
    fun release_doesNotRedactShortDigitSequences() {
        RedactingLogger.setDebugForTest(false)
        assertEquals(
            "STUN public IP: 203.0.113.7:3478 (code 42)",
            RedactingLogger.redact("STUN public IP: 203.0.113.7:3478 (code 42)")
        )
    }

    @Test
    fun release_doesNotRedactErrorCodeOrDuration() {
        RedactingLogger.setDebugForTest(false)
        assertEquals(
            "appops RECORD_AUDIO: ok=true (4500ms)",
            RedactingLogger.redact("appops RECORD_AUDIO: ok=true (4500ms)")
        )
    }

    // ── Edge cases: SSIDs, hex, short numbers ────────────────────────────

    @Test
    fun release_doesNotRedactSsid() {
        RedactingLogger.setDebugForTest(false)
        assertEquals(
            "Connected to HomeWiFi_5G",
            RedactingLogger.redact("Connected to HomeWiFi_5G")
        )
    }

    @Test
    fun release_doesNotRedactHexadecimal() {
        RedactingLogger.setDebugForTest(false)
        assertEquals(
            "SSRC=0xDEADBEEF seq=0x1234",
            RedactingLogger.redact("SSRC=0xDEADBEEF seq=0x1234")
        )
    }

    @Test
    fun release_doesNotRedactVersionString() {
        RedactingLogger.setDebugForTest(false)
        assertEquals(
            "[v2.8.51] SIP client started",
            RedactingLogger.redact("[v2.8.51] SIP client started")
        )
    }

    @Test
    fun release_leavesShortNumberUntouched() {
        RedactingLogger.setDebugForTest(false)
        assertEquals(
            "Dialing extension 100",
            RedactingLogger.redact("Dialing extension 100")
        )
    }

    // ── Boundary conditions on the matcher ──────────────────────────────

    @Test
    fun release_handlesEmptyAndNumberlessInput() {
        RedactingLogger.setDebugForTest(false)
        assertEquals("", RedactingLogger.redact(""))
        assertEquals("Network available", RedactingLogger.redact("Network available"))
    }

    @Test
    fun release_minimalSevenDigitPlusNumberIsRedacted() {
        RedactingLogger.setDebugForTest(false)
        // + + 7 digits is the smallest branch-1 match.
        val out = RedactingLogger.redact("x+1234567")
        assertTrue("expected last-4 suffix, got: $out", out.endsWith("4567"))
        assertFalse("full number must not survive: $out", out.contains("1234567"))
    }
}
