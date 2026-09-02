package com.gsm2computer.bridge.util

import android.util.Log
import com.gsm2computer.bridge.BuildConfig

/**
 * Logging wrapper that redacts phone-number-like substrings in release builds.
 *
 * In debug builds the message is passed through unchanged so developers can
 * debug against real numbers on the Redmi/Pixel. In release builds any token
 * that looks like a phone number (7+ digits, optionally prefixed with `+` and
 * optionally using spaces, dashes, or parens as separators) is replaced with
 * its last four digits preceded by an ellipsis (e.g. `+14155551234` → `…1234`).
 *
 * IP-safety: the matcher requires 7+ digits for a redaction and explicitly
 * skips any digit run that sits immediately next to a `.` (so IPv4 octets,
 * version strings, and dotted numeric tokens are never mangled). Short
 * standalone numbers (ports, durations, status codes) lack the digit count and
 * are left intact.
 */
object RedactingLogger {

    /**
     * Matches a phone-number-ish token: an optional `+`, optional opening paren,
     * and a run of digits interleaved with spaces, dashes, and parens.
     *
     * The actual "is this a phone number" decision (vs an IPv4, a port, or an
     * SSID fragment) is made in [redact] by counting digits — a candidate is
     * only redacted if it carries 7+ digits AND is not immediately flanked by a
     * dot (which would make it part of a dotted IP or version string).
     */
    private val PHONE_PATTERN = Regex("""\+?\(?\d[\d\s\-()]*\d|\+?\(?\d""")

    /**
     * Lookarounds that reject a candidate sitting inside a dotted numeric token
     * such as an IPv4 octet (`142` in `142.55.0.10`) or a version (`2.8`).
     */
    private fun isInsideDottedNumber(msg: String, start: Int, end: Int): Boolean {
        val before = if (start == 0) ' ' else msg[start - 1]
        val after = if (end == msg.length) ' ' else msg[end]
        return before == '.' || after == '.'
    }

    /**
     * Whether log messages are passed through unredacted. Mirrors
     * [BuildConfig.DEBUG] in production; overridable by tests via
     * [setDebugForTest].
     */
    @Volatile
    private var isDebug: Boolean = BuildConfig.DEBUG

    /** Test-only override of the debug/release gating. */
    fun setDebugForTest(debug: Boolean) {
        isDebug = debug
    }

    /** Visible for testing. Redact the message according to the active build. */
    internal fun redact(msg: String): String {
        if (isDebug) return msg
        return PHONE_PATTERN.replace(msg) { match ->
            val range = match.range
            if (isInsideDottedNumber(msg, range.first, range.last + 1)) {
                return@replace match.value
            }
            val digits = match.value.filter { it.isDigit() }
            if (digits.length < 7) match.value else "…${digits.takeLast(4)}"
        }
    }

    fun v(tag: String, msg: String) {
        if (isDebug) Log.v(tag, msg) else Log.v(tag, redact(msg))
    }

    fun d(tag: String, msg: String) {
        if (isDebug) Log.d(tag, msg) else Log.d(tag, redact(msg))
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, redact(msg))
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, redact(msg))
    }

    fun w(tag: String, msg: String, t: Throwable) {
        Log.w(tag, redact(msg), t)
    }

    fun e(tag: String, msg: String) {
        Log.e(tag, redact(msg))
    }

    fun e(tag: String, msg: String, t: Throwable) {
        Log.e(tag, redact(msg), t)
    }
}
