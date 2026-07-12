package com.noop.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parity tests for [BackfillPolicy] — empty-streak backoff (PR #228 / NOOP 8.6.1). */
class BackfillPolicyTest {

    @Test
    fun nullLastAlwaysRuns() {
        for (t in BackfillTrigger.entries) {
            assertTrue(t.name, BackfillPolicy.shouldRun(t, nowSeconds = 1000.0, lastBackfillAtSeconds = null))
        }
    }

    @Test
    fun manualAndAutoContinueBypassEverything() {
        assertTrue(BackfillPolicy.shouldRun(BackfillTrigger.MANUAL, 1001.0, 1000.0))
        assertTrue(BackfillPolicy.shouldRun(BackfillTrigger.AUTO_CONTINUE, 1001.0, 1000.0))
        assertTrue(
            BackfillPolicy.shouldRun(
                BackfillTrigger.MANUAL, 1001.0, 1000.0, emptyStreak = 9, clockUntrusted = true,
            ),
        )
    }

    @Test
    fun connectUsesEventFloorNoBackoff() {
        assertFalse(BackfillPolicy.shouldRun(BackfillTrigger.CONNECT, 1089.0, 1000.0))
        assertTrue(BackfillPolicy.shouldRun(BackfillTrigger.CONNECT, 1090.0, 1000.0))
        assertTrue(BackfillPolicy.shouldRun(BackfillTrigger.CONNECT, 1090.0, 1000.0, emptyStreak = 9))
    }

    @Test
    fun periodicBaseFloor() {
        assertFalse(BackfillPolicy.shouldRun(BackfillTrigger.PERIODIC, 1899.0, 1000.0))
        assertTrue(BackfillPolicy.shouldRun(BackfillTrigger.PERIODIC, 1900.0, 1000.0))
        assertTrue(BackfillPolicy.shouldRun(BackfillTrigger.PERIODIC, 1900.0, 1000.0, emptyStreak = 2))
    }

    @Test
    fun periodicBackoffCurve() {
        assertFalse(BackfillPolicy.shouldRun(BackfillTrigger.PERIODIC, 1000.0 + 1799.0, 1000.0, emptyStreak = 3))
        assertTrue(BackfillPolicy.shouldRun(BackfillTrigger.PERIODIC, 1000.0 + 1800.0, 1000.0, emptyStreak = 3))
        assertFalse(BackfillPolicy.shouldRun(BackfillTrigger.PERIODIC, 1000.0 + 3599.0, 1000.0, emptyStreak = 4))
        assertTrue(BackfillPolicy.shouldRun(BackfillTrigger.PERIODIC, 1000.0 + 3600.0, 1000.0, emptyStreak = 4))
    }

    @Test
    fun untrustedClockSkipsAutomaticOnly() {
        assertFalse(BackfillPolicy.shouldRun(BackfillTrigger.PERIODIC, 100_000.0, 1000.0, clockUntrusted = true))
        assertFalse(BackfillPolicy.shouldRun(BackfillTrigger.STRAP, 100_000.0, 1000.0, clockUntrusted = true))
        assertTrue(BackfillPolicy.shouldRun(BackfillTrigger.CONNECT, 1090.0, 1000.0, clockUntrusted = true))
        assertTrue(BackfillPolicy.shouldRun(BackfillTrigger.MANUAL, 1001.0, 1000.0, clockUntrusted = true))
    }
}
