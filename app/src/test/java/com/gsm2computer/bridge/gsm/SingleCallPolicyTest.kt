package com.gsm2computer.bridge.gsm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleCallPolicyTest {

    @Test
    fun `no live call means the next one is not waiting`() {
        val incoming = Any()
        assertFalse(isWaitingGsmCall(null, incoming))
    }

    @Test
    fun `the live call is not waiting against itself`() {
        val live = Any()
        assertFalse(isWaitingGsmCall(live, live))
    }

    @Test
    fun `a different object is waiting and must not replace the live call`() {
        val live = Any()
        val waiting = Any()
        assertTrue(isWaitingGsmCall(live, waiting))
    }
}
