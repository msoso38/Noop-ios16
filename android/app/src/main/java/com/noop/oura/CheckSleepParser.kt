package com.noop.oura

/**
 * Parse the ring's OWN computed sleep window out of its `check_sleep` debug-text (`0x43`) stream
 * (OURA_PROTOCOL.md §6.15). PROTOTYPE / INVESTIGATION source (§6.12.1): the ring's firmware periodically
 * logs its sleep-detection state as plain ASCII lines —
 *
 *     check_sleep
 *     s: 114643            <- bedtime, a ring timestamp in the anchor's domain
 *     e: 446340            <- wake,    a ring timestamp in the anchor's domain
 *     not needed
 *
 * — and those `s:` / `e:` values are ring timestamps in the SAME domain as the `0x42` UTC anchor (§5.5),
 * so [OuraDriver.unixSeconds] converts them straight to bedtime/wake UTC.
 *
 * Why this matters: the decoded OURA_SLEEP_PHASE (`0x4E`) events are sparse bursts the ring emits at
 * connection time, NOT a continuous overnight timeline, so a session built from them under-counts. The
 * `s:`/`e:` window is the ring's own boundary decision and far more reliable for sleep DURATION. Unlike
 * the Tier-B `sleep_summary` tags (`0x49/4B/4C/57/58` = ASCII `I/K/L/W/X`, which a framing desync aliases
 * out of debug text — verified junk), the `s:`/`e:` lines are unambiguous ASCII and safe to read.
 *
 * Honest-data stance: reads only what the ring computed; never fabricates stages. Byte-for-byte twin of
 * Swift `OuraCheckSleepParser`. INVESTIGATION prototype — the caller LOGS the anchored window, does not
 * yet persist a session.
 */
class OuraCheckSleepParser {
    /** A ring-timestamp sleep window: [startRt] = bedtime, [endRt] = wake, both in the anchor domain. */
    data class Window(val startRt: Long, val endRt: Long)

    private var lastStartRt: Long? = null
    private var lastEndRt: Long? = null
    private var lastEmitted: Window? = null

    private companion object {
        /** Longest plausible in-bed span, in ring ticks (100 ms/tick, §5.5): 18 h. A `check_sleep` block
         *  can emit a lone `e:` (wake) with no fresh `s:`, pairing the NEW wake against the PREVIOUS night's
         *  stale `s:` — observed live as a phantom 33 h window (2026-07-09). No real sleep is 18 h, so reject
         *  a longer window rather than emit a cross-block mis-pairing (honest-data). Twin of Swift. */
        const val MAX_WINDOW_TICKS = 648_000L   // 18 h × 3600 s × 10 ticks/s
    }

    /** Clear accumulated state (call on stop/disconnect so a new session starts fresh). */
    fun reset() {
        lastStartRt = null
        lastEndRt = null
        lastEmitted = null
    }

    /**
     * Feed ONE trimmed debug-text line. Returns a [Window] when a NEW, complete (`endRt > startRt`) sleep
     * window is recognized; null otherwise (an unrelated line, an incomplete pair, or a window identical to
     * the last emitted — so repeated `e:` refinements collapse to one emit each). Matches ONLY the exact
     * lowercase boundary lines `s: <digits>` / `e: <digits>` (so `tsc:`, `bed:`, `ns=`, etc. are ignored).
     */
    fun ingest(line: String): Window? {
        val s = ringValue(line, "s:")
        if (s != null) {
            lastStartRt = s
        } else {
            val e = ringValue(line, "e:")
            if (e != null) lastEndRt = e else return null   // not a boundary line
        }
        val start = lastStartRt ?: return null
        val end = lastEndRt ?: return null
        if (end <= start || end - start > MAX_WINDOW_TICKS) return null
        val window = Window(start, end)
        if (window == lastEmitted) return null              // unchanged since last emit
        lastEmitted = window
        return window
    }

    /**
     * Parse `"<prefix> <digits>"` (single ASCII space) into a ring timestamp, or null if the line is not
     * exactly that shape. Rejects a non-numeric or overflowing tail rather than guessing.
     */
    private fun ringValue(line: String, prefix: String): Long? {
        if (!line.startsWith(prefix)) return null
        val rest = line.substring(prefix.length).trimStart(' ')
        if (rest.isEmpty() || !rest.all { it.isDigit() }) return null
        return rest.toLongOrNull()
    }
}
