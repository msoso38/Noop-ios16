package com.noop.alarm

/**
 * After the morning wake alarm, watch live HR briefly. If heart rate rose (awake) then falls
 * toward sleep again, suggest a turn-back buzz / phone cue.
 *
 * Pure / testable. Not a clinical sleep classifier — coarse HR heuristic only.
 */
class TurnBackWatcher(
    /** How far below the post-wake high (bpm) counts as "settling back down". */
    private val dropBpm: Int = 8,
    /** Ignore tiny highs (still in bed, never really woke). */
    private val minHighBpm: Int = 52,
    /** Require this many samples after wake before arming. */
    private val minSamples: Int = 8,
) {
    private var highBpm: Int = 0
    private var samples: Int = 0
    private var fired: Boolean = false

    fun reset() {
        highBpm = 0
        samples = 0
        fired = false
    }

    /** Feed one HR sample. Returns true once when a turn-back cue should fire. */
    fun shouldCue(bpm: Int): Boolean {
        if (bpm <= 0) return false
        samples++
        if (bpm > highBpm) highBpm = bpm
        if (fired) return false
        if (samples < minSamples) return false
        if (highBpm < minHighBpm) return false
        if (bpm <= highBpm - dropBpm) {
            fired = true
            return true
        }
        return false
    }
}
