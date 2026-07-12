package com.noop.analytics

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HcNoopAlignTest {

    @Test
    fun fuseDaily_smallGap_leavesNoop() {
        val daily = DailyMetric(deviceId = "x", day = "2026-07-10", totalSleepMin = 420.0, efficiency = 0.9)
        val hc = HcNoopAlign.HcNight(asleepMin = 430.0, deepMin = 60.0, remMin = 90.0, hasStages = true)
        val out = HcNoopAlign.fuseDaily(daily, hc)
        assertEquals(420.0, out.totalSleepMin!!, 0.01)
    }

    @Test
    fun fuseDaily_largeGap_blendsTowardHc() {
        val daily = DailyMetric(
            deviceId = "x",
            day = "2026-07-10",
            totalSleepMin = 300.0,
            efficiency = 0.85,
            deepMin = 20.0,
            remMin = 40.0,
        )
        val hc = HcNoopAlign.HcNight(asleepMin = 420.0, deepMin = 70.0, remMin = 95.0, lightMin = 255.0, hasStages = true)
        val out = HcNoopAlign.fuseDaily(daily, hc)
        assertTrue(out.totalSleepMin!! in 370.0..400.0) // ~0.65*420 + 0.35*300 = 378
        assertEquals(70.0, out.deepMin!!, 0.01)
        assertEquals(95.0, out.remMin!!, 0.01)
    }

    @Test
    fun fuseDaily_noStages_noBlend() {
        val daily = DailyMetric(deviceId = "x", day = "2026-07-10", totalSleepMin = 300.0, efficiency = 0.9)
        val hc = HcNoopAlign.HcNight(asleepMin = 450.0, hasStages = false)
        assertEquals(300.0, HcNoopAlign.fuseDaily(daily, hc).totalSleepMin!!, 0.01)
    }

    @Test
    fun preferSteps_order() {
        assertEquals(9000, HcNoopAlign.preferSteps(9000, 8000, 7000))
        assertEquals(8000, HcNoopAlign.preferSteps(null, 8000, 7000))
        assertEquals(7000, HcNoopAlign.preferSteps(null, null, 7000))
        assertNull(HcNoopAlign.preferSteps(null, null, null))
    }

    @Test
    fun stagesFromJson_readsDeepRemLight() {
        val json = """[{"stage":"deep","min":55},{"stage":"rem","min":80},{"stage":"light","min":200}]"""
        val (d, r, l) = HcNoopAlign.stagesFromJson(json)
        assertEquals(55.0, d!!, 0.01)
        assertEquals(80.0, r!!, 0.01)
        assertEquals(200.0, l!!, 0.01)
    }
}
