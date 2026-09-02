package com.gsm2computer.bridge.ui

import androidx.fragment.app.FragmentActivity

/**
 * Implemented by the activity that hosts the gateway fragments. Fragments call up
 * through this interface for anything that spans the whole screen (in-call overlay,
 * tab switching) or that is owned by the activity (status broadcast consumption).
 */
interface GatewayHost {
    val activity: FragmentActivity

    /** Display a status line in the log viewer (timestamped on the UI thread). */
    fun appendLog(msg: String)

    /** Switch the visible top-level tab: "dialer" | "calls" | "settings". */
    fun switchTab(tab: String)

    /** Pre-fill the dialer with a number and show it. Used by the calls list. */
    fun openDiallerWithNumber(number: String)

    /** True when the SIP gateway service is running (registered/bridging or starting). */
    fun isGatewayRunning(): Boolean

    /** Open the full-screen in-call overlay for an outgoing call to [number]. */
    fun openInCallScreen(number: String)
}
