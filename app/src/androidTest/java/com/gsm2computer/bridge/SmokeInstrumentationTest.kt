package com.gsm2computer.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation smoke: package identity plus default hub wiring.
 *
 * Heavy codec / device-profile coverage lives under app/src/test/.
 */
@RunWith(AndroidJUnit4::class)
class SmokeInstrumentationTest {
    @Test
    fun appContextLoads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.gsm2computer.bridge", context.packageName)
    }

    @Test
    fun defaultHubControlUrlIsTailscale() {
        assertEquals("http://100.101.181.110:8787", BuildConfig.DEFAULT_HUB_CONTROL_URL)
        assertEquals(
            "ws://100.101.181.110:8787",
            HubEndpoints.webSocketUrl(BuildConfig.DEFAULT_HUB_CONTROL_URL),
        )
        assertTrue(HubEndpoints.smsUrl(BuildConfig.DEFAULT_HUB_CONTROL_URL).endsWith("/sms"))
    }
}
