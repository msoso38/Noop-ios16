package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoopGoalBoardTest {

    @Test
    fun completeAndNotCompletePartition() {
        val all = NoopGoalBoard.all()
        val complete = NoopGoalBoard.complete()
        val notComplete = NoopGoalBoard.notComplete()
        assertEquals(all.size, complete.size + notComplete.size)
        assertTrue(complete.all { it.isComplete })
        assertTrue(notComplete.all { it.isNotComplete })
        // G3 BLE + G10 goals board are the verified done items in this ship
        assertTrue(complete.any { it.id == "G3" })
        assertTrue(complete.any { it.id == "G10" })
        assertFalse(complete.any { it.id == "G4" })
        assertFalse(complete.any { it.id == "G5" })
    }

    @Test
    fun everyGoalHasTestImpactBenefitAndWorksNow() {
        NoopGoalBoard.all().forEach { g ->
            assertTrue(g.id.isNotBlank())
            assertTrue(g.title.isNotBlank())
            assertTrue(g.howToTest.length > 10)
            assertTrue(g.futureImpact.length > 10)
            assertTrue(g.humanBenefit.length > 10)
            assertTrue(g.worksNow.length > 5)
        }
    }

    @Test
    fun statusLabelsHumanReadable() {
        assertEquals("Complete", NoopGoalBoard.statusLabel(NoopGoalBoard.Status.DONE))
        assertEquals("Not complete", NoopGoalBoard.statusLabel(NoopGoalBoard.Status.NOT_DONE))
        assertEquals("Partial", NoopGoalBoard.statusLabel(NoopGoalBoard.Status.PARTIAL))
        assertEquals("Blocked", NoopGoalBoard.statusLabel(NoopGoalBoard.Status.BLOCKED))
    }
}
