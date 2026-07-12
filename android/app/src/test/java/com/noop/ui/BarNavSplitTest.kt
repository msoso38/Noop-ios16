package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards crescent balance when Cycle is added to the bottom bar. */
class BarNavSplitTest {

    @Test
    fun fourTabsSplitEvenly() {
        assertEquals(2, barLeftTabCount(4))
    }

    @Test
    fun fiveTabsPutCycleOnLeftWithTodayTrends() {
        // Today · Trends · Cycle | Sleep · More
        assertEquals(3, barLeftTabCount(5))
    }

    @Test
    fun emptyAndTiny() {
        assertEquals(0, barLeftTabCount(0))
        assertEquals(1, barLeftTabCount(2))
        assertEquals(1, barLeftTabCount(3))
    }
}
