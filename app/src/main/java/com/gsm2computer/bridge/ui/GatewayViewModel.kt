package com.gsm2computer.bridge.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UI-facing gateway state. Sources: GatewayService status broadcasts (forwarded by
 * MainActivity) and the log bus. Fragments observe the LiveDatas; the activity is the
 * sole writer, which preserves the existing single-receiver broadcast model exactly.
 */
class GatewayViewModel : ViewModel() {

    private val _statusText = MutableLiveData("Stopped")
    val statusText: LiveData<String> = _statusText

    private val _statusDotColor = MutableLiveData("#DC2626")
    val statusDotColor: LiveData<String> = _statusDotColor

    private val _uptimeText = MutableLiveData("")
    val uptimeText: LiveData<String> = _uptimeText

    private val _gatewayRunning = MutableLiveData(false)
    val gatewayRunning: LiveData<Boolean> = _gatewayRunning

    private val _callActive = MutableLiveData(false)
    val callActive: LiveData<Boolean> = _callActive

    private val _logText = MutableLiveData(StringBuilder())
    val logText: LiveData<StringBuilder> = _logText

    private val _callLogFilter = MutableLiveData("IN")
    val callLogFilter: LiveData<String> = _callLogFilter

    /** Number preloaded into the dialer (e.g. tapped from the calls list). */
    private val _dialNumber = MutableLiveData("")
    val dialNumber: LiveData<String> = _dialNumber

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun setStatus(state: String, info: String) {
        val dotColor = when (state) {
            "IDLE", "BRIDGED" -> "#16A34A"
            "STARTING", "GSM_RINGING", "GSM_ANSWERED",
            "SIP_CALLING", "SIP_RINGING", "GSM_DIALING",
            "TEARING_DOWN" -> "#EAB308"
            else -> "#DC2626"
        }
        _statusDotColor.value = dotColor

        val text = when (state) {
            "IDLE" -> if (info == "SIP registered") "Registered — Ready" else info
            "BRIDGED" -> "Call active: $info"
            "STOPPED" -> "Stopped"
            "ERROR" -> info
            "STARTING" -> info
            else -> info
        }
        _statusText.value = text
    }

    fun setUptime(text: String) {
        _uptimeText.value = text
    }

    fun setGatewayRunning(running: Boolean) {
        _gatewayRunning.value = running
    }

    fun setCallActive(active: Boolean) {
        _callActive.value = active
    }

    fun appendLog(msg: String) {
        val ts = timeFmt.format(Date())
        val sb = (_logText.value ?: StringBuilder())
        sb.append("$ts  $msg\n")
        _logText.value = sb
    }

    fun clearLog() {
        _logText.value = StringBuilder()
    }

    fun setCallLogFilter(filter: String) {
        _callLogFilter.value = filter
    }

    fun setDialNumber(number: String) {
        _dialNumber.value = number
    }
}
