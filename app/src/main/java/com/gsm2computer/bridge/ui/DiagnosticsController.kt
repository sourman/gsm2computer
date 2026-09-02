package com.gsm2computer.bridge.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.wifi.WifiManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.gsm2computer.bridge.DeviceProfile
import com.gsm2computer.bridge.R
import com.gsm2computer.bridge.RootShell

/**
 * Builds and shows the "Device Info" dialog: GSM/WiFi/IP readouts plus the "Check
 * Gateway Support" self-test. Extracted verbatim from the legacy MainActivity so the
 * host fragment stays small; behavior is unchanged.
 */
class DiagnosticsController(private val activity: FragmentActivity) {

    @SuppressLint("MissingPermission")
    fun show() {
        val view = activity.layoutInflater.inflate(R.layout.dialog_info, null)

        val tvGsmNetwork = view.findViewById<TextView>(R.id.tvGsmNetwork)
        val tvNetworkType = view.findViewById<TextView>(R.id.tvNetworkType)
        val tvGsmSignal = view.findViewById<TextView>(R.id.tvGsmSignal)
        val tvCellId = view.findViewById<TextView>(R.id.tvCellId)
        val tvImei = view.findViewById<TextView>(R.id.tvImei)
        val tvPhoneNumber = view.findViewById<TextView>(R.id.tvPhoneNumber)
        val tvWifiNetwork = view.findViewById<TextView>(R.id.tvWifiNetwork)
        val tvWifiType = view.findViewById<TextView>(R.id.tvWifiType)
        val tvWifiSignal = view.findViewById<TextView>(R.id.tvWifiSignal)
        val tvWifiIp = view.findViewById<TextView>(R.id.tvWifiIp)
        val tvPublicIp = view.findViewById<TextView>(R.id.tvPublicIp)

        val hasPhonePerm = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        val hasLocationPerm = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val tm = activity.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        tvGsmNetwork.text = tm.networkOperatorName.ifEmpty { "N/A" }

        if (hasPhonePerm) {
            @Suppress("DEPRECATION")
            val netType = tm.networkType
            tvNetworkType.text = networkTypeName(netType)
        } else {
            tvNetworkType.text = "No permission"
        }

        if (hasPhonePerm) {
            try {
                val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val subMgr = activity.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                    subMgr.getPhoneNumber(SubscriptionManager.getDefaultSubscriptionId())
                } else {
                    @Suppress("DEPRECATION")
                    tm.line1Number
                }
                tvPhoneNumber.text = if (number.isNullOrEmpty()) "N/A" else number
            } catch (_: Exception) {
                tvPhoneNumber.text = "N/A"
            }

            try {
                val imei = tm.getImei(0)
                    ?: tm.getImei(1)
                    ?: tm.imei
                tvImei.text = imei ?: "N/A"
            } catch (_: SecurityException) {
                try {
                    val androidId = android.provider.Settings.Secure.getString(
                        activity.contentResolver, android.provider.Settings.Secure.ANDROID_ID
                    )
                    tvImei.text = androidId ?: "N/A"
                } catch (_: Exception) {
                    tvImei.text = "N/A"
                }
            } catch (_: Exception) {
                tvImei.text = "N/A"
            }
        } else {
            tvPhoneNumber.text = "No permission"
            tvImei.text = "No permission"
        }

        if (hasLocationPerm) {
            try {
                val cellInfo = tm.allCellInfo?.firstOrNull()
                // CellInfoNr requires API 29 (Q). A when-branch type test can't
                // carry a version guard, so check the level alongside the type here.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is CellInfoNr) {
                    val id = cellInfo.cellIdentity as? android.telephony.CellIdentityNr
                    tvCellId.text = id?.nci?.let {
                        if (it == Long.MAX_VALUE) "N/A" else it.toString()
                    } ?: "N/A"
                    tvGsmSignal.text = "${cellInfo.cellSignalStrength.level}/4 (${cellInfo.cellSignalStrength.dbm} dBm)"
                } else when (cellInfo) {
                    is CellInfoLte -> {
                        tvCellId.text = cellInfo.cellIdentity.ci.let {
                            if (it == Int.MAX_VALUE) "N/A" else it.toString()
                        }
                        tvGsmSignal.text = "${cellInfo.cellSignalStrength.level}/4 (${cellInfo.cellSignalStrength.dbm} dBm)"
                    }
                    is CellInfoGsm -> {
                        tvCellId.text = cellInfo.cellIdentity.cid.let {
                            if (it == Int.MAX_VALUE) "N/A" else it.toString()
                        }
                        tvGsmSignal.text = "${cellInfo.cellSignalStrength.level}/4 (${cellInfo.cellSignalStrength.dbm} dBm)"
                    }
                    is CellInfoWcdma -> {
                        tvCellId.text = cellInfo.cellIdentity.cid.let {
                            if (it == Int.MAX_VALUE) "N/A" else it.toString()
                        }
                        tvGsmSignal.text = "${cellInfo.cellSignalStrength.level}/4 (${cellInfo.cellSignalStrength.dbm} dBm)"
                    }
                    else -> {
                        tvCellId.text = "N/A"
                        tvGsmSignal.text = "N/A"
                    }
                }
            } catch (_: Exception) {
                tvCellId.text = "N/A"
                tvGsmSignal.text = "N/A"
            }
        } else {
            tvCellId.text = "No permission"
            tvGsmSignal.text = "No permission"
        }

        val wm = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val wifiInfo = wm.connectionInfo
        if (wifiInfo != null && wifiInfo.networkId != -1) {
            @Suppress("DEPRECATION")
            val ssid = wifiInfo.ssid?.replace("\"", "") ?: "N/A"
            tvWifiNetwork.text = if (ssid == "<unknown ssid>") "N/A (no location permission)" else ssid
            @Suppress("DEPRECATION")
            val freq = wifiInfo.frequency
            tvWifiType.text = when {
                freq in 2400..2500 -> "2.4 GHz"
                freq in 5000..5900 -> "5 GHz"
                freq in 5925..7125 -> "6 GHz"
                else -> "${freq} MHz"
            }
            @Suppress("DEPRECATION")
            val rssi = wifiInfo.rssi
            val level = WifiManager.calculateSignalLevel(rssi, 5)
            tvWifiSignal.text = "$level/4 ($rssi dBm)"

            @Suppress("DEPRECATION")
            val ip = wifiInfo.ipAddress
            if (ip != 0) {
                tvWifiIp.text = String.format(
                    "%d.%d.%d.%d",
                    ip and 0xff, ip shr 8 and 0xff,
                    ip shr 16 and 0xff, ip shr 24 and 0xff
                )
            } else {
                tvWifiIp.text = "N/A"
            }
        } else {
            tvWifiNetwork.text = "Not connected"
            tvWifiType.text = "—"
            tvWifiSignal.text = "—"
            tvWifiIp.text = "—"
        }

        Thread {
            val pubIp = try {
                java.net.URL("https://api.ipify.org").readText().trim()
            } catch (_: Exception) {
                "N/A"
            }
            activity.runOnUiThread { tvPublicIp.text = pubIp }
        }.start()

        val btnCheck = view.findViewById<Button>(R.id.btnCheckSupport)
        val resultsContainer = view.findViewById<LinearLayout>(R.id.checkResultsContainer)
        btnCheck.setOnClickListener {
            btnCheck.isEnabled = false
            btnCheck.text = "Checking…"
            resultsContainer.visibility = View.VISIBLE
            resultsContainer.removeAllViews()
            runGatewayChecks(resultsContainer) {
                btnCheck.text = "Check Gateway Support"
                btnCheck.isEnabled = true
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("Device Info")
            .setView(view)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun runGatewayChecks(container: LinearLayout, onDone: () -> Unit) {
        val dp = activity.resources.displayMetrics.density
        val greenColor = Color.parseColor("#16A34A")
        val redColor = Color.parseColor("#DC2626")
        val grayColor = Color.parseColor("#6B7280")

        fun addSectionHeader(title: String) {
            val tv = TextView(activity).apply {
                text = title
                textSize = 13f
                setTextColor(ContextCompat.getColor(activity, R.color.primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, (8 * dp).toInt(), 0, (2 * dp).toInt())
            }
            container.addView(tv)
        }

        fun addResultRow(label: String, passed: Boolean, detail: String = "") {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
            }
            val icon = TextView(activity).apply {
                text = if (passed) "\u2713" else "\u2717"
                textSize = 14f
                setTextColor(if (passed) greenColor else redColor)
                layoutParams = LinearLayout.LayoutParams((20 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val tvLabel = TextView(activity).apply {
                text = label
                textSize = 13f
                setTextColor(if (passed) greenColor else redColor)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(icon)
            row.addView(tvLabel)
            if (detail.isNotEmpty()) {
                val tvDetail = TextView(activity).apply {
                    text = detail
                    textSize = 11f
                    setTextColor(grayColor)
                }
                row.addView(tvDetail)
            }
            container.addView(row)
        }

        Thread {
            data class CheckResult(val label: String, val passed: Boolean, val detail: String = "")
            val results = mutableListOf<CheckResult>()

            val hasRecordAudio = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            val hasPhoneState = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED

            val hasAnswerCalls = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED

            val hasCallPhone = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            val hasCaptureOutput = ContextCompat.checkSelfPermission(
                activity, "android.permission.CAPTURE_AUDIO_OUTPUT"
            ) == PackageManager.PERMISSION_GRANTED

            val appOpsResult = RootShell.execForOutput("appops get ${activity.packageName} RECORD_AUDIO", timeoutMs = 2000)
            val hasAppOps = appOpsResult.contains("allow")

            val profile = DeviceProfile.detect()
            val tinymix = DeviceProfile.tinymixBin
            val mixerDump = if (tinymix.isNotEmpty()) DeviceProfile.discoverMixerControls() else ""

            results.add(CheckResult("RECORD_AUDIO", hasRecordAudio))
            results.add(CheckResult("AppOps RECORD_AUDIO", hasAppOps, if (hasAppOps) "allowed" else "denied (bg limit)"))
            results.add(CheckResult("CAPTURE_AUDIO_OUTPUT", hasCaptureOutput, if (hasCaptureOutput) "Magisk" else "needs Magisk"))
            results.add(CheckResult("ANSWER_PHONE_CALLS", hasAnswerCalls))
            results.add(CheckResult("CALL_PHONE", hasCallPhone))
            results.add(CheckResult("READ_PHONE_STATE", hasPhoneState))

            val telecomMgr = activity.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val isDefaultDialer = activity.packageName == telecomMgr.defaultDialerPackage
            results.add(CheckResult("Default Dialer", isDefaultDialer, if (isDefaultDialer) "" else "required for InCallService"))

            data class SourceTest(val source: Int, val name: String, val rate: Int)
            val sources = listOf(
                SourceTest(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_UPLINK, "VOICE_UPLINK", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION", 8000),
                SourceTest(MediaRecorder.AudioSource.MIC, "MIC", 8000)
            )

            val sourceResults = mutableListOf<CheckResult>()
            for (src in sources) {
                var ok = false
                var detail = ""
                try {
                    val minBuf = AudioRecord.getMinBufferSize(
                        src.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minBuf > 0) {
                        val rec = AudioRecord(
                            src.source, src.rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            minBuf.coerceAtLeast(4096)
                        )
                        if (rec.state == AudioRecord.STATE_INITIALIZED) {
                            try {
                                rec.startRecording()
                                val buf = ByteArray(320)
                                val read = rec.read(buf, 0, buf.size)
                                ok = read > 0
                                if (!ok) detail = "read=$read"
                                rec.stop()
                            } catch (e: Exception) {
                                detail = e.message?.take(30) ?: "start failed"
                            }
                        } else {
                            detail = "init failed"
                        }
                        rec.release()
                    } else {
                        detail = "invalid buffer"
                    }
                } catch (e: Exception) {
                    detail = e.message?.take(30) ?: "error"
                }
                sourceResults.add(CheckResult(src.name, ok, detail))
            }

            val aecAvail = AcousticEchoCanceler.isAvailable()
            val nsAvail = NoiseSuppressor.isAvailable()

            data class PropCheck(val prop: String, val expected: String, val label: String)
            val propChecks = listOf(
                PropCheck("voice.record.conc.disabled", "false", "Concurrent recording"),
                PropCheck("voice.playback.conc.disabled", "false", "Concurrent playback"),
                PropCheck("voice.voip.conc.disabled", "false", "Concurrent VoIP")
            )
            val propResults = mutableListOf<CheckResult>()
            for (pc in propChecks) {
                val value = try {
                    @Suppress("PrivateApi")
                    val cls = Class.forName("android.os.SystemProperties")
                    val get = cls.getMethod("get", String::class.java, String::class.java)
                    get.invoke(null, pc.prop, "") as String
                } catch (_: Exception) { "" }
                val ok = value == pc.expected
                propResults.add(CheckResult(pc.label, ok, if (value.isNotEmpty()) "$value" else "not set"))
            }

            val hasRoot = try {
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val exitCode = proc.waitFor()
                proc.destroy()
                exitCode == 0
            } catch (_: Exception) {
                try {
                    java.io.File("/system/bin/su").exists() ||
                        java.io.File("/system/xbin/su").exists() ||
                        java.io.File("/sbin/su").exists()
                } catch (_: Exception) { false }
            }

            val hasUsableSource = sourceResults.any { it.passed }
            val hasDownlink = sourceResults.firstOrNull { it.label == "VOICE_DOWNLINK" }?.passed == true

            activity.runOnUiThread {
                addSectionHeader("Permissions")
                for (r in results) addResultRow(r.label, r.passed, r.detail)

                addSectionHeader("Audio Sources")
                for (r in sourceResults) addResultRow(r.label, r.passed, r.detail)

                addSectionHeader("Audio Effects")
                addResultRow("AcousticEchoCanceler", aecAvail)
                addResultRow("NoiseSuppressor", nsAvail)

                addSectionHeader("System Properties")
                for (r in propResults) addResultRow(r.label, r.passed, r.detail)

                addSectionHeader("System")
                addResultRow("Root (su)", hasRoot, if (hasRoot) "" else "needed for Magisk")

                addSectionHeader("Audio Architecture")
                addResultRow("Profile", true, profile.name)
                addResultRow("ABOX Device", profile.routing.isAbox, if (profile.routing.isAbox) "Samsung DSP" else "Standard HAL")
                addResultRow("tinymix Tool", tinymix.isNotEmpty(), if (tinymix.isNotEmpty()) tinymix else "Not installed")

                if (mixerDump.isNotEmpty()) {
                    addSectionHeader("Mixer ALSA State")
                    val tvMixer = TextView(activity).apply {
                        text = mixerDump.take(2000) + if (mixerDump.length > 2000) "\n... (truncated)" else ""
                        textSize = 10f
                        typeface = android.graphics.Typeface.MONOSPACE
                        setTextColor(grayColor)
                        setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt())
                        setBackgroundColor(Color.parseColor("#11000000"))
                    }
                    container.addView(tvMixer)
                }

                val divider = View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                    ).apply { topMargin = (8 * dp).toInt(); bottomMargin = (8 * dp).toInt() }
                    setBackgroundColor(ContextCompat.getColor(activity, R.color.border_card))
                }
                container.addView(divider)

                val gatewayReady = hasRecordAudio && isDefaultDialer && hasUsableSource && hasCaptureOutput
                val verdict = TextView(activity).apply {
                    text = if (gatewayReady) {
                        val src = if (hasDownlink) "VOICE_DOWNLINK" else
                            sourceResults.firstOrNull { it.passed }?.label ?: "?"
                        "\u2713 Gateway supported (capture: $src)"
                    } else {
                        val missing = mutableListOf<String>()
                        if (!hasRecordAudio) missing.add("RECORD_AUDIO")
                        if (!hasCaptureOutput) missing.add("CAPTURE_AUDIO_OUTPUT")
                        if (!isDefaultDialer) missing.add("Default Dialer")
                        if (!hasUsableSource) missing.add("audio source")
                        "\u2717 Not ready: missing ${missing.joinToString(", ")}"
                    }
                    textSize = 13f
                    setTextColor(if (gatewayReady) greenColor else redColor)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                container.addView(verdict)

                onDone()
            }
        }.start()
    }

    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        else -> "Unknown"
    }
}
