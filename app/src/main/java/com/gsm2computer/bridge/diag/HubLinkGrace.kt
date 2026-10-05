package com.gsm2computer.bridge.diag

/**
 * How long a live GSM call keeps trying the hub websocket after the link drops.
 *
 * The clock starts at the first loss. Later losses do not extend the window.
 * A restored socket clears the clock so the next dip gets a full grace period.
 * [graceMs] of 0 means "do not retry" (immediate teardown).
 */
class HubLinkGrace(
    val graceMs: Long = DEFAULT_GRACE_MS,
    val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
) {
    private val lock = Any()

    var lostAtMs: Long? = null
        private set

    var attempt: Int = 0
        private set

    fun markLost(nowMs: Long) = synchronized(lock) {
        if (lostAtMs == null) {
            lostAtMs = nowMs
        }
        attempt += 1
    }

    fun markRestored() = synchronized(lock) {
        lostAtMs = null
        attempt = 0
    }

    /**
     * Milliseconds to wait before the next connect attempt.
     * Null when the grace window is already over (or grace is disabled).
     */
    fun retryDelayMs(nowMs: Long): Long? = synchronized(lock) {
        if (graceMs <= 0L) return null
        val start = lostAtMs ?: return 0L
        val elapsed = nowMs - start
        if (elapsed >= graceMs) return null
        val remain = graceMs - elapsed
        val shift = (attempt - 1).coerceAtLeast(0).coerceAtMost(4)
        var delay = initialDelayMs
        repeat(shift) {
            if (delay > maxDelayMs / 2) {
                delay = maxDelayMs
            } else {
                delay *= 2
            }
        }
        if (delay > maxDelayMs) delay = maxDelayMs
        if (delay > remain) delay = remain
        delay
    }

    companion object {
        const val DEFAULT_GRACE_MS = 55_000L
        const val DEFAULT_INITIAL_DELAY_MS = 1_000L
        const val DEFAULT_MAX_DELAY_MS = 8_000L
    }
}
