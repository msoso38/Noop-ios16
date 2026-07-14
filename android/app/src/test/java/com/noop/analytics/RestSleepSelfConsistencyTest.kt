package com.noop.analytics

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Screenshot-grounded Rest / asleep self-consistency (13 Jul WHOOP vs NOOP).
 *
 * Hero "8h 37m" vs stages "7h 19m", Rest 73 "Strong" on 2% deep / 0% REM, and
 * debt ledger vs Trends "0.0 h" all share one root: totalSleepMin diverging from
 * stage sums, plus Rest overweighting duration when restorative collapses.
 */
class RestSleepSelfConsistencyTest {

    private val eps = 1e-6

    @Test
    fun canonicalAsleepMin_prefersStageSumOverStaleTotal() {
        val daily = DailyMetric(
            deviceId = "t",
            day = "2026-07-13",
            totalSleepMin = 517.0, // "8h 37m" banked / fused
            efficiency = 0.69,
            deepMin = 10.0,
            remMin = 0.0,
            lightMin = 429.0, // 7h 19m stage sum
        )
        assertEquals(439.0, RestScorer.canonicalAsleepMin(daily)!!, eps)
    }

    @Test
    fun canonicalAsleepMin_fallsBackToTotalWhenStagesMissing() {
        val daily = DailyMetric(
            deviceId = "t",
            day = "2026-07-13",
            totalSleepMin = 426.0,
            efficiency = 0.76,
        )
        assertEquals(426.0, RestScorer.canonicalAsleepMin(daily)!!, eps)
    }

    @Test
    fun restFromDaily_usesStageSumWhenTotalDisagrees() {
        val daily = DailyMetric(
            deviceId = "t",
            day = "2026-07-13",
            totalSleepMin = 517.0,
            efficiency = 0.69,
            deepMin = 10.0,
            remMin = 0.0,
            lightMin = 429.0,
        )
        val fromStages = RestScorer.rest(
            asleepSeconds = 439.0 * 60.0,
            efficiency = 0.69,
            deepSeconds = 10.0 * 60.0,
            remSeconds = 0.0,
            sleepNeedHours = 7.5,
        )!!
        val fromDaily = RestScorer.restFromDaily(daily)!!
        // restFromDaily uses default 8h need; compare shape via same need.
        val fromDailyNeed = RestScorer.rest(
            asleepSeconds = RestScorer.canonicalAsleepMin(daily)!! * 60.0,
            efficiency = 0.69,
            deepSeconds = 600.0,
            remSeconds = 0.0,
            sleepNeedHours = 8.0,
        )!!
        assertEquals(fromDailyNeed, fromDaily, eps)
        assertTrue("stage-sum Rest must be below the old Strong/73 band ($fromStages)", fromStages < 65.0)
        assertTrue("must stay above a total tank ($fromStages)", fromStages > 40.0)
    }

    @Test
    fun rest_lowDeepNightDoesNotReadStrong() {
        // Screenshot night: ~7h19m asleep, 69% eff, 10m deep, 0 REM, need 7.5h.
        val score = RestScorer.rest(
            asleepSeconds = 439.0 * 60.0,
            efficiency = 0.69,
            deepSeconds = 10.0 * 60.0,
            remSeconds = 0.0,
            sleepNeedHours = 7.5,
        )
        assertNotNull(score)
        assertTrue("Rest=$score must not read Strong (≥70) on 2% deep / 0% REM", score!! < 65.0)
        assertTrue("Rest=$score should not crater below Fair territory", score > 45.0)
    }

    @Test
    fun rest_wellStructuredNightStillScoresHigh() {
        val score = RestScorer.rest(
            asleepSeconds = 8 * 3600.0,
            efficiency = 0.90,
            deepSeconds = 1.5 * 3600.0,
            remSeconds = 2.0 * 3600.0,
        )!!
        assertTrue("healthy night Rest=$score should stay high", score >= 85.0)
    }

    @Test
    fun sleepDebtLedger_matchesCanonicalSeries() {
        val nights = listOf(
            "2026-07-04" to DailyMetric("t", "2026-07-04", totalSleepMin = 500.0, deepMin = 40.0, remMin = 40.0, lightMin = 300.0),
            "2026-07-05" to DailyMetric("t", "2026-07-05", totalSleepMin = 200.0, deepMin = 10.0, remMin = 10.0, lightMin = 180.0),
            "2026-07-13" to DailyMetric("t", "2026-07-13", totalSleepMin = 517.0, deepMin = 10.0, remMin = 0.0, lightMin = 429.0),
        )
        val ledger = SleepDebt.ledger(
            series = nights.map { it.first to RestScorer.canonicalAsleepMin(it.second) },
            needHours = 7.5,
        )
        // Stage sums: 380 + 200 + 439 = 1019; need 3×450 = 1350; balance = −331 min ≈ −5.5h
        assertEquals(3, ledger.nightCount)
        assertTrue("ledger should show net debt (balance=${ledger.balanceMin})", ledger.isDebt)
        assertEquals(439.0, ledger.nights.last().sleptMin, eps)
    }
}
