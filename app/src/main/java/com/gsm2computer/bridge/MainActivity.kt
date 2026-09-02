package com.gsm2computer.bridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.app.role.RoleManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.gsm2computer.bridge.BridgeConfig
import com.gsm2computer.bridge.gsm.GsmCallManager
import com.gsm2computer.bridge.service.GatewayService
import com.gsm2computer.bridge.ui.CallsFragment
import com.gsm2computer.bridge.ui.DialerFragment
import com.gsm2computer.bridge.ui.GatewayHost
import com.gsm2computer.bridge.ui.GatewayViewModel
import com.gsm2computer.bridge.ui.SettingsFragment

class MainActivity : AppCompatActivity(), GatewayHost {

    override val activity: FragmentActivity get() = this

    private lateinit var vm: GatewayViewModel

    // Tab containers + bottom bar
    private lateinit var tabbedRoot: LinearLayout
    private lateinit var tabBtnDialer: View
    private lateinit var tabBtnCalls: View
    private lateinit var tabBtnSettings: View
    private lateinit var tabIconDialer: android.widget.ImageView
    private lateinit var tabIconCalls: android.widget.ImageView
    private lateinit var tabIconSettings: android.widget.ImageView
    private lateinit var tabLabelDialer: TextView
    private lateinit var tabLabelCalls: TextView
    private lateinit var tabLabelSettings: TextView
    private var currentTab = "dialer"

    private val dialerFragment = DialerFragment()
    private val callsFragment = CallsFragment()
    private val settingsFragment = SettingsFragment()

    // In-call views
    private lateinit var inCallView: LinearLayout
    private lateinit var tvInCallStatus: TextView
    private lateinit var tvInCallNumber: TextView
    private lateinit var tvInCallTimer: TextView
    private lateinit var btnInCallEnd: Button
    private lateinit var llIncomingActions: LinearLayout
    private lateinit var btnInCallAnswer: Button
    private lateinit var btnInCallReject: Button
    private lateinit var llInCallControls: LinearLayout
    private lateinit var btnInCallMute: Button
    private lateinit var btnInCallKeypadToggle: Button
    private lateinit var btnInCallSpeaker: Button
    private lateinit var llInCallKeypad: LinearLayout
    private var isMuted = false
    private var isSpeakerOn = false
    private var isKeypadVisible = false
    private var inCallOpen = false
    private var inCallOpenTime = 0L
    private var viewBeforeInCall = "dialer"
    private var callStartTime = 0L
    private var lastGsmPollState = -1
    private var inCallCloseScheduled = false
    private val callTimerHandler = Handler(Looper.getMainLooper())
    private val callTimerRunnable = object : Runnable {
        override fun run() {
            if (callStartTime > 0) {
                val elapsed = (System.currentTimeMillis() - callStartTime) / 1000
                tvInCallTimer.text = String.format("%02d:%02d", elapsed / 60, elapsed % 60)
                callTimerHandler.postDelayed(this, 1000)
            }
        }
    }
    private val gsmPollRunnable = object : Runnable {
        override fun run() {
            if (!inCallOpen) return
            val call = GsmCallManager.activeCall
            val state = GsmCallManager.activeCallState
            if (call != null && state != lastGsmPollState) {
                lastGsmPollState = state
                when (state) {
                    android.telecom.Call.STATE_CONNECTING -> tvInCallStatus.text = "Calling..."
                    android.telecom.Call.STATE_DIALING -> {
                        tvInCallStatus.text = "Ringing..."
                        llIncomingActions.visibility = View.GONE
                        btnInCallEnd.visibility = View.VISIBLE
                    }
                    android.telecom.Call.STATE_RINGING -> {
                        tvInCallStatus.text = "Ringing..."
                        llIncomingActions.visibility = View.VISIBLE
                        btnInCallEnd.visibility = View.GONE
                    }
                    android.telecom.Call.STATE_ACTIVE -> {
                        llIncomingActions.visibility = View.GONE
                        btnInCallEnd.visibility = View.VISIBLE
                        if (running) {
                            tvInCallStatus.text = "Connecting..."
                            llInCallControls.visibility = View.GONE
                        } else {
                            tvInCallStatus.text = "Connected"
                            llInCallControls.visibility = View.VISIBLE
                            if (callStartTime == 0L) {
                                callStartTime = System.currentTimeMillis()
                                tvInCallTimer.text = "00:00"
                                tvInCallTimer.visibility = View.VISIBLE
                                callTimerRunnable.run()
                            }
                        }
                    }
                    android.telecom.Call.STATE_DISCONNECTED -> {
                        scheduleInCallClose()
                        return
                    }
                }
            } else if (call == null && lastGsmPollState != -1) {
                // GSM call was seen by poll but is now gone — call ended
                scheduleInCallClose()
                return
            } else if (call == null && lastGsmPollState == -1) {
                // Never saw a GSM call — failed dial or slow setup
                // Safety timeout to avoid stuck screen
                if (System.currentTimeMillis() - inCallOpenTime > 8000) {
                    closeInCallScreen()
                    return
                }
            }
            callTimerHandler.postDelayed(this, 500)
        }
    }

    private var running = false
    private var gsmCallActive = false
    private var onlineSince = 0L

    private val uptimeHandler = Handler(Looper.getMainLooper())
    private val uptimeRunnable = object : Runnable {
        override fun run() {
            if (onlineSince > 0) {
                val elapsed = (System.currentTimeMillis() - onlineSince) / 1000
                vm.setUptime(formatDuration(elapsed))
                uptimeHandler.postDelayed(this, 1000)
            }
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                GatewayService.STATUS_ACTION -> {
                    val state = intent.getStringExtra("state") ?: return
                    val info = intent.getStringExtra("info") ?: ""

                    vm.setStatus(state, info)

                    val newOnlineSince = intent.getLongExtra("online_since", 0L)
                    if (newOnlineSince != onlineSince) {
                        onlineSince = newOnlineSince
                        uptimeHandler.removeCallbacks(uptimeRunnable)
                        if (onlineSince > 0) {
                            uptimeRunnable.run()
                        } else {
                            vm.setUptime("")
                        }
                    }

                    val wasRunning = running
                    running = state != "STOPPED" && state != "ERROR"
                    if (running != wasRunning) vm.setGatewayRunning(running)

                    val callActive = state in listOf(
                        "GSM_RINGING", "GSM_ANSWERED", "CONNECTING",
                        "CONNECTING", "BRIDGED", "GSM_DIALING", "TEARING_DOWN"
                    )
                    if (callActive != gsmCallActive) {
                        gsmCallActive = callActive
                        vm.setCallActive(callActive)
                    }

                    // Show in-call screen for incoming calls on Dialer or Calls tab.
                    // On Settings tab the gateway still auto-answers — status + log is enough.
                    if (!inCallOpen && state == "GSM_RINGING") {
                        val callerNum = info.removePrefix("GSM call from ")
                        if (currentTab == "dialer" || currentTab == "calls") {
                            openInCallScreen(callerNum)
                            tvInCallStatus.text = "Incoming call"
                        }
                    }

                    if (inCallOpen) {
                        when (state) {
                            "GSM_DIALING" -> tvInCallStatus.text = "Calling..."
                            "GSM_ANSWERED", "CONNECTING" -> tvInCallStatus.text = "Connecting..."
                            "CONNECTING" -> tvInCallStatus.text = "Ringing..."
                            "BRIDGED" -> {
                                tvInCallStatus.text = "Connected"
                                if (callStartTime == 0L) {
                                    callStartTime = System.currentTimeMillis()
                                    tvInCallTimer.text = "00:00"
                                    tvInCallTimer.visibility = View.VISIBLE
                                    callTimerRunnable.run()
                                }
                            }
                            "TEARING_DOWN" -> tvInCallStatus.text = "Ending..."
                            "IDLE" -> {
                                scheduleInCallClose()
                            }
                        }
                    }

                    vm.appendLog("[$state] $info")
                }
                GatewayService.LOG_ACTION -> {
                    val msg = intent.getStringExtra("msg") ?: return
                    vm.appendLog(msg)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_MIC_CAPABILITY_RELAUNCH, false)) {
            relaunchGatewayForMicCapability()
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        vm = ViewModelProvider(this).get(GatewayViewModel::class.java)

        // Tab containers + bottom bar
        tabbedRoot = findViewById(R.id.tabbedRoot)
        tabBtnDialer = findViewById(R.id.tabBtnDialer)
        tabBtnCalls = findViewById(R.id.tabBtnCalls)
        tabBtnSettings = findViewById(R.id.tabBtnSettings)
        tabIconDialer = findViewById(R.id.tabIconDialer)
        tabIconCalls = findViewById(R.id.tabIconCalls)
        tabIconSettings = findViewById(R.id.tabIconSettings)
        tabLabelDialer = findViewById(R.id.tabLabelDialer)
        tabLabelCalls = findViewById(R.id.tabLabelCalls)
        tabLabelSettings = findViewById(R.id.tabLabelSettings)

        tabBtnDialer.setOnClickListener { switchTab("dialer") }
        tabBtnCalls.setOnClickListener { switchTab("calls") }
        tabBtnSettings.setOnClickListener { switchTab("settings") }

        // Host the three fragments, all added up-front; visibility toggled by switchTab.
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.tabContent, settingsFragment, "settings")
                .add(R.id.tabContent, callsFragment, "calls")
                .add(R.id.tabContent, dialerFragment, "dialer")
                .hide(settingsFragment)
                .hide(callsFragment)
                .commitNow()
        }

        // In-call views
        inCallView = findViewById(R.id.inCallView)
        tvInCallStatus = findViewById(R.id.tvInCallStatus)
        tvInCallNumber = findViewById(R.id.tvInCallNumber)
        tvInCallTimer = findViewById(R.id.tvInCallTimer)
        btnInCallEnd = findViewById(R.id.btnInCallEnd)
        llIncomingActions = findViewById(R.id.llIncomingActions)
        btnInCallAnswer = findViewById(R.id.btnInCallAnswer)
        btnInCallReject = findViewById(R.id.btnInCallReject)

        llInCallControls = findViewById(R.id.llInCallControls)
        btnInCallMute = findViewById(R.id.btnInCallMute)
        btnInCallKeypadToggle = findViewById(R.id.btnInCallKeypadToggle)
        btnInCallSpeaker = findViewById(R.id.btnInCallSpeaker)
        llInCallKeypad = findViewById(R.id.llInCallKeypad)

        btnInCallEnd.setOnClickListener { endCallFromInCallScreen() }
        btnInCallAnswer.setOnClickListener { GsmCallManager.answerCall() }
        btnInCallReject.setOnClickListener { GsmCallManager.rejectCall() }

        btnInCallMute.setOnClickListener { toggleMute() }
        btnInCallKeypadToggle.setOnClickListener { toggleKeypad() }
        btnInCallSpeaker.setOnClickListener { toggleSpeaker() }
        setupInCallKeypad()

        requestPermissions()
        requestBatteryOptimizationExemption()
        requestDefaultDialerRole()

        // Auto-start gateway if autoconnect enabled and credentials configured
        autoStartGateway()

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra("incoming_call", false)) {
            val number = intent.getStringExtra("number") ?: "unknown"
            if (!inCallOpen) {
                openInCallScreen(number)
                tvInCallStatus.text = "Incoming call"
                llIncomingActions.visibility = View.VISIBLE
                btnInCallEnd.visibility = View.GONE
            }
        }
    }

    private fun isGatewayServiceRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (info in am.getRunningServices(Int.MAX_VALUE)) {
            if (info.service.className == GatewayService::class.java.name) return true
        }
        return false
    }

    /** Re-establish FGS from foreground so FOREGROUND_MICROPHONE capability sticks. */
    private fun relaunchGatewayForMicCapability() {
        val cfg = BridgeConfig.resolve(BridgeConfig.openPrefs(this))
        if (!cfg.autoconnect || !cfg.streamEnabled) return
        if (running || isGatewayServiceRunning()) {
            GatewayService.relaunchFromForeground(this)
        } else {
            GatewayService.start(this)
        }
    }

    private fun autoStartGateway() {
        val cfg = BridgeConfig.resolve(BridgeConfig.openPrefs(this))
        if (!cfg.autoconnect || !cfg.streamEnabled) return
        val wasRunning = running || isGatewayServiceRunning()
        relaunchGatewayForMicCapability()
        if (!wasRunning) {
            running = true
            vm.setGatewayRunning(running)
            vm.appendLog("Auto-starting bridge → ${cfg.streamTokenUrl}")
        } else {
            vm.appendLog("Re-launching bridge from foreground (mic capability)")
        }
    }

    // ── Tab Navigation ───────────────────────────────────

    private fun switchTabInternal(tab: String) {
        if (tab == currentTab) return
        currentTab = tab

        val tx = supportFragmentManager.beginTransaction()
        when (tab) {
            "dialer" -> {
                tx.show(dialerFragment)
                tx.hide(callsFragment)
                tx.hide(settingsFragment)
            }
            "calls" -> {
                tx.hide(dialerFragment)
                tx.show(callsFragment)
                tx.hide(settingsFragment)
            }
            "settings" -> {
                tx.hide(dialerFragment)
                tx.hide(callsFragment)
                tx.show(settingsFragment)
            }
        }
        tx.commitNowAllowingStateLoss()

        val activeColor = ContextCompat.getColor(this, R.color.primary)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_hint)

        tabIconDialer.setColorFilter(if (tab == "dialer") activeColor else inactiveColor)
        tabIconCalls.setColorFilter(if (tab == "calls") activeColor else inactiveColor)
        tabIconSettings.setColorFilter(if (tab == "settings") activeColor else inactiveColor)

        tabLabelDialer.setTextColor(if (tab == "dialer") activeColor else inactiveColor)
        tabLabelCalls.setTextColor(if (tab == "calls") activeColor else inactiveColor)
        tabLabelSettings.setTextColor(if (tab == "settings") activeColor else inactiveColor)

        // Refresh call log when switching to Calls tab
        if (tab == "calls") {
            callsFragment.refreshCallLog()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (inCallOpen) {
            return // must use END CALL
        } else if (currentTab != "dialer") {
            switchTab("dialer")
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(GatewayService.STATUS_ACTION)
            addAction(GatewayService.LOG_ACTION)
        }
        registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)

        // Replay any log messages buffered while activity was paused
        val buffered = GatewayService.drainLogBuffer()
        for (msg in buffered) {
            vm.appendLog(msg)
        }

        if (onlineSince > 0) {
            uptimeHandler.removeCallbacks(uptimeRunnable)
            uptimeRunnable.run()
        }

        if (inCallOpen) {
            if (GsmCallManager.activeCall == null &&
                System.currentTimeMillis() - inCallOpenTime > 2000
            ) {
                closeInCallScreen()
            } else {
                if (callStartTime > 0) callTimerRunnable.run()
                callTimerHandler.removeCallbacks(gsmPollRunnable)
                callTimerHandler.postDelayed(gsmPollRunnable, 500)
            }
        } else if (currentTab == "dialer") {
            refreshCallButtonState()
        }

        // Refresh calls tab if visible
        if (currentTab == "calls") {
            callsFragment.refreshCallLog()
        }
    }

    override fun onPause() {
        super.onPause()
        uptimeHandler.removeCallbacks(uptimeRunnable)
        callTimerHandler.removeCallbacks(callTimerRunnable)
        callTimerHandler.removeCallbacks(gsmPollRunnable)
        unregisterReceiver(statusReceiver)
    }

    // ── GatewayHost ──────────────────────────────────────

    override fun appendLog(msg: String) = vm.appendLog(msg)

    override fun switchTab(tab: String) = switchTabInternal(tab)

    override fun openDiallerWithNumber(number: String) {
        vm.setDialNumber(number)
        switchTabInternal("dialer")
    }

    override fun isGatewayRunning(): Boolean = running

    override fun openInCallScreen(number: String) {
        inCallOpen = true
        inCallOpenTime = System.currentTimeMillis()
        inCallCloseScheduled = false
        callStartTime = 0L
        lastGsmPollState = -1
        tvInCallNumber.text = number
        tvInCallStatus.text = "Calling..."
        tvInCallTimer.visibility = View.GONE
        viewBeforeInCall = currentTab

        // Setup buttons based on current state
        val state = GsmCallManager.activeCallState
        if (state == android.telecom.Call.STATE_RINGING) {
            llIncomingActions.visibility = View.VISIBLE
            btnInCallEnd.visibility = View.GONE
            llInCallControls.visibility = View.GONE
        } else {
            llIncomingActions.visibility = View.GONE
            btnInCallEnd.visibility = View.VISIBLE
            llInCallControls.visibility = if (running) View.GONE else View.VISIBLE
        }

        // Reset in-call controls
        isMuted = false
        isSpeakerOn = false
        isKeypadVisible = false
        updateInCallControlUI()
        llInCallKeypad.visibility = View.GONE

        // Hide tabs, show in-call overlay
        tabbedRoot.visibility = View.GONE
        inCallView.visibility = View.VISIBLE
        callTimerHandler.removeCallbacks(gsmPollRunnable)
        callTimerHandler.postDelayed(gsmPollRunnable, 500)
    }

    // ── In-Call Screen ───────────────────────────────────

    /** Show "Call ended" briefly then close the in-call screen */
    private fun scheduleInCallClose() {
        if (!inCallOpen || inCallCloseScheduled) return
        inCallCloseScheduled = true
        tvInCallStatus.text = "Call ended"
        callTimerHandler.removeCallbacks(gsmPollRunnable)
        callTimerHandler.postDelayed({ closeInCallScreen() }, 1500)
    }

    private fun closeInCallScreen() {
        if (!inCallOpen) return
        inCallOpen = false
        callTimerHandler.removeCallbacks(callTimerRunnable)
        callTimerHandler.removeCallbacks(gsmPollRunnable)
        callStartTime = 0L
        lastGsmPollState = -1
        inCallView.visibility = View.GONE
        tabbedRoot.visibility = View.VISIBLE
        gsmCallActive = false
        vm.setCallActive(false)
        // Refresh calls list if returning to calls tab
        if (currentTab == "calls") {
            callsFragment.refreshCallLog()
        }
    }

    private fun endCallFromInCallScreen() {
        val call = GsmCallManager.activeCall
        if (call != null) {
            tvInCallStatus.text = "Ending..."
            GsmCallManager.hangupCall()
        } else {
            closeInCallScreen()
        }
    }

    private fun refreshCallButtonState() {
        gsmCallActive = GsmCallManager.activeCall != null
        vm.setCallActive(gsmCallActive)
    }

    private fun toggleMute() {
        isMuted = !isMuted
        GsmCallManager.setMuteMode(isMuted)
        updateInCallControlUI()
    }

    private fun toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn
        GsmCallManager.setSpeakerMode(isSpeakerOn)
        updateInCallControlUI()
    }

    private fun toggleKeypad() {
        isKeypadVisible = !isKeypadVisible
        llInCallKeypad.visibility = if (isKeypadVisible) View.VISIBLE else View.GONE
        updateInCallControlUI()
    }

    private fun updateInCallControlUI() {
        val activeColor = ColorStateList.valueOf(Color.parseColor("#16A34A")) // Green
        val inactiveColor = ColorStateList.valueOf(Color.parseColor("#333333")) // Dark gray
        val activeTextColor = Color.WHITE
        val inactiveTextColor = Color.parseColor("#E5E7EB") // Gray 200

        btnInCallMute.backgroundTintList = if (isMuted) activeColor else inactiveColor
        btnInCallMute.setTextColor(if (isMuted) activeTextColor else inactiveTextColor)
        btnInCallMute.text = if (isMuted) "UNMUTE" else "MUTE"

        btnInCallSpeaker.backgroundTintList = if (isSpeakerOn) activeColor else inactiveColor
        btnInCallSpeaker.setTextColor(if (isSpeakerOn) activeTextColor else inactiveTextColor)
        btnInCallSpeaker.text = if (isSpeakerOn) "EARPIECE" else "SPEAKER"

        btnInCallKeypadToggle.backgroundTintList = if (isKeypadVisible) activeColor else inactiveColor
        btnInCallKeypadToggle.setTextColor(if (isKeypadVisible) activeTextColor else inactiveTextColor)
    }

    private fun setupInCallKeypad() {
        val digitButtons = mapOf(
            R.id.btnDtmf0 to '0', R.id.btnDtmf1 to '1', R.id.btnDtmf2 to '2',
            R.id.btnDtmf3 to '3', R.id.btnDtmf4 to '4', R.id.btnDtmf5 to '5',
            R.id.btnDtmf6 to '6', R.id.btnDtmf7 to '7', R.id.btnDtmf8 to '8',
            R.id.btnDtmf9 to '9', R.id.btnDtmfStar to '*', R.id.btnDtmfHash to '#'
        )
        for ((id, char) in digitButtons) {
            val btn = findViewById<TextView>(id)
            btn.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    GsmCallManager.playDtmfTone(char)
                    btn.isPressed = true
                } else if (event.action == android.view.MotionEvent.ACTION_UP || event.action == android.view.MotionEvent.ACTION_CANCEL) {
                    GsmCallManager.stopDtmfTone()
                    btn.isPressed = false
                }
                true
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────

    private fun formatDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    // ── Permissions ─────────────────────────────────────

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            perms.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMS)
        }
    }

    private fun requestDefaultDialerRole() {
        val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        if (packageName == tm.defaultDialerPackage) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(Context.ROLE_SERVICE) as RoleManager
            if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                !rm.isRoleHeld(RoleManager.ROLE_DIALER)
            ) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_DEFAULT_DIALER)
            }
        } else {
            @Suppress("DEPRECATION")
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }
            startActivityForResult(intent, REQ_DEFAULT_DIALER)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DEFAULT_DIALER) {
            if (resultCode == RESULT_OK) {
                vm.appendLog("Set as default phone app")
            } else {
                vm.appendLog("WARN: Not set as default phone app — GSM call handling disabled")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            val denied = permissions.zip(grantResults.toTypedArray())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first.substringAfterLast('.') }
            if (denied.isNotEmpty()) {
                vm.appendLog("WARN: Denied permissions: ${denied.joinToString()}")
            }
        }
    }

    companion object {
        private const val REQ_PERMS = 100
        private const val REQ_DEFAULT_DIALER = 101
        const val EXTRA_MIC_CAPABILITY_RELAUNCH = "mic_capability_relaunch"
    }
}

