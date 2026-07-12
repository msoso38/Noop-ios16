package com.noop.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnBackWatcherTest {
    @Test
    fun cuesWhenHrRisesThenDrops() {
        val w = TurnBackWatcher(dropBpm = 8, minHighBpm = 52, minSamples = 3)
        assertFalse(w.shouldCue(70))
        assertFalse(w.shouldCue(72))
        assertFalse(w.shouldCue(74)) // samples met, high=74
        assertTrue(w.shouldCue(65)) // drop >= 8
        assertFalse(w.shouldCue(60)) // only once
    }

    @Test
    fun ignoresLowHigh() {
        val w = TurnBackWatcher(dropBpm = 8, minHighBpm = 52, minSamples = 2)
        assertFalse(w.shouldCue(48))
        assertFalse(w.shouldCue(40))
    }
}
