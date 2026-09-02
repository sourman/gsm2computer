package com.gsm2computer.bridge.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.gsm2computer.bridge.R
import com.gsm2computer.bridge.gsm.GsmCallManager
import com.gsm2computer.bridge.service.GatewayService

class DialerFragment : Fragment() {

    private val vm: GatewayViewModel by lazy {
        ViewModelProvider(requireActivity()).get(GatewayViewModel::class.java)
    }

    private lateinit var tvDialNumber: TextView
    private lateinit var btnCall: ImageView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dialer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvDialNumber = view.findViewById(R.id.tvDialNumber)
        btnCall = view.findViewById(R.id.btnCall)

        setupKeypad(view)

        btnCall.setOnClickListener { onCallButtonPressed() }

        vm.dialNumber.observe(viewLifecycleOwner) { number ->
            if (number.isNotEmpty() && tvDialNumber.text.toString() != number) {
                tvDialNumber.text = number
            }
        }

        vm.callActive.observe(viewLifecycleOwner) { active -> updateCallButton(active) }
    }

    private fun setupKeypad(view: View) {
        val digitButtons = mapOf(
            R.id.btnDial0 to "0", R.id.btnDial1 to "1", R.id.btnDial2 to "2",
            R.id.btnDial3 to "3", R.id.btnDial4 to "4", R.id.btnDial5 to "5",
            R.id.btnDial6 to "6", R.id.btnDial7 to "7", R.id.btnDial8 to "8",
            R.id.btnDial9 to "9", R.id.btnDialStar to "*", R.id.btnDialHash to "#",
            R.id.btnDialPlus to "+"
        )
        for ((id, digit) in digitButtons) {
            view.findViewById<TextView>(id).setOnClickListener { tvDialNumber.append(digit) }
        }

        view.findViewById<TextView>(R.id.btnBackspace).setOnClickListener {
            val current = tvDialNumber.text.toString()
            if (current.isNotEmpty()) {
                tvDialNumber.text = current.dropLast(1)
            }
        }
        view.findViewById<TextView>(R.id.btnBackspace).setOnLongClickListener {
            tvDialNumber.text = ""
            true
        }
    }

    private fun onCallButtonPressed() {
        val host = requireActivity() as? GatewayHost ?: return
        val ctx = requireContext()

        if (GsmCallManager.activeCall != null) {
            val num = GsmCallManager.currentNumber
                ?: tvDialNumber.text.toString().trim()
            if (num.isNotEmpty()) host.openInCallScreen(num)
            return
        }

        val number = tvDialNumber.text.toString().trim()
        if (number.isEmpty()) {
            Toast.makeText(ctx, "Enter a number to call", Toast.LENGTH_SHORT).show()
            return
        }

        host.openInCallScreen(number)

        if (host.isGatewayRunning()) {
            val intent = Intent(ctx, GatewayService::class.java).apply {
                action = GatewayService.ACTION_DIAL
                putExtra(GatewayService.EXTRA_NUMBER, number)
            }
            ctx.startService(intent)
        } else {
            GsmCallManager.makeCall(ctx, number)
        }

        host.appendLog("Dialling $number")
    }

    private fun updateCallButton(callActive: Boolean) {
        if (callActive) {
            btnCall.backgroundTintList =
                android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#DC2626"))
        } else {
            btnCall.backgroundTintList = null
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh call-button tint from the current GSM state when returning to the dialer.
        updateCallButton(GsmCallManager.activeCall != null)
    }
}
