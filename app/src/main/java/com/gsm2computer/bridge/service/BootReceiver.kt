package com.gsm2computer.bridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.gsm2computer.bridge.BridgeConfig
import com.gsm2computer.bridge.MicCapabilityGuard
import com.gsm2computer.bridge.RootShell

/** Auto-starts the bridge service on boot when hub stream URL is configured. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        Log.i(TAG, "Boot/update received, checking config...")
        earlyPermissionSetup(context)

        val prefs = BridgeConfig.openPrefs(context)
        if (!BridgeConfig.resolveAutoconnect(prefs)) {
            Log.i(TAG, "Autoconnect disabled, skipping")
            return
        }
        if (!BridgeConfig.isConfigured(prefs)) {
            Log.i(TAG, "Hub stream not configured, skipping auto-start")
            return
        }

        Thread({
            try {
                RootShell.init()
                if (!MicCapabilityGuard.launchRelaunchActivity(context.packageName)) {
                    GatewayService.start(context)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Foreground relaunch failed: ${e.message}")
                GatewayService.start(context)
            }
        }, "boot-relaunch").start()
    }

    private fun earlyPermissionSetup(context: Context) {
        Thread({
            try {
                RootShell.init()
                val pkg = context.packageName
                val maxWaitMs = 90_000L
                val pollMs = 3_000L
                val waitStart = System.currentTimeMillis()
                val uidFlag = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
                while (System.currentTimeMillis() - waitStart < maxWaitMs) {
                    val probe = RootShell.execForOutput("appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1")
                    if (!probe.contains("Can't find service", ignoreCase = true)) break
                    Thread.sleep(pollMs)
                }
                val autoRevoke = if (Build.VERSION.SDK_INT >= 30)
                    "appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>&1; " else ""
                RootShell.execForOutput(
                    "pm grant $pkg android.permission.RECORD_AUDIO 2>&1; " +
                        "pm grant $pkg android.permission.RECEIVE_SMS 2>&1; " +
                        autoRevoke +
                        "appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                        "appops set $pkg RECORD_AUDIO allow 2>&1"
                )
            } catch (e: Exception) {
                Log.w(TAG, "Early permission setup failed: ${e.message}")
            }
        }, "boot-perms").start()
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
