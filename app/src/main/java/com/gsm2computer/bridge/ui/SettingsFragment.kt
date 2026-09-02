package com.gsm2computer.bridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.gsm2computer.bridge.BridgeConfig
import com.gsm2computer.bridge.R
import com.gsm2computer.bridge.service.GatewayService

class SettingsFragment : Fragment() {

    private val vm: GatewayViewModel by lazy {
        ViewModelProvider(requireActivity()).get(GatewayViewModel::class.java)
    }
    private var activeConfigDialog: AlertDialog? = null

    private lateinit var statusDot: View
    private lateinit var tvStatusText: TextView
    private lateinit var tvUptime: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var btnStart: Button
    private lateinit var btnCopyLog: Button
    private lateinit var btnConfig: ImageButton
    private lateinit var btnInfo: ImageButton

    private val importConfigLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { loadConfigFromFile(it) }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        statusDot = view.findViewById(R.id.statusDot)
        tvStatusText = view.findViewById(R.id.tvStatusText)
        tvUptime = view.findViewById(R.id.tvUptime)
        tvLog = view.findViewById(R.id.tvLog)
        svLog = view.findViewById(R.id.svLog)
        btnStart = view.findViewById(R.id.btnStart)
        btnCopyLog = view.findViewById(R.id.btnCopyLog)
        btnConfig = view.findViewById(R.id.btnConfig)
        btnInfo = view.findViewById(R.id.btnInfo)

        tvLog.text = ""
        GatewayService.drainLogBuffer()

        btnStart.setOnClickListener {
            if (vm.gatewayRunning.value == true) stopGateway() else startGateway()
        }
        btnCopyLog.setOnClickListener { copyLog() }
        btnConfig.setOnClickListener { showConfigDialog() }
        btnInfo.setOnClickListener { DiagnosticsController(requireActivity()).show() }

        vm.statusText.observe(viewLifecycleOwner) { tvStatusText.text = it }
        vm.statusDotColor.observe(viewLifecycleOwner) { color ->
            statusDot.backgroundTintList =
                android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(color))
        }
        vm.uptimeText.observe(viewLifecycleOwner) { tvUptime.text = it }
        vm.gatewayRunning.observe(viewLifecycleOwner) { running -> updateToggleButton(running) }
        vm.logText.observe(viewLifecycleOwner) { sb ->
            tvLog.text = sb.toString()
            svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun startGateway() {
        val cfg = BridgeConfig.resolve(BridgeConfig.openPrefs(requireActivity()))
        if (!cfg.streamEnabled) {
            Toast.makeText(requireContext(), "Set hub control URL in settings first", Toast.LENGTH_LONG).show()
            return
        }
        GatewayService.start(requireContext())
        vm.setGatewayRunning(true)
        val dest = cfg.hubControlUrl.ifBlank { cfg.streamTokenUrl }
        (requireActivity() as GatewayHost).appendLog("Starting bridge → $dest")
    }

    private fun stopGateway() {
        GatewayService.stop(requireContext())
        vm.setGatewayRunning(false)
    }

    private fun updateToggleButton(running: Boolean) {
        if (running) {
            btnStart.text = "STOP"
            btnStart.backgroundTintList =
                android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#DC2626"))
        } else {
            btnStart.text = "START"
            btnStart.backgroundTintList =
                android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#16A34A"))
        }
    }

    private fun showConfigDialog() {
        val prefs = BridgeConfig.openPrefs(requireActivity())
        val cfg = BridgeConfig.resolve(prefs)
        val view = layoutInflater.inflate(R.layout.dialog_config, null)

        val etHubUrl = view.findViewById<EditText>(R.id.etHubControlUrl)
        val etTokenUrl = view.findViewById<EditText>(R.id.etStreamTokenUrl)
        val etModel = view.findViewById<EditText>(R.id.etStreamModel)
        val etVoice = view.findViewById<EditText>(R.id.etStreamVoice)
        val cbAutoconnect = view.findViewById<CheckBox>(R.id.cbAutoconnect)
        val btnLoadConfig = view.findViewById<Button>(R.id.btnLoadConfig)

        fun updateOpenAiVisibility() {
            val hubMode = etHubUrl.text.toString().isNotBlank()
            val vis = if (hubMode) View.GONE else View.VISIBLE
            etModel.visibility = vis
            etVoice.visibility = vis
        }
        etHubUrl.doAfterTextChanged { updateOpenAiVisibility() }
        etHubUrl.setText(cfg.hubControlUrl)
        etTokenUrl.setText(cfg.streamTokenUrl)
        etModel.setText(cfg.streamModel)
        etVoice.setText(cfg.streamVoice)
        cbAutoconnect.isChecked = cfg.autoconnect
        updateOpenAiVisibility()

        btnLoadConfig.setOnClickListener {
            activeConfigDialog?.dismiss()
            importConfigLauncher.launch("*/*")
        }

        activeConfigDialog = AlertDialog.Builder(requireContext())
            .setTitle("Hub configuration")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val hubUrl = etHubUrl.text.toString().trim()
                val tokenUrl = etTokenUrl.text.toString().trim()
                prefs.edit()
                    .putString(BridgeConfig.KEY_HUB_CONTROL_URL, hubUrl)
                    .putString(BridgeConfig.KEY_STREAM_TOKEN_URL, tokenUrl)
                    .putString(BridgeConfig.KEY_STREAM_MODEL, etModel.text.toString().trim())
                    .putString(BridgeConfig.KEY_STREAM_VOICE, etVoice.text.toString().trim())
                    .putBoolean(BridgeConfig.KEY_AUTOCONNECT, cbAutoconnect.isChecked)
                    .putBoolean(
                        BridgeConfig.KEY_STREAM_ENABLED,
                        hubUrl.isNotBlank() || tokenUrl.isNotBlank(),
                    )
                    .apply()
                (requireActivity() as GatewayHost).appendLog(
                    "Config saved (autoconnect=${cbAutoconnect.isChecked} hub=$hubUrl)"
                )
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { activeConfigDialog = null }
            .show()
    }

    private fun loadConfigFromFile(uri: Uri) {
        val host = requireActivity() as GatewayHost
        try {
            requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                val jsonText = inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(jsonText)
                val prefs = BridgeConfig.openPrefs(requireActivity())
                val editor = prefs.edit()
                if (json.has("hub_control_url")) {
                    editor.putString(BridgeConfig.KEY_HUB_CONTROL_URL, json.getString("hub_control_url"))
                }
                if (json.has("hub_owned_session")) {
                    editor.putBoolean(BridgeConfig.KEY_HUB_OWNED_SESSION, json.getBoolean("hub_owned_session"))
                }
                if (json.has("stream_token_url")) {
                    editor.putString(BridgeConfig.KEY_STREAM_TOKEN_URL, json.getString("stream_token_url"))
                }
                if (json.has("stream_model")) {
                    editor.putString(BridgeConfig.KEY_STREAM_MODEL, json.getString("stream_model"))
                }
                if (json.has("stream_voice")) {
                    editor.putString(BridgeConfig.KEY_STREAM_VOICE, json.getString("stream_voice"))
                }
                if (json.has("autoconnect")) {
                    editor.putBoolean(BridgeConfig.KEY_AUTOCONNECT, json.getBoolean("autoconnect"))
                }
                editor.apply()
                Toast.makeText(requireContext(), "Config loaded successfully", Toast.LENGTH_SHORT).show()
                showConfigDialog()
            }
        } catch (e: Exception) {
            host.appendLog("ERROR loading config: ${e.message}")
            Toast.makeText(requireContext(), "Failed to load config file", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyLog() {
        val logText = tvLog.text.toString()
        if (logText.isEmpty()) {
            Toast.makeText(requireContext(), "Log is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("gsm2computer log", logText))
        Toast.makeText(requireContext(), "Log copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
