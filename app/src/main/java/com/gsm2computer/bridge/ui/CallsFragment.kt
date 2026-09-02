package com.gsm2computer.bridge.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.gsm2computer.bridge.R
import com.gsm2computer.bridge.service.CallLogEntry
import com.gsm2computer.bridge.service.CallLogStore
import com.gsm2computer.bridge.service.GatewayService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallsFragment : Fragment() {

    private lateinit var callLogContainer: LinearLayout
    private lateinit var callLogScroll: ScrollView
    private lateinit var tvCallsEmpty: TextView
    private lateinit var btnFilterIn: ImageButton
    private lateinit var btnFilterOut: ImageButton

    private var callLogFilter = "IN"

    private var cachedInEntries: List<CallLogEntry> = emptyList()
    private var cachedOutEntries: List<CallLogEntry> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_calls, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        callLogContainer = view.findViewById(R.id.callLogContainer)
        callLogScroll = view.findViewById(R.id.callLogScroll)
        tvCallsEmpty = view.findViewById(R.id.tvCallsEmpty)
        btnFilterIn = view.findViewById(R.id.btnFilterIn)
        btnFilterOut = view.findViewById(R.id.btnFilterOut)

        view.findViewById<ImageButton>(R.id.btnCallLogClear).setOnClickListener { confirmClearCallLog() }
        btnFilterIn.setOnClickListener { setCallLogFilter("IN") }
        btnFilterOut.setOnClickListener { setCallLogFilter("OUT") }

        preloadCallLog()
    }

    override fun onResume() {
        super.onResume()
        refreshCallLog()
    }

    private fun setCallLogFilter(filter: String) {
        callLogFilter = filter

        val activeColor = ContextCompat.getColor(requireContext(), R.color.primary)
        val inactiveColor = 0xFF374151.toInt()
        val activeIconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        val inactiveIconTint = android.content.res.ColorStateList.valueOf(0xFF9CA3AF.toInt())

        if (filter == "IN") {
            btnFilterIn.backgroundTintList = android.content.res.ColorStateList.valueOf(activeColor)
            btnFilterIn.imageTintList = activeIconTint
            btnFilterOut.backgroundTintList = android.content.res.ColorStateList.valueOf(inactiveColor)
            btnFilterOut.imageTintList = inactiveIconTint
        } else {
            btnFilterOut.backgroundTintList = android.content.res.ColorStateList.valueOf(activeColor)
            btnFilterOut.imageTintList = activeIconTint
            btnFilterIn.backgroundTintList = android.content.res.ColorStateList.valueOf(inactiveColor)
            btnFilterIn.imageTintList = inactiveIconTint
        }

        showCallLog()
    }

    /** Pre-load both IN and OUT lists off the main thread so tab switching is instant */
    private fun preloadCallLog() {
        val ctx = requireContext()
        Thread {
            val all = CallLogStore.getEntries(ctx)
            cachedInEntries = all.filter { it.direction == "IN" }.take(MAX_CALL_LOG)
            cachedOutEntries = all.filter { it.direction == "OUT" }.take(MAX_CALL_LOG)
            activity?.runOnUiThread { showCallLog() }
        }.start()
    }

    fun refreshCallLog() {
        if (view != null) preloadCallLog()
    }

    /** Show the already-cached list for the current filter — runs on UI thread, no I/O */
    private fun showCallLog() {
        val entries = if (callLogFilter == "IN") cachedInEntries else cachedOutEntries
        buildCallLogUI(entries)
    }

    private fun buildCallLogUI(entries: List<CallLogEntry>) {
        val ctx = requireContext()
        callLogContainer.removeAllViews()

        if (entries.isEmpty()) {
            callLogScroll.visibility = View.GONE
            tvCallsEmpty.visibility = View.VISIBLE
            return
        }

        callLogScroll.visibility = View.VISIBLE
        tvCallsEmpty.visibility = View.GONE

        val dp = resources.displayMetrics.density
        val dateFmt = SimpleDateFormat("dd/MM HH:mm", Locale.US)

        for (entry in entries) {
            val number = entry.number.ifEmpty { "Unknown" }

            val rowView = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (4 * dp).toInt()
                }
                background = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x40FFFFFF),
                    GradientDrawable().apply {
                        setColor(0xFF1F2937.toInt())
                        cornerRadius = 12 * dp
                    },
                    GradientDrawable().apply {
                        setColor(0xFFFFFFFF.toInt())
                        cornerRadius = 12 * dp
                    }
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { openDiallerWithNumber(number) }
            }

            val textBlock = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvNum = TextView(ctx).apply {
                text = number
                textSize = 16f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(0xFFFFFFFF.toInt())
            }

            val detailRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * dp).toInt()
                }
            }

            val dirIcon = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((16 * dp).toInt(), (16 * dp).toInt())
                setImageResource(
                    if (entry.direction == "IN") R.drawable.ic_call_incoming
                    else R.drawable.ic_call_outgoing
                )
                setColorFilter(0xFF9CA3AF.toInt())
            }

            val tvDate = TextView(ctx).apply {
                text = dateFmt.format(Date(entry.timestamp))
                textSize = 13f
                maxLines = 1
                setTextColor(0xFF9CA3AF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (6 * dp).toInt()
                }
            }

            val tvDur = TextView(ctx).apply {
                text = formatDurationCompact(entry.durationSec)
                textSize = 13f
                maxLines = 1
                setTextColor(0xFF9CA3AF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (12 * dp).toInt()
                }
            }

            detailRow.addView(dirIcon)
            detailRow.addView(tvDate)
            detailRow.addView(tvDur)

            textBlock.addView(tvNum)
            textBlock.addView(detailRow)

            val btnCall = ImageView(ctx).apply {
                val btnSize = (40 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    marginStart = (12 * dp).toInt()
                }
                setImageResource(R.drawable.ic_phone_call)
                setColorFilter(0xFF10B981.toInt())
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
                background = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x4010B981),
                    GradientDrawable().apply {
                        setColor(0xFF374151.toInt())
                        cornerRadius = 20 * dp
                    },
                    GradientDrawable().apply {
                        setColor(0xFFFFFFFF.toInt())
                        cornerRadius = 20 * dp
                    }
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { openDiallerWithNumber(number) }
            }

            rowView.addView(textBlock)
            rowView.addView(btnCall)
            callLogContainer.addView(rowView)
        }
    }

    private fun openDiallerWithNumber(number: String) {
        val host = activity as? GatewayHost ?: return
        val vm = androidx.lifecycle.ViewModelProvider(host.activity).get(GatewayViewModel::class.java)
        vm.setDialNumber(number)
        host.switchTab("dialer")
    }

    private fun confirmClearCallLog() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("Clear Call Log")
            .setMessage("Delete all call history? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                CallLogStore.clear(ctx)
                ctx.startService(Intent(ctx, GatewayService::class.java).apply {
                    action = GatewayService.ACTION_RELOAD_STATS
                })
                Toast.makeText(ctx, "Call log cleared", Toast.LENGTH_SHORT).show()
                refreshCallLog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatDurationCompact(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    companion object {
        private const val MAX_CALL_LOG = 20
    }
}
