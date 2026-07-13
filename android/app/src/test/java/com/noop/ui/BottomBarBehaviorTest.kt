package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the scroll-reactive bottom-bar behavior (#86): storage-string resolution,
 * the COMPACT / EXPANDED / REACTIVE collapse decision, and the collapse-fraction composition that
 * blends scroll-linked pre-compression with the eased settle. No Compose / Robolectric — these are
 * the pure functions extracted from AppRoot so the state machine is testable without a device.
 */
class BottomBarBehaviorTest {

    private val eps = 1e-6f

    @Test
    fun fromStorage_resolvesKnownValues() {
        assertEquals(BottomBarBehavior.REACTIVE, BottomBarBehavior.fromStorage("reactive"))
        assertEquals(BottomBarBehavior.EXPANDED, BottomBarBehavior.fromStorage("expanded"))
        assertEquals(BottomBarBehavior.COMPACT, BottomBarBehavior.fromStorage("compact"))
    }

    @Test
    fun fromStorage_defaultsToReactiveForNullOrUnknown() {
        assertEquals(BottomBarBehavior.REACTIVE, BottomBarBehavior.fromStorage(null))
        assertEquals(BottomBarBehavior.REACTIVE, BottomBarBehavior.fromStorage(""))
        assertEquals(BottomBarBehavior.REACTIVE, BottomBarBehavior.fromStorage("nonsense"))
    }

    @Test
    fun storageValue_roundTripsThroughFromStorage() {
        for (b in BottomBarBehavior.entries) {
            assertEquals(b, BottomBarBehavior.fromStorage(b.storageValue))
        }
    }

    @Test
    fun effectivelyCollapsed_compactAlways_expandedNever() {
        assertTrue(isBarEffectivelyCollapsed(BottomBarBehavior.COMPACT, reactiveCollapsed = false))
        assertTrue(isBarEffectivelyCollapsed(BottomBarBehavior.COMPACT, reactiveCollapsed = true))
        assertFalse(isBarEffectivelyCollapsed(BottomBarBehavior.EXPANDED, reactiveCollapsed = false))
        assertFalse(isBarEffectivelyCollapsed(BottomBarBehavior.EXPANDED, reactiveCollapsed = true))
    }

    @Test
    fun effectivelyCollapsed_reactiveFollowsScrollFlag() {
        assertFalse(isBarEffectivelyCollapsed(BottomBarBehavior.REACTIVE, reactiveCollapsed = false))
        assertTrue(isBarEffectivelyCollapsed(BottomBarBehavior.REACTIVE, reactiveCollapsed = true))
    }

    @Test
    fun resolveBarCollapse_reduceMotionSnapsToEndpoints() {
        // Reduce Motion ignores precompression/settled and snaps to 0 or 1 by the effective state.
        assertEquals(1f, resolveBarCollapse(BottomBarBehavior.COMPACT, reduceMotion = true,
            reactiveCollapsed = false, precompression = 0.1f, settled = 0.3f), eps)
        assertEquals(0f, resolveBarCollapse(BottomBarBehavior.EXPANDED, reduceMotion = true,
            reactiveCollapsed = true, precompression = 0.1f, settled = 0.3f), eps)
        assertEquals(1f, resolveBarCollapse(BottomBarBehavior.REACTIVE, reduceMotion = true,
            reactiveCollapsed = true, precompression = 0f, settled = 0f), eps)
        assertEquals(0f, resolveBarCollapse(BottomBarBehavior.REACTIVE, reduceMotion = true,
            reactiveCollapsed = false, precompression = 0.1f, settled = 0.9f), eps)
    }

    @Test
    fun resolveBarCollapse_nonReactiveUsesSettledValue() {
        // EXPANDED/COMPACT ride the eased settle only — no scroll-linked precompression.
        assertEquals(0.42f, resolveBarCollapse(BottomBarBehavior.EXPANDED, reduceMotion = false,
            reactiveCollapsed = false, precompression = 0.9f, settled = 0.42f), eps)
        assertEquals(0.42f, resolveBarCollapse(BottomBarBehavior.COMPACT, reduceMotion = false,
            reactiveCollapsed = true, precompression = 0.9f, settled = 0.42f), eps)
    }

    @Test
    fun resolveBarCollapse_reactiveCollapsedTakesMaxOfPrecompressionAndSettle() {
        // Once collapsed, the fraction never dips below the eased settle even if precompression lags.
        assertEquals(0.5f, resolveBarCollapse(BottomBarBehavior.REACTIVE, reduceMotion = false,
            reactiveCollapsed = true, precompression = 0.16f, settled = 0.5f), eps)
        // ...and never below precompression while the settle is still catching up from 0.
        assertEquals(0.16f, resolveBarCollapse(BottomBarBehavior.REACTIVE, reduceMotion = false,
            reactiveCollapsed = true, precompression = 0.16f, settled = 0.0f), eps)
    }

    @Test
    fun resolveBarCollapse_reactiveExpandingFollowsPrecompressionThenSettle() {
        // Not yet collapsed but mid pre-compression → follow the scroll-linked drift.
        assertEquals(0.1f, resolveBarCollapse(BottomBarBehavior.REACTIVE, reduceMotion = false,
            reactiveCollapsed = false, precompression = 0.1f, settled = 0.0f), eps)
        // Fully expanded, no precompression → the settle value (animating back toward 0).
        assertEquals(0.0f, resolveBarCollapse(BottomBarBehavior.REACTIVE, reduceMotion = false,
            reactiveCollapsed = false, precompression = 0f, settled = 0.0f), eps)
    }
}
