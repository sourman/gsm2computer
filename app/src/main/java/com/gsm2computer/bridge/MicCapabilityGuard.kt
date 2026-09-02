package com.gsm2computer.bridge

import android.content.Context
import android.util.Log
import com.gsm2computer.bridge.BridgeConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Ensures PROCESS_CAPABILITY_FOREGROUND_MICROPHONE sticks on the gateway process.
 *
 * AudioRecord(VOICE_CALL) is silenced without the M bit. The bit is only granted
 * when the FGS is started while the app is foreground. BootReceiver and
 * START_STICKY restarts begin in background, so we root-launch MainActivity as an
 * invisible trampoline (see MainActivity.EXTRA_MIC_CAPABILITY_RELAUNCH).
 */
object MicCapabilityGuard {
    private const val TAG = "MicCapabilityGuard"
    private const val RELAUNCH_COOLDOWN_MS = 30_000L
    private const val CHECK_INTERVAL_MS = 120_000L

    private val relaunchInProgress = AtomicBoolean(false)
    private val monitorStarted = AtomicBoolean(false)
    @Volatile private var lastRelaunchMs = 0L

    fun hasForegroundMicrophoneCapability(packageName: String): Boolean {
        repeat(3) { attempt ->
            val output = RootShell.execForOutput(
                "dumpsys activity processes $packageName 2>/dev/null | " +
                    "grep '^    curCapability=' | head -1",
                timeoutMs = 15_000
            )
            val match = Regex("curCapability=([A-Z-]+)").find(output)
            if (match != null) {
                val cap = match.groupValues[1]
                return cap.length > 2 && cap[2] == 'M'
            }
            if (attempt < 2) Thread.sleep(1500)
        }
        return false
    }

    /** Root shell am start — bypasses background-activity-launch restrictions. */
    fun launchRelaunchActivity(packageName: String): Boolean {
        val component = "$packageName/.MainActivity"
        val extra = MainActivity.EXTRA_MIC_CAPABILITY_RELAUNCH
        val cmd = "am start -n $component --ez $extra true --activity-brought-to-front"
        val result = RootShell.execForOutput(cmd, timeoutMs = 8000)
        val failed = result.contains("Error", ignoreCase = true) ||
            result.contains("SecurityException", ignoreCase = true) ||
            result.contains("does not exist", ignoreCase = true)
        Log.i(TAG, "Root am start: ok=${!failed} [$result]")
        return !failed
    }

    /**
     * Quick check + root relaunch without blocking. Returns true when a relaunch
     * was triggered (caller should defer gateway init).
     */
    fun requestRelaunchIfNeeded(context: Context, reason: String, inCall: Boolean = false): Boolean {
        if (inCall) return false
        if (!isAutoconnectConfigured(context)) return false

        val pkg = context.packageName
        if (hasForegroundMicrophoneCapability(pkg)) {
            Log.d(TAG, "M bit present ($reason)")
            return false
        }

        val now = System.currentTimeMillis()
        if (now - lastRelaunchMs < RELAUNCH_COOLDOWN_MS) {
            Log.d(TAG, "Relaunch cooldown ($reason)")
            return false
        }
        if (!relaunchInProgress.compareAndSet(false, true)) return false

        try {
            Log.i(TAG, "M bit missing ($reason), root-launching foreground relaunch")
            lastRelaunchMs = now
            return launchRelaunchActivity(pkg)
        } finally {
            relaunchInProgress.set(false)
        }
    }

    fun ensureMicCapability(context: Context, reason: String, inCall: Boolean = false): Boolean {
        if (!requestRelaunchIfNeeded(context, reason, inCall)) return false
        val pkg = context.packageName
        val ok = waitForMicCapability(pkg, maxWaitMs = 20_000L)
        Log.i(TAG, "Post-relaunch M bit: $ok ($reason)")
        return ok
    }

    fun startMonitor(context: Context, inCall: () -> Boolean) {
        if (!monitorStarted.compareAndSet(false, true)) return
        thread(name = "mic-cap-monitor", isDaemon = true) {
            Thread.sleep(CHECK_INTERVAL_MS)
            while (true) {
                try {
                    ensureMicCapability(context.applicationContext, "periodic", inCall())
                } catch (e: Exception) {
                    Log.w(TAG, "Monitor error: ${e.message}")
                }
                Thread.sleep(CHECK_INTERVAL_MS)
            }
        }
    }

    private fun waitForMicCapability(packageName: String, maxWaitMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            if (hasForegroundMicrophoneCapability(packageName)) return true
            Thread.sleep(2000)
        }
        return hasForegroundMicrophoneCapability(packageName)
    }

    private fun isAutoconnectConfigured(context: Context): Boolean {
        val prefs = BridgeConfig.openPrefs(context)
        return BridgeConfig.resolveAutoconnect(prefs) && BridgeConfig.isConfigured(prefs)
    }
}
