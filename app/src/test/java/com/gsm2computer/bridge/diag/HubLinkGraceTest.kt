package com.gsm2computer.bridge.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubLinkGraceTest {

    @Test
    fun delaysDoubleUntilTheCap() {
        val grace = HubLinkGrace(graceMs = 55_000L, initialDelayMs = 1_000L, maxDelayMs = 8_000L)
        grace.markLost(0L)
        assertEquals(1_000L, grace.retryDelayMs(0L))
        grace.markLost(1_000L)
        assertEquals(2_000L, grace.retryDelayMs(1_000L))
        grace.markLost(3_000L)
        assertEquals(4_000L, grace.retryDelayMs(3_000L))
        grace.markLost(7_000L)
        assertEquals(8_000L, grace.retryDelayMs(7_000L))
        grace.markLost(15_000L)
        assertEquals(8_000L, grace.retryDelayMs(15_000L))
    }

    @Test
    fun theWindowDoesNotExtendWhenTheLinkDropsAgain() {
        val grace = HubLinkGrace(graceMs = 10_000L, initialDelayMs = 1_000L, maxDelayMs = 8_000L)
        grace.markLost(0L)
        grace.markLost(9_500L)
        assertEquals(500L, grace.retryDelayMs(9_500L))
        assertNull(grace.retryDelayMs(10_000L))
    }

    @Test
    fun aRestoredLinkResetsTheClock() {
        val grace = HubLinkGrace(graceMs = 55_000L)
        grace.markLost(0L)
        grace.markLost(1_000L)
        grace.markRestored()
        assertEquals(0, grace.attempt)
        assertNull(grace.lostAtMs)
        grace.markLost(80_000L)
        assertEquals(1, grace.attempt)
        assertEquals(1_000L, grace.retryDelayMs(80_000L))
    }

    @Test
    fun zeroGraceDoesNotRetry() {
        val grace = HubLinkGrace(graceMs = 0L)
        grace.markLost(0L)
        assertNull(grace.retryDelayMs(0L))
    }
}
