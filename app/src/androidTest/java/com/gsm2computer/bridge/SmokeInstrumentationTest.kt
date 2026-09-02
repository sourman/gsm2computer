package com.gsm2computer.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation test placeholder. The heavy testable components (RTP packet
 * codec, G.722, PCMA, SIP auth, STUN parser) are covered by unit tests under
 * app/src/test/ because they don't need an Android instrumentation context.
 *
 * Add androidTest cases here once UI / Telephony-layer integration coverage
 * is needed.
 */
@RunWith(AndroidJUnit4::class)
class SmokeInstrumentationTest {
    @Test
    fun appContextLoads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.gsm2computer.bridge", context.packageName)
    }
}
