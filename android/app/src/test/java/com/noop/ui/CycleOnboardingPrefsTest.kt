package com.noop.analytics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prefs carry onboardingComplete for the true Cycle first-run flow. */
class CycleOnboardingPrefsTest {

    @Test
    fun defaultPrefsNeedOnboarding() {
        val prefs = PeriodCalendar.Prefs()
        assertFalse(prefs.enabled)
        assertFalse(prefs.onboardingComplete)
    }

    @Test
    fun completedOnboardingCopies() {
        val prefs = PeriodCalendar.Prefs(
            enabled = true,
            onboardingComplete = true,
            avgCycleLengthOverride = 28,
            avgPeriodLengthOverride = 5,
        )
        assertTrue(prefs.onboardingComplete)
        assertTrue(prefs.enabled)
        assertEquals(28, prefs.avgCycleLengthOverride)
    }

    private fun assertEquals(expected: Int, actual: Int?) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
