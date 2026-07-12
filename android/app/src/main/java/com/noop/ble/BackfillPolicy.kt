package com.noop.ble

import kotlin.math.min
import kotlin.math.pow

/**
 * What prompted a historical-offload attempt. Port of Swift `BackfillTrigger`
 * (NOOP 8.6.1 / PR #228 battery savings).
 */
enum class BackfillTrigger {
    /** The repeating 900s timer while connected + bonded. */
    PERIODIC,
    /** A (re)connect / bond confirmation. */
    CONNECT,
    /** The app became active (foreground). */
    FOREGROUND,
    /** The user tapped "Sync now". */
    MANUAL,
    /** An incoming strap EVENT packet. */
    STRAP,
    /** Immediate continuation of a deep backlog / bounded WHOOP-5 history retry. */
    AUTO_CONTINUE,
}

/**
 * Pure rate-limiter for historical-offload kicks. Empty-streak backoff so a not-banking strap
 * is not re-offloaded every 15 min forever (Android battery + strap wake) — PR #228.
 */
object BackfillPolicy {
    const val PERIODIC_FLOOR_SECONDS = 900.0
    const val EVENT_FLOOR_SECONDS = 90.0
    const val EMPTY_BACKOFF_THRESHOLD = 3
    const val MAX_EMPTY_BACKOFF = 4.0

    fun shouldRun(
        trigger: BackfillTrigger,
        nowSeconds: Double,
        lastBackfillAtSeconds: Double?,
        emptyStreak: Int = 0,
        clockUntrusted: Boolean = false,
    ): Boolean {
        val last = lastBackfillAtSeconds ?: return true
        val elapsed = nowSeconds - last
        val backoff: Double = if (emptyStreak >= EMPTY_BACKOFF_THRESHOLD) {
            min(2.0.pow((emptyStreak - EMPTY_BACKOFF_THRESHOLD + 1).toDouble()), MAX_EMPTY_BACKOFF)
        } else {
            1.0
        }
        return when (trigger) {
            BackfillTrigger.MANUAL, BackfillTrigger.AUTO_CONTINUE -> true
            BackfillTrigger.CONNECT, BackfillTrigger.FOREGROUND -> elapsed >= EVENT_FLOOR_SECONDS
            BackfillTrigger.STRAP -> !clockUntrusted && elapsed >= EVENT_FLOOR_SECONDS * backoff
            BackfillTrigger.PERIODIC -> !clockUntrusted && elapsed >= PERIODIC_FLOOR_SECONDS * backoff
        }
    }
}
